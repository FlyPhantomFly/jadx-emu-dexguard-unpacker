# Changes

Implements the full set of recommendations. **Important caveat:** this was written against the jadx-emu `0.1.0-beta.4` API as inferred from the existing extension source — I could not compile it here (Maven Central / Google's Maven are not reachable in my build sandbox, so `jadx-emu`, `jadx-core` and the Kotlin plugin can't be resolved). Treat it as a reviewed patch set, not a verified build. Confidence is called out per item.

New hooks use defensive signature strings: if a signature doesn't match how jadx-emu actually dispatches a call, the hook simply never fires rather than breaking anything.

## Solid (self-contained; unit-tested where logic allows)

- **`AospMath.kt` (new)** — all opaque-constant math extracted into one pure, dependency-free object and corrected against AOSP. Fixes real bugs in the old stubs:
  - `getDefaultSize` treated `AT_MOST` as `UNSPECIFIED` (matched `0xC0000000` instead of `0x80000000`), returning `size` instead of the spec size.
  - `resolveOpacity` ignored `UNKNOWN(0)`, checked `OPAQUE` before `TRANSPARENT`, and fell back to `TRANSLUCENT` instead of `OPAQUE`.
  - `getModifierMetaStateMask` returned `0x33077`; correct is `0x770FF`.
  - `normalizeMetaState` was a no-op mask (`and 0x33077.inv().inv()`); now the real SHIFT/ALT/CTRL/META-folding algorithm masked with `META_ALL_MASK` (`0x7770FF`).
  - ExpandableListView packed positions used `-1L` as the null sentinel; AOSP uses `0xFFFFFFFF`. Group bits are now masked to 31 bits.
  - `getAbsoluteGravity` used `xor` + `if/if`, which could re-trigger the END branch after a START rewrite; now `and-not` + `if/else-if` per AOSP.
  - `complexToFraction` now returns `0` for non-fraction units.
  - `keyCodeFromString` now maps symbolic names (`KEYCODE_A` → 29, …) via a table, falling back to integer parse then `0`, like AOSP.
- **`VirtualClock` (in `AospMath.kt`)** — monotonic clock; `SystemClock`/`System.currentTimeMillis`/ `nanoTime` advance so polling loops terminate instead of burning the step budget. `SystemClock.sleep` advances the clock.
- **`DexBytes.kt` (new)** — version-tolerant DEX detection, extraction of `classesN.dex` from zip/jar/apk payloads, DEX header repair (file_size + SHA-1 signature + Adler-32 checksum) so dumped files pass baksmali/dex2jar validation, and `classDefCount` for logging.
- **`DexStreams`** — now fed the composite source (base + recovered dexes) instead of just the base dex, so stream shims resolve classes from hidden dexes too. (Wiring change in `DexGuardExtension`.)
- **Serialized runs + cancel** — `Unpacker` now runs on a single-thread executor with a cancellable future and a "cancel" action, instead of spawning a daemon thread per click (they shared the hook registry and dump state).
- **DEX dump quality** — dumped files are header-repaired and logged with class-def counts; a `report.json` is written per run.
- **`Repackager.kt` (new)** — rebuilds the input APK with recovered dexes appended as `classesN.dex`, stripping the old v1 signature. Pure `java.util.zip`; output is unsigned (re-sign with apksigner).
- **`CandidateScanner.kt` (new)** — ranks loader-like classes over jadx-core's public API. Uses `JadxDecompiler.getClasses()` / `JavaClass.getCode()`; reasonably confident on that API.
- **`RunReport.kt` (new)** — dependency-free JSON report.
- **Tests + CI** — `AospMathTest.kt` locks in the corrected AOSP values and DEX repair; `build.gradle.kts` gains a JUnit 5 test setup; `.github/workflows/build.yml` runs `test` + `jar`.
- **README** — rewritten; fixed the "hidded" typo; documented the new actions.

## Best-effort against the inferred jadx-emu API (review the NEEDS-VERIFICATION notes)

