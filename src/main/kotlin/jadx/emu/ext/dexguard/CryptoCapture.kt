package jadx.emu.ext.dexguard

import org.slf4j.Logger
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Captures symmetric decryption as it happens and, when the plaintext is a DEX (or a zip of DEX),
 * feeds it straight into the loader pipeline. DexGuard's dex decrypt is almost always a
 * `Cipher.doFinal`, so grabbing the plaintext gets the dex out regardless of how complete the
 * class-loader emulation chain is.
 *
 * Since jadx-emu gained return-value hooks ([ExtHooks.addReturn]), this OBSERVES the real output
 * of the host `Cipher` - it no longer reproduces the cipher from a tracked key/iv/transformation, and
 * it no longer has to guess a transformation when none was seen. The `SecretKeySpec` / `IvParameterSpec`
 * / `getInstance` / `init` hooks are kept purely to record key material for the `crypto.json` index.
 *
 * Streaming is handled: the real `update()` chunks are accumulated per cipher and joined with the
 * final `doFinal` block, so a streamed decrypt is captured whole (the app still receives each real
 * chunk unchanged - we only observe).
 *
 * Assumes byte[] args/returns arrive as real ByteArray (the rest of this extension relies on the same).
 */
internal class CryptoCapture(
    private val log: Logger,
    private val config: ExtensionConfig,
    private val onDex: (ByteArray) -> Unit,
) {
    private data class KeyMat(val bytes: ByteArray, val algo: String)
    private data class CipherState(val transformation: String?, val opmode: Int, val key: ByteArray, val keyAlgo: String, val iv: ByteArray?)
    private class Captured(val transformation: String?, val opmode: Int, val keyHex: String?, val ivHex: String?, val inLen: Int, val out: ByteArray, val dexLike: Boolean)

    private val keySpecs = java.util.IdentityHashMap<Any, KeyMat>()
    private val ivSpecs = java.util.IdentityHashMap<Any, ByteArray>()
    private val ciphers = java.util.IdentityHashMap<Any, CipherState>()
    private val streamBufs = java.util.IdentityHashMap<Any, ByteArrayOutputStream>()
    private val lastTransformation = ThreadLocal<String?>()
    private val captured = java.util.Collections.synchronizedList(ArrayList<Captured>())

    fun register(hooks: ExtHooks) {
        if (!config.captureCrypto) return

        // --- key material (before-hooks; observers, recorded for the crypto.json index only) ---
        hooks.add("Ljavax/crypto/spec/SecretKeySpec;-><init>([BLjava/lang/String;)V") { call ->
            val recv = call.receiver ?: return@add
            val bytes = call.args.getOrNull(0) as? ByteArray ?: return@add
            val algo = call.args.getOrNull(1) as? String ?: "AES"
            keySpecs[recv] = KeyMat(bytes.copyOf(), algo)
        }
        hooks.add("Ljavax/crypto/spec/SecretKeySpec;-><init>([BIILjava/lang/String;)V") { call ->
            val recv = call.receiver ?: return@add
            val bytes = call.args.getOrNull(0) as? ByteArray ?: return@add
            val off = call.args.getOrNull(1) as? Int ?: 0
            val len = call.args.getOrNull(2) as? Int ?: (bytes.size - off)
            val algo = call.args.getOrNull(3) as? String ?: "AES"
            keySpecs[recv] = KeyMat(bytes.copyOfRange(off, off + len), algo)
        }
        hooks.add("Ljavax/crypto/spec/IvParameterSpec;-><init>([B)V") { call ->
            val recv = call.receiver ?: return@add
            (call.args.getOrNull(0) as? ByteArray)?.let { ivSpecs[recv] = it.copyOf() }
        }
        hooks.add("Ljavax/crypto/spec/IvParameterSpec;-><init>([BII)V") { call ->
            val recv = call.receiver ?: return@add
            val iv = call.args.getOrNull(0) as? ByteArray ?: return@add
            val off = call.args.getOrNull(1) as? Int ?: 0
            val len = call.args.getOrNull(2) as? Int ?: (iv.size - off)
            ivSpecs[recv] = iv.copyOfRange(off, off + len)
        }
        hooks.add("Ljavax/crypto/spec/GCMParameterSpec;-><init>(I[B)V") { call ->
            val recv = call.receiver ?: return@add
            (call.args.getOrNull(1) as? ByteArray)?.let { ivSpecs[recv] = it.copyOf() }
        }

        // transformation hint (consumed at init, same thread)
        for (sig in listOf(
            "Ljavax/crypto/Cipher;->getInstance(Ljava/lang/String;)Ljavax/crypto/Cipher;",
            "Ljavax/crypto/Cipher;->getInstance(Ljava/lang/String;Ljava/lang/String;)Ljavax/crypto/Cipher;",
            "Ljavax/crypto/Cipher;->getInstance(Ljava/lang/String;Ljava/security/Provider;)Ljavax/crypto/Cipher;",
        )) hooks.add(sig) { call -> (call.args.getOrNull(0) as? String)?.let { lastTransformation.set(it) } }

        // init: bind opmode + key + iv to the cipher object (metadata only)
        for (sig in INIT_SIGS) hooks.add(sig) { call ->
            val recv = call.receiver ?: return@add
            val opmode = call.args.getOrNull(0) as? Int ?: return@add
            val km = resolveKey(call.args.getOrNull(1)) ?: return@add
            val iv = resolveIv(call.args.getOrNull(2))
            val tr = lastTransformation.get()
            lastTransformation.remove()
            ciphers[recv] = CipherState(tr, opmode, km.bytes, km.algo, iv)
        }

        // --- real output capture (return-hooks) ---
        // update(): accumulate each real chunk so a streamed decrypt is captured whole. Observe only.
        for (sig in UPDATE_SIGS) hooks.addReturn(sig) { call, result ->
            val recv = call.receiver
            if (recv != null && result is ByteArray && result.isNotEmpty()) {
                streamBufs.getOrPut(recv) { ByteArrayOutputStream() }.write(result)
            }
            result
        }
        // doFinal(): the real plaintext. Join any accumulated update() output, check, feed. Observe only.
        hooks.addReturn("Ljavax/crypto/Cipher;->doFinal([B)[B") { call, result ->
            if (result is ByteArray) onDoFinal(call.receiver, (call.args.getOrNull(0) as? ByteArray)?.size ?: result.size, result)
            result
        }
        hooks.addReturn("Ljavax/crypto/Cipher;->doFinal([BII)[B") { call, result ->
            if (result is ByteArray) onDoFinal(call.receiver, (call.args.getOrNull(2) as? Int) ?: result.size, result)
            result
        }
    }

    /** Clear per-run capture state so counts/outputs reflect only the current run. */
    fun reset() {
        synchronized(captured) { captured.clear() }
        keySpecs.clear(); ivSpecs.clear(); ciphers.clear(); streamBufs.clear()
    }

    private fun onDoFinal(cipher: Any?, inLen: Int, finalOut: ByteArray) {
        // Join streamed update() output (if this cipher was used in streaming mode) with the final block.
        val buf = cipher?.let { streamBufs.remove(it) }
        val out = if (buf != null && buf.size() > 0) buf.apply { write(finalOut) }.toByteArray() else finalOut
        val dexLike = DexBytes.isDex(out) || (DexBytes.looksLikeZip(out) && DexBytes.dexesFromZip(out).isNotEmpty())
        val st = cipher?.let { ciphers[it] }
        captured.add(Captured(st?.transformation, st?.opmode ?: -1, st?.key?.let { hex(it) }, st?.iv?.let { hex(it) }, inLen, out, dexLike))
        Telemetry.count("crypto.captured")
        Telemetry.event("crypto", "${st?.transformation ?: "?"} op=${st?.opmode ?: -1} $inLen->${out.size}${if (dexLike) " DEX" else ""}")
        if (dexLike && config.feedDecryptedDex) {
            Telemetry.count("crypto.fedDex")
            log.info("crypto capture: decrypted {}-byte DEX payload ({}), feeding to loader", out.size, st?.transformation ?: "unknown transformation")
            runCatching { onDex(out) }.onFailure { log.warn("feed decrypted dex failed: {}", it.toString()) }
        }
    }

    private fun resolveKey(k: Any?): KeyMat? {
        if (k == null) return null
        keySpecs[k]?.let { return it }
        // host java.security.Key fallback
        val bytes = runCatching { k.javaClass.getMethod("getEncoded").invoke(k) as? ByteArray }.getOrNull() ?: return null
        val algo = runCatching { k.javaClass.getMethod("getAlgorithm").invoke(k) as? String }.getOrNull() ?: "AES"
        return KeyMat(bytes, algo)
    }

    private fun resolveIv(p: Any?): ByteArray? {
        if (p == null) return null
        ivSpecs[p]?.let { return it }
        return runCatching { p.javaClass.getMethod("getIV").invoke(p) as? ByteArray }.getOrNull()
    }

    /** Writes a crypto.json index plus non-dex plaintexts (dex payloads already land as hidden-*.dex). */
    fun flush(outDir: Path) {
        val snapshot = synchronized(captured) { captured.toList() }
        if (snapshot.isEmpty()) return
        runCatching {
            val dir = outDir.resolve("crypto")
            Files.createDirectories(dir)
            val sb = StringBuilder("[\n")
            snapshot.forEachIndexed { i, c ->
                val sha = DexBytes.sha256(c.out)
                if ((!c.dexLike || !config.feedDecryptedDex) && c.out.size <= MAX_DUMP) {
                    runCatching { Files.write(dir.resolve("plain-${sha.substring(0, 12)}.bin"), c.out) }
                }
                sb.append("  {")
                    .append("\"transformation\": ").append(if (c.transformation == null) "null" else str(c.transformation))
                    .append(", \"opmode\": ").append(c.opmode)
                    .append(", \"keyHex\": ").append(if (c.keyHex == null) "null" else str(c.keyHex))
                    .append(", \"ivHex\": ").append(if (c.ivHex == null) "null" else str(c.ivHex))
                    .append(", \"inLen\": ").append(c.inLen)
                    .append(", \"outLen\": ").append(c.out.size)
                    .append(", \"outSha256\": ").append(str(sha))
                    .append(", \"dexLike\": ").append(c.dexLike)
                    .append("}")
                sb.append(if (i == snapshot.lastIndex) "\n" else ",\n")
            }
            sb.append("]\n")
            Files.write(dir.resolve("crypto.json"), sb.toString().toByteArray())
            log.info("crypto capture: {} record(s) written to {}", snapshot.size, dir.resolve("crypto.json"))
        }.onFailure { log.debug("cannot write crypto.json: {}", it.toString()) }
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
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

    companion object {
        private const val MAX_DUMP = 8 * 1024 * 1024
        private val INIT_SIGS = listOf(
            "Ljavax/crypto/Cipher;->init(ILjava/security/Key;)V",
            "Ljavax/crypto/Cipher;->init(ILjava/security/Key;Ljava/security/SecureRandom;)V",
            "Ljavax/crypto/Cipher;->init(ILjava/security/Key;Ljava/security/spec/AlgorithmParameterSpec;)V",
            "Ljavax/crypto/Cipher;->init(ILjava/security/Key;Ljava/security/spec/AlgorithmParameterSpec;Ljava/security/SecureRandom;)V",
            "Ljavax/crypto/Cipher;->init(ILjava/security/Key;Ljava/security/AlgorithmParameters;)V",
            "Ljavax/crypto/Cipher;->init(ILjava/security/Key;Ljava/security/AlgorithmParameters;Ljava/security/SecureRandom;)V",
        )
        private val UPDATE_SIGS = listOf(
            "Ljavax/crypto/Cipher;->update([B)[B",
            "Ljavax/crypto/Cipher;->update([BII)[B",
        )

        /** Pure host-side cipher, exposed for unit testing. Returns null on any failure. */
        fun runCipher(transformation: String, opmode: Int, key: ByteArray, keyAlgo: String, iv: ByteArray?, input: ByteArray): ByteArray? =
            runCatching {
                val c = Cipher.getInstance(transformation)
                val sk = SecretKeySpec(key, keyAlgo.substringBefore('/'))
                when {
                    transformation.contains("GCM") && iv != null -> c.init(opmode, sk, GCMParameterSpec(128, iv))
                    iv != null -> c.init(opmode, sk, IvParameterSpec(iv))
                    else -> c.init(opmode, sk)
                }
                c.doFinal(input)
            }.getOrNull()
    }
}
