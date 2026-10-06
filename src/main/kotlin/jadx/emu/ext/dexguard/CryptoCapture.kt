package jadx.emu.ext.dexguard

import jadx.plugins.emu.exec.HookRegistry
import org.slf4j.Logger
import java.nio.file.Files
import java.nio.file.Path
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Captures symmetric decryption as it happens and, when the plaintext is a DEX (or a zip of DEX),
 * feeds it straight into the loader pipeline. This is the pragmatic fallback for when the
 * class-loader emulation chain is incomplete: DexGuard's dex decrypt is almost always a single
 * `Cipher.doFinal`, so recovering (key, iv, ciphertext->plaintext) gets the dex out regardless.
 *
 * How it works: the replace-only hook model can't observe the *return* of a host-executed call, so
 * at `doFinal` we reproduce the cipher ourselves on the host JVM using the key/iv/transformation we
 * tracked from `SecretKeySpec` / `IvParameterSpec` / `Cipher.getInstance` / `Cipher.init`, capture the
 * result, and replace the call with it. If anything is missing or the host cipher throws (e.g. an
 * unusual mode), we DON'T replace - the emulator's own handling proceeds untouched.
 *
 * NEEDS-VERIFICATION: assumes byte[] args arrive as real ByteArray (the rest of this extension relies
 * on the same) and that init/doFinal dispatch through the HookRegistry. Streaming via repeated
 * `update()` is intentionally not taken over (only single-shot `doFinal([B)` / `doFinal([BII)`), so a
 * streaming loader is captured by the loader hooks instead, not here.
 */
internal class CryptoCapture(
    private val log: Logger,
    private val config: ExtensionConfig,
    private val onDex: (ByteArray) -> Unit,
) {
    private data class KeyMat(val bytes: ByteArray, val algo: String)
    private data class CipherState(var transformation: String?, val opmode: Int, val key: ByteArray, val keyAlgo: String, val iv: ByteArray?)
    private class Captured(val transformation: String, val opmode: Int, val keyHex: String, val ivHex: String?, val inLen: Int, val out: ByteArray, val dexLike: Boolean)

    private val keySpecs = java.util.IdentityHashMap<Any, KeyMat>()
    private val ivSpecs = java.util.IdentityHashMap<Any, ByteArray>()
    private val ciphers = java.util.IdentityHashMap<Any, CipherState>()
    private val lastTransformation = ThreadLocal<String?>()
    private val captured = java.util.Collections.synchronizedList(ArrayList<Captured>())

    fun register(hooks: HookRegistry) {
        if (!config.captureCrypto) return

        // key material
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

        // init: bind opmode + key + iv to the cipher object
        for (sig in INIT_SIGS) hooks.add(sig) { call ->
            val recv = call.receiver ?: return@add
            val opmode = call.args.getOrNull(0) as? Int ?: return@add
            val km = resolveKey(call.args.getOrNull(1)) ?: return@add
            val iv = resolveIv(call.args.getOrNull(2))
            val tr = lastTransformation.get()
            lastTransformation.remove()
            ciphers[recv] = CipherState(tr, opmode, km.bytes, km.algo, iv)
        }

        // doFinal: reproduce host-side, capture, feed, replace
        hooks.add("Ljavax/crypto/Cipher;->doFinal([B)[B") { call ->
            val recv = call.receiver ?: return@add
            val input = call.args.getOrNull(0) as? ByteArray ?: return@add
            handleDoFinal(recv, input)?.let { call.replace(it) }
        }
        hooks.add("Ljavax/crypto/Cipher;->doFinal([BII)[B") { call ->
            val recv = call.receiver ?: return@add
            val buf = call.args.getOrNull(0) as? ByteArray ?: return@add
            val off = call.args.getOrNull(1) as? Int ?: 0
            val len = call.args.getOrNull(2) as? Int ?: (buf.size - off)
            handleDoFinal(recv, buf.copyOfRange(off, off + len))?.let { call.replace(it) }
        }
    }

    private fun handleDoFinal(cipher: Any, input: ByteArray): ByteArray? {
        val st = ciphers[cipher] ?: return null
        val guessed = st.transformation == null
        val transformation = st.transformation ?: guessTransformation(st)
        val out = runCipher(transformation, st.opmode, st.key, st.keyAlgo, st.iv, input) ?: run {
            Telemetry.count("crypto.failed")
            return null
        }
        val dexLike = DexBytes.isDex(out) || DexBytes.looksLikeZip(out)
        // If we had to GUESS the transformation (no getInstance seen), a wrong guess can still "succeed"
        // and yield garbage. Only act on a guess when the output is clearly a DEX/zip (strong evidence the
        // guess was right); otherwise leave the emulator's own doFinal untouched rather than corrupt it.
        if (guessed && !dexLike) {
            Telemetry.count("crypto.guessSkipped")
            return null
        }
        captured.add(Captured(transformation, st.opmode, hex(st.key), st.iv?.let { hex(it) }, input.size, out, dexLike))
        Telemetry.count("crypto.captured")
        Telemetry.event("crypto", "$transformation op=${st.opmode} ${input.size}->${out.size}${if (dexLike) " DEX" else ""}")
        if (dexLike && config.feedDecryptedDex) {
            Telemetry.count("crypto.fedDex")
            log.info("crypto capture: decrypted {}-byte DEX payload ({}), feeding to loader", out.size, transformation)
            runCatching { onDex(out) }.onFailure { log.warn("feed decrypted dex failed: {}", it.toString()) }
        }
        return out
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

    private fun guessTransformation(st: CipherState): String {
        val base = st.keyAlgo.substringBefore('/')
        return if (st.iv != null) "$base/CBC/PKCS5Padding" else "$base/ECB/PKCS5Padding"
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
                if (!c.dexLike && c.out.size <= MAX_DUMP) {
                    runCatching { Files.write(dir.resolve("plain-${sha.substring(0, 12)}.bin"), c.out) }
                }
                sb.append("  {")
                    .append("\"transformation\": ").append(str(c.transformation))
                    .append(", \"opmode\": ").append(c.opmode)
                    .append(", \"keyHex\": ").append(str(c.keyHex))
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
    private fun str(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

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
