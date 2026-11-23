# Changes

## 2.2.1

Maintenance; no behavior change to the recovery path (FaceTec 10.0.30 gate: 4 dex, unchanged).

- `autoSweep()` (headless `autoUnpack=true`) runs on the same worker thread the GUI actions use and waits for it, so the decompile pass can no longer race a GUI click on the shared sweep state.
- `requiredEmuVersion` corrected to `0.2.0` (the extension calls `Hooks.after`, a 0.2.0 API).
- Diagnosis and docs no longer advertise a working `nativeBackend=unidbg` / `nativeMethods=...` path: no `NativeBackendProvider` adapter has shipped since 2.1.0, so the native tier is scan-and-report only.
- Fingerprint documentation constants `$b`/`$d` corrected to `0xAA55AA55` (the stored instruction bytes decode to it).
- `scripts/deploy-engine.sh` now exits 1 when the installed jadx-emu jar count is not exactly one.

# Changes

## 2.2.0

The extension now detects DexGuard's centralized native "return to Java" routine before it runs.
`NativeRoutineScan` scans the APK's `lib/arm64-v8a/*.so` for the six `movz`+`movk` AArch64
constant pairs (0x55AA55AA / 0xAA55AA55 / 0x2A55AA55 into x10/x12/x13) that fingerprint the routine -
the same byte patterns as a YARA rule proven over 27k+ DexGuard APKs. Every match is logged, listed in
`report.json` under `returnToJava` (pattern, file offset, mapped address via the ELF program headers,
register and constant), and the presence of the native tier feeds `diagnosis.txt` with the advice to
run the library through the unidbg backend and hook the reported address.

## 2.1.0

Built against `io.github.flyphantomfly:jadx-emu` 0.2.0 and released under GPLv2. The native-unidbg module and the old CI workflow were dropped, and both are still in the v2.0.0 tag.

The docs and scripts were rewritten after the release. `deploy-engine.sh` now matches the 0.2.0 engine, the smoke-test fixtures are back, `deploy-unidbg.sh` and `WORKSPACE.md` are gone, and the notes about separate 1.x and 2.x engines were removed.

## 2.0.0

Ported to jadx-emu's rewritten hook API (upstream commit ba65619). Recovery, config keys and outputs are the same as 1.0.13. What changed is how the extension talks to the engine: handlers get the running VM, the inline scheduler runs on the VM that dispatched the call, and the engine's diagnostics feed the telemetry. It needs `Hooks.after`, a hook that sees what a call really returned, which is in the engine from 0.2.0.

Checked with the unit tests, 11 end-to-end tests that run real emulated Dalvik, `scripts/smoke.sh` through the real jadx CLI, and FaceTec (4 dex, 421 classDefs, the same four files as 1.0.x). One known problem: the FaceTec sweep took about 12 minutes against about 4 on 1.0.x. Benchmarks ruled out the engine rewrite, `Hooks.after` and the diagnostics sink, and the cause hasn't been found.

## 1.0.13 and earlier

- 1.0.13: `persistHiddenVm` (opt-in) keeps the hidden-dex VM and its static state between stages.
- 1.0.12: an end-to-end pipeline test.
- 1.0.11: a progress line during long sweeps, and `scripts/find-decryptors.sh`.
- 1.0.10: return-value hooks (`addReturn` on engine beta.6). Crypto and decrypt capture now read the real result instead of reproducing it.
- 1.0.9: wider loader coverage: `DelegateLastClassLoader`, in-memory `DexFile`, `addDexPath`, and `InMemoryDexClassLoader` with a single `ByteBuffer`.
- 1.0.8: a readable diagnosis when a run recovers nothing, written to `diagnosis.txt`.
- 1.0.7: an inline scheduler. Thread, Executor and Handler bodies run synchronously in the emulator instead of being denied.
- 1.0.0 to 1.0.6: the first rework of nitanmarcel's original unpacker. AOSP-correct math stubs, a telemetry and config file, crypto capture, multi-stage recovery, a better candidate scanner, the one-click auto-sweep (1.0.2 to 1.0.4) and headless mode through `autoUnpack` (1.0.6).

The 1.x line is frozen at v1.0.13. It needs the older engine and isn't built from this branch.
