package jadx.emu.ext.dexguard

import jadx.plugins.emu.api.Hooks
import jadx.plugins.emu.api.MethodCall
import jadx.plugins.emu.api.Registration
import jadx.plugins.emu.exec.NotHandled
import jadx.plugins.emu.exec.VmAbort
import jadx.plugins.emu.exec.runtime.DvmThrowable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Implementation of a stubbed method: returns the call's result, or [NotHandled] to fall through.
 */
internal fun interface StubHandler {
    fun invoke(receiver: Any?, args: List<Any?>): Any?
}

/**
 * This extension's view of jadx-emu's [Hooks].
 *
 * jadx-emu runs the handlers of one pattern newest-first and stops at the first `replace`. This extension was
 * written for the earlier model where every handler on a descriptor ran, in registration order, and the last
 * `replace` won - several descriptors (reflective `Method.invoke`, `Field.get*`, `Constructor.newInstance`)
 * carry a guarded observer and a guarded replacer. So instead of registering each handler with the engine,
 * this registers ONE engine handler per descriptor that runs this extension's chain in registration order.
 * That keeps the behaviour of every hook independent of how the engine orders or short-circuits handlers.
 *
 * A handler that throws does not take the others down or change the call's result: it is counted and
 * skipped. Emulated exceptions ([DvmThrowable]) and run aborts ([VmAbort]) still propagate to the engine.
 */
internal class ExtHooks(val raw: Hooks) {

    private val befores = ConcurrentHashMap<String, CopyOnWriteArrayList<(MethodCall) -> Unit>>()
    private val afters = ConcurrentHashMap<String, CopyOnWriteArrayList<(MethodCall, Any?) -> Any?>>()

    /**
     * Run [handler] before every call to [descriptor]; it may `replace` the call.
     */
    fun add(descriptor: String, handler: (MethodCall) -> Unit) {
        val chain = befores.computeIfAbsent(descriptor) { d ->
            val list = CopyOnWriteArrayList<(MethodCall) -> Unit>()
            raw.hook(d) { fire(d, this) }
            list
        }
        chain.add(handler)
    }

    /**
     * Run [handler] after every call to [descriptor] that was not replaced, with the call's real result;
     * return the value the caller should receive (the result itself, to only observe).
     */
    fun addReturn(descriptor: String, handler: (MethodCall, Any?) -> Any?) {
        val chain = afters.computeIfAbsent(descriptor) { d ->
            val list = CopyOnWriteArrayList<(MethodCall, Any?) -> Any?>()
            raw.after(d) { result -> fireReturn(d, this, result) }
            list
        }
        chain.add(handler)
    }

    /**
     * Implement every overload of `classDesc->name`. [handler] returns the result, or [NotHandled] to fall through.
     */
    fun registerMethod(classDesc: String, name: String, handler: StubHandler): Registration =
        raw.stub("$classDesc->$name") {
            val v = handler.invoke(receiver, args)
            if (v !== NotHandled) replace(v)
        }

    fun isFrameworkClass(declClass: String): Boolean = raw.isFrameworkClass(declClass)

    /**
     * Run this extension's before-chain for [descriptor] on [call], exactly as the engine does for the one
     * handler registered per descriptor.
     */
    internal fun fire(descriptor: String, call: MethodCall) {
        val chain = befores[descriptor] ?: return
        for (h in chain) {
            try {
                h(call)
            } catch (t: DvmThrowable) {
                throw t
            } catch (t: VmAbort) {
                throw t
            } catch (t: Throwable) {
                failed(descriptor, t)
            }
        }
    }

    /**
     * Run this extension's after-chain for [descriptor]; each handler receives the previous one's result.
     */
    internal fun fireReturn(descriptor: String, call: MethodCall, result: Any?): Any? {
        val chain = afters[descriptor] ?: return result
        var out = result
        for (h in chain) {
            try {
                out = h(call, out)
            } catch (t: DvmThrowable) {
                throw t
            } catch (t: VmAbort) {
                throw t
            } catch (t: Throwable) {
                failed(descriptor, t)
            }
        }
        return out
    }

    private fun failed(descriptor: String, t: Throwable) {
        Telemetry.count("hook.failed")
        Telemetry.event("hook", "$descriptor: $t")
    }
}
