package jadx.emu.ext.dexguard

import jadx.plugins.emu.api.Hooks
import jadx.plugins.emu.exec.runtime.DvmThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * ExtHooks keeps the semantics this extension's hooks were written for - every handler on a descriptor runs, in
 * registration order, and the last `replace` wins - even though jadx-emu itself runs newest-first and stops at
 * the first `replace`. These tests pin that, and that one broken handler cannot change a call's outcome.
 */
class ExtHooksTest {

    private val desc = "Ljava/lang/reflect/Method;->invoke(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;"

    @AfterEach fun restoreTelemetry() { Telemetry.enabled = false; Telemetry.reset() }

    @Test fun everyHandlerRunsInRegistrationOrderAndTheLastReplaceWins() {
        val hooks = ExtHooks(Hooks())
        val order = ArrayList<String>()
        hooks.add(desc) { c -> order.add("first"); c.replace(1) }
        hooks.add(desc) { order.add("observer") }
        hooks.add(desc) { c -> order.add("last"); c.replace(2) }
        val call = TestCalls.call(desc, null, emptyList())
        hooks.fire(desc, call)
        assertEquals(listOf("first", "observer", "last"), order)
        assertTrue(call.replaced)
        assertEquals(2, call.result)
    }

    @Test fun anObserverRegisteredBeforeAReplacerStillSeesTheCall() {
        // the engine alone would stop at the (newer) replacer and never run the older observer
        val hooks = ExtHooks(Hooks())
        var seen = false
        hooks.add(desc) { seen = true }
        hooks.add(desc) { c -> c.replace("x") }
        hooks.fire(desc, TestCalls.call(desc, null, emptyList()))
        assertTrue(seen)
    }

    @Test fun aThrowingHandlerIsSkippedAndTheOthersStillRun() {
        Telemetry.enabled = true
        val hooks = ExtHooks(Hooks())
        hooks.add(desc) { throw IllegalStateException("bang") }
        hooks.add(desc) { c -> c.replace(7) }
        val call = TestCalls.call(desc, null, emptyList())
        hooks.fire(desc, call)
        assertEquals(7, call.result)
        assertEquals(1L, Telemetry.counterOf("hook.failed"))
    }

    @Test fun emulatedExceptionsStillPropagateToTheEngine() {
        val hooks = ExtHooks(Hooks())
        hooks.add(desc) { throw DvmThrowable("Ljava/lang/IllegalStateException;", "no", null) }
        assertThrows(DvmThrowable::class.java) { hooks.fire(desc, TestCalls.call(desc, null, emptyList())) }
    }

    @Test fun descriptorsAreIndependent() {
        val hooks = ExtHooks(Hooks())
        hooks.add(desc) { c -> c.replace(1) }
        val other = TestCalls.call("Ljava/lang/Object;->hashCode()I", null, emptyList())
        hooks.fire("Ljava/lang/Object;->hashCode()I", other)
        assertFalse(other.replaced)
    }

    @Test fun returnHandlersChainInRegistrationOrder() {
        val hooks = ExtHooks(Hooks())
        hooks.addReturn(desc) { _, r -> (r as Int) + 1 }
        hooks.addReturn(desc) { _, r -> (r as Int) * 10 }
        assertEquals(150, TestCalls.fireReturn(hooks, desc, null, emptyList(), 14))
    }

    @Test fun aThrowingReturnHandlerKeepsTheRealResult() {
        Telemetry.enabled = true
        val hooks = ExtHooks(Hooks())
        hooks.addReturn(desc) { _, _ -> throw IllegalStateException("bang") }
        hooks.addReturn(desc) { _, r -> (r as Int) + 1 }
        assertEquals(15, TestCalls.fireReturn(hooks, desc, null, emptyList(), 14))
        assertEquals(1L, Telemetry.counterOf("hook.failed"))
    }

    @Test fun returnHandlersSeeTheCallsArguments() {
        val hooks = ExtHooks(Hooks())
        var seen: List<Any?>? = null
        hooks.addReturn(desc) { c, r -> seen = c.args.toList(); r }
        TestCalls.fireReturn(hooks, desc, null, listOf(1, "a"), 0)
        assertEquals(listOf<Any?>(1, "a"), seen)
    }
}
