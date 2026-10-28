package jadx.emu.ext.dexguard

import jadx.plugins.emu.api.EmuExtension
import jadx.plugins.emu.api.EmuExtensionContext
import jadx.plugins.emu.api.EmuExtensionInfo
import jadx.plugins.emu.api.ExtensionMode
import jadx.plugins.emu.exec.NativeBridge as EngineNativeBridge
import java.util.ServiceLoader

const val DEXGUARD_UNPACKER_VERSION = "2.1.0"

class DexGuardExtension : EmuExtension {

    override fun info(): EmuExtensionInfo = EmuExtensionInfo(
        id = "dexguard-unpacker",
        name = "DexGuard unpacker",
        description = "Unpacks the dex files DexGuard loads at runtime",
        mode = ExtensionMode.ON_DEMAND,
        requiredEmuVersion = "0.1.0",
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
        val backend = loadNativeBackend(ctx, config)
        val replay = NativeBridge(ctx).also { it.load() }
        replay.register(hooks)
        val engineNative: EngineNativeBridge? = backend ?: replay.takeIf { it.hasReplay() }
        if (engineNative != null) {
            ctx.log.info("dexguard-unpacker: native bridge ACTIVE ({})", if (backend != null) config.nativeBackend else "replay")
        }

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

    /** Discover an optional native backend (e.g. unidbg) via ServiceLoader when configured. */
    private fun loadNativeBackend(ctx: EmuExtensionContext, config: ExtensionConfig): EngineNativeBridge? {
        val which = config.nativeBackend
        if (which.equals("none", ignoreCase = true)) return null
        return runCatching {
            val cl = Thread.currentThread().contextClassLoader ?: javaClass.classLoader
            val provider = ServiceLoader.load(NativeBackendProvider::class.java, cl)
                .firstOrNull { it.id.equals(which, ignoreCase = true) }
            if (provider == null) {
                ctx.log.info("dexguard-unpacker: native backend '{}' not found on classpath; using replay", which)
                null
            } else {
                ctx.log.info("dexguard-unpacker: using native backend '{}'", provider.id)
                provider.create(ctx)
            }
        }.onFailure { ctx.log.warn("dexguard-unpacker: native backend init failed: {}", it.toString()) }.getOrNull()
    }
}
