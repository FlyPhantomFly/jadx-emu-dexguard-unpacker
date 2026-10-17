package jadx.emu.ext.dexguard

import jadx.plugins.emu.api.Hooks
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.helpers.NOPLogger
import java.nio.file.Files
import javax.crypto.Cipher

/**
 * Tests the crypto-capture hook path after the move to return-value hooks (jadx-emu `addReturn`):
 * CryptoCapture now OBSERVES the real Cipher output instead of reproducing it. We fire the real
 * handlers CryptoCapture.register() installs - the SecretKeySpec / IvParameterSpec / getInstance /
 * init BEFORE-hooks (metadata) and the update() / doFinal RETURN-hooks (output) - with the arg/result
 * shapes the engine would supply, and check detection, feed, and streamed-chunk joining.
 *
 * Scope: verifies the capture/detection/feed logic given those shapes; it does not prove a real
 * emulated Cipher delivers them (the engine source guarantees the hooks fire at every invoke). Because
 * capture is now observe-only, the real plaintext is supplied as the hook's `result` rather than
 * reproduced from a ciphertext.
 */
class CryptoCaptureTest {

    private val nop = NOPLogger.NOP_LOGGER
    private fun config() = ExtensionConfig.load(Files.createTempDirectory("cc-test"), nop)

    private fun fire(hooks: ExtHooks, desc: String, recv: Any?, vararg args: Any?) = TestCalls.fire(hooks, desc, recv, *args)

    /** Fire the RETURN-hooks for [desc] with [result] as the method's real output; returns the final value. */
    private fun fireReturn(hooks: ExtHooks, desc: String, recv: Any?, args: List<Any?>, result: Any?): Any? =
        TestCalls.fireReturn(hooks, desc, recv, args, result)

    private fun minimalDex(): ByteArray =
        ByteArray(0x70).also { byteArrayOf(0x64, 0x65, 0x78, 0x0a, 0x30, 0x33, 0x35, 0x00).copyInto(it) } // "dex\n035\0"

    /** Fire the metadata before-hooks (key/iv/getInstance/init), binding them to [cipher]. */
    private fun bindCipher(hooks: ExtHooks, cipher: Any) {
        val keyObj = Any(); val ivObj = Any()
        fire(hooks, "Ljavax/crypto/spec/SecretKeySpec;-><init>([BLjava/lang/String;)V", keyObj, ByteArray(16), "AES")
        fire(hooks, "Ljavax/crypto/spec/IvParameterSpec;-><init>([B)V", ivObj, ByteArray(16))
        fire(hooks, "Ljavax/crypto/Cipher;->getInstance(Ljava/lang/String;)Ljavax/crypto/Cipher;", null, "AES/CBC/PKCS5Padding")
        fire(hooks, "Ljavax/crypto/Cipher;->init(ILjava/security/Key;Ljava/security/spec/AlgorithmParameterSpec;)V", cipher, Cipher.DECRYPT_MODE, keyObj, ivObj)
    }

    /** Single-shot decrypt whose real plaintext is a DEX: observed unchanged, detected, fed once. */
    @Test fun capturesAndFeedsSingleShotDex() {
        val dex = minimalDex()
        assertTrue(DexBytes.isDex(dex))
        val fed = ArrayList<ByteArray>()
        val hooks = ExtHooks(Hooks())
        CryptoCapture(nop, config()) { fed.add(it) }.register(hooks)

        val cipher = Any()
        bindCipher(hooks, cipher)
        val ct = ByteArray(dex.size) // input arg only affects inLen; result is the observed plaintext
        val out = fireReturn(hooks, "Ljavax/crypto/Cipher;->doFinal([B)[B", cipher, listOf(ct), dex)

        assertArrayEquals(dex, out as ByteArray, "observe-only: the app still receives the real plaintext")
        assertEquals(1, fed.size, "the DEX must be fed to the loader exactly once")
        assertArrayEquals(dex, fed[0])
    }

    /** Single-shot decrypt whose plaintext is NOT a DEX: observed unchanged, recorded, NOT fed. */
    @Test fun nonDexNotFed() {
        val plain = ByteArray(64) { it.toByte() }
        val fed = ArrayList<ByteArray>()
        val hooks = ExtHooks(Hooks())
        CryptoCapture(nop, config()) { fed.add(it) }.register(hooks)

        val cipher = Any()
        bindCipher(hooks, cipher)
        val out = fireReturn(hooks, "Ljavax/crypto/Cipher;->doFinal([B)[B", cipher, listOf(ByteArray(64)), plain)

        assertArrayEquals(plain, out as ByteArray)
        assertTrue(fed.isEmpty(), "non-DEX plaintext must not be fed to the loader")
    }

    /** Streamed decrypt (update()+update()+doFinal): the real chunks are joined into the whole DEX and fed once. */
    @Test fun capturesStreamedDex() {
        val dex = minimalDex()
        val p1 = dex.copyOfRange(0, 0x30)
        val p2 = dex.copyOfRange(0x30, 0x60)
        val p3 = dex.copyOfRange(0x60, 0x70)
        val fed = ArrayList<ByteArray>()
        val hooks = ExtHooks(Hooks())
        CryptoCapture(nop, config()) { fed.add(it) }.register(hooks)

        val cipher = Any()
        bindCipher(hooks, cipher)
        fireReturn(hooks, "Ljavax/crypto/Cipher;->update([B)[B", cipher, listOf(ByteArray(0x30)), p1)
        fireReturn(hooks, "Ljavax/crypto/Cipher;->update([B)[B", cipher, listOf(ByteArray(0x30)), p2)
        val out = fireReturn(hooks, "Ljavax/crypto/Cipher;->doFinal([B)[B", cipher, listOf(ByteArray(0x10)), p3)

        assertArrayEquals(p3, out as ByteArray, "doFinal still returns only the final block to the app")
        assertEquals(1, fed.size, "the joined stream must be fed exactly once")
        assertArrayEquals(dex, fed[0], "update() chunks + the doFinal block must be joined into the whole DEX")
    }
}
