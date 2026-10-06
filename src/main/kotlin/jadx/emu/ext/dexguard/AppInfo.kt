package jadx.emu.ext.dexguard

import jadx.api.JadxDecompiler
import jadx.api.ResourceType
import jadx.api.ResourcesLoader
import jadx.core.xmlgen.entry.ValuesParser
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URL
import java.security.cert.CertificateFactory
import java.util.zip.ZipFile

internal class AppInfo(private val decompiler: JadxDecompiler) {

    private val strings: Map<Int, String> by lazy { loadStrings() }

    val packageName: String? by lazy { manifestAttr("package") ?: decompiler.root.appPackage }

    val targetSdkVersion: Int by lazy { manifestAttr("android:targetSdkVersion")?.toIntOrNull() ?: 33 }

    val minSdkVersion: Int by lazy { manifestAttr("android:minSdkVersion")?.toIntOrNull() ?: 24 }

    val versionName: String? by lazy { manifestAttr("android:versionName") }

    val versionCode: Long by lazy { manifestAttr("android:versionCode")?.toLongOrNull() ?: 1L }

    /** DER-encoded v1 signing certificate(s) from the APK, for signature/RASP checks. */
    val signingCerts: List<ByteArray> by lazy { loadSigningCerts() }

    /** Fake-device profile seeded from the manifest; mutate fields to match a target's expectations. */
    val profile: DeviceProfile by lazy {
        DeviceProfile(minSdk = minSdkVersion, targetSdk = targetSdkVersion, sdkInt = maxOf(targetSdkVersion, minSdkVersion))
    }

    fun string(resId: Int): String? = strings[resId]

    fun resourceUrl(name: String): URL? {
        val entry = name.removePrefix("/")
        val f = archiveWith(entry) ?: return null
        return URL("jar:" + f.toURI() + "!/" + entry)
    }

    fun resourceBytes(name: String): ByteArray? {
        val entry = name.removePrefix("/")
        val f = archiveWith(entry) ?: return null
        return runCatching { ZipFile(f).use { z -> z.getInputStream(z.getEntry(entry)).use { it.readBytes() } } }.getOrNull()
    }

    private fun archiveWith(entry: String): File? =
        decompiler.args.inputFiles.firstOrNull { f -> runCatching { ZipFile(f).use { it.getEntry(entry) != null } }.getOrDefault(false) }

    private fun loadStrings(): Map<Int, String> {
        val arsc = decompiler.resources.firstOrNull { it.type == ResourceType.ARSC } ?: return emptyMap()
        return runCatching {
            ResourcesLoader.decodeStream(arsc) { _, input ->
                val parser = decompiler.resourcesLoader.decodeTable(arsc, input)
                val storage = parser.resStorage
                val values = ValuesParser(parser.strings, storage.resourcesNames)
                val out = HashMap<Int, String>()
                for (entry in storage.resources) {
                    if (entry.typeName != "string" || entry.config.isNotEmpty() && entry.config != "default") continue
                    val v = values.getSimpleValueString(entry) ?: continue
                    out.putIfAbsent(entry.id, v)
                }
                out
            }
        }.onFailure { LOG.warn("dexguard: cannot read string resources", it) }.getOrDefault(emptyMap())
    }

    private val manifestText: String? by lazy {
        val manifest = decompiler.resources.firstOrNull { it.type == ResourceType.MANIFEST } ?: return@lazy null
        runCatching { manifest.loadContent().text.codeStr }.getOrNull()
    }

    private fun manifestAttr(name: String): String? {
        val text = manifestText ?: return null
        val m = Regex("""\b${Regex.escape(name)}="([^"]*)"""").find(text) ?: return null
        return m.groupValues[1]
    }

    private fun loadSigningCerts(): List<ByteArray> {
        val apk = decompiler.args.inputFiles.firstOrNull { f ->
            runCatching { ZipFile(f).use { it.getEntry("AndroidManifest.xml") != null } }.getOrDefault(false)
        } ?: return emptyList()
        return runCatching {
            ZipFile(apk).use { zip ->
                val sigEntry = zip.entries().asSequence().firstOrNull {
                    val n = it.name.uppercase()
                    n.startsWith("META-INF/") && (n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC"))
                } ?: return emptyList()
                val cf = CertificateFactory.getInstance("X.509")
                zip.getInputStream(sigEntry).use { input ->
                    cf.generateCertificates(input).map { it.encoded }
                }
            }
        }.onFailure { LOG.debug("dexguard: cannot read signing certificate: {}", it.toString()) }.getOrDefault(emptyList())
    }

    private companion object {
        val LOG = LoggerFactory.getLogger(AppInfo::class.java)
    }
}