- **Wider capture (`HiddenDex`)** — in addition to the existing reflective `Constructor.newInstance` path: direct `<init>` hooks for `InMemoryDexClassLoader` (ByteBuffer / ByteBuffer[] variants) and file-path loaders (`DexClassLoader` / `PathClassLoader` / `BaseDexClassLoader` / `DexFile.loadDex`), resolving paths through the virtual FS then the APK. Capture now also understands zip-wrapped dex payloads. Depends on how jadx-emu dispatches `<init>`; wrong signatures are inert.
- **More entry points (`Unpacker`)** — "unpack from this class" now also runs `attachBaseContext`, `onCreate`, and ContentProvider `attachInfo`; a new "unpack from this method" action runs a selected method (method-id discovery from the action target is best-effort, with a fallback to class entry points).
- **RASP-faithful environment** — `DeviceProfile.kt` (configurable device/SDK/installer); `AppInfo` now reads min-SDK, versionName/Code and the real signing certificate from the APK; `HostShims` adds no-debugger, Build.* (reflective reads), ANDROID_ID; `PackageStubs.kt` (new) serves PackageManager/PackageInfo/Signature/SigningInfo/installer. Direct `sget` of `Build.*`/`SDK_INT` still goes through jadx-emu's own framework model, not these hooks.
- **`VirtualFs.kt` (new)** — captures runtime file writes (FileOutputStream / `Context.openFileOutput`) to back path-based loaders. Relies on those being modeled as DvmObjects; if not, the in-memory loader path still covers the common case.

## Seam only (documented, not functional without upstream support)

- **`NativeBridge.kt` (new)** — record/replay for JNI decryption: a `native-replay.properties` file maps `Lcls;->name` symbols to results, returned in place of a *reflective* invoke of that symbol (native decryptors being the usual case). Executing the real `.so` in-process (unidbg) needs an engine-level native-call hook in jadx-emu. Superseded: the engine's `NativeBridge` seam was found and wired later (see CHANGES-FROM-ORIGINAL.md sections 10 and 11); `attachUnidbgSeam()` is an unused leftover.

## Not done

- A fully automatic `addPass` scanner was left as an on-demand action instead, to preserve the extension's intentional on-demand UX. The scanner logic is ready to move into a pass if desired.

---

# Follow-up additions (telemetry, crypto capture, config, fixpoint)

All of the below are new since the first patch set. The pure pieces are unit-tested; the hook-based pieces carry the same "inert if the signature doesn't match" property and `NEEDS-VERIFICATION` notes.

## Solid / pure (unit-tested)

- **`Telemetry.kt` (new)** — thread-safe, opt-in (`telemetry=true`) run telemetry written to `telemetry.json`: stub fire counts, UNKNOWN-return counts (un-foldable constants), unresolved class/method set, and a bounded event log. No-op on the hot path when disabled. This is the headline fix for the "everything is unmeasured" gap — it turns the best-effort hooks into observable facts.
- **`ExtensionConfig.kt` (new)** — optional `dexguard-unpacker.properties`: telemetry, emulation limits, crypto/fixpoint toggles, and fake-device overrides, all with defaults. No source edits needed to tune a target.
- **`CryptoCapture.runCipher` (new, tested)** — pure host-side AES round-trip (CBC/ECB/GCM); three tests cover CBC, ECB, and the graceful-null-on-bad-input path.
- **`DexBytes` CDEX/container awareness (tested)** — recognises CompactDex (`cdex`) and skips it with a telemetry note instead of emitting a broken file; flags dex-container (v41+) payloads that may need a newer parser; adds `kind()`/`dexVersion()`. Covered by `ExtensionsTest`.
- **Expanded opaque-constant coverage** — `Integer`/`Long` bit ops (`bitCount`, `highestOneBit`, `numberOf{Leading,Trailing}Zeros`, `reverse(Bytes)`, `rotate{Left,Right}`, `signum`, `lowestOneBit`) and `Character` (`digit`, `getNumericValue`, `to{Lower,Upper}Case`, `isDigit`, `isLetter`). These are only consulted where the engine doesn't already intrinsify them; results match the JDK.

## Best-effort against the inferred API (NEEDS-VERIFICATION)

