package jadx.emu.ext.dexguard

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Rebuilds the input APK with the recovered dex files appended as classesN.dex, dropping the old
 * v1 signature. The result is UNSIGNED - re-sign with apksigner before installing. jadx itself only
 * needs the dumped .dex files, so this is a convenience for loading the app into other tooling.
 */
internal object Repackager {

    fun isApk(f: File): Boolean = runCatching {
        ZipFile(f).use { it.getEntry("AndroidManifest.xml") != null && it.getEntry("classes.dex") != null }
    }.getOrDefault(false)

    fun repackage(apk: File, dexes: List<ByteArray>, outDir: Path): Path? = runCatching {
        var next = maxDexIndex(apk) + 1
        if (next < 2) next = 2
        val out = outDir.resolve(apk.nameWithoutExtension + "-unpacked.apk")
        ZipOutputStream(Files.newOutputStream(out)).use { zos ->
            ZipFile(apk).use { zf ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (isSignatureEntry(e.name)) continue
                    // Preserve the original storage method; STORED entries (e.g. resources.arsc) must
                    // stay uncompressed and need size/crc set before the entry is written.
                    val ne = ZipEntry(e.name)
                    ne.method = e.method
                    ne.time = e.time
                    if (e.method == ZipEntry.STORED) {
                        ne.size = e.size
                        ne.compressedSize = e.compressedSize
                        ne.crc = e.crc
                    }
                    zos.putNextEntry(ne)
                    zf.getInputStream(e).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            for (d in dexes) {
                zos.putNextEntry(ZipEntry("classes$next.dex"))
                zos.write(DexBytes.repair(d))
                zos.closeEntry()
                next++
            }
        }
        out
    }.getOrNull()

    private fun maxDexIndex(apk: File): Int = runCatching {
        ZipFile(apk).use { zf ->
            zf.entries().asSequence().mapNotNull { dexIndex(it.name) }.maxOrNull() ?: 0
        }
    }.getOrDefault(0)

    private fun dexIndex(name: String): Int? {
        val m = Regex("""^classes(\d*)\.dex$""").find(name) ?: return null
        val n = m.groupValues[1]
        return if (n.isEmpty()) 1 else n.toIntOrNull()
    }

    private fun isSignatureEntry(name: String): Boolean {
        val n = name.uppercase()
        return n.startsWith("META-INF/") && (
            n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC") ||
                n.endsWith(".SF") || n == "META-INF/MANIFEST.MF"
            )
    }
}
