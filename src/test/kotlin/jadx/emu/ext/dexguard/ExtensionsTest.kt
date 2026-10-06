package jadx.emu.ext.dexguard

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.crypto.Cipher

class ExtensionsTest {

    @Test fun cryptoRoundTripCbc() {
        val key = ByteArray(16) { it.toByte() }
        val iv = ByteArray(16) { (15 - it).toByte() }
        val plain = "classes.dex payload bytes for round-trip".toByteArray()
        val ct = CryptoCapture.runCipher("AES/CBC/PKCS5Padding", Cipher.ENCRYPT_MODE, key, "AES", iv, plain)!!
        val pt = CryptoCapture.runCipher("AES/CBC/PKCS5Padding", Cipher.DECRYPT_MODE, key, "AES", iv, ct)!!
        assertArrayEquals(plain, pt)
    }

    @Test fun cryptoRoundTripEcbNoIv() {
        val key = ByteArray(16) { (it * 7).toByte() }
        val plain = ByteArray(32) { it.toByte() }
        val ct = CryptoCapture.runCipher("AES/ECB/PKCS5Padding", Cipher.ENCRYPT_MODE, key, "AES", null, plain)!!
        val pt = CryptoCapture.runCipher("AES/ECB/PKCS5Padding", Cipher.DECRYPT_MODE, key, "AES", null, ct)!!
        assertArrayEquals(plain, pt)
    }

    @Test fun cryptoBadInputReturnsNull() {
        // wrong key size -> host cipher throws -> null (so we never replace / mislead)
        val badKey = ByteArray(7)
        val r = CryptoCapture.runCipher("AES/CBC/PKCS5Padding", Cipher.DECRYPT_MODE, badKey, "AES", ByteArray(16), ByteArray(16))
        assertNull(r)
    }

    @Test fun dexBytesKindClassification() {
        val dex = ByteArray(0x70).also { "dex\n039\u0000".forEachIndexed { i, c -> it[i] = c.code.toByte() } }
        assertEquals(DexBytes.Kind.DEX, DexBytes.kind(dex))
        assertEquals(39, DexBytes.dexVersion(dex))
        assertEquals(1, DexBytes.extract(dex).size)   // a bare dex extracts to itself

        val cdex = ByteArray(0x70).also { "cdex001\u0000".forEachIndexed { i, c -> it[i] = c.code.toByte() } }
        assertTrue(DexBytes.isCompactDex(cdex))
        assertEquals(DexBytes.Kind.CDEX, DexBytes.kind(cdex))
        assertTrue(DexBytes.extract(cdex).isEmpty())   // cdex is not loadable as standard dex

        val junk = ByteArray(0x70) { 0x7f }
        assertEquals(DexBytes.Kind.UNKNOWN, DexBytes.kind(junk))
    }
}