- **`CryptoCapture.kt` (new)** — captures symmetric decryption by reproducing `Cipher.doFinal` host-side (from tracked `SecretKeySpec`/`IvParameterSpec`/`getInstance`/`init`) and, when the plaintext is a DEX/zip, feeds it straight into the loader via `HiddenDex.feedDecrypted`. Replaces the call only when it can reproduce it; otherwise it leaves the emulator's own handling untouched. This is the pragmatic path that gets the dex out even when loader emulation is incomplete. Single-shot `doFinal` only (streaming `update()` is left to the loader hooks).
- **Multi-stage fixpoint (`Unpacker` + `HiddenDex.invokeHidden`)** — after the seed class, lifecycle subclasses (Application/ContentProvider/…) revealed by earlier stages are driven on the composite VM until no new dex appears or `fixpointMaxRounds` is hit. Bounded by a visited set; only lifecycle classes are driven, so it never blindly runs every hidden `<clinit>`.

## Deliberately NOT done (need a real build or upstream support)

- IR/graph-based candidate detection (current scanner is text-based), string-decryption dump as a first-class output, and the real unidbg native seam. These are the "bigger bets" — each wants a compile against real jadx-emu or an upstream API change first.

---

# Scanner scoring fix + one-click auto-sweep

- **CandidateScanner scoring bug fixed.** The old scoring multiplied each keyword by its raw occurrence count, so a weak signal (the `.dex` substring) in a giant data class produced scores in the millions and buried the real loaders. Now: each signal contributes `weight * min(count, 6)` (size can't run away), strong structural signals are weighted far higher (InMemoryDexClassLoader/defineClass/DexFile/ Cipher/reflective-ctor/ByteBuffer/ZipFile/assets), and only real `classesN.dex` filenames count as a dex reference. Classes like the Cipher decryptor now rank at the top where they belong.
- **New action: "DexGuard: auto-sweep (scan + unpack top candidates)."** One click runs the scanner and then runs entry-point emulation (+ the multi-stage fixpoint) on the top-N ranked classes in a single run, writing `telemetry.json` once. `sweepTopN` (default 12) is configurable. This avoids clicking each candidate by hand.
- Deferred: headless CLI (`jadx app.apk` with no GUI) via an `ExtensionMode.AUTO` pass — pending verification of the jadx-emu `addPass` interface.

---

# Authoritative counting, per-candidate budget

- **Counting fixed (was undercounting).** The in-memory load list skipped a dex the engine had already seen this session (dedup), so `report.json`/log counts could read lower than the files on disk. All counting, `report.json`, and "add to project" now derive from the output dir on disk, so they always match `ls` - including dexes recovered via the fixpoint or crypto-feed paths.
- **Per-candidate sweep budget (`sweepBudgetMs`, default 0 = unlimited).** Caps the total wall-clock time spent on one sweep candidate *without* cutting a decrypt mid-run (it only stops launching more invokes/fixpoint rounds for that candidate) - smarter and safer than the flat per-invoke `maxMillis` cap, which could starve a slow loader. Adds per-candidate timing logs (`-> N new dex, T ms`).

---

# Return-value hooks + inline scheduler + loader/v41 coverage (engine 0.1.0-beta.6, extension v1.0.7–v1.0.10)

First changeset that modifies the **engine fork** as well as the extension. Built and unit-tested against a local `jadx-emu` beta.6 (same "reviewed patch set" caveat as above for the hook-signature pieces). Validated end-to-end on a real DexGuard target (FaceTec: 4 hidden dex / 421 classDefs, via the loader path — see below).

## Engine (jadx-emu 0.1.0-beta.5 -> beta.6)

- **Return-value hooks (`Hooks.kt`, `Interpreter.kt`)** — new `ReturnInterceptor { onReturn(call, result) }` + `HookRegistry.addReturn`/`returnInterceptors`; `Interpreter.invoke` fires them (`applyReturnHooks`) on the reflection, native-bridge, and main dex/host result. Backward-compatible: no-op when unused, and the `Vm` constructor is unchanged, so the extension's `EngineCompat` is unaffected. The only engine change; a general primitive (observe/rewrite any call's real return), not DexGuard-specific. Kept minimal on purpose — upstreamable as a PR to `nitanmarcel/jadx-emu`.

## Solid / pure (unit-tested)

