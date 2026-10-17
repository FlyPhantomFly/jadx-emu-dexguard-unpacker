package jadx.emu.ext.dexguard

import jadx.plugins.emu.api.Hooks
import jadx.plugins.emu.exec.EngineContext
import jadx.plugins.emu.exec.ExecLimits
import jadx.plugins.emu.exec.NativeBridge
import jadx.plugins.emu.exec.Vm

/**
 * The one place this extension builds a [Vm].
 *
 * jadx-emu is a beta whose Kotlin API changes between releases, and a `Vm` built any other way than through
 * [EngineContext.newVm] has to track every constructor change (an earlier jadx-emu appended a defaulted parameter and
 * broke binary compatibility). Funnelling every VM through here keeps a future API change to one function.
 *
 * Every VM gets this extension's [EngineDiagnostics] when telemetry is on (so refused host calls and missing stubs are
 * reported), a fresh statics store, and the given [nativeBridge] (null keeps the validated, bridge-less behaviour).
 */
internal object EngineCompat {

    fun newVm(
        ec: EngineContext,
        limits: ExecLimits,
        nativeBridge: NativeBridge?,
        hooks: Hooks,
        androidEnvUnknown: Boolean,
    ): Vm = ec.newVm(
        limits = limits,
        hooks = hooks,
        androidEnvUnknown = androidEnvUnknown,
        diagnostics = EngineDiagnostics.sinks(),
        nativeBridge = nativeBridge,
    )
}
