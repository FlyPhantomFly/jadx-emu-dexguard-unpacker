package jadx.emu.ext.dexguard

/**
 * Configurable fake-device/environment description used by the context, package-manager and
 * host-property stubs. minSdk/targetSdk are filled from the manifest by AppInfo; the rest are
 * plausible defaults for a modern Pixel and can be edited to match a target's RASP expectations.
 */
internal data class DeviceProfile(
    var minSdk: Int = 24,
    var targetSdk: Int = 33,
    var sdkInt: Int = 33,
    var manufacturer: String = "Google",
    var brand: String = "google",
    var model: String = "Pixel 6",
    var device: String = "oriole",
    var product: String = "oriole",
    var hardware: String = "oriole",
    var fingerprint: String = "google/oriole/oriole:13/TQ3A.230805.001/10316531:user/release-keys",
    var tags: String = "release-keys",
    var type: String = "user",
    var androidId: String = "a1b2c3d4e5f60718",
    var installerPackage: String? = "com.android.vending",
    var debuggable: Boolean = false,
    var pid: Int = 4242,
    var uid: Int = 10042,
)