- **Inline scheduler (`SyncScheduler.kt`, new; item 1)** — intercepts Thread / Executor / Handler / Future dispatch and runs the submitted body synchronously on the composite VM (`hidden::invokeValue`), on the same HookRegistry, in the caller's run. Extension-side only; deterministic (no real threads). Fixes the silent "loader decrypts off the main thread -> UNKNOWN no-op -> nothing recovered" miss. `sched.run`/`sched.call` counters.
- **Prescriptive diagnosis (`Diagnosis.kt`, new; `Telemetry` accessors; item 8)** — on a run (esp. zero recovery) emits an actionable cause from existing counters (empty source, cdex skip, cipher-ran-but-not-dex, no off-thread dispatch, top unresolved) + a `diagnosis.txt`.
- **v41 dex container (`DexBytes.kt`; item 4, partial)** — a v41 (Android-15) container is trimmed to `container_size` and passed whole to the reader instead of truncated to the first dex's `file_size` (which dropped dex2+). `trimToContainer` + `OFF_CONTAINER_SIZE`.
- **CryptoCaptureTest rewritten** — exercises the new return-hook flow + a streamed-decrypt (`update()`+`doFinal`) case.

## Best-effort against the engine API (NEEDS-VERIFICATION where noted)

- **Return-hook capture rewrite (`CryptoCapture.kt`, `DecryptCapture.kt`; item 2)** — both capturers now OBSERVE the real return instead of reproducing/re-invoking:
  - CryptoCapture reads the genuine `doFinal` plaintext; `update()` return-hooks accumulate streamed chunks and join them with the final block (streaming now captured whole — previously left to the loader hooks). No host-side reproduction or transformation guessing in the live path; key/iv/init hooks kept only for the `crypto.json` metadata. `runCipher` retained as the tested pure helper.
  - DecryptCapture is a single return-hook: no re-invoke, no re-entry guard, no `source`/`invoke` ctor params; InputStream *arguments* are no longer consumed (a returned stream is still re-wrapped for the app).
- **Wider loader coverage (`HiddenDex.kt`; item 7)** — `DelegateLastClassLoader` (+ its file-path ctors), the single-`ByteBuffer`+searchPath `InMemoryDexClassLoader` ctor, `BaseDexClassLoader.addDexPath`, and in-memory `DexFile` (direct `new DexFile(buf)`, reflective `DexFile(buf/[])`, `DexFile.loadClass`). The engine fires ctor hooks before host construction, so the direct-ctor path is reached (resolving the old NEEDS-VERIFICATION concern) — only exact signatures remain a risk, inert if wrong.

## Deferred (need a known-answer test — tracked to the end-to-end fixture, item 9)

- **cdex -> standard-dex conversion** and **v41 -> standalone single-dex split**: both need offset rewriting, where a subtle error yields a parseable-but-corrupt dex the validation guard can't catch. KAT-first.
- **Deterministic seeded SHA1PRNG (item 5)**: `java.util.Random(seed)` already folds (only the no-arg ctor is denied); the remaining gap is the pre-N Crypto-provider `SHA1PRNG(seed)` KDF, provider-specific and must be pinned with a device vector.

## Real-target validation (DexGuard)

- FaceTec, Vodafone, PayU all recover via the **loader path** (`dex.loaded`, HiddenDex / item 7); none decrypts its hidden dex through `javax.crypto`, so CryptoCapture (item 2) stays correctly dormant — confirmed by `counters` holding only `dex.loaded` and no `crypto.json`. CryptoCapture remains a fallback for a JCA-based protector, proven by unit tests and (pending) the item-9 fixture. The FaceTec loader stages use custom XOR string decryptors — `captureDecrypt` + `captureDecryptMethods` is the way to exercise the DecryptCapture half of item 2 on a real target.

## Build / workflow

- Two repos now change together: the engine fork (beta.6) and the extension (v1.0.10). The engine plugin jar jadx loads at runtime MUST be beta.6 (else `addReturn` -> `NoSuchMethodError`). `scripts/deploy-engine.sh` builds + installs it and publishes beta.6 to mavenLocal so `scripts/deploy.sh` resolves it; or use the composite workspace build (WORKSPACE.md) to compile against the fork directly.
- `scripts/results.sh` now reports the crypto/sched/decrypt path and a one-line "which path produced the dex" verdict.

---

# Sweep progress heartbeat + decryptor discovery helper (extension v1.0.11)

