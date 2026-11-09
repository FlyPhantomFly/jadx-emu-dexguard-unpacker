package jadx.emu.ext.dexguard

import java.nio.file.Files
import java.nio.file.Path

/** Minimal dependency-free JSON report of a run: recovered dex files, their stats, and the fake env. */
internal object RunReport {

    fun write(
        path: Path,
        dexes: List<ByteArray>,
        files: List<Path>,
        env: DeviceProfile,
        packageName: String?,
        nativeRoutine: List<NativeRoutineScan.Result> = emptyList(),
    ) {
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
        writeNative(sb, nativeRoutine)
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

    /** The DexGuard "return to Java" fingerprint ([NativeRoutineScan]), so scripts can pick up the address. */
    private fun writeNative(sb: StringBuilder, results: List<NativeRoutineScan.Result>) {
        sb.append("  \"returnToJava\": {\n")
        sb.append("    \"found\": ").append(results.isNotEmpty()).append(",\n")
        sb.append("    \"libs\": [\n")
        results.forEachIndexed { i, r ->
            sb.append("      {\n")
            sb.append("        \"lib\": ").append(str(r.lib)).append(",\n")
            sb.append("        \"abi\": ").append(str(r.abi)).append(",\n")
            sb.append("        \"matches\": [\n")
            r.matches.forEachIndexed { j, m ->
                sb.append("          {")
                sb.append("\"pattern\": ").append(str(m.pattern))
                sb.append(", \"fileOffset\": ").append(m.fileOffset)
                sb.append(", \"virtualAddress\": ").append(if (m.virtualAddress >= 0) "\"0x%08x\"".format(m.virtualAddress) else null)
                sb.append(", \"register\": ").append(m.register)
                sb.append(", \"constant\": \"0x%08x\"".format(m.constant))
                sb.append("}")
                sb.append(if (j == r.matches.lastIndex) "\n" else ",\n")
            }
            sb.append("        ]\n")
            sb.append("      }")
            sb.append(if (i == results.lastIndex) "\n" else ",\n")
        }
        sb.append("    ]\n")
        sb.append("  },\n")
    }

    private fun str(s: String?): String {
        if (s == null) return "null"
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u").append("%04x".format(c.code)) else sb.append(c)
        }
        return sb.append("\"").toString()
    }
}
