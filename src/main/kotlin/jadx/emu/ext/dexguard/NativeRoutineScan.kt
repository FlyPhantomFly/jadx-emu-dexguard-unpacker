package jadx.emu.ext.dexguard

import java.io.File
import java.util.zip.ZipFile

/**
 * Locates DexGuard's centralized native "return to Java" routine inside the app's arm64 libraries.
 *
 * This is the native end of DexGuard's obfuscation: whatever a JNI decryptor computes funnels back to
 * Java through one central routine, so its fingerprint is a reliable marker of the native string/payload
 * tiers and gives the exact address where return values get their final treatment.
 *
 * The fingerprint comes from a YARA rule field-proven over 27k+ DexGuard APKs across five months. It
 * matches the AArch64 `movz`/`movk` instruction pairs that build the routine's alternating-bit
 * constants into a register:
 *
 *     $a: movz x12, #0x55AA ; movk x12, #0x55AA, lsl #16  ->  x12 = 0x55AA55AA
 *     $b: movz x12, #0xAA55 ; movk x12, #0xAA55, lsl #16  ->  x12 = 0xAAAA5555
 *     $c: movz x12, #0xAA55 ; movk x12, #0x2A55, lsl #16  ->  x12 = 0x2A55AA55
 *     $d: movz x13, #0xAA55 ; movk x13, #0xAA55, lsl #16  ->  x13 = 0xAAAA5555
 *     $e: movz x10, #0x55AA ; movk x10, #0x55AA, lsl #16  ->  x10 = 0x55AA55AA
 *     $f: movz x13, #0xAA55 ; movk x13, #0x2A55, lsl #16  ->  x13 = 0x2A55AA55
 *
 * The rows are stored as the literal little-endian instruction bytes, exactly as the YARA rule spells
 * them, and matched byte-for-byte. Nothing is decoded *to find* a match; [Match.register] and
 * [Match.constant] only label the hit, and are derived from the matched bytes themselves.
 */
internal object NativeRoutineScan {

    /** One fingerprint hit: where in the file it matched, and what the instruction pair builds. */
    data class Match(
        /** The pattern row that matched, `$a`..`$f` (see the table in the class doc). */
        val pattern: String,
        /** Byte offset of the first instruction in the .so on disk. */
        val fileOffset: Long,
        /** The address this becomes in memory when the library is mapped, via ELF `PT_LOAD`; -1 when unknown. */
        val virtualAddress: Long,
        /** The register the pair writes, 0..31. */
        val register: Int,
        /** The 64-bit value the pair builds. */
        val constant: Long,
    )

    /** Scan outcome for one library. */
    data class Result(
        val lib: String,
        val abi: String,
        val matches: List<Match>,
    ) {
        val found: Boolean get() = matches.isNotEmpty()
    }

    private val ABI_DEFAULT = "arm64-v8a"

    /** The six fingerprint rows, verbatim from the proven YARA rule (`movz`+`movk` little-endian pairs). */
    private val PATTERNS: Map<String, ByteArray> = linkedMapOf(
        "\$a" to hex("4C B5 8A D2 4C B5 AA F2"),
        "\$b" to hex("AC 4A 95 D2 AC 4A B5 F2"),
        "\$c" to hex("AC 4A 95 D2 AC 4A A5 F2"),
        "\$d" to hex("AD 4A 95 D2 AD 4A B5 F2"),
        "\$e" to hex("4A B5 8A D2 4A B5 AA F2"),
        "\$f" to hex("AD 4A 95 D2 AD 4A A5 F2"),
    )

