package jadx.emu.ext.dexguard

import jadx.plugins.emu.api.EmuExtension
import jadx.plugins.emu.api.EmuExtensionContext
import jadx.plugins.emu.api.EmuExtensionInfo
import jadx.plugins.emu.api.ExtensionMode

class DexGuardExtension : EmuExtension {

    override fun info(): EmuExtensionInfo = EmuExtensionInfo(
        id = "dexguard-unpacker",
        name = "DexGuard unpacker",
        description = "Unpacks the dex files DexGuard loads at runtime",
        mode = ExtensionMode.ON_DEMAND,
        requiredEmuVersion = "0.1.0",
    )

    override fun init(ctx: EmuExtensionContext) {
        val stubs = ctx.emu.engine.android
        val hooks = ctx.emu.world.hooks
        val config = ExtensionConfig.load(ctx.dataDir, ctx.log)
        Telemetry.enabled = config.telemetry

        val app = AppInfo(ctx.jadx.decompiler)
        val profile = app.profile
        config.applyTo(profile)

        // Constant folding + a faithful, non-debuggable, correctly-signed runtime environment.
        OpaqueConstants.register(stubs)
        HostShims(app, profile).register(stubs, hooks)
        AppContextStubs(app, profile).register(stubs, hooks)
        PackageStubs(app, profile).register(stubs, hooks)

        // Capture files the app drops to disk, so path-based loaders can be followed.
        val vfs = VirtualFs().also { it.register(stubs, hooks) }

        val unpacker = Unpacker(ctx, hooks, config)
        val streamVm by lazy { ctx.emu.engine.newVm(unpacker.limits, hooks = hooks) }

        // DexStreams and NativeBridge both see the composite source (base dex + recovered dexes).
        val hidden = HiddenDex(ctx.emu.source, ctx.emu.engine, hooks, unpacker.limits, ctx.log, unpacker::onLoad, app, vfs)
        DexStreams(hidden.composite) { streamVm }.register(hooks)
        hidden.register()

        // Crypto capture: reproduce doFinal host-side and feed any decrypted DEX back into the loader.
        val crypto = CryptoCapture(ctx.log, config) { bytes -> hidden.feedDecrypted(bytes) }.also { it.register(hooks) }

        // Custom (non-javax.crypto) decrypt capture: observe I/O of methods named in config.
        val decrypt = DecryptCapture(ctx.log, config, hidden.composite, hidden::invokeValue).also { it.register(hooks) }

        NativeBridge(ctx).also { it.load() }.register(hooks)

        unpacker.attach(hidden, app, profile, crypto, decrypt)
        unpacker.registerActions()
    }
}
