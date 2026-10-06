package jadx.emu.ext.dexguard

import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight, thread-safe run telemetry. Turns the "best-effort" hooks into *measured* facts: which
 * stubs fired, which returned UNKNOWN (i.e. an un-foldable constant), which classes/methods could not
 * be resolved, and a bounded event log. All recording is a no-op unless [enabled] (set from config),
 * so the hot path stays cheap when telemetry is off.
 *
 * This records only aggregate/diagnostic data - never key material or decrypted bytes (those are the
 * crypto capture's concern and live in their own, separately-gated output).
 */
internal object Telemetry {

    @Volatile var enabled: Boolean = false

    private const val MAX_EVENTS = 4000
    private const val MAX_SET = 4000

    private val stubFires = ConcurrentHashMap<String, AtomicLong>()
    private val unknownReturns = ConcurrentHashMap<String, AtomicLong>()
    private val unresolved: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val events: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val counters = ConcurrentHashMap<String, AtomicLong>()

    fun reset() {
        stubFires.clear(); unknownReturns.clear(); unresolved.clear(); counters.clear()
        synchronized(events) { events.clear() }
    }

    fun stub(cls: String, name: String, returnedUnknown: Boolean) {
        if (!enabled) return
        inc(stubFires, "$cls->$name")
        if (returnedUnknown) inc(unknownReturns, "$cls->$name")
    }

    fun unresolved(what: String) {
        if (!enabled) return
        if (unresolved.size < MAX_SET) unresolved.add(what)
    }

    fun count(key: String) {
        if (!enabled) return
        inc(counters, key)
    }

    fun event(category: String, detail: String) {
        if (!enabled) return
        synchronized(events) {
            if (events.size < MAX_EVENTS) events.add("$category: $detail")
        }
    }

    fun write(path: Path) {
        if (!enabled) return
        runCatching { Files.write(path, toJson().toByteArray()) }
    }

    fun toJson(): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"counters\": ").append(mapJson(counters)).append(",\n")
        sb.append("  \"stubFires\": ").append(mapJson(stubFires)).append(",\n")
        sb.append("  \"unknownReturns\": ").append(mapJson(unknownReturns)).append(",\n")
        sb.append("  \"unresolved\": ").append(listJson(unresolved.sorted())).append(",\n")
        val snapshot = synchronized(events) { events.toList() }
        sb.append("  \"events\": ").append(listJson(snapshot)).append("\n")
        sb.append("}\n")
        return sb.toString()
    }

    private fun inc(m: ConcurrentHashMap<String, AtomicLong>, k: String) =
        m.computeIfAbsent(k) { AtomicLong() }.incrementAndGet()

    private fun mapJson(m: Map<String, AtomicLong>): String {
        if (m.isEmpty()) return "{}"
        val entries = m.entries.sortedByDescending { it.value.get() }
        return entries.joinToString(prefix = "{", postfix = "}") { "\n    ${str(it.key)}: ${it.value.get()}" }
            .let { if (it == "{}") it else "$it\n  " }
    }

    private fun listJson(items: List<String>): String =
        if (items.isEmpty()) "[]" else items.joinToString(prefix = "[", postfix = "\n  ]") { "\n    ${str(it)}" }

    private fun str(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
