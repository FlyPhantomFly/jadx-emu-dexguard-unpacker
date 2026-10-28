# Usage & options

The DexGuard unpacker recovers the DEX files a DexGuard-protected app decrypts at runtime, by emulating the app's own loader inside jadx-emu and capturing the plaintext DEX at the moment it reaches a class loader. No device, no running the app. See `README.md` for install and `TESTING.md` for the clean-run protocol.

## Running

**GUI (jadx-gui):** open the APK (File > Open; for an XAPK/App Bundle, extract it and open the base APK that contains `classes.dex`). Right-click any class > **Jadx Emu**:

| action | what it does |
|---|---|
| **DexGuard: auto-sweep** | the one-click path - ranks loader candidates and runs the top ones. Start here. |
| **DexGuard: scan for loader classes** | just the ranking (raw-DEX scan), printed to the log - no emulation. |
| **DexGuard: unpack from this class** | run the selected class's `<clinit>` + common entry points. |
| **DexGuard: unpack from this method** | run the selected method. |
| **DexGuard: repackage APK with recovered dex** | write an *unsigned* `*-unpacked.apk` (re-sign with `apksigner`). |
| **DexGuard: cancel running unpack** | cancel the in-flight run. |

**Headless (no GUI):** set `autoUnpack=true`, then `jadx -d out app.apk` runs the sweep during decompilation and writes the outputs.

On startup the log prints the version, the live config, and which jar is running:
```
dexguard-unpacker v2.0.0 | nativeBackend=none scanMode=dex sweepTopN=12 maxMillis=300000 telemetry=true
dexguard-unpacker: running from /.../extensions/dexguard-unpacker-2.0.0.jar
jadx-emu 0.1.0-beta.6-fp1 loaded: 1 extension(s) found in /.../extensions, 1 active
```

## Options

Edit `<jadx config>/plugins-data/jadx-emu/data/dexguard-unpacker/dexguard-unpacker.properties` (path is logged on startup). All keys are optional. Inline `# comments` after a value are allowed.