- **Sweep heartbeat (`Unpacker`)** — a side thread logs `auto-sweep [N/M] still working: <candidate> (Ss, D dex, C decrypt captures so far)` every 20s while a candidate runs >15s, so a slow/quiet candidate (esp. with `captureDecrypt` on, where string decryptors fire per-string) no longer looks hung. Cancelled in a `finally`; no effect on fast candidates. Also an upfront one-line warning when decrypt capture is armed.
- **`scripts/find-decryptors.sh` (new)** — re-derives this app's candidate custom-decrypt signatures from a run's recovered dex (baksmali + xor-int scan, filtered to decryptor shapes, ClassLoader plumbing excluded) and prints a ready-to-paste `captureDecryptMethods` line. DexGuard renames per app, so the sigs are never the same twice — this replaces the hand-run baksmali+awk.

---

# End-to-end pipeline test (extension v1.0.12, test-only)

- **`PipelineTest.kt` (new, 9 tests)** — deterministic, CI-safe end-to-end test of the path a decrypted payload travels once it appears, with no engine and no APK. Builds real DEX-shaped bytes + a real zip and drives the actual extension code: DexBytes detection / repair round-trip (Adler-32 + SHA-1, so dumps pass baksmali/dex2jar) / standard trim / **v41 container kept whole** (item 4) / zip `classesN.dex` extraction; CryptoCapture feeding a real dex via the `doFinal` return-hook; DecryptCapture observing + recording a named method's real return and re-wrapping a returned InputStream. Proves **both** capture halves of item 2 — including the DecryptCapture path FaceTec couldn't exercise. Runtime jar unchanged from 1.0.11.
- **Not covered (by design):** the emulator interpreting a loader that calls InMemoryDexClassLoader/DexFile (item 7) — validated by real-target runs (FaceTec: 4 hidden dex via `dex.loaded`); a follow-up engine-level harness would unit-test it.

---

# Persistent hidden VM (extension v1.0.13, opt-in)

- **`persistHiddenVm` (new config flag, default false)** — keep the hidden-dex VM and its accumulated static/clinit state alive across recovered stages instead of rebuilding per stage. Turns out extension-only (no engine change): `HiddenDex.Composite` is a live overlay source (lookups scan `sources` uncached, so a kept VM sees new dex immediately) and `Vm.invoke` resets the deadline per top-level invoke (`maxMillis` is per-invoke), so a long-lived VM both resolves later stages and gets a fresh time budget. Faster and more faithful (clinit-once) than the rebuild-per-stage default. Default off so it can't silently regress recovery; validate by A/B on a real target (`set-prop.sh persistHiddenVm true`, re-run, expect the same recovered count faster). Files: `ExtensionConfig.kt`, `HiddenDex.kt` (`persistVm` param), `DexGuardExtension.kt`.

---

# Upstream commit ba65619 — "exec: rewrite hooks and stub api" (nitanmarcel/jadx-emu)

What the commit contained, and how each statement is known. **Authoritative totals: `git show --stat ba65619`** (the commit page lists 23 files;
I was given the diff of 8 of them and read the rest in the resulting tree).

## Seen in the diff (8 files, +324 / -176)

