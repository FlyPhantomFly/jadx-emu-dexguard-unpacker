package jadx.emu.ext.dexguard

import jadx.plugins.emu.exec.HookRegistry
import jadx.plugins.emu.exec.MethodSource
import jadx.plugins.emu.exec.model.DexMethod
import org.slf4j.Logger
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections

/**
 * Captures the input/output of custom (non-javax.crypto) decrypt methods, so a target's homemade
 * string/resource/stream deobfuscation can be reconstructed from ground-truth pairs instead of
 * hand-reversing flattened, opaque-predicate-laden smali.
 *
 * You name the methods in config (`captureDecryptMethods=<full sig>,...`). For each, when the app
 * calls it, we re-invoke the REAL method once on the emulator (guarded against re-entry), capture the
 * arguments and the returned value, then return that value to the app - so execution is unchanged, we
 * just observe it. A returned InputStream is read out and replayed as a fresh stream so the app still
 * gets its bytes.
 *
 * NEEDS-VERIFICATION: relies on the re-invoke returning the same value the direct call would (true for
 * methods that depend only on receiver + args, which custom decryptors are), and on the emulator
 * modelling byte[]/String/InputStream as host values (the rest of this extension assumes the same).
 * An InputStream *argument* is consumed by the re-invoke, so for stream inputs only the output is
 * captured; byte[]/String inputs are captured on both sides.
 */
internal class DecryptCapture(
    private val log: Logger,
    private val config: ExtensionConfig,
    private val source: MethodSource,
    private val invoke: (DexMethod, List<Any?>, Any?) -> Any?,
) {
    private class Rec(val sig: String, val inputs: String, val outBytes: ByteArray?, val outText: String?)
    private class Out(val forApp: Any?, val bytes: ByteArray?, val text: String?)

    private val recs = Collections.synchronizedList(ArrayList<Rec>())
    private val inFlight = ThreadLocal.withInitial { HashSet<String>() }

    fun register(hooks: HookRegistry) {
        if (!config.captureDecrypt) return
        val sigs = config.captureDecryptMethods
        if (sigs.isEmpty()) {
            log.info("dexguard-unpacker: captureDecrypt is on but captureDecryptMethods is empty - nothing to hook")
            return
        }
        for (sig in sigs) hooks.add(sig) { call ->
            val set = inFlight.get()
            if (sig in set) return@add                       // re-invoke path: let the real body run
            val m = resolve(sig) ?: return@add               // not loaded/resolvable yet: run normally, no capture
            set.add(sig)
            val result = try { invoke(m, call.args, call.receiver) } finally { set.remove(sig) }
            // We consumed the real execution via the re-invoke, so we MUST return a value here or the
            // call would fall through and execute the body a second time.
            val out = materialize(result)
            recs.add(Rec(sig, describeInputs(call.args), out.bytes, out.text))
            Telemetry.count("decrypt.captured")
            call.replace(out.forApp)
        }
        log.info("dexguard-unpacker: decrypt capture armed for {} method(s)", sigs.size)
    }

    private fun resolve(sig: String): DexMethod? {
        val cls = sig.substringBefore("->", "")
        val shortId = sig.substringAfter("->", "")
        if (cls.isEmpty() || shortId.isEmpty()) return null
        return runCatching { source.method(cls, shortId) }.getOrNull()
    }

    private fun materialize(result: Any?): Out = when (result) {
        is ByteArray -> Out(result, result, null)
        is String -> Out(result, result.toByteArray(Charsets.UTF_8), result)
        is InputStream -> {
            val b = runCatching { result.readBytes() }.getOrDefault(ByteArray(0))
            Out(ByteArrayInputStream(b), b, null)
        }
        else -> Out(result, null, null)
    }

    private fun describeInputs(args: List<Any?>): String = args.joinToString(", ") { a ->
        when (a) {
            null -> "null"
            is ByteArray -> "byte[${a.size}]:" + a.take(16).joinToString("") { "%02x".format(it) }
            is String -> "\"" + (if (a.length > 40) a.take(40) + "..." else a) + "\""
            is Int, is Long, is Short, is Byte, is Boolean, is Char -> a.toString()
            is InputStream -> "<inputstream>"
            else -> a.javaClass.simpleName
        }
    }

    fun flush(outDir: Path) {
        val snapshot = synchronized(recs) { recs.toList() }
        if (snapshot.isEmpty()) return
        runCatching {
            val dir = outDir.resolve("decrypt")
            Files.createDirectories(dir)
            val sb = StringBuilder("[\n")
            snapshot.forEachIndexed { i, r ->
                val sha = r.outBytes?.let { DexBytes.sha256(it) }
                val bytes = r.outBytes
                if (bytes != null && sha != null && r.outText == null && bytes.size <= MAX_DUMP) {
                    runCatching { Files.write(dir.resolve("out-${sha.substring(0, 12)}.bin"), bytes) }
                }
                sb.append("  {")
                    .append("\"method\": ").append(str(r.sig))
                    .append(", \"inputs\": ").append(str(r.inputs))
                    .append(", \"outLen\": ").append(r.outBytes?.size ?: 0)
                if (sha != null) sb.append(", \"outSha256\": ").append(str(sha))
                if (r.outText != null) sb.append(", \"outText\": ").append(str(if (r.outText.length > 200) r.outText.take(200) + "..." else r.outText))
                sb.append("}")
                sb.append(if (i == snapshot.lastIndex) "\n" else ",\n")
            }
            sb.append("]\n")
            Files.write(dir.resolve("decrypt-capture.json"), sb.toString().toByteArray())
            log.info("dexguard-unpacker: {} decrypt record(s) written to {}", snapshot.size, dir.resolve("decrypt-capture.json"))
        }.onFailure { log.debug("cannot write decrypt-capture.json: {}", it.toString()) }
    }

    private fun str(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private companion object {
        const val MAX_DUMP = 8 * 1024 * 1024
    }
}