### Core recovery
| key | default | what it does / when to change |
|---|---|---|
| `scanMode` | `dex` | `dex` = raw-DEX reference scan (fast, universal). `signature` = emulator-source fallback. |
| `sweepTopN` | `12` | how many ranked candidates the auto-sweep runs. Raise if a loader ranks low and is missed. |
| `sweepBudgetMs` | `0` | per-candidate wall-clock cap; `0` = unlimited (recommended). |
| `fixpoint` | `true` | drive stage-2 lifecycle classes revealed by earlier stages (multi-stage payloads). |
| `fixpointMaxRounds` | `6` | cap on fixpoint rounds. |
| `persistHiddenVm` | `false` | keep the hidden-dex VM and its static/clinit state across recovered stages instead of rebuilding it per stage (faster; later stages see earlier stages' statics). Opt-in; validate with `EXPECT=N scripts/results.sh` that the recovered count is unchanged. |
| `sweepClasses` | (empty) | comma-separated classes to always run (descriptor or dotted), on top of the ranking. |
| `autoUnpack` | `false` | headless: run the sweep automatically during decompilation, so `jadx -d out app.apk` unpacks with no GUI. |

### Emulation limits (raise these if recovery is incomplete)
| key | default | what it does / when to change |
|---|---|---|
| `maxMillis` | `300000` | per-invoke wall-clock cap (ms); `0` = unlimited. A cut-off is logged as `HIT the maxMillis limit`. |
| `maxSteps` | `50000000` | per-method instruction cap. Raise for very heavy loaders (a step cut-off is NOT separately logged - see below). |
| `maxDepth` | `64` | max call depth. |
| `clockAdvanceMs` | `1` | ms the virtual clock jumps per read. **Set `1000` if a loader hits the time limit while spinning on a time check** - it skips anti-analysis wait loops. Leave at `1` otherwise (a high value can trip detect-slow anti-debug checks). |

### Crypto / decrypt capture (fallbacks; DexGuard usually doesn't use these)
| key | default | what it does |
|---|---|---|
| `captureCrypto` | `true` | observe the real `javax.crypto` output (`update()` chunks are joined with the `doFinal` block, so streamed ciphers are captured whole) and feed any decrypted DEX to the loader. Nothing is reproduced or guessed. Writes `crypto.json` if it fires. |
| `feedDecryptedDex` | `true` | when a decrypted plaintext is a DEX, load it automatically. |
| `captureDecrypt` | `false` | capture I/O of custom (non-javax.crypto) decrypt methods named below. |
| `captureDecryptMethods` | (empty) | comma-separated full sigs, e.g. `Lcom/foo/Bar;->a(Ljava/io/InputStream;IS)Ljava/io/InputStream;`. |

### Observability
| key | default | what it does |
|---|---|---|
| `telemetry` | `false` | write `telemetry.json` (counters, stub fires, unresolved, `limit:` events, per-run event log) **and** attach the engine's diagnostics to every VM, so a zero-recovery run names the host calls the engine refused and the framework calls with no stub. Turn on while debugging. |

### Native backend (OFF by default; DexGuard DEX decrypt is not native)
| key | default | what it does |
|---|---|---|
| `nativeBackend` | `none` | `none` = no native bridge (recommended; no bridge is wired, so VMs are built as they are without the native feature). `unidbg` = execute listed native methods via the optional unidbg jar (see `native-unidbg/README.md`). |
| `nativeMethods` | (empty) | **required** for unidbg: comma-separated `Lcls;->name` to execute. Empty = no bridge wired. |
| `nativeAbi` / `nativeLibs` / `nativeSdk` / `nativeVerbose` | arm64-v8a / all / 23 / false | unidbg tuning. |

### Fake-device overrides (optional; seeded from the manifest otherwise)
`device.manufacturer`, `device.brand`, `device.model`, `device.device`, `device.product`, `device.hardware`, `device.fingerprint`, `device.tags`, `device.type`, `device.androidId`, `device.installer`, `device.sdkInt`, `device.minSdk`, `device.targetSdk`, `device.debuggable`.

## Outputs (per run, under `<dataDir>/<inputHash>/`)

- `hidden-<sha>.dex` - the recovered DEX files (header-repaired, baksmali-valid).
- `report.json` - package, recovered count, per-dex size/classDefs/sha256, the emulated env.
- `telemetry.json` - only with `telemetry=true`.
- `crypto/crypto.json` + `plain-*.bin` - only if `javax.crypto` capture fires.
- `decrypt/decrypt-capture.json` - only with `captureDecrypt` when a listed method runs (input summary + real return).
- `diagnosis.txt` - only when the run recovered nothing: likely cause and next steps.

`telemetry.json` counters worth knowing: `dex.loaded`; `crypto.captured` / `crypto.fedDex`; `decrypt.captured`; `sched.run` / `sched.call` (off-thread/executor/handler bodies the extension ran inline); `hook.failed` (one of this extension's handlers threw and was skipped); `engine.<KIND>` and `engine.<KIND> @ Lcls;->m()V` (what the engine refused - `HOST_DENIED`, `MISSING_STUB`, `HANDLER_FAILED`, `ABORTED`, ...). `scripts/results.sh` prints the crypto/sched/decrypt counters and a one-line verdict of which path produced the dex.

## Troubleshooting

| symptom | cause | fix |
|---|---|---|
| fewer dex than expected, `telemetry` shows `limit: ... hit maxMillis` | a loader was cut off by the time cap | **`clockAdvanceMs=1000`** first (time-gated loop), else `maxMillis=0` |
| fewer dex, NO `limit:` event | may be a silent `maxSteps` cut-off (not auto-detectable) or an `unknown branch` bail | raise `maxSteps` (e.g. `500000000`); if still short, inspect the class |
| `no candidates` / `emulator source is EMPTY (0 methods)` | input is an XAPK / App Bundle / split APK | extract it, open the **base** APK (the one with `classes.dex`) directly |
| startup shows `native bridge ACTIVE` unexpectedly | a `nativeBackend`/`nativeMethods` or `native-replay.properties` is set, or the unidbg jar is deployed | `scripts/baseline.sh <ver>` to reset to `nativeBackend=none`, one core jar |
| results feel confounded between runs | stale jar / second checkout | `scripts/audit.sh` (flags extra jars, duplicate checkouts), then `scripts/baseline.sh <ver>` |
| no `crypto.json` ever | DexGuard decrypts via the class loader, not `javax.crypto` | expected - the loader hooks already captured it; crypto capture is only a fallback |
| the console is quiet for minutes during a sweep | a slow candidate (typical with `captureDecrypt`, whose string decryptors fire per string) | not hung: while a candidate runs >15 s the log prints `auto-sweep [N/M] still working: <class> (Ss, D dex, C decrypt captures so far)` every 20 s; `top` shows java busy. Wait for `telemetry written` |
| `diagnosis:` says the engine refused host calls (`engine.HOST_DENIED`) | the loader needs a call the host policy denies (e.g. a thread/executor) | the inline scheduler covers Thread/Executor/Handler dispatch; otherwise allow it in the jadx-emu host policy or register a `Hooks.hook`/`stub` |
| `extension 'dexguard-unpacker' failed to init`, `0 active`, `NoSuchMethodError` | extension and engine are on different lines (2.x needs `0.1.0-beta.6-fp1`, 1.x needs `0.1.0-beta.6`) | `scripts/deploy-engine.sh <matching engine checkout>`, then `scripts/deploy.sh <ver>`; `scripts/smoke.sh` shows it |
| `SMOKE: FAIL` | the chain is broken somewhere between engine, extension and config | read the lines `smoke.sh` prints (startup line, last errors); it restores your properties either way |

## Scripts (`scripts/`)

`baseline.sh <ver>` (clean + reset + deploy core), `clean.sh`, `deploy.sh <ver>`, `deploy-unidbg.sh <ver>`, `audit.sh` (read-only confound check), `set-prop.sh <key> <val|--unset>`, `results.sh [log]` (summarise newest run; `EXPECT=4` for PASS/FAIL), `crypto-vs-native.sh <apk|dir>` (triage decrypt surface), `find-decryptors.sh [run-dir]` (list this app's candidate custom-decrypt sigs for `captureDecryptMethods`), `deploy-engine.sh <engine checkout>` (build + install the matching engine), `smoke.sh` (post-deploy smoke test, no APK). See `TESTING.md`.
