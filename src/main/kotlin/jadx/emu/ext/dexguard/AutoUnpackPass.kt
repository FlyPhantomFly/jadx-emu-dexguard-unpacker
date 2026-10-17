package jadx.emu.ext.dexguard

import jadx.api.plugins.pass.JadxPassInfo
import jadx.api.plugins.pass.impl.OrderedJadxPassInfo
import jadx.api.plugins.pass.types.JadxDecompilePass
import jadx.core.dex.nodes.ClassNode
import jadx.core.dex.nodes.MethodNode
import jadx.core.dex.nodes.RootNode
import org.slf4j.Logger

/**
 * Headless auto-unpack. When `autoUnpack=true` in config, runs the loader auto-sweep once during
 * decompilation - so `jadx -d out app.apk` (no GUI, no clicks) recovers the hidden DEX and writes the
 * usual `hidden-*.dex` / `report.json` / `telemetry.json` outputs, then exits.
 *
 * The GUI context-menu actions remain the primary interface; this pass does nothing unless enabled, so
 * it's harmless to register unconditionally. It acts in [init] (once, before any per-class visits) and
 * does not transform decompiled code.
 */
internal class AutoUnpackPass(
    private val unpacker: Unpacker,
    private val config: ExtensionConfig,
    private val log: Logger,
) : JadxDecompilePass {

    @Volatile private var done = false

    override fun getInfo(): JadxPassInfo =
        OrderedJadxPassInfo("DexGuardAutoUnpack", "Auto-unpack DexGuard hidden dex when autoUnpack is enabled")

    override fun init(root: RootNode) {
        if (!config.autoUnpack || done) return
        done = true
        log.info("dexguard-unpacker: autoUnpack enabled - running sweep")
        runCatching { unpacker.autoSweep() }.onFailure { log.warn("dexguard-unpacker: auto-unpack failed: {}", it.toString()) }
    }

    override fun visit(cls: ClassNode): Boolean = false   // no per-class work

    override fun visit(mth: MethodNode) {}
}
