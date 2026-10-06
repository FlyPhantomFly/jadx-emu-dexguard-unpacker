package jadx.emu.ext.dexguard

import java.security.MessageDigest
import java.util.zip.Adler32
import java.util.zip.ZipInputStream

/**
 * Central DEX detection, extraction and header repair. Replaces the ad-hoc `dex\n` magic check
 * that lived in HiddenDex, and adds:
 *  - version-tolerant magic validation,
 *  - extraction of classesN.dex from a zip/jar/apk blob (in classes, classes2, ... order),
 *  - Adler-32 checksum + SHA-1 signature repair so baksmali / dex2jar accept dumped files.
 */
internal object DexBytes {

    const val HEADER_SIZE = 0x70
    private const val OFF_CHECKSUM = 0x08
    private const val OFF_SIGNATURE = 0x0c
    private const val OFF_FILE_SIZE = 0x20
    private const val OFF_CLASS_DEFS_SIZE = 0x60

    private fun u(b: ByteArray, i: Int): Int = b[i].toInt() and 0xff

    fun isDex(b: ByteArray): Boolean {
        if (b.size < HEADER_SIZE) return false
        if (u(b, 0) != 'd'.code || u(b, 1) != 'e'.code || u(b, 2) != 'x'.code || u(b, 3) != 0x0a) return false
        if (u(b, 7) != 0x00) return false
        for (i in 4..6) { val c = u(b, i); if (c < '0'.code || c > '9'.code) return false }
        return true
    }

    fun looksLikeZip(b: ByteArray): Boolean =
        b.size > 4 && u(b, 0) == 0x50 && u(b, 1) == 0x4b && u(b, 2) == 0x03 && u(b, 3) == 0x04

    /** CompactDex ("cdex") - ART's internal format; cannot be loaded as a standard DEX as-is. */
    fun isCompactDex(b: ByteArray): Boolean =
        b.size >= HEADER_SIZE && u(b, 0) == 'c'.code && u(b, 1) == 'd'.code && u(b, 2) == 'e'.code && u(b, 3) == 'x'.code

    enum class Kind { DEX, CDEX, ZIP, UNKNOWN }

    fun kind(b: ByteArray): Kind = when {
        isDex(b) -> Kind.DEX
        isCompactDex(b) -> Kind.CDEX
        looksLikeZip(b) -> Kind.ZIP
        else -> Kind.UNKNOWN
    }

    /** DEX format version (the 3 ASCII digits in the magic), or -1 if not a dex. */
    fun dexVersion(b: ByteArray): Int =
        if (!isDex(b)) -1 else ((u(b, 4) - '0'.code) * 100 + (u(b, 5) - '0'.code) * 10 + (u(b, 6) - '0'.code))

    /** All DEX payloads reachable from a blob: the blob itself, or every classesN.dex if it is a zip. */
    fun extract(b: ByteArray): List<ByteArray> = when (kind(b)) {
        Kind.DEX -> {
            // v41+ is the Android 15 "dex container" layout; a single header may front several dexes,
            // which an older DexInputSource can misread. Flag it so a miss is explained, not silent.
            if (dexVersion(b) >= 41) Telemetry.event("dex", "container-format v${dexVersion(b)} (may need a newer parser)")
            listOf(b)
        }
        Kind.ZIP -> dexesFromZip(b)
        Kind.CDEX -> {
            Telemetry.count("dex.cdexSkipped")
            Telemetry.event("dex", "CompactDex payload skipped (not loadable as standard dex)")
            emptyList()
        }
        Kind.UNKNOWN -> emptyList()
    }

    fun dexesFromZip(b: ByteArray): List<ByteArray> {
        val byName = HashMap<String, ByteArray>()
        runCatching {
            ZipInputStream(b.inputStream()).use { zin ->
                var e = zin.nextEntry
                while (e != null) {
                    if (!e.isDirectory && e.name.endsWith(".dex")) {
                        val bytes = zin.readBytes()
                        if (isDex(bytes)) byName[e.name] = bytes
                    }
                    e = zin.nextEntry
                }
            }
        }
        return byName.entries.sortedBy { dexOrder(it.key) }.map { it.value }
    }

    private fun dexOrder(name: String): Int {
        val m = Regex("""classes(\d*)\.dex$""").find(name) ?: return Int.MAX_VALUE
        val n = m.groupValues[1]
        return if (n.isEmpty()) 1 else (n.toIntOrNull() ?: Int.MAX_VALUE)
    }

    fun classDefCount(b: ByteArray): Int = if (b.size >= OFF_CLASS_DEFS_SIZE + 4) le32(b, OFF_CLASS_DEFS_SIZE) else -1

    /** Returns a copy with a corrected file_size, SHA-1 signature and Adler-32 checksum. */
    fun repair(src: ByteArray): ByteArray {
        if (!isDex(src)) return src
        val b = src.copyOf()
        putLe32(b, OFF_FILE_SIZE, b.size)                         // file_size
        val sha = MessageDigest.getInstance("SHA-1").digest(b.copyOfRange(OFF_FILE_SIZE, b.size))
        System.arraycopy(sha, 0, b, OFF_SIGNATURE, 20)           // signature covers [0x20, end)
        val adler = Adler32().apply { update(b, OFF_SIGNATURE, b.size - OFF_SIGNATURE) }.value
        putLe32(b, OFF_CHECKSUM, adler.toInt())                  // checksum covers [0x0C, end)
        return b
    }

    fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun le32(b: ByteArray, off: Int): Int =
        u(b, off) or (u(b, off + 1) shl 8) or (u(b, off + 2) shl 16) or (u(b, off + 3) shl 24)

    private fun putLe32(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xff).toByte()
        b[off + 1] = ((v shr 8) and 0xff).toByte()
        b[off + 2] = ((v shr 16) and 0xff).toByte()
        b[off + 3] = ((v shr 24) and 0xff).toByte()
    }
}
