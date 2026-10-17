package jadx.emu.ext.dexguard

import jadx.plugins.emu.api.Hooks
import jadx.plugins.emu.api.MethodCall
import jadx.plugins.emu.exec.EngineContext
import jadx.plugins.emu.exec.ExecLimits
import jadx.plugins.emu.exec.HostBoundary
import jadx.plugins.emu.exec.MethodSource
import jadx.plugins.emu.exec.Vm
import jadx.plugins.emu.exec.model.MethodRef
import java.lang.reflect.Proxy

/**
 * Builds [MethodCall]s for unit tests that drive this extension's hook handlers directly (through
 * [ExtHooks.fire] / [ExtHooks.fireReturn]) without interpreting any Dalvik code. The VM sits on an empty source: a
 * call is only a carrier for (method, receiver, args), which is all the handlers read.
 */
internal object TestCalls {

    private val source: MethodSource =
        Proxy.newProxyInstance(MethodSource::class.java.classLoader, arrayOf(MethodSource::class.java)) { _, _, _ -> null } as MethodSource

    val vm: Vm by lazy {
        val limits = ExecLimits(1000, 1000L, 8)
        EngineCompat.newVm(EngineContext(source, limits, HostBoundary(), Hooks()), limits, null, Hooks(), true)
    }

    /** A call of `Lcls;->shortId` (as written in a hook descriptor). */
    fun call(descriptor: String, receiver: Any?, args: List<Any?>): MethodCall =
        MethodCall(vm, MethodRef.of(descriptor.substringBefore("->"), descriptor.substringAfter("->")), receiver, args.toMutableList())

    fun fire(hooks: ExtHooks, descriptor: String, receiver: Any?, vararg args: Any?) =
        hooks.fire(descriptor, call(descriptor, receiver, args.toList()))

    fun fireReturn(hooks: ExtHooks, descriptor: String, receiver: Any?, args: List<Any?>, result: Any?): Any? =
        hooks.fireReturn(descriptor, call(descriptor, receiver, args), result)
}
