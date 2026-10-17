package jadx.emu.ext.dexguard

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
 * You name the methods in config (`captureDecryptMethods=<full sig>,...`). Since jadx-emu gained
 * return-value hooks ([ExtHooks.addReturn]), we OBSERVE each named method's real return as it runs
 * - no re-invocation, no re-entry guard, and (unlike before) InputStream *arguments* are no longer
 * consumed. A returned InputStream is read out for capture and replaced with a fresh stream over the
 * same bytes, so the app still gets its data; everything else is passed through unchanged.
 */
internal class DecryptCapture(
    private val log: Logger,
    private val config: ExtensionConfig,
) {
    private class Rec(val sig: String, val inputs: String, val outBytes: ByteArray?, val outText: String?)
    private class Out(val forApp: Any?, val bytes: ByteArray?, val text: String?)

    private val recs = Collections.synchronizedList(ArrayList<Rec>())

    /** Clear per-run capture state. */
    fun reset() { synchronized(recs) { recs.clear() } }

    fun register(hooks: ExtHooks) {
        if (!config.captureDecrypt) return
        val sigs = config.captureDecryptMethods
        if (sigs.isEmpty()) {
            log.info("dexguard-unpacker: captureDecrypt is on but captureDecryptMethods is empty - nothing to hook")
            return
        }
        for (sig in sigs) hooks.addReturn(sig) { call, result ->
            val out = materialize(result)
            recs.add(Rec(sig, describeInputs(call.args), out.bytes, out.text))
            Telemetry.count("decrypt.captured")
            out.forApp                              // return the (re-wrapped, for InputStream) value to the app
        }
        log.info("dexguard-unpacker: decrypt capture armed for {} method(s)", sigs.size)
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

    private fun str(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u").append("%04x".format(c.code)) else sb.append(c)
        }
        return sb.append("\"").toString()
    }

    private companion object {
        const val MAX_DUMP = 8 * 1024 * 1024
    }
}
