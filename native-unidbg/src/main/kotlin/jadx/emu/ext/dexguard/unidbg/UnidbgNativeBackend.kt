package jadx.emu.ext.dexguard.unidbg

import jadx.emu.ext.dexguard.NativeBackendProvider
import jadx.plugins.emu.api.EmuExtensionContext
import jadx.plugins.emu.exec.NotHandled
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.zip.ZipFile
import jadx.plugins.emu.exec.NativeBridge as EngineNativeBridge

/**
 * unidbg-backed native (JNI) execution for the engine's NativeBridge seam.
 *
 * SURGICAL BY DESIGN. Only the native methods you list in `nativeMethods` are executed through unidbg;
 * every other native call returns NotHandled (=> UNKNOWN), so a protected app's RASP native calls
 * behave exactly as they do without this backend. If `nativeMethods` is empty, NO bridge is created at
 * all. This is deliberate: a bridge that intercepts every native call changes the engine's dispatch and
 * breaks emulation of protected apps (learned the hard way).
 *
 * Config (in <dataDir>/dexguard-unpacker.properties):
 *   nativeBackend=unidbg
 *   nativeMethods=Lcom/foo/Bar;->decrypt,Lcom/foo/Baz;->unpack   # REQUIRED - which natives to execute
 *   nativeAbi=arm64-v8a      # which lib/<abi> to pull from the APK (default arm64-v8a)
 *   nativeLibs=libfoo.so     # comma list, primary first; empty = all <abi> .so
 *   nativeSdk=23             # AndroidResolver SDK level for system libs
 *   nativeVerbose=false      # unidbg's own instruction/JNI tracing (loud; for debugging a failing .so)
 *
 * Phase 1: self-contained natives ((byte[]|String|primitives) in, byte[]|String|primitive out). Phase 2
 * (native code calling back into Java) and Phase 3 (RASP fidelity) are not implemented. Validate against
 * a known decryptor - Standalone.kt runs a .so method outside jadx for fast iteration.
 */
class UnidbgNativeBackend : NativeBackendProvider {
    override val id = "unidbg"

    override fun create(ctx: EmuExtensionContext): EngineNativeBridge? {
        val props = readProps(ctx.dataDir.resolve("dexguard-unpacker.properties"))
        val allow = csv(props.getProperty("nativeMethods")).toSet()
        if (allow.isEmpty()) {
            ctx.log.warn(
                "unidbg backend: nativeMethods is empty - nothing to execute, not wiring a bridge. " +
                    "Set nativeMethods=Lcls;->name,... to the decryptor(s) unidbg should run.",
            )
            return null
        }
        val abi = props.getProperty("nativeAbi", "arm64-v8a")
        val apk = ctx.jadx.args.inputFiles.firstOrNull { isApk(it) } ?: run {
            ctx.log.warn("unidbg backend: no input APK found"); return null
        }
        val extracted = extractSoLibs(apk, abi, csv(props.getProperty("nativeLibs")))
        if (extracted.isEmpty()) { ctx.log.warn("unidbg backend: no {} .so extracted from {}", abi, apk.name); return null }
        val sdk = props.getProperty("nativeSdk", "23").trim().toIntOrNull() ?: 23
        val verbose = props.getProperty("nativeVerbose", "false").trim().lowercase() in setOf("true", "1", "yes", "on")
        ctx.log.info("unidbg backend: {} lib(s) [{}], will execute {} method(s)", extracted.size, abi, allow.size)
        return UnidbgNativeBridge(ctx, UnidbgRunner(extracted, sdk, verbose) { ctx.log.warn("unidbg: {}", it) }, allow)
    }

    private fun readProps(f: Path): Properties {
        val p = Properties()
        if (Files.exists(f)) runCatching { Files.newInputStream(f).use { p.load(it) } }
        return p
    }
    private fun csv(s: String?): List<String> = (s ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
    private fun isApk(f: File): Boolean =
        runCatching { ZipFile(f).use { it.getEntry("AndroidManifest.xml") != null } }.getOrDefault(false)

    private fun extractSoLibs(apk: File, abi: String, want: List<String>): List<File> {
        val dir = Files.createTempDirectory("unidbg-so").toFile()
        val out = ArrayList<File>()
        runCatching {
            ZipFile(apk).use { zip ->
                val entries = zip.entries().asSequence()
                    .filter { it.name.startsWith("lib/$abi/") && it.name.endsWith(".so") }.toList()
                val chosen = if (want.isEmpty()) entries else entries.filter { e -> want.any { e.name.endsWith("/$it") } }
                for (e in chosen) {
                    val dst = File(dir, e.name.substringAfterLast('/'))
                    zip.getInputStream(e).use { ins -> dst.outputStream().use { ins.copyTo(it) } }
                    if (want.isNotEmpty() && e.name.endsWith("/${want.first()}")) out.add(0, dst) else out.add(dst)
                }
            }
        }
        return out
    }
}

/** Bridge: routes only whitelisted native methods to the runner; logs each; NotHandled on anything else. */
internal class UnidbgNativeBridge(
    private val ctx: EmuExtensionContext,
    private val runner: UnidbgRunner,
    private val allow: Set<String>,
) : EngineNativeBridge {

    override fun call(className: String, methodName: String, signature: String, args: List<Any?>, receiver: Any?): Any? {
        val key = "L$className;->$methodName"
        if (key !in allow) return NotHandled              // only named decryptors run through unidbg
        return runCatching {
            val r = runner.invoke(className, methodName, signature, args, receiver)
            ctx.log.info("unidbg: {}{} -> {}", key, signature, describe(r))
            r ?: NotHandled
        }.getOrElse {
            ctx.log.warn("unidbg: {}{} FAILED: {}", key, signature, it.toString())
            NotHandled
        }
    }

    private fun describe(r: Any?): String = when (r) {
        null -> "null"
        is ByteArray -> "byte[${r.size}]"
        is String -> "\"${r.take(40)}\"" + if (r.length > 40) "…" else ""
        else -> r.toString()
    }
}