    /**
     * Scan every `.so` under `lib/<abi>/` of an APK for the return-to-Java fingerprint,
     * and report only libraries with at least one match. A corrupt or unreadable APK
     * yields an empty list, never a throw.
     */
    fun scan(apk: File, abi: String = ABI_DEFAULT): List<Result> = runCatching {
        ZipFile(apk).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith("lib/$abi/") && it.name.endsWith(".so") }
                .map { entry ->
                    val name = entry.name.substringAfterLast('/')
                    scanBytes(name, zip.getInputStream(entry).readBytes(), abi)
                }
                .filter { it.found }
                .toList()
        }
    }.getOrDefault(emptyList())

    /** Scan one library image for all six rows. */
    fun scanBytes(lib: String, bytes: ByteArray, abi: String = ABI_DEFAULT): Result {
        val hits = ArrayList<Match>()
        for ((name, needle) in PATTERNS) {
            var from = 0
            while (true) {
                val at = indexOf(bytes, needle, from) ?: break
                val (reg, const) = decodePair(bytes, at)
                hits += Match(name, at.toLong(), virtualAddress(bytes, at.toLong()), reg, const)
                from = at + 1
            }
        }
        return Result(lib, abi, hits.sortedBy { it.fileOffset })
    }

    /** Where the library maps the byte at [offset], walking the ELF64 `PT_LOAD` program headers; -1 otherwise. */
    fun virtualAddress(bytes: ByteArray, offset: Long): Long {
        if (bytes.size < 0x40 || bytes[4] != 2.toByte() || bytes[5] != 1.toByte()) return -1L   // ELF64, little-endian
        val ePhoff = longAt(bytes, 0x20)
        val ePhentsize = u16At(bytes, 0x36)
        val ePhnum = u16At(bytes, 0x38)
        if (ePhoff <= 0L || ePhentsize == 0) return -1L
        for (i in 0 until ePhnum) {
            val ph = ePhoff + i.toLong() * ePhentsize
            if (ph + ePhentsize > bytes.size) break
            if (u32At(bytes, ph) != 1) continue                                 // PT_LOAD
            val pOffset = longAt(bytes, ph + 8)
            val pVaddr = longAt(bytes, ph + 16)
            val pFilesz = longAt(bytes, ph + 32)
            if (pOffset <= offset && offset < pOffset + pFilesz) return pVaddr + (offset - pOffset)
        }
        return -1L
    }

    /**
     * Decode the `movz`/`movk` pair starting at [at]: (target register, the 64-bit value the pair builds).
     * A64 `mov`-wide-immediate fields: `opc`=bits 31..29, `100101`=bits 28..23 (shift stays zero), `hw`=bits 22..21, `imm16`=bits 20..5, `Rd`=bits 4..0.
     */
    fun decodePair(bytes: ByteArray, at: Int): Pair<Int, Long> {
        val w1 = wordLe(bytes, at)
        val w2 = wordLe(bytes, at + 4)
        val rd = (w2 and 0x1F).toInt()
        val v1 = ((w1 shr 5) and 0xFFFF) shl (((w1 shr 21) and 0x3) * 16).toInt()
        val v2 = ((w2 shr 5) and 0xFFFF) shl (((w2 shr 21) and 0x3) * 16).toInt()
        return rd to (v1 or v2)
    }

    private fun indexOf(data: ByteArray, needle: ByteArray, from: Int): Int? {
        val last = data.size - needle.size
        var i = from
        while (i <= last) {
            if (data[i] == needle[0]) {
                var j = 1
                while (j < needle.size && data[i + j] == needle[j]) j++
                if (j == needle.size) return i
            }
            i++
        }
        return null
    }

    private fun hex(s: String): ByteArray = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    private fun wordLe(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)

    private fun u16At(b: ByteArray, off: Long): Int =
        ((b[off.toInt()].toInt() and 0xFF) or ((b[(off + 1).toInt()].toInt() and 0xFF) shl 8))

    private fun u32At(b: ByteArray, off: Long): Int =
        (0 until 4).fold(0) { acc, i -> acc or ((b[(off + i).toInt()].toInt() and 0xFF) shl (i * 8)) }

    private fun longAt(b: ByteArray, off: Long): Long =
        (0 until 8).fold(0L) { acc, i -> acc or ((b[(off + i).toInt()].toLong() and 0xFF) shl (i * 8)) }
}
