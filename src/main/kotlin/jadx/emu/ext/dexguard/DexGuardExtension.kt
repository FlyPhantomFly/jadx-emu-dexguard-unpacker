package jadx.emu.ext.dexguard

import jadx.plugins.emu.api.EmuExtension
import jadx.plugins.emu.api.EmuExtensionContext
import jadx.plugins.emu.api.EmuExtensionInfo
import jadx.plugins.emu.api.ExtensionMode
import jadx.plugins.emu.api.NativeRequest
import jadx.plugins.emu.exec.NativeBridge as EngineNativeBridge
import java.io.File
import java.nio.file.Files

const val DEXGUARD_UNPACKER_VERSION = "2.3.0"

class DexGuardExtension : EmuExtension {

    override fun info(): EmuExtensionInfo = EmuExtensionInfo(
        id = "dexguard-unpacker",
        name = "DexGuard unpacker",
        description = "Unpacks the dex files DexGuard loads at runtime",
        mode = ExtensionMode.ON_DEMAND,
        requiredEmuVersion = "0.3.0",
    )

    override fun init(ctx: EmuExtensionContext) {
        val hooks = ExtHooks(ctx.hooks)   // this extension's scope of jadx-emu's Hooks; cleared by jadx-emu on unload
        val config = ExtensionConfig.load(ctx.dataDir, ctx.log)
        Telemetry.enabled = config.telemetry
        VirtualClock.stepMs = config.clockAdvanceMs
        val jar = runCatching { DexGuardExtension::class.java.protectionDomain.codeSource.location.path }.getOrDefault("?")
        ctx.log.info(
            "dexguard-unpacker v{} | nativeBackend={} scanMode={} sweepTopN={} maxMillis={} telemetry={}",
            DEXGUARD_UNPACKER_VERSION, config.nativeBackend, config.scanMode, config.sweepTopN, config.maxMillis, config.telemetry,
        )
        ctx.log.info("dexguard-unpacker: running from {}", jar)

        val app = AppInfo(ctx.jadx.decompiler)
        val profile = app.profile
        config.applyTo(profile)

        // Constant folding + a faithful, non-debuggable, correctly-signed runtime environment.
        OpaqueConstants.register(hooks)
        HostShims(app, profile).register(hooks)
        AppContextStubs(app, profile).register(hooks)
        PackageStubs(app, profile).register(hooks)

        // Capture files the app drops to disk, so path-based loaders can be followed.
        val vfs = VirtualFs().also { it.register(hooks) }

        val unpacker = Unpacker(ctx, hooks, config)

        // Native bridge. CRITICAL: by default this is null so the VMs are built exactly as the
        // validated (pre-unidbg) path - passing any bridge object changes the engine's native-method
        // dispatch and regresses recovery. It is wired ONLY when a backend (e.g. unidbg) actually
        // loaded, or when native-replay.properties supplied mappings. The replay object always hooks
        // reflective Method.invoke (as in the validated build); that is a no-op when it has no mappings.
        // memoized: every call site shares one resolve (one backend open), evaluated on the first VM build
        val once = lazy { resolveNativeBridge(ctx, config) }
        val engineNative: () -> EngineNativeBridge? = { once.value }

        val hidden = HiddenDex(ctx.emu.source, ctx.emu.engine, hooks, unpacker.limits, ctx.log, unpacker::onLoad, app, vfs, engineNative, config.persistHiddenVm)
        val engineCtx = ctx.emu.engine
        val streamVm by lazy { EngineCompat.newVm(engineCtx, unpacker.limits, null, hooks.raw, true) }
        DexStreams(hidden.composite) { streamVm }.register(hooks)
        hidden.register()

        // Crypto capture: observe the real Cipher output (return hooks) and feed any decrypted DEX back in.
        val crypto = CryptoCapture(ctx.log, config) { bytes -> hidden.feedDecrypted(bytes) }.also { it.register(hooks) }

        // Custom (non-javax.crypto) decrypt capture: observe the real return of methods named in config.
        val decrypt = DecryptCapture(ctx.log, config).also { it.register(hooks) }

        // Run work the app dispatches to another thread / executor / handler INLINE, so a loader that
        // decrypts-and-loads off the main thread is followed. Those dispatch calls are otherwise denied
        // no-ops under the host policy, so the Runnable/Callable body (and any loader/crypto hook in it)
        // never runs. Executes on the same composite VM + hooks, in the caller's run.
        SyncScheduler(hidden.composite, hidden::invokeValue).register(hooks)

        unpacker.attach(hidden, app, profile, crypto, decrypt, engineNative)
        unpacker.registerActions()

        // Headless: run the sweep automatically during decompilation when autoUnpack=true.
        ctx.addPass(AutoUnpackPass(unpacker, config, ctx.log))
    }

