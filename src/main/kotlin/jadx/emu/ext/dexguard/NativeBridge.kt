package jadx.emu.ext.dexguard

import jadx.plugins.emu.api.EmuExtensionContext
import jadx.plugins.emu.exec.DvmMethodHandle
import jadx.plugins.emu.exec.HookRegistry
import java.nio.file.Files
import java.util.Properties

/**
 * Record/replay bridge for native (JNI) methods.
 *
 * DexGuard's heaviest tiers push the decryption routine into a .so. There is no way to *run* that
 * code inside a pure-JVM Dalvik emulator, so this bridge lets you supply the results instead: drop a
 * `native-replay.properties` file into the extension data dir with lines of the form
 *
 *     Lcom/foo/Bar;->decrypt=1234        # int/long result
 *     Lcom/foo/Bar;->tag=release         # string result
 *
 * When a *reflective* call (Method.invoke) targets a listed `Lcls;->name` symbol, the replay value is
 * returned in place of actually invoking it (native decryptors being the usual reason to list one).
 *
 * LIMITATION / SEAM: direct bytecode invocation of a native method (invoke-virtual/-static straight
 * to a native symbol, not via reflection) cannot be intercepted here - jadx-emu would need a native
 * call hook (e.g. an `onNativeCall(symbol, args)` seam on the engine). The right long-term fix is to
 * wire an in-process unidbg instance to that seam and execute the real .so; see attachUnidbgSeam().
 */
internal class NativeBridge(private val ctx: EmuExtensionContext) {

    private val replay = HashMap<String, String>()

    fun load() {
        val f = ctx.dataDir.resolve("native-replay.properties")
        if (!Files.exists(f)) return
        runCatching {
            val p = Properties()
            Files.newInputStream(f).use { p.load(it) }
            for (name in p.stringPropertyNames()) replay[name] = p.getProperty(name)
            ctx.log.info("native replay loaded: {} entries", replay.size)
        }.onFailure { ctx.log.warn("cannot read native-replay.properties: {}", it.toString()) }
    }

    fun register(hooks: HookRegistry) {
        if (replay.isEmpty()) return
        hooks.add(HostShims.METHOD_INVOKE) { call ->
            val h = call.receiver as? DvmMethodHandle ?: return@add
            if (h.hostMethod != null) return@add
            val symbol = h.symbol ?: return@add
            // Match on the fully-qualified "Lcls;->name" key the user listed. We intentionally do not
            // gate on a native-flag check: the user populates the file precisely with the symbols they
            // want replayed (native decryptors being the usual case), and the dex method's native flag
            // is keyed by the full shortId (with return type) which a reflective symbol may not carry.
            val raw = replay[symbol.declClass + "->" + symbol.name] ?: return@add
            call.replace(coerce(raw))
        }
    }

    /**
     * Integration point for real native execution. Intentionally a no-op: it documents exactly what
     * jadx-emu must expose. Once the engine offers a native-call seam, create a unidbg AndroidEmulator,
     * map the APK's lib/<abi>, register the JNI environment, and translate Dalvik args to native
     * ones here, returning the native result to the VM.
     */
    @Suppress("unused")
    fun attachUnidbgSeam() {
        // TODO: requires an engine.onNativeCall(symbol, receiver, args) hook in jadx-emu.
        ctx.log.debug("native unidbg seam not attached (no engine native-call hook available)")
    }

    private fun coerce(raw: String): Any {
        raw.toLongOrNull()?.let { l ->
            return if (l in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) l.toInt() else l
        }
        return raw.toDoubleOrNull() ?: raw
    }
}
