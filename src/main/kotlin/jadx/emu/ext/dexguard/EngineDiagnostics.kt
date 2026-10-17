package jadx.emu.ext.dexguard

import jadx.plugins.emu.exec.Diagnostic
import jadx.plugins.emu.exec.DiagnosticSink
import java.util.concurrent.ConcurrentHashMap

/**
 * Receives jadx-emu's own [Diagnostic]s - the calls it could not execute - and records them in [Telemetry], so a
 * run that recovers nothing can name the host calls it refused and the framework methods that have no stub,
 * from the engine's ground truth rather than from inference.
 *
 * Only attached when telemetry is on; with it off the VMs get no sink and nothing is recorded or logged.
 */
internal object EngineDiagnostics : DiagnosticSink {

    private const val MAX_CALLEES = 2000
    private val seen: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun report(d: Diagnostic) {
        Telemetry.count("engine.${d.kind}")
        val callee = "engine.${d.kind} @ ${d.ref.declClass}->${d.ref.shortId}"
        if (seen.size < MAX_CALLEES || callee in seen) {
            seen.add(callee)
            Telemetry.count(callee)
        }
    }

    /** The sinks to give a VM: this one when telemetry is on, none otherwise. */
    fun sinks(): List<DiagnosticSink> = if (Telemetry.enabled) listOf(this) else emptyList()

    /** The most frequent callees reported with [kind], most frequent first, as (callee, count). */
    fun top(kind: Diagnostic.Kind, n: Int): List<Pair<String, Long>> {
        val prefix = "engine.$kind @ "
        return Telemetry.topCounters(prefix, n).map { it.first.removePrefix(prefix) to it.second }
    }

    fun count(kind: Diagnostic.Kind): Long = Telemetry.counterOf("engine.$kind")
}
