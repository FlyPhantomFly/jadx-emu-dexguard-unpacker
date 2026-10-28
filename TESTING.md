# Clean-run and test protocol

**Rule:** every result is only meaningful together with its startup line and an audit. Past runs were confounded by leftover jars, a second checkout, and stale properties - these scripts make that visible or impossible. Work from ONE checkout.

## Engine prerequisite (only when the jadx-emu engine changed)

The extension and the engine must be on the same **line** - they use different hook APIs and cannot share an install:

| extension | engine (`build.gradle.kts` version) | engine source |
|---|---|---|
| **2.x** (2.0.0+) | `0.1.0-beta.6-fp1` | upstream **ba65619+** (rewritten `Hooks` API) + the `Hooks.after` seam (`jadx-emu-hooks2-upgrades`) |
| 1.x (v1.0.x, frozen at 1.0.13) | `0.1.0-beta.6` | the older `HookRegistry` engine + `addReturn` (git tag `beta6-baseline`) |

`baseline.sh`/`deploy.sh` build only the extension, so after any engine change rebuild + reinstall the engine FIRST. The
version is read from the engine checkout:

```bash
bash scripts/deploy-engine.sh ~/jadx-emu/jadx-emu
```
This runs the engine's tests, `publishToMavenLocal`s it (so the next `deploy.sh` resolves it), installs the fat `-plugin`
jar as the ONLY `jadx-emu*.jar`, and tells you which line it built for. Confirm `jadx-emu <version> loaded ... 1 active` in
the run log. A mismatched pair fails at init, loudly (`extension 'dexguard-unpacker' failed to init`, `0 active`): 2.x on the old engine ->
`NoSuchMethodError: ...EmuExtensionContext.getHooks()`; 1.x on the new engine -> `NoSuchMethodError: ...EngineContext.getAndroid()`.
(Both pairings were run for real; `scripts/smoke.sh` shows the failure.) Skip this step on runs where only the
extension changed.

**Switching lines:** check out the engine for that line, `deploy-engine.sh`, then deploy the matching extension version
(`baseline.sh 2.0.0` or `baseline.sh 1.0.13`) from the matching extension checkout.

**After any deploy, in this order:**
1. `bash scripts/smoke.sh` - 10 seconds, no APK: proves the engine loaded, the extension initialized, the inline scheduler ran an Executor task, CryptoCapture caught the AES decrypt and the recovered dex is byte-identical to the payload. A line mismatch fails here, with a hint.
2. FaceTec: `EXPECT=4 bash scripts/results.sh <log>` (4 dex, 421 classDefs) - the real-target acceptance. The smoke test does not replace it.

## 0. Fresh, known-good state (one command)

```bash
cd ~/jadx-emu/<your-checkout>
bash scripts/baseline.sh 2.0.0
```
Cleans every checkout's build output, deletes ALL `dexguard-unpacker*.jar` (core + unidbg), recovered output and jadx's cache; resets the properties to `telemetry=true` + `nativeBackend=none`; deletes `native-replay.properties`; builds + tests; installs the core jar as the ONLY jar; prints the audit. The audit must end with `audit: CLEAN`.

## 1. Run it (capture the log)

```bash
jadx-gui 2>&1 | tee ~/run-1-baseline.log
```
File > Open the APK (never a saved project) -> right-click a class -> Jadx Emu -> **DexGuard: auto-sweep** -> wait for `telemetry written to ...` -> close jadx. The log must contain:
```
dexguard-unpacker v2.0.0 | nativeBackend=none scanMode=dex sweepTopN=12 maxMillis=300000 telemetry=true
dexguard-unpacker: running from /home/.../extensions/dexguard-unpacker-2.0.0.jar
```
and NO `native bridge ACTIVE` line.

## 2. Read the result

```bash
EXPECT=4 bash scripts/results.sh ~/run-1-baseline.log      # FaceTec demo: 4 dex / 421 classDefs
```
Prints the dex on disk, class counts, telemetry (incl. **LIMIT HITS**), per-candidate timings and the startup line, then `RESULT: PASS|FAIL`.

## 3. If it is not 4 - decision table

| what you see | meaning | do |
|---|---|---|
| `HIT the maxMillis=... limit` / `LIMIT HITS > 0` | cut off by the wall-clock cap (engine swallows it silently) | **try `set-prop.sh clockAdvanceMs 1000` first** (time-gated loop - often the real fix), else `set-prop.sh maxMillis 0`, re-run |
| no HIT, wrong startup line / `native bridge ACTIVE` | wrong jar or backend live | `bash scripts/baseline.sh 2.0.0` |
| no HIT, clean startup, still < 4 | a genuine emulation difference | send me the saved log + `telemetry.json` |

## 4. Prove the mechanism (optional, 2 runs)

Reproduce the cut-off on purpose, then show it is gone:
```bash
bash scripts/set-prop.sh maxMillis 120000     # the old default
# run as in step 1 (log: ~/run-2-limit120.log), then:
bash scripts/results.sh ~/run-2-limit120.log
bash scripts/set-prop.sh maxMillis --unset    # back to the 300000 default
```
Candidate 1's `-> N new dex, X ms` line shows how close to the cap this machine runs.

## 5. The unidbg backend (separate, only when deliberately testing it)

```bash
bash scripts/deploy-unidbg.sh 2.0.0          # jar must be several MB; ~15 KB means unidbg was not bundled
bash scripts/set-prop.sh nativeBackend unidbg
bash scripts/set-prop.sh nativeMethods 'Lcom/foo/Bar;->decrypt'   # REQUIRED, or no bridge is wired
```
Startup then shows `native bridge ACTIVE (unidbg)`. Test a `.so` outside jadx first: `cd native-unidbg && ./gradlew run --args="/path/libfoo.so Lcom/foo/Bar; decrypt ([B)[B 0011223344"`. Return to baseline with `bash scripts/baseline.sh 2.0.0`.

## Script reference

| script | purpose |
|---|---|
| `deploy-engine.sh <engine-path> [ver]` | build the jadx-emu fork, install the `-plugin` jar as the only engine jar, publish to mavenLocal (run first when the engine changed) |
| `baseline.sh <ver>` | clean + reset properties + deploy core only |
| `clean.sh` | remove builds/jars/output/cache, keep properties |
| `deploy.sh <ver>` | build + test, install core jar as the only jar, audit |
| `deploy-unidbg.sh <ver>` | build + install the optional unidbg jar |
| `audit.sh` | read-only: jars, live properties, replay file, duplicate checkouts |
| `set-prop.sh <key> <val\|--unset>` | edit one properties key idempotently |
| `find-decryptors.sh [run-dir]` | list candidate custom-decrypt method sigs from a run's recovered dex (per-app; prints a ready-to-paste captureDecryptMethods line) |
| `smoke.sh` | 10-second post-deploy smoke test through the REAL jadx + plugin + extension on `fixtures/smoke.dex` (no APK): PASS only if the recovered dex is byte-identical to `fixtures/payload.dex`; restores your properties |
| `results.sh [log]` | summarise the newest run; reports the crypto/sched/decrypt path + a one-line "which path produced the dex" verdict (`EXPECT=4` for PASS/FAIL) |
| `crypto-vs-native.sh <apk\|dir>` | pre-run heuristic: is the decryption surface javax.crypto or native? |
