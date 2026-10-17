package jadx.emu.ext.dexguard

import jadx.plugins.emu.exec.Diagnostic
import org.slf4j.Logger
import java.nio.file.Files
import java.nio.file.Path

/**
 * Turns a run's telemetry counters into a prescriptive, human-readable diagnosis - so when a run
 * recovers nothing the log says *why* and what to change, instead of leaving you to guess.
 *
 * [Telemetry] counters are only populated when telemetry is enabled; without it the diagnosis still
 * reports the always-knowable facts (recovery count, empty source, time-limit hits) and points you at
 * `telemetry=true` for the detailed version. Everything here is derived from signals the extension
 * already records (crypto capture, the inline scheduler, cdex skips, un-foldable stubs, unresolved
 * references) - no new instrumentation.
 *
 * With telemetry on, every VM also reports to [EngineDiagnostics], so a zero-recovery run names the host calls the
 * engine refused to run and the framework methods that have no stub - read from the engine, not inferred.
 */
internal object Diagnosis {

    fun report(log: Logger, outDir: Path, recovered: Int, sourceEmpty: Boolean, limitHits: Int) {
        val lines = build(recovered, sourceEmpty, limitHits)
        if (recovered > 0) {
            // Partial-recovery hints only (don't nag on a clean run).
            lines.forEach { log.info("diagnosis: {}", it) }
            return
        }
        log.warn("diagnosis: recovered 0 dex - likely cause & next steps:")
        lines.forEach { log.warn("  - {}", it) }
        runCatching {
            Files.write(outDir.resolve("diagnosis.txt"), (lines.joinToString("\n") + "\n").toByteArray())
            log.info("diagnosis written to {}", outDir.resolve("diagnosis.txt"))
        }
    }

    private fun top(kind: Diagnostic.Kind): String =
        EngineDiagnostics.top(kind, 3).joinToString(", ") { "${it.first} x${it.second}" }

    /** What the engine itself reports it could not execute (telemetry on only). */
    private fun engineLines(out: MutableList<String>) {
        val denied = EngineDiagnostics.count(Diagnostic.Kind.HOST_DENIED)
        if (denied > 0) out.add(
            "The engine refused $denied host call(s) under its host policy - most frequent: ${top(Diagnostic.Kind.HOST_DENIED)}. " +
                "If one of these gates the payload, allow it in the jadx-emu host policy or register a Hooks.hook/stub for it.",
        )
        val missing = EngineDiagnostics.count(Diagnostic.Kind.MISSING_STUB)
        if (missing > 0) out.add(
            "$missing framework call(s) have no stub and returned UNKNOWN - most frequent: ${top(Diagnostic.Kind.MISSING_STUB)}. " +
                "Register a Hooks.stub for the ones the loader depends on.",
        )
        val failed = EngineDiagnostics.count(Diagnostic.Kind.HANDLER_FAILED)
        if (failed > 0) out.add(
            "$failed hook/stub handler(s) threw (${top(Diagnostic.Kind.HANDLER_FAILED)}); the affected calls became UNKNOWN. " +
                "See the jadx-emu log for the exception.",
        )
        val aborted = EngineDiagnostics.count(Diagnostic.Kind.ABORTED)
        if (aborted > 0) out.add(
            "$aborted emulated call(s) were aborted by a step, time or depth limit (${top(Diagnostic.Kind.ABORTED)}). " +
                "Raise maxSteps / maxDepth, or maxMillis=0.",
        )
    }

    private fun build(recovered: Int, sourceEmpty: Boolean, limitHits: Int): List<String> {
        val out = ArrayList<String>()

        if (sourceEmpty) out.add(
            "Emulator source is EMPTY (0 methods) - jadx-emu read no DEX. If this is an XAPK / App Bundle / " +
                "split APK, extract it and open the base APK (the one with classes.dex) directly.",
        )
        if (limitHits > 0) out.add(
            "$limitHits run(s) hit the maxMillis time limit and were CUT OFF - work after the cut (incl. later " +
                "hidden dex) was not produced. Set maxMillis=0, and clockAdvanceMs=1000 if a timing loop spins.",
        )

        val cdexSkipped = Telemetry.counterOf("dex.cdexSkipped")
        if (cdexSkipped > 0) out.add(
            "$cdexSkipped CompactDex (cdex) payload(s) were skipped - not loadable as a standard dex yet " +
                "(tracked: item 4). The loader reached the payload; the format isn't supported.",
        )

        val cryptoCaptured = Telemetry.counterOf("crypto.captured")
        val cryptoFed = Telemetry.counterOf("crypto.fedDex")
        val cryptoGuessSkipped = Telemetry.counterOf("crypto.guessSkipped")
        if (cryptoCaptured > 0 && cryptoFed == 0L) out.add(
            "A cipher ran ($cryptoCaptured doFinal) but no output was a DEX/zip - the decrypted payload is " +
                "probably not the dex (nested stage, or a non-dex asset). Inspect crypto/crypto.json.",
        )
        if (cryptoGuessSkipped > 0) out.add(
            "$cryptoGuessSkipped cipher output(s) came from a GUESSED transformation and were skipped - no " +
                "Cipher.getInstance was seen. The real transformation is needed; see crypto/crypto.json.",
        )

        val sched = Telemetry.counterOf("sched.run") + Telemetry.counterOf("sched.call")
        if (sched > 0) {
            out.add("Inline scheduler drove $sched off-thread/executor body(ies) (item 1 active).")
        } else if (Telemetry.enabled) {
            out.add(
                "No off-thread/executor/handler dispatch was observed. If the loader decrypts on a background " +
                    "thread via a path not yet covered (FutureTask ctor, AsyncTask), that body may not have run.",
            )
        }

        Telemetry.topUnknownReturns(3).takeIf { it.isNotEmpty() }?.let { top ->
            out.add(
                "Stubs that returned UNKNOWN (un-foldable constants; a better stub may unblock a value): " +
                    top.joinToString(", ") { "${it.first} x${it.second}" },
            )
        }
        Telemetry.topUnresolved(3).takeIf { it.isNotEmpty() }?.let { top ->
            out.add(
                "Unresolved references (not found in app+hidden dex; missing stage, or native/renamed target): " +
                    top.joinToString(", "),
            )
        }

        if (recovered == 0) engineLines(out)

        if (!Telemetry.enabled) out.add(
            "Set telemetry=true in dexguard-unpacker.properties and re-run for a detailed diagnosis (which " +
                "stubs fired, what returned UNKNOWN, what couldn't be resolved).",
        )

        out.add(
            "Common fixes: maxMillis=0 (no time cap) | clockAdvanceMs=1000 (skip timing loops) | " +
                "nativeBackend=unidbg + nativeMethods=Lcls;->name (native decryptor) | sweepClasses=com.app.Loader " +
                "(inject a known loader) | captureDecrypt=true + captureDecryptMethods=... (custom decrypt).",
        )
        return out
    }
}
