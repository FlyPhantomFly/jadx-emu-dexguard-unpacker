package jadx.emu.ext.dexguard

import org.slf4j.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * Optional runtime configuration, read once from `dexguard-unpacker.properties` in the extension data
 * dir. Everything has a sane default, so the file is purely opt-in - it exists so you can tune limits,
 * turn telemetry on, toggle crypto capture / multi-stage recursion, or override the fake device
 * WITHOUT editing source. Unknown keys are ignored; malformed values fall back to the default.
 */
internal class ExtensionConfig private constructor(private val p: Properties) {

    val telemetry: Boolean get() = bool("telemetry", false)
    val verbose: Boolean get() = bool("verbose", false)

    val maxSteps: Int get() = int("maxSteps", 50_000_000)
    val maxMillis: Long get() = long("maxMillis", 120_000L)
    val maxDepth: Int get() = int("maxDepth", 64)

    val captureCrypto: Boolean get() = bool("captureCrypto", true)
    val feedDecryptedDex: Boolean get() = bool("feedDecryptedDex", true)

    /** Capture input/output of custom (non-javax.crypto) decrypt methods named in captureDecryptMethods. */
    val captureDecrypt: Boolean get() = bool("captureDecrypt", false)
    val captureDecryptMethods: List<String>
        get() = str("captureDecryptMethods")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    val fixpoint: Boolean get() = bool("fixpoint", true)
    val fixpointMaxRounds: Int get() = int("fixpointMaxRounds", 6)

    /** How many top-ranked candidates the "auto-sweep" action tries in one click. */
    val sweepTopN: Int get() = int("sweepTopN", 12)

    /** Per-candidate wall-clock budget (ms) for the sweep; 0 = unlimited (thorough). Caps total time
     *  spent on one candidate without cutting a decrypt mid-run - smarter than a flat per-invoke cap. */
    val sweepBudgetMs: Long get() = long("sweepBudgetMs", 0L)

    /** Scanner passes: "signature" (fast, no decompile) | "body" (decompiles) | "both" (default). */
    val scanMode: String get() = (str("scanMode") ?: "dex").lowercase()

    /** Classes (descriptor or dotted) the sweep always runs, on top of scanner hits - inject known loaders here. */
    val sweepClasses: List<String>
        get() = str("sweepClasses")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    /** Applies any device.* overrides onto the profile in place. */
    fun applyTo(profile: DeviceProfile) {
        str("device.manufacturer")?.let { profile.manufacturer = it }
        str("device.brand")?.let { profile.brand = it }
        str("device.model")?.let { profile.model = it }
        str("device.device")?.let { profile.device = it }
        str("device.product")?.let { profile.product = it }
        str("device.hardware")?.let { profile.hardware = it }
        str("device.fingerprint")?.let { profile.fingerprint = it }
        str("device.tags")?.let { profile.tags = it }
        str("device.type")?.let { profile.type = it }
        str("device.androidId")?.let { profile.androidId = it }
        str("device.installer")?.let { profile.installerPackage = it.ifBlank { null } }
        intOrNull("device.sdkInt")?.let { profile.sdkInt = it }
        intOrNull("device.minSdk")?.let { profile.minSdk = it }
        intOrNull("device.targetSdk")?.let { profile.targetSdk = it }
        boolOrNull("device.debuggable")?.let { profile.debuggable = it }
    }

    private fun str(k: String): String? = p.getProperty(k)?.trim()?.takeIf { it.isNotEmpty() }
    private fun bool(k: String, d: Boolean): Boolean = boolOrNull(k) ?: d
    private fun boolOrNull(k: String): Boolean? = str(k)?.lowercase()?.let { it == "true" || it == "1" || it == "yes" }
    private fun int(k: String, d: Int): Int = intOrNull(k) ?: d
    private fun intOrNull(k: String): Int? = str(k)?.toIntOrNull()
    private fun long(k: String, d: Long): Long = str(k)?.toLongOrNull() ?: d

    companion object {
        fun load(dataDir: Path, log: Logger): ExtensionConfig {
            val f = dataDir.resolve("dexguard-unpacker.properties")
            val p = Properties()
            if (Files.exists(f)) {
                runCatching { Files.newInputStream(f).use { p.load(it) } }
                    .onSuccess { log.info("dexguard-unpacker: loaded config ({} keys) from {}", p.size, f) }
                    .onFailure { log.warn("dexguard-unpacker: cannot read config at {}: {}", f, it.toString()) }
            } else {
                // Make the expected location discoverable: if telemetry/crypto toggles seem ignored,
                // this is the exact path the properties file must live at.
                log.info("dexguard-unpacker: no config file, using defaults. Create {} to tune.", f)
            }
            return ExtensionConfig(p)
        }
    }
}