    /**
     * The native bridge for VMs, resolved on first VM creation (not in [init]) because extensions initialize
     * in jar-name order and the backend provider (e.g. `native-unidbg`) may register later.
     *
     * With `nativeBackend=<id>` the ENGINE backend API is used: `ctx.emu.nativeBackend(id)` opens a
     * `NativeRequest(apk, methods, libs, abi)` and the returned bridge dispatches exactly the named methods.
     * `nativeBackend=none` keeps the validated bridge-less path; a `native-replay.properties` replay bridge
     * still applies on top when present.
     */
    private fun resolveNativeBridge(ctx: EmuExtensionContext, config: ExtensionConfig): EngineNativeBridge? {
        val which = config.nativeBackend
        if (which.equals("none", ignoreCase = true)) return null

        val replay = NativeBridge(ctx).also { it.load() }
        if (config.nativeMethods.isEmpty() || config.nativeLibs.isEmpty()) {
            ctx.log.info(
                "dexguard-unpacker: nativeBackend='{}' but nativeMethods ({}) / nativeLibs ({}) is empty; using replay only",
                which, config.nativeMethods.size, config.nativeLibs.size,
            )
            return replay.takeIf { it.hasReplay() }
        }
        return runCatching {
            val backend = ctx.emu.nativeBackend(which)
            if (backend == null) {
                ctx.log.warn(
                    "dexguard-unpacker: no native backend '{}' is registered (is jadx-emu-native-unidbg installed and active?); using replay",
                    which,
                )
                return@runCatching replay.takeIf { it.hasReplay() }
            }
            val apk = nativeApk(ctx, config)
            if (apk == null) {
                ctx.log.warn("dexguard-unpacker: could not resolve the APK holding the native libraries; using replay")
                return@runCatching replay.takeIf { it.hasReplay() }
            }
            ctx.log.info(
                "dexguard-unpacker: opening native backend '{}' ({} method(s), {} lib(s), {} from {})",
                backend.id, config.nativeMethods.size, config.nativeLibs.size, config.nativeAbi, apk.name,
            )
            val bridge = backend.open(NativeRequest(apk, config.nativeMethods.toSet(), config.nativeLibs, config.nativeAbi))
            ctx.log.info("dexguard-unpacker: native bridge ACTIVE ({})", backend.id)
            bridge
        }.getOrElse {
            ctx.log.warn("dexguard-unpacker: native backend init failed: {} — falling back to replay", it.toString())
            NativeBridge(ctx).also { r -> r.load() }.takeIf { r -> r.hasReplay() }
        }
    }

    /** The APK that holds the libraries: `nativeApk` config, else the jadx input; a bundle's arm64 split is extracted. */
    private fun nativeApk(ctx: EmuExtensionContext, config: ExtensionConfig): File? {
        config.nativeApk?.let { return File(it) }
        val apk = ctx.jadx.args.inputFiles.firstOrNull { Repackager.isApk(it) } ?: return null
        val f = File(apk.absolutePath)
        if (f.extension.lowercase() !in setOf("apks", "apkm", "xapk")) return f
        val abiDir = "split_config.${config.nativeAbi}.apk"
        return runCatching {
            val outDir = ctx.dataDir.resolve("native-split").also { Files.createDirectories(it) }
            val out = outDir.resolve(abiDir)
            if (!Files.exists(out)) {
                java.util.zip.ZipFile(f).use { bundle ->
                    val entry = bundle.getEntry(abiDir) ?: return@runCatching null
                    Files.copy(bundle.getInputStream(entry), out, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            }
            if (Files.exists(out)) out.toFile() else null
        }.onFailure { ctx.log.warn("dexguard-unpacker: cannot extract {} from {}: {}", abiDir, f.name, it.toString()) }
            .getOrNull()
    }
}
