package jadx.emu.ext.dexguard

import jadx.plugins.emu.api.EmuExtensionContext
import jadx.plugins.emu.exec.ExecLimits
import jadx.plugins.emu.exec.HookRegistry
import jadx.plugins.emu.exec.input.DexInputSource
import jadx.plugins.emu.exec.model.DexMethod
import jadx.plugins.emu.exec.runtime.DvmObject
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal class Unpacker(
    private val ctx: EmuExtensionContext,
    private val hooks: HookRegistry,
    private val config: ExtensionConfig,
) {
    val limits = ExecLimits(maxSteps = config.maxSteps, maxMillis = config.maxMillis, maxDepth = config.maxDepth)

    private val outDir: Path by lazy { ctx.dataDir.resolve(inputHash()).also { Files.createDirectories(it) } }

    // Per-candidate wall-clock deadline for the sweep (0 = none). Checked between invokes/rounds so a
    // decrypt in flight is never cut mid-run; it only stops launching *more* work for that candidate.
    @Volatile private var deadlineMs: Long = 0L

    // Runs are serialized: concurrent VMs share one HookRegistry and the recovered-dex state, so
    // overlapping clicks previously raced. One worker thread + a cancellable Future fixes it.
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dexguard-unpacker").apply { isDaemon = true } }
    @Volatile private var current: Future<*>? = null

    // Multi-stage fixpoint bookkeeping: classes newly revealed by a load, and (class|shortId) already run.
    private val pending = LinkedHashSet<String>()
    private val visited = HashSet<String>()

    private lateinit var hidden: HiddenDex
    private lateinit var app: AppInfo
    private lateinit var profile: DeviceProfile
    private var crypto: CryptoCapture? = null
    private var decrypt: DecryptCapture? = null

    fun attach(hidden: HiddenDex, app: AppInfo, profile: DeviceProfile, crypto: CryptoCapture?, decrypt: DecryptCapture?) {
        this.hidden = hidden
        this.app = app
        this.profile = profile
        this.crypto = crypto
        this.decrypt = decrypt
    }

    fun registerActions() {
        ctx.addCodeAction("DexGuard: unpack from this class", enabled = { !it.isMethod }) { target ->
            submit { unpackFromClass(target.rawClassName) }
        }
        ctx.addCodeAction("DexGuard: unpack from this method", enabled = { it.isMethod }) { target ->
            val rawClassName = target.rawClassName
            val shortId = target.shortId   // EmuTarget exposes the method short id directly
            submit { unpackFromMethod(rawClassName, shortId) }
        }
        ctx.addCodeAction("DexGuard: scan for loader classes", enabled = { true }) { _ ->
            submit { scan() }
        }
        ctx.addCodeAction("DexGuard: auto-sweep (scan + unpack top candidates)", enabled = { true }) { _ ->
            submit { sweep() }
        }
        ctx.addCodeAction("DexGuard: repackage APK with recovered dex", enabled = { true }) { _ ->
            submit { repackage() }
        }
        ctx.addCodeAction("DexGuard: cancel running unpack", enabled = { true }) { _ ->
            current?.cancel(true)?.let { ctx.log.info("cancellation requested") }
        }
    }

    private fun submit(body: () -> Unit) {
        current = worker.submit {
            try {
                body()
            } catch (e: InterruptedException) {
                ctx.log.info("unpack cancelled")
            } catch (e: Throwable) {
                ctx.log.warn("unpack failed: {}", e.toString())
            }
        }
    }

    fun onLoad(bytes: ByteArray, src: DexInputSource) {
        // Dump a header-repaired copy so dex2jar / baksmali accept the file even if DexGuard
        // left a stale checksum/signature.
        val repaired = DexBytes.repair(bytes)
        val name = "hidden-" + DexBytes.sha256(bytes).substring(0, 12) + ".dex"
        val path = outDir.resolve(name)
        if (!Files.exists(path)) Files.write(path, repaired)
        // Feed newly revealed classes into the fixpoint queue (these are hidden-dex classes).
        pending.addAll(src.allMethods().map { it.declClass }.distinct())
        ctx.log.info(
            "hidden dex {} ({} classDefs, {} classes) written to {}",
            name, DexBytes.classDefCount(bytes), src.allMethods().map { it.declClass }.distinct().size, path,
        )
    }

    private fun unpackFromClass(rawClassName: String) {
        startRun()
        val desc = ctx.emu.descriptorOf(rawClassName)
        val ran = runEntryPoints(desc)
        if (!ran) {
            ctx.log.warn("{} has no static initializer or known entry point", rawClassName)
            return
        }
        fixpoint()
        finish(rawClassName)
    }

    private fun unpackFromMethod(rawClassName: String, shortId: String?) {
        startRun()
        val desc = ctx.emu.descriptorOf(rawClassName)
        val m = shortId?.let { ctx.emu.source.method(desc, it) }
        if (m != null) {
            Telemetry.event("entrypoint", "$desc#$shortId (selected)")
            run(m, defaultArgsFor(m))
        } else {
            ctx.log.info("could not resolve the selected method; running class entry points instead")
            runEntryPoints(desc)
        }
        fixpoint()
        finish(rawClassName)
    }

    /** Runs <clinit> plus the common runtime entry points DexGuard decrypts from. */
    private fun runEntryPoints(desc: String): Boolean {
        var any = false
        for (shortId in ENTRY_POINTS) {
            if (pastDeadline()) break
            val m = ctx.emu.source.method(desc, shortId) ?: continue
            any = true
            Telemetry.event("entrypoint", "$desc#$shortId")
            run(m, defaultArgsFor(m))
        }
        return any
    }

    /**
     * After the seed class runs, drive the lifecycle entry points of any newly revealed hidden classes
     * (stage-2 Application / ContentProvider, etc.) until no new dex appears or the round cap is hit.
     * Bounded by a visited set and [ExtensionConfig.fixpointMaxRounds]; only lifecycle subclasses are
     * driven, so this doesn't blindly run every hidden <clinit>.
     */
    private fun fixpoint() {
        if (!config.fixpoint || !::hidden.isInitialized) return
        var round = 0
        while (pending.isNotEmpty() && round < config.fixpointMaxRounds) {
            if (pastDeadline()) break
            round++
            val batch = pending.toList()
            pending.clear()
            var ran = 0
            for (desc in batch) {
                if (pastDeadline()) break
                if (!isLifecycle(desc)) continue
                for (shortId in ENTRY_POINTS) {
                    if (pastDeadline()) break
                    if (!visited.add("$desc|$shortId")) continue
                    val m = hidden.composite.method(desc, shortId) ?: continue
                    ran++
                    Telemetry.event("fixpoint", "round $round: $desc#$shortId")
                    hidden.invokeHidden(m, defaultArgsFor(m))
                }
            }
            if (ran == 0) break
        }
    }

    private fun isLifecycle(desc: String): Boolean {
        if (!::hidden.isInitialized) return false
        var cur: String? = desc
        var hops = 0
        while (cur != null && hops++ < 24) {
            if (cur in LIFECYCLE) return true
            cur = hidden.composite.classInfo(cur)?.superType
        }
        return false
    }

    private fun scan() {
        ctx.log.info("scanning for loader-like classes (mode={})...", config.scanMode)
        val hits = CandidateScanner(ctx.jadx.args.inputFiles, ctx.emu.source, config.scanMode).scan()
        if (hits.isEmpty()) {
            ctx.log.info("no obvious loader candidates found")
            return
        }
        ctx.log.info("top DexGuard loader candidates:")
        hits.forEachIndexed { i, h -> ctx.log.info("  {}. {} (score {}) - {}", i + 1, pretty(h.cls), h.score, h.reasons.joinToString(", ")) }
    }

    private fun pretty(desc: String): String = desc.removePrefix("L").removeSuffix(";").replace('/', '.')

    private fun toDesc(s: String): String = if (s.startsWith("L") && s.endsWith(";")) s else "L" + s.replace('.', '/') + ";"

    /** One click: scan for loader candidates, then run entry points on the top-ranked ones. */
    private fun sweep() {
        startRun()
        ctx.log.info("auto-sweep: ranking loader candidates (mode={})...", config.scanMode)
        val hits = CandidateScanner(ctx.jadx.args.inputFiles, ctx.emu.source, config.scanMode).scan(limit = config.sweepTopN)
        // User-injected known loaders run first, then scanner hits. Descriptors, deduped, order preserved.
        val targets = LinkedHashSet<String>()
        config.sweepClasses.forEach { targets.add(toDesc(it)) }
        hits.forEach { targets.add(it.cls) }
        if (targets.isEmpty()) {
            ctx.log.info("auto-sweep: no candidates found")
            return
        }
        ctx.log.info("auto-sweep: trying {} target(s)", targets.size)
        val budget = config.sweepBudgetMs
        var idx = 0
        for (desc in targets) {
            idx++
            if (Thread.currentThread().isInterrupted) {
                ctx.log.info("auto-sweep: cancelled")
                break
            }
            ctx.log.info("auto-sweep [{}/{}]: {}", idx, targets.size, pretty(desc))
            val cStart = System.currentTimeMillis()
            val cBefore = recoveredFiles().size
            deadlineMs = if (budget > 0L) cStart + budget else 0L
            runCatching { runEntryPoints(desc) }
                .onFailure { ctx.log.warn("auto-sweep: {} failed: {}", pretty(desc), it.toString()) }
            fixpoint()
            deadlineMs = 0L
            val elapsed = System.currentTimeMillis() - cStart
            val got = recoveredFiles().size - cBefore
            if (got > 0 || elapsed >= 2000) {
                val capped = if (budget > 0L && elapsed >= budget) " (budget hit)" else ""
                ctx.log.info("  -> {} new dex, {} ms{}", got, elapsed, capped)
            }
        }
        finish("auto-sweep")
    }

    private fun repackage() {
        val dexes = recoveredDexBytes()
        if (dexes.isEmpty()) {
            ctx.log.info("nothing to repackage yet - unpack a class first")
            return
        }
        val apk = ctx.jadx.args.inputFiles.firstOrNull { Repackager.isApk(it) }
        if (apk == null) {
            ctx.log.info("no input APK found to repackage into")
            return
        }
        val out = Repackager.repackage(apk, dexes, outDir)
        if (out != null) {
            ctx.log.info("wrote unsigned APK with recovered dex: {} (re-sign with: apksigner sign --ks <ks> {})", out, out.fileName)
        }
    }

    private fun startRun() {
        pending.clear()
        visited.clear()
        deadlineMs = 0L
        Telemetry.reset()
    }

    private fun pastDeadline(): Boolean = deadlineMs > 0L && System.currentTimeMillis() > deadlineMs

    private fun finish(label: String) {
        val files = recoveredFiles()
        // Count from disk - the authoritative set. The in-memory load list undercounts because the
        // engine dedups a dex it already saw this session, so a re-recovered dex wouldn't be re-added.
        ctx.log.info("{}: {} hidden dex file(s) on disk ({})", label, files.size, outDir)
        writeReport(files)
        Telemetry.write(outDir.resolve("telemetry.json"))
        if (Telemetry.enabled) ctx.log.info("telemetry written to {}", outDir.resolve("telemetry.json"))
        crypto?.flush(outDir)
        decrypt?.flush(outDir)
        if (files.isNotEmpty()) offer(files)
    }

    private fun run(method: DexMethod, args: List<Any?>) {
        val vm = ctx.emu.engine.newVm(limits, hooks = hooks, androidEnvUnknown = false)
        // Instance entry points (attachBaseContext/onCreate/attachInfo) need a non-null `this`.
        val receiver = if (method.isStatic) null else DvmObject(method.declClass)
        runCatching { vm.invoke(method, args, receiver) }
            .onFailure { ctx.log.warn("{}#{}: failed: {}", method.declClass, method.ref.name, it.toString()) }
    }

    private fun offer(paths: List<Path>) {
        val gui = ctx.gui ?: run {
            ctx.log.info("add them to the jadx inputs to decompile the hidden classes: {}", paths.joinToString(" "))
            return
        }
        val current = ctx.jadx.args.inputFiles.map { it.toPath().toAbsolutePath() }.toSet()
        val missing = paths.filter { it.toAbsolutePath() !in current }
        if (missing.isEmpty()) return
        gui.uiRun {
            runCatching {
                gui.mainFrame.javaClass.getMethod("addFiles", List::class.java).invoke(gui.mainFrame, missing)
            }.onFailure { ctx.log.warn("cannot add unpacked dex files to the project: {}", it.toString()) }
        }
    }

    private fun writeReport(files: List<Path>) {
        runCatching {
            val env = if (::profile.isInitialized) profile else DeviceProfile()
            val pkg = if (::app.isInitialized) runCatching { app.packageName }.getOrNull() else null
            RunReport.write(outDir.resolve("report.json"), recoveredDexBytes(files), files, env, pkg)
        }.onFailure { ctx.log.debug("could not write report.json: {}", it.toString()) }
    }

    /** The authoritative recovered set: hidden-*.dex actually present in the output dir. */
    private fun recoveredFiles(): List<Path> = runCatching {
        Files.newDirectoryStream(outDir, "hidden-*.dex").use { ds -> ds.sortedBy { it.fileName.toString() } }
    }.getOrDefault(emptyList())

    private fun recoveredDexBytes(files: List<Path> = recoveredFiles()): List<ByteArray> =
        files.mapNotNull { runCatching { Files.readAllBytes(it) }.getOrNull() }.filter { DexBytes.isDex(it) }

    private fun contextStub(): DvmObject = DvmObject("Landroid/content/Context;")

    private fun defaultArgsFor(m: DexMethod): List<Any?> = m.ref.argTypes.map { t ->
        when (t) {
            "I", "S", "B", "C" -> 0
            "J" -> 0L
            "Z" -> false
            "F" -> 0f
            "D" -> 0.0
            "Landroid/content/Context;" -> contextStub()
            else -> null
        }
    }


    private fun inputHash(): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (f in ctx.jadx.args.inputFiles.sortedBy { it.absolutePath }) {
            md.update(f.absolutePath.toByteArray())
            md.update(f.length().toString().toByteArray())
            md.update(f.lastModified().toString().toByteArray())
        }
        return md.digest().joinToString("") { "%02x".format(it) }.substring(0, 16)
    }

    private companion object {
        // Well-known runtime entry points; args are synthesized from each method's signature.
        val ENTRY_POINTS: List<String> = listOf(
            "<clinit>()V",
            "attachBaseContext(Landroid/content/Context;)V",
            "onCreate()V",
            "onCreate()Z",                                            // ContentProvider
            "attachInfo(Landroid/content/Context;Landroid/content/pm/ProviderInfo;)V",
        )

        // Lifecycle base classes whose subclasses the fixpoint will drive.
        val LIFECYCLE: Set<String> = setOf(
            "Landroid/app/Application;",
            "Landroid/content/ContextWrapper;",
            "Landroid/content/ContentProvider;",
            "Landroid/app/Activity;",
            "Landroid/app/Service;",
            "Landroid/content/BroadcastReceiver;",
        )
    }
}
