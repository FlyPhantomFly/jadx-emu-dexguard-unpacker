package jadx.emu.ext.dexguard

import jadx.plugins.emu.api.Hooks
import jadx.plugins.emu.exec.EngineContext
import jadx.plugins.emu.exec.ExecLimits
import jadx.plugins.emu.exec.HostBoundary
import jadx.plugins.emu.exec.MethodSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/**
 * EngineCompat is the single place this extension builds a Vm. These tests pin what it promises: the Vm uses the
 * hooks and Android-environment mode it is given, and carries this extension's diagnostics only when telemetry is on.
 */
class EngineCompatTest {

    private val limits = ExecLimits(1000, 1000L, 8)

    private fun context(hooks: Hooks): EngineContext {
        val src = Proxy.newProxyInstance(MethodSource::class.java.classLoader, arrayOf(MethodSource::class.java)) { _, _, _ -> null } as MethodSource
        return EngineContext(src, limits, HostBoundary(), hooks)
    }

    @AfterEach fun restoreTelemetry() { Telemetry.enabled = false; Telemetry.reset() }

    @Test fun buildsAVmAndPropagatesAndroidEnvUnknown() {
        val hooks = Hooks()
        for (unknown in listOf(true, false)) {
            val vm = EngineCompat.newVm(context(hooks), limits, null, hooks, unknown)
            assertEquals(unknown, vm.androidEnvUnknown)
        }
    }

    @Test fun theVmUsesTheGivenHooksAndLimits() {
        val hooks = Hooks()
        val vm = EngineCompat.newVm(context(Hooks()), limits, null, hooks, true)
        assertSame(hooks, vm.hooks)
        assertSame(limits, vm.limits)
    }

    @Test fun diagnosticsAreAttachedOnlyWhenTelemetryIsOn() {
        val hooks = Hooks()
        Telemetry.enabled = false
        assertTrue(EngineCompat.newVm(context(hooks), limits, null, hooks, true).diagnostics.isEmpty())
        Telemetry.enabled = true
        val sinks = EngineCompat.newVm(context(hooks), limits, null, hooks, true).diagnostics
        assertEquals(1, sinks.size)
        assertSame(EngineDiagnostics, sinks[0])
    }
}
