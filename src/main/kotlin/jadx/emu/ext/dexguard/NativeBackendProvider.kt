package jadx.emu.ext.dexguard

import jadx.plugins.emu.api.EmuExtensionContext

/**
 * SPI for a pluggable native (JNI) execution backend. The core extension stays free of heavy native
 * deps (e.g. unidbg); an optional backend module implements this interface and registers itself via
 * `META-INF/services/jadx.emu.ext.dexguard.NativeBackendProvider`. When `nativeBackend=<id>` is set in
 * config, the extension discovers the matching provider with `ServiceLoader` and uses the bridge it
 * builds as the engine's native-call handler; otherwise the built-in replay bridge is used.
 */
interface NativeBackendProvider {
    /** Matches the `nativeBackend` config value, e.g. "unidbg". */
    val id: String

    /** Build the engine native-call bridge, or null if it can't initialize (missing .so, wrong abi, …). */
    fun create(ctx: EmuExtensionContext): jadx.plugins.emu.exec.NativeBridge?
}
