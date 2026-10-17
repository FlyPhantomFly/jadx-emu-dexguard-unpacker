package jadx.emu.ext.dexguard

import jadx.plugins.emu.api.Hooks
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.helpers.NOPLogger
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.Adler32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * End-to-end test for the path a decrypted payload travels once it appears - with NO engine and NO
 * wild APK, so it is deterministic and CI-safe. It builds real DEX-shaped bytes and a real zip, then
 * drives the ACTUAL extension code: DexBytes detection/repair/trim/container/zip-extract, the
 * CryptoCapture return-hook feed (javax.crypto path, item 2), and the DecryptCapture return-hook
 * capture (custom-decrypt path, item 2). These all run *before* the Dalvik interpreter, so they are
 * provable without it - and they are exactly the two capture paths a real DexGuard run (FaceTec) left
 * unproven (its dex came via the loader path, and its decryptors weren't on the swept path).
 *
 * NOT covered here: the emulator actually interpreting a loader class that calls InMemoryDexClassLoader
 * / DexFile (the item-7 HiddenDex path). That needs the engine to execute real dex bytecode and is
 * validated by real-target runs (FaceTec: 4 hidden dex via dex.loaded). A future engine-level harness
 * - build a loader dex (dexlib2) + a Vm (EngineCompat) + assert HiddenDex recovered it - would unit-test
 * that too; it's deferred because it needs a dex compiler and the engine on the test classpath.
 *
 * The crafted DEX is header-valid (magic / version / file_size / class_defs_size / container_size) but
 * not executable: every DexBytes op here is byte-level, and the capturers only inspect/forward bytes,
 * so header-validity is the right fidelity.
 */
class PipelineTest {

    private val nop = NOPLogger.NOP_LOGGER

    // --- fixtures ---------------------------------------------------------

    private fun craftDex(version: String = "035", classDefs: Int = 1, size: Int = 0x200): ByteArray {
        require(size >= 0x74) { "dex header needs at least 0x74 bytes" }
        val b = ByteArray(size)
        byteArrayOf('d'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), 0x0a).copyInto(b, 0)
        version.toByteArray(Charsets.US_ASCII).copyInto(b, 4) // 3 ASCII digits at [4,6]
        b[7] = 0
        putLe32(b, 0x20, size)      // file_size
        putLe32(b, 0x60, classDefs) // class_defs_size
        return b
    }

    /** A v41 "dex container": first header's file_size is small, container_size spans the whole blob. */
    private fun craftContainer(totalSize: Int, firstFileSize: Int): ByteArray {
        val b = craftDex(version = "041", classDefs = 1, size = totalSize)
        putLe32(b, 0x20, firstFileSize) // first logical dex is smaller than the container...
        putLe32(b, 0x70, totalSize)     // ...container_size covers everything (v41 header extension)
        return b
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun configWith(vararg kv: Pair<String, String>): ExtensionConfig {
        val d = Files.createTempDirectory("e2e-cfg")
        Files.write(d.resolve("dexguard-unpacker.properties"), kv.joinToString("\n") { "${it.first}=${it.second}" }.toByteArray())
        return ExtensionConfig.load(d, nop)
    }

    /** Fire the return-hooks for [desc] with [result] as the method's real output; returns the final value. */
    private fun fireReturn(hooks: ExtHooks, desc: String, recv: Any?, args: List<Any?>, result: Any?): Any? =
        TestCalls.fireReturn(hooks, desc, recv, args, result)

    private fun putLe32(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xff).toByte(); b[off + 1] = ((v shr 8) and 0xff).toByte()
        b[off + 2] = ((v shr 16) and 0xff).toByte(); b[off + 3] = ((v shr 24) and 0xff).toByte()
    }

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or ((b[off + 3].toInt() and 0xff) shl 24)

    // --- DexBytes: detection ----------------------------------------------

    @Test fun detectsDexAndReadsHeader() {
        val d = craftDex(version = "038", classDefs = 7)
        assertTrue(DexBytes.isDex(d))
        assertEquals(38, DexBytes.dexVersion(d))
        assertEquals(7, DexBytes.classDefCount(d))
        assertEquals(DexBytes.Kind.DEX, DexBytes.kind(d))
        assertFalse(DexBytes.isDex(ByteArray(0x80))) // zeros are not a dex
    }

    // --- DexBytes: repair writes baksmali/dex2jar-valid checksum + signature ---

    @Test fun repairFixesChecksumAndSignature() {
        val r = DexBytes.repair(craftDex())
        val adler = Adler32().apply { update(r, 0x0c, r.size - 0x0c) }.value.toInt()
        assertEquals(adler, le32(r, 0x08), "checksum must be Adler-32 over [0x0c, end)")
        val sha = MessageDigest.getInstance("SHA-1").digest(r.copyOfRange(0x20, r.size))
        assertArrayEquals(sha, r.copyOfRange(0x0c, 0x0c + 20), "signature must be SHA-1 over [0x20, end)")
    }

    // --- DexBytes: v41 container (item 4) passed whole, NOT truncated to the first dex ---

    @Test fun v41ContainerPassedWholeNotTruncated() {
        val out = DexBytes.extract(craftContainer(totalSize = 0x400, firstFileSize = 0x100))
        assertEquals(1, out.size)
        assertEquals(0x400, out[0].size, "a v41 container is kept at container_size, not the first dex's file_size")
    }

    // --- DexBytes: standard dex trimmed to file_size (trailing padding stripped) ---

    @Test fun standardDexTrimmedToFileSize() {
        val d = craftDex(size = 0x200)
        putLe32(d, 0x20, 0x180) // declared file_size < array
        val out = DexBytes.extract(d)
        assertEquals(1, out.size)
        assertEquals(0x180, out[0].size)
    }

    // --- DexBytes: classesN.dex extracted from a zip payload, in order ---

    @Test fun extractsDexesFromZipInOrder() {
        val zip = zipOf(
            "classes.dex" to craftDex(size = 0x100),
            "classes2.dex" to craftDex(size = 0x180),
            "res/stuff.bin" to ByteArray(10), // ignored (not a .dex)
        )
        assertTrue(DexBytes.looksLikeZip(zip))
        assertEquals(DexBytes.Kind.ZIP, DexBytes.kind(zip))
        val dexes = DexBytes.dexesFromZip(zip)
        assertEquals(2, dexes.size)
        assertEquals(0x100, dexes[0].size) // classes.dex before classes2.dex
        assertEquals(0x180, dexes[1].size)
    }

    // --- CryptoCapture (item 2): real dex observed at doFinal -> fed to the loader ---

    @Test fun cryptoCaptureFeedsRealDex() {
        val dex = craftDex()
        val fed = ArrayList<ByteArray>()
        val hooks = ExtHooks(Hooks())
        CryptoCapture(nop, configWith("captureCrypto" to "true"), { fed.add(it) }).register(hooks)
        val out = fireReturn(hooks, "Ljavax/crypto/Cipher;->doFinal([B)[B", Any(), listOf(ByteArray(dex.size)), dex)
        assertArrayEquals(dex, out as ByteArray, "observe-only: the app still gets the plaintext")
        assertEquals(1, fed.size, "a DEX plaintext must be fed to the loader exactly once")
        assertArrayEquals(dex, fed[0])
    }

    @Test fun cryptoCaptureIgnoresNonDex() {
        val fed = ArrayList<ByteArray>()
        val hooks = ExtHooks(Hooks())
        CryptoCapture(nop, configWith("captureCrypto" to "true"), { fed.add(it) }).register(hooks)
        fireReturn(hooks, "Ljavax/crypto/Cipher;->doFinal([B)[B", Any(), listOf(ByteArray(64)), ByteArray(64) { it.toByte() })
        assertTrue(fed.isEmpty(), "non-DEX plaintext must not be fed")
    }

    // --- DecryptCapture (item 2): a named method's real return observed + recorded ---
    //     This is the half FaceTec could not exercise (its decryptors weren't on the swept path).

    @Test fun decryptCaptureObservesNamedMethodReturn() {
        val plaintext = "unlock-key-0123"
        val sig = "Lcom/app/Dec;->run([BI)Ljava/lang/String;"
        val dc = DecryptCapture(nop, configWith("captureDecrypt" to "true", "captureDecryptMethods" to sig))
        val hooks = ExtHooks(Hooks())
        dc.register(hooks)
        val ret = fireReturn(hooks, sig, Any(), listOf(byteArrayOf(9, 9, 9), 3), plaintext)
        assertEquals(plaintext, ret, "observe-only passthrough")
        val dir = Files.createTempDirectory("e2e-dec")
        dc.flush(dir)
        val json = dir.resolve("decrypt/decrypt-capture.json")
        assertTrue(Files.exists(json), "a capture record must be written")
        assertTrue(String(Files.readAllBytes(json)).contains("Lcom/app/Dec;"))
    }

    @Test fun decryptCaptureRewrapsInputStream() {
        val payload = craftDex()
        val sig = "Lcom/app/Dec;->stream(Ljava/io/InputStream;)Ljava/io/InputStream;"
        val hooks = ExtHooks(Hooks())
        DecryptCapture(nop, configWith("captureDecrypt" to "true", "captureDecryptMethods" to sig)).register(hooks)
        // the returned stream is consumed for capture, so the app must get a FRESH stream over the same bytes
        val ret = fireReturn(hooks, sig, Any(), listOf(ByteArrayInputStream(ByteArray(0))), ByteArrayInputStream(payload))
        assertArrayEquals(payload, (ret as InputStream).readBytes())
    }
}