| file | + / - | what changed |
|---|---|---|
| `exec/Hooks.kt` | +0 / -91 | **deleted**: `HookCall`, `Interceptor`, `HookRegistry` (before-hooks only; `add/remove/interceptors/clear`) |
| `exec/Vm.kt` | +54 / -25 | constructor now `(source, host, limits, hook, nativeBridge, hooks: Hooks, ctx, statics, androidEnvUnknown, diagnostics)` - the `android: AndroidStubs` parameter is gone; `syntheticCaller` comes from `hooks.env`; **new `invoke(receiver, shortId, args)`** (resolve on the receiver's runtime class, host fallback); `call()` now works outside a run (resets the deadline when nothing is active); an `active` counter makes a nested `invoke` from a hook **share the outer run's deadline**; `hostStaticField` consults `hooks.fieldValue` |
| `exec/Interpreter.kt` | +4 / -7 | the interceptor loop became `vm.hooks.runHooks(vm, ref, recv, args)`; a replaced call returns before reflection / construction / dispatch (same position as before) |
| `exec/HostExec.kt` | +4 / -4 | stubs consulted through `vm.hooks.runStubs(...)` instead of `android.callStatic/callInstance` |
| `exec/EmuWorld.kt` | +7 / -7 | `hooks: Hooks` (defaults to `ctx?.hooks`), `android` removed; `worldVm` passes `world.hooks` |
| `test/TestDex.kt` | +5 / -7 | helpers moved to `Hooks` / `EngineContext(source, limits, host, hooks)` |
| `test/exec/HooksStubsTest.kt` | +112 / -35 | rewritten for the new API |
| `test/exec/StubCallbackTest.kt` | +138 / -0 | **new**, 6 tests: a denied executor recovers nothing; value-only stubs recover nothing; a stub that runs `vm.invoke(args[0], "run()V")` recovers the secret; the same on another world VM (shares statics); **on an unrelated VM the write is lost**; a hook can run emulated code too |

## Read in the resulting tree (the page lists these files as touched; I did not see their diffs)

- **new `api/Hooks.kt` (231 lines)**: `Hooks` (`hook`, `stub(pattern, handler | value)`, `field`, `scope()`, `clear()`, `isFrameworkClass`), `MethodCall(vm, method, receiver, args)` with `replace`,
  `MethodHandler`, `Registration`, `AndroidEnv`, and built-in stubs (Base64, `Log.*`, `getStackTrace`). `AndroidStubs.kt` no longer exists.
- Matching: an exact `Lcls;->name(args)ret` pattern, then a name-only `Lcls;->name` pattern (all overloads); **newest registration first; the first `replace` stops the chain**.
- A handler that throws is reported as `Diagnostic.Kind.HANDLER_FAILED` and, for a hook, the call is replaced with UNKNOWN; `DvmThrowable` (emulated exception) and `VmAbort` propagate.
  A framework-class (`Landroid/`, `Lcom/android/`, `Ldalvik/`, `Llibcore/`) call with no handling stub throws `StubNotImplemented` (reported as `MISSING_STUB`).
- `EngineContext(source, limits, host, hooks)` with a `diagnostics` sink list (`LogSink` by default) and `newVm(limits, hook, hooks, statics, androidEnvUnknown, diagnostics, nativeBridge)`.
- `EmuExtensionContext.hooks` - "hooks and stubs registered by this extension": `ExtensionLoader` gives each extension `emu.world.hooks.scope()` and calls `clear()` on unload.
- `runStubs` and `fieldValue` are `internal`; `Vm.hostExec` and `Vm.hostStaticField` are the public routes.

## Consequences for an extension (why 1.x stops compiling)

`HookRegistry`/`HookCall`/`Interceptor`/`AndroidStubs`/`StubHandler` no longer exist (15 of our files referenced them). Handlers run newest-first and stop at the first `replace` (before: all ran
in registration order and the last `replace` won). There is no after/return hook. A separately built VM does not share statics with the one that made the call. All handled in 2.0.0 below.

---

# 2.0.0 — port to jadx-emu's rewritten Hooks API (engine 0.1.0-beta.6-fp1)

Upstream commit ba65619 ("exec: rewrite hooks and stub api") deleted `HookRegistry`/`HookCall`/`Interceptor`/`AndroidStubs` for a
single public `Hooks` class and gave handlers the running `Vm`. The extension was ported; **1.x stays frozen at v1.0.13 on engine 0.1.0-beta.6**.
Verified by compiling with Kotlin 2.3.10 against jadx 1.5.6 and running with JUnit 5, then **confirmed under the real Gradle build on the maintainer's machine (2026-10-15, `run.log`)**: engine `clean test publishToMavenLocal shadowJar` and extension `clean test jar` both BUILD SUCCESSFUL with their tests, plugin installed (`0.1.0-beta.6-fp1`), and `scripts/smoke.sh` printed `SMOKE: PASS` (`sched.run=3 crypto.fedDex=3 dex.loaded=1 engine.HOST_DENIED=2`, identical to my sandbox run). **FaceTec has now been run on 2.0.0: PASS (4 dex / 421 classDefs) - see Best-effort below for one open performance item.**

## Engine (separate overlay `jadx-emu-hooks2-upgrades`, also a PR patch for nitanmarcel/jadx-emu)

- **`Hooks.after(pattern) { result -> ... }`** — the old return-value seam on the new API: `ReturnHandler`, an `afters` registry,
  `runAfters`, and three call sites in `Interpreter.invoke` (reflection, native bridge, dex/host result). Not fired for calls a
  hook replaced, calls that threw, or host-object construction (the `UninitHost` path); **a stub's result is seen** (and can be rewritten).
  Newest first, each seeing the previous result; a failing handler is `HANDLER_FAILED` and keeps the result. Applied by an anchored
  all-or-nothing patcher. Engine suite: upstream's 68 + 8 new = **76/76**; with dispatch disabled 7 of 8 fail (the eighth asserts a negative).
  *Correction:* an earlier draft of the KDoc and PR text said stub-handled calls were not seen; a test showed they are, and the text was fixed.
- Version labelled **0.1.0-beta.6-fp1** because upstream also calls its build 0.1.0-beta.6 and publishing it would overwrite the beta.6 the 1.x line needs.

## Solid (unit + real-interpreter tests; each guarantee mutation-tested)

- **`ExtHooks`** — upstream runs a descriptor's handlers newest-first and stops at the first `replace`; this extension was written for
  all-run-in-order, last-replace-wins (`Method.invoke`, `Field.get*`, `Constructor.newInstance` each carry a guarded observer and a
  guarded replacer). `ExtHooks` registers ONE engine handler per descriptor that runs our chain, so every hook keeps its semantics
  regardless of engine ordering. A throwing handler is counted (`hook.failed`) and skipped; `DvmThrowable`/`VmAbort` still propagate.
  *Correction:* an earlier note here said only 2 descriptors overlap; that audit counted string literals and missed the shared-constant ones.
- **`SyncScheduler` on the dispatching VM** — runs the body on `call.vm`, sharing its statics and time limit; falls back to the hidden-dex
  composite VM only for classes the dispatching VM cannot see. (`HiddenDex.vm()` gives each rebuilt VM empty statics and `load()` drops the
  VM mid-run, so the old path could lose the body's writes - upstream's `StubCallbackTest` names that failure.) End to end: an executor
  task and a `new Thread(task).start()` both recover the decrypted string; without the scheduler nothing is recovered.
- **CryptoCapture proven on a real cipher** — an emulated AES-CBC `doFinal` through the interpreter feeds the decrypted dex exactly once and
  returns the plaintext unchanged. (Previously only unit-tested; FaceTec/Vodafone/PayU never use javax.crypto.)
- **DecryptCapture on an emulated method** records the real return (`inllm`) and the app still receives it.
- **Engine diagnostics** — `EngineDiagnostics` feeds the engine's `Diagnostic`s into telemetry; a zero-recovery `diagnosis.txt` now names the
  host calls the engine refused (e.g. `Executors.newSingleThreadExecutor`), framework calls with no stub, failed handlers and aborts.
  **Closes the item-8 follow-up.** Off when telemetry is off.
- **`HostShims` reflective paths** verified: `Method.invoke` of a framework method is answered by its stub; reflective `Build.MODEL` reads the device profile.
- `EngineCompat` is now a thin wrapper over `EngineContext.newVm` - still the single place a `Vm` is built.
- **Real-plugin smoke test** (`scripts/smoke.sh`, `fixtures/`) - the engine plugin jar and the extension jar were built and run under the actual jadx 1.5.6 CLI on a tiny
  synthetic app (an Executor task AES-decrypting a real dex): `jadx-emu 0.1.0-beta.6-fp1 loaded ... 1 active`, the executor refused by host policy, the task run by the scheduler,
  and the recovered dex **byte-identical** to the payload (sha256 equal). Run as a 2x2 matrix: 1.x extension on the 1.x engine and 2.x on the 2.x engine both recover the same
  bytes; the crossed pairings fail at init with `NoSuchMethodError` (`EmuExtensionContext.getHooks()` / `EngineContext.getAndroid()`), `0 active`.
  `sched.run` differs between the lines (3 vs 2) with identical recovery; I did not trace the extra run (likely the engine's clinit snapshot running the initializer with our hooks active).

## Best-effort (NEEDS-VERIFICATION)

- **FaceTec acceptance (2026-10-15): PASS - 4 dex, 421 classDefs, the same four files (same content-hash names and sizes) as v1.0.x.** **Known, unexplained: the sweep took about 12.6 minutes** (candidate 1: 122 s, candidate 2: 67 s, candidate 3 `com.facetec.sdk.eu`: 565 s with 0 new dex) against roughly 4 minutes on the earlier 1.x run (judged from file timestamps; not logged). Candidate 3 shows two aborted `eu.<clinit>` runs, consistent with each reaching `maxMillis=300000`. Benchmarked and ruled out: the engine rewrite itself (old vs new engine, same speed on a 500k-invoke loop), `Hooks.after` (within noise), and the diagnostics sink (~0.5 us per event, ~3 s at this run's 5.8M events). To localize: re-run with `telemetry=false`, and run the same APK on the 1.x line (branch `1.x`, engine tag `beta6-baseline`). Vodafone (`EXPECT=2`) and PayU (`EXPECT=2`, `clockAdvanceMs=1000`) still to re-run.
  **Acceptance:** FaceTec `EXPECT=4` (4 dex, 421 classDefs).
- `HostShims`' reflective `Field.get` now reads `vm.hooks.env` only: the extension registers no static-field stubs, so this matches the old
  `stubs.field`; another extension's `Hooks.field(...)` would not be seen on that path.
- Stubs are now a stack (newest first, fall through when a handler does not replace) instead of one slot per method; our duplicate
  registrations (`getPackageName`) resolve to the newest, as before.

## Not changed

Recovery logic, loader hooks, config keys, output files, `native-unidbg` (uses only `NativeBridge`, `NotHandled`, `EmuExtensionContext`, none changed upstream).
Still deferred: #3 native JNI bridge, #5 SHA1PRNG, #4 cdex converter/v41 split.


---

# Summary - everything added in this work (versions are extension versions)

| ver | what | where |
|---|---|---|
| 1.0.7 | **inline scheduler**: Thread / Executor / Handler / `View.post` bodies run synchronously on the emulator instead of being denied no-ops | `SyncScheduler.kt` |
| 1.0.8 | **prescriptive diagnosis** on a zero-recovery run + `diagnosis.txt` | `Diagnosis.kt`, `Telemetry.kt`, `Unpacker.kt` |
| 1.0.9 | **wider loader coverage**: `DelegateLastClassLoader`, in-memory `DexFile` (direct + reflective + `loadClass`), `BaseDexClassLoader.addDexPath`, single-`ByteBuffer` `InMemoryDexClassLoader` ctor | `HiddenDex.kt` |
| 1.0.10 | **return-value hooks** (engine `0.1.0-beta.6`, `addReturn`); `CryptoCapture` / `DecryptCapture` now observe the real result (no reproduce, no re-invoke; streamed ciphers captured whole); **v41 dex container** kept whole | engine `Hooks.kt`/`Interpreter.kt`; `CryptoCapture.kt`, `DecryptCapture.kt`, `DexBytes.kt` |
| 1.0.11 | **progress heartbeat** (`auto-sweep [N/M] still working ...` every 20 s) + `scripts/find-decryptors.sh` | `Unpacker.kt` |
| 1.0.12 | **end-to-end pipeline test** (DexBytes repair/trim/container/zip + both capture paths) | `PipelineTest.kt` |
| 1.0.13 | **`persistHiddenVm`** (opt-in): keep the hidden VM and its statics across stages | `ExtensionConfig.kt`, `HiddenDex.kt` |
| 2.0.0 | **port to upstream `ba65619`** (engine `0.1.0-beta.6-fp1`): `ExtHooks`, scheduler on the dispatching VM, engine diagnostics, `EngineCompat` over `EngineContext.newVm`, `HostShims` reflective paths; engine `Hooks.after`; real-interpreter end-to-end tests; `smoke.sh` | see the 2.0.0 section |
| scripts | `results.sh` (path verdict), `deploy-engine.sh`, `find-decryptors.sh`, `smoke.sh` + `fixtures/` | `scripts/` |

What the engine itself reported on that FaceTec run (first time it was visible): ~1.9M `Integer.valueOf` / `Character.charValue` host calls with unknown input or that threw (about the combined size of the four recovered dex, so likely a per-character decrypt loop; not traced), 673 denied `Runtime.getRuntime()` calls, 17 aborted calls (7 in `cdSS.loadClass`), and two `InMemoryDexClassLoader.<init>(ByteBuffer, ClassLoader)` events - the dex handoff. None changed the recovered dex.

Still deferred: **#3** native JNI-callback bridge (needs a native dex-decryptor target; PairIP is a native VM, a different problem), **#5** `SHA1PRNG` (needs a device vector), **#4** cdex converter / v41 split (needs a cdex + dex pair).
How each was verified is in its own section above; 2.0.0's real-target acceptance run is FaceTec `EXPECT=4`.
