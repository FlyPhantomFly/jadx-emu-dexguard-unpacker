package jadx.emu.ext.dexguard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NativeRoutineScanTest {

    private fun le(buf: ByteArray, off: Int, v: Long, width: Int) {
        for (i in 0 until width) buf[off + i] = ((v shr (i * 8)) and 0xFF).toByte()
    }

    /** A 64-bit aarch64 ELF (ET_DYN) with one PT_LOAD mapping file range [loadFileOff, +size) to [loadVaddr, +size). */
    private fun elfWithLoad(loadFileOff: Int, loadVaddr: Long, loadSize: Int): ByteArray {
        val buf = ByteArray(loadFileOff + loadSize)
        System.arraycopy(byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 0x02, 0x01, 0x01, 0x00), 0, buf, 0, 8)
        le(buf, 0x10, 3, 2)          // e_type = ET_DYN
        le(buf, 0x12, 0xB7, 2)       // e_machine = AArch64
        le(buf, 0x14, 1, 4)          // e_version
        le(buf, 0x20, 64L, 8)        // e_phoff = right after the header
        le(buf, 0x34, 64, 2)         // e_ehsize
        le(buf, 0x36, 56, 2)         // e_phentsize
        le(buf, 0x38, 1, 2)          // e_phnum
        val ph = 0x40
        le(buf, ph, 1L, 4)           // p_type = PT_LOAD
        le(buf, ph + 4, 5L, 4)       // p_flags = RX
        le(buf, ph + 8, loadFileOff.toLong(), 8)   // p_offset
        le(buf, ph + 16, loadVaddr, 8)             // p_vaddr
        le(buf, ph + 32, loadSize.toLong(), 8)     // p_filesz
        return buf
    }

    private fun withPattern(bytes: ByteArray, pattern: String, at: Int): ByteArray {
        val needle = pattern.split(" ").map { it.toInt(16).toByte() }
        System.arraycopy(needle.toByteArray(), 0, bytes, at, needle.size)
        return bytes
    }

    @Test fun findsAllSixRows() {
        // Six distinct slots inside the mapped segment, plus noise around them.
        val bytes = elfWithLoad(loadFileOff = 0x200, loadVaddr = 0x10000, loadSize = 0xC00)
        val slots = intArrayOf(0x200, 0x300, 0x410, 0x520, 0x640, 0x760)
        val rows = listOf(
            "4C B5 8A D2 4C B5 AA F2",   // $a
            "AC 4A 95 D2 AC 4A B5 F2",   // $b
            "AC 4A 95 D2 AC 4A A5 F2",   // $c
            "AD 4A 95 D2 AD 4A B5 F2",   // $d
            "4A B5 8A D2 4A B5 AA F2",   // $e
            "AD 4A 95 D2 AD 4A A5 F2",   // $f
        )
        slots.forEachIndexed { i, at -> withPattern(bytes, rows[i], at) }
        for (i in 0x900 until bytes.size) bytes[i] = (i * 31 and 0xFF).toByte()   // trailing noise

        val r = NativeRoutineScan.scanBytes("libtest.so", bytes)
        assertTrue(r.found)
        assertEquals(6, r.matches.size)
        assertEquals(listOf("\$a", "\$b", "\$c", "\$d", "\$e", "\$f"), r.matches.map { it.pattern })
        for (i in r.matches.indices) assertEquals(0x10000L + (slots[i] - 0x200), r.matches[i].virtualAddress)
    }

    @Test fun labelsRegisterAndConstant() {
        // $e at a mapped offset: movz x10, #0x55AA ; movk x10, #0x55AA, lsl 16.
        val bytes = elfWithLoad(loadFileOff = 0x200, loadVaddr = 0x40000, loadSize = 0x400)
        withPattern(bytes, "4A B5 8A D2 4A B5 AA F2", 0x300)
        val m = NativeRoutineScan.scanBytes("libtest.so", bytes).matches.single()
        assertEquals(10, m.register)
        assertEquals(0x55AA55AA, m.constant)
        assertEquals(0x40000L + 0x100, m.virtualAddress)
        assertEquals("\$e", m.pattern)
    }

    @Test fun rejectsPartialCorruptedAndForeignRegister() {
        val base = elfWithLoad(loadFileOff = 0, loadVaddr = 0x0, loadSize = 0x100)
        fun clean() = base.copyOf()

        // Truncated: only the first 7 bytes of $a.
        val trunc = withPattern(clean(), "4C B5 8A D2 4C B5 AA", 0x10)
        assertFalse(NativeRoutineScan.scanBytes("l", trunc).found)

        // Corrupted middle byte.
        val bad = withPattern(clean(), "4C B5 8A D2 4C B5 12 F2", 0x20)
        assertFalse(NativeRoutineScan.scanBytes("l", bad).found)

        // Honest $a is in the rows; the same pair for x11 (4B) is not.
        val registered = withPattern(clean(), "4B B5 8A D2 4B B5 AA F2", 0x30)
        assertFalse(NativeRoutineScan.scanBytes("l", registered).found)

        // A real row still matches.
        val real = withPattern(clean(), "4C B5 8A D2 4C B5 AA F2", 0x40)
        assertTrue(NativeRoutineScan.scanBytes("l", real).found)
    }

    @Test fun virtualAddressWithoutLoadOrOutsideSegment() {
        val bytes = elfWithLoad(loadFileOff = 0x200, loadVaddr = 0x8000, loadSize = 0x100)
        // Inside the segment: 0x200 maps to 0x8000.
        assertEquals(0x8000L, NativeRoutineScan.virtualAddress(bytes, 0x200))
        // Header/program header live before the segment: unmapped.
        assertEquals(-1L, NativeRoutineScan.virtualAddress(bytes, 0x40))
        // Not an ELF at all.
        assertEquals(-1L, NativeRoutineScan.virtualAddress(byteArrayOf(1, 2, 3, 4), 0))
    }

    @Test fun scansApkJniLibsOnly() {
        val apkData = ByteArrayOutputStream()
        ZipOutputStream(apkData).use { zip ->
            val elf = elfWithLoad(loadFileOff = 0x200, loadVaddr = 0x20000, loadSize = 0x600)
            withPattern(elf, "4C B5 8A D2 4C B5 AA F2", 0x210)   // $a
            withPattern(elf, "AD 4A 95 D2 AD 4A A5 F2", 0x420)   // $f
            zip.putNextEntry(ZipEntry("lib/arm64-v8a/libdg.so")); zip.write(elf); zip.closeEntry()

            // The A64 fingerprint first appears in the arm32 build: must be ignored.
            zip.putNextEntry(ZipEntry("lib/armeabi-v7a/libdg.so"))
            zip.write("AA5555AAhello".toByteArray()); zip.closeEntry()

            zip.putNextEntry(ZipEntry("assets/data.bin")); zip.write(ByteArray(16)); zip.closeEntry()
        }
        val apk = File.createTempFile("apk-scan", ".zip").apply {
            writeBytes(apkData.toByteArray()); deleteOnExit()
        }
        try {
            val results = NativeRoutineScan.scan(apk)
            assertEquals(1, results.size)
            val r = results.single()
            assertEquals("libdg.so", r.lib)
            assertEquals("arm64-v8a", r.abi)
            assertEquals(listOf("\$a", "\$f"), r.matches.map { it.pattern })
            assertEquals(0x20010L, r.matches[0].virtualAddress)              // 0x20000 + (0x210 - 0x200)
        } finally {
            apk.delete()
        }
    }

    @Test fun corruptApkYieldsEmptyNotThrow() {
        val junk = File.createTempFile("junk", ".zip").apply { deleteOnExit() }
        try {
            junk.writeBytes("this is not a zip".toByteArray())
            assertEquals(emptyList<NativeRoutineScan.Result>(), NativeRoutineScan.scan(junk))
        } finally {
            junk.delete()
        }
    }
}
