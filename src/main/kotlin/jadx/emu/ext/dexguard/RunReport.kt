package jadx.emu.ext.dexguard

import java.nio.file.Files
import java.nio.file.Path

/** Minimal dependency-free JSON report of a run: recovered dex files, their stats, and the fake env. */
internal object RunReport {

    fun write(path: Path, dexes: List<ByteArray>, files: List<Path>, env: DeviceProfile, packageName: String?) {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"package\": ").append(str(packageName)).append(",\n")
        sb.append("  \"recoveredCount\": ").append(dexes.size).append(",\n")
        sb.append("  \"env\": {\n")
        sb.append("    \"minSdk\": ").append(env.minSdk).append(",\n")
        sb.append("    \"targetSdk\": ").append(env.targetSdk).append(",\n")
        sb.append("    \"sdkInt\": ").append(env.sdkInt).append(",\n")
        sb.append("    \"fingerprint\": ").append(str(env.fingerprint)).append(",\n")
        sb.append("    \"installer\": ").append(str(env.installerPackage)).append("\n")
        sb.append("  },\n")
        sb.append("  \"dex\": [\n")
        files.forEachIndexed { i, f ->
            val bytes = runCatching { Files.readAllBytes(f) }.getOrNull()
            sb.append("    {")
            sb.append("\"file\": ").append(str(f.fileName.toString()))
            if (bytes != null) {
                sb.append(", \"size\": ").append(bytes.size)
                sb.append(", \"classDefs\": ").append(DexBytes.classDefCount(bytes))
                sb.append(", \"sha256\": ").append(str(DexBytes.sha256(bytes)))
            }
            sb.append("}")
            sb.append(if (i == files.lastIndex) "\n" else ",\n")
        }
        sb.append("  ]\n")
        sb.append("}\n")
        Files.write(path, sb.toString().toByteArray())
    }

    private fun str(s: String?): String =
        if (s == null) "null" else "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
