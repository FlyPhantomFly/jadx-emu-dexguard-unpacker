# jadx-emu-dexguard-unpacker

A [jadx-emu](https://github.com/nitanmarcel/jadx-emu) extension that recovers the DEX files a **DexGuard**-protected app decrypts at runtime. It doesn't reverse DexGuard's encryption — it runs the app's own loader inside jadx-emu's Dalvik emulator and captures the plaintext DEX at the moment it's handed to a class loader, overlays the recovered classes, and keeps going for nested stages.

> Fork of [`nitanmarcel/jadx-emu-dexguard-unpacker`](https://github.com/nitanmarcel/jadx-emu-dexguard-unpacker), substantially extended. See [`CHANGES-FROM-ORIGINAL.md`](CHANGES-FROM-ORIGINAL.md) for the full diff. Built on nitanmarcel's [jadx-emu](https://github.com/nitanmarcel/jadx-emu). GPL-2.0.

## How it works

The emulator provides a faithful, non-debuggable, correctly-signed Android environment so DexGuard's RASP/anti-tamper checks pass during emulation. A read-only **DEX-reference scan** (no decompilation) ranks loader candidates; running a candidate's entry points triggers its decrypt-and-load, and hooks on the in-memory / file-path / `DexFile` loaders (and reflective `Constructor.newInstance`) capture the plaintext DEX. A bounded fixpoint drives stage-2 classes revealed by earlier stages. Recovered DEX is header-repaired so baksmali/dex2jar/jadx accept it.

## Install

1. **jadx** 1.5.6+ and a **jadx-emu** engine installed as a jadx plugin. The extension and the engine must be on the same line - they use different hook APIs:

   | extension | engine | notes |
   |---|---|---|
   | **2.x** (this tree) | `0.1.0-beta.6-fp1` | upstream `ba65619` ("rewrite hooks and stub api") + the `Hooks.after` return-value seam (a PR candidate). Build and install it with `scripts/deploy-engine.sh <jadx-emu checkout>`; the engine overlay is `jadx-emu-hooks2-upgrades`. |
   | 1.x (frozen at v1.0.13) | `0.1.0-beta.6` | the older `HookRegistry` engine plus `addReturn`. v1.0.6 and earlier also ran on the published beta.4 / beta.5 (`jadx plugins --install "github:nitanmarcel:jadx-emu"`). |

   A mismatched pair fails at init (`extension 'dexguard-unpacker' failed to init`, `0 active`) with a `NoSuchMethodError`; see [`TESTING.md`](TESTING.md).
2. Build this extension's jar (below) and drop it into jadx-emu's extensions directory — `<jadx config>/plugins-data/jadx-emu/extensions/` (on Linux `~/.config/jadx/plugins-data/jadx-emu/extensions/`):
   ```bash
   cp build/libs/dexguard-unpacker-*.jar ~/.config/jadx/plugins-data/jadx-emu/extensions/
   ```
3. Restart jadx-gui (extensions are discovered at startup via the `META-INF/services/jadx.plugins.emu.api.EmuExtension` entry).

## Build

```bash
./gradlew test     # unit tests (AOSP math, DEX repair, hook-chain semantics, capture paths) + end-to-end tests that run real emulated Dalvik
./gradlew jar      # build/libs/dexguard-unpacker-2.0.0.jar   (VERSION env overrides the version)
```

`jadx-emu` is `compileOnly` and resolves from **mavenLocal** (`scripts/deploy-engine.sh` publishes `0.1.0-beta.6-fp1` there; build it first). `jadx-core` resolves from Maven Central (JDK 21 required). The end-to-end tests also use `jadx-core`, `jadx-dex-input` and `smali` 3.0.9 (the artifacts jadx-emu's own tests use). To build against a local jadx-emu checkout instead, use the composite build in [`WORKSPACE.md`](WORKSPACE.md).

## Usage

Open a DexGuard APK in jadx-gui, then right-click a class in the tree and use the **Jadx Emu** submenu:

- **DexGuard: auto-sweep (scan + unpack top candidates)** — the one-click path: ranks loader candidates and runs the top ones. Recovers the full payload with no manual class selection.
- **DexGuard: scan for loader classes** — just the ranking (raw-DEX reference scan; fast, no decompilation), printed to the log.
- **DexGuard: unpack from this class** — runs the class's `<clinit>` plus common entry points (`attachBaseContext`, `onCreate`, ContentProvider `attachInfo`).
- **DexGuard: unpack from this method** — runs the selected method.
- **DexGuard: repackage APK with recovered dex** — writes an *unsigned* `*-unpacked.apk`; re-sign with `apksigner` before installing.
- **DexGuard: cancel running unpack** — cancels the in-flight run.

Recovered DEX is auto-added to the jadx project; on the CLI the paths are logged.

**Headless (no GUI).** Set `autoUnpack=true` in the config, then run jadx from the command line — the sweep runs automatically during decompilation and writes the same outputs:

```bash
jadx -d out/ app.apk          # add -v to see the extension's own log lines
```

See [`USAGE.md`](USAGE.md) for every config option (defaults, when to change each), the outputs, a troubleshooting table, and the `scripts/` helpers.

## Outputs per run

Under `<dataDir>/<inputHash>/` (the data dir is printed on startup):

- `hidden-<sha>.dex` — recovered DEX (header-repaired).
- `report.json` — recovered-dex summary + the emulated environment. Counts are read from disk, so they always match `ls` (including fixpoint-recovered dexes).
- `telemetry.json` — only with `telemetry=true`: counters (`dex.loaded`, `crypto.*`, `decrypt.captured`, `sched.run`/`sched.call` for off-thread bodies run inline, `hook.failed`, and `engine.<KIND>` for what the engine itself refused - e.g. `engine.HOST_DENIED @ Lcls;->m()V`), which stubs fired, which returned UNKNOWN (un-foldable constants worth a new stub), unresolved classes/methods, and an event log - how to see *why* a run recovered what it did.
- `diagnosis.txt` — written when a run recovers **nothing**: the likely cause and next steps, from the counters above (empty source, time-limit cut-offs, cdex skips, host calls the engine refused, framework calls with no stub, failed handlers, aborts). The same lines are logged as `diagnosis:`.
- `crypto/crypto.json` (+ `crypto/plain-<sha>.bin`) — when `javax.crypto` decryption is seen.
- `decrypt/decrypt-capture.json` (+ dumps) — when `captureDecrypt` observes a listed custom decrypt method.

## Configuration (optional)

Create `dexguard-unpacker.properties` in the **data dir** (`<jadx config>/plugins-data/jadx-emu/data/dexguard-unpacker/`, printed on startup). All keys are optional; defaults shown:

```properties
# observability
telemetry=false

# emulation limits
maxSteps=50000000
maxMillis=300000            # per-invoke wall-clock cap. A hit is logged ("HIT the maxMillis limit") - raise it if a hidden dex goes missing
maxDepth=64
clockAdvanceMs=1               # ms the virtual clock jumps per read; raise (e.g. 1000) to make anti-analysis timing loops exit fast

# recovery
captureCrypto=true            # observe the real Cipher output (update()/doFinal), feed a decrypted DEX to the loader; key/iv recorded for crypto.json
feedDecryptedDex=true         # when a decrypted plaintext is a DEX, load it automatically
fixpoint=true                 # drive stage-2+ lifecycle classes revealed by earlier stages
fixpointMaxRounds=6
persistHiddenVm=false         # keep the hidden-dex VM (and its statics) across recovered stages instead of rebuilding it per stage; opt-in

# sweep
scanMode=dex                  # dex (raw-DEX reference scan) | signature (emulator-source fallback)
sweepTopN=12                  # candidates the auto-sweep tries
sweepBudgetMs=0               # per-candidate wall-clock cap; 0 = unlimited/thorough (never cuts a decrypt mid-run)
sweepClasses=                 # optional comma-separated classes to always run (descriptor or dotted)
autoUnpack=false              # headless: run the sweep automatically during `jadx app.apk` (no GUI)

# custom (non-javax.crypto) decrypt capture
captureDecrypt=false
captureDecryptMethods=        # comma-separated full sigs, e.g. Lcom/foo/Bar;->a(Ljava/io/InputStream;IS)Ljava/io/InputStream;

# fake-device overrides (all optional; seeded from the manifest otherwise)
device.model=Pixel 7
device.fingerprint=google/panther/panther:13/...:user/release-keys
device.sdkInt=33
device.androidId=0123456789abcdef
device.installer=com.android.vending
```

Tuning notes: leave `sweepBudgetMs=0` for a guaranteed-complete run; set e.g. `90000` for faster triage (a slow-but-productive loader is never starved — the budget only stops launching *more* work). The emulated app is non-debuggable, Play-installed and correctly-signed (`AppInfo` reads the real signing certificate and manifest SDK levels); edit `DeviceProfile` / `device.*` to change it.

### Native (JNI) decryption

DexGuard's DEX decryption does not need this. If a target's decryptor lives in a `.so`, you can drop a `native-replay.properties` in the data dir mapping native symbols (called directly or reflectively) to pre-captured results:

```properties
Lcom/foo/Bar;->decrypt=1234
Lcom/foo/Bar;->tag=release
```

Providing a replay file makes the extension wire a native bridge into the VMs; leave it out for DexGuard targets. To execute the real `.so` instead of replaying values, see the experimental unidbg backend in [`native-unidbg/`](native-unidbg/README.md).

## Scripts and test protocol

`scripts/` has one-command helpers (`baseline.sh`, `deploy.sh`, `deploy-engine.sh`, `audit.sh`, `results.sh`, `smoke.sh`, `find-decryptors.sh`, `crypto-vs-native.sh`, ...) that guarantee a single deployed jar, a matching engine and known-good settings. After any deploy run `scripts/smoke.sh` (10 seconds, no APK, runs the real jadx + plugin + extension on a tiny synthetic app and must print `SMOKE: PASS`). See [`TESTING.md`](TESTING.md).

## Status

**v2.0.0** — ported to jadx-emu's rewritten `Hooks` API (upstream `ba65619`) on engine `0.1.0-beta.6-fp1`. Recovery logic, loader hooks, config keys and outputs are unchanged from 1.0.13; what changed is how the extension talks to the engine (see [`CHANGES.md`](CHANGES.md): `ExtHooks`, the scheduler running on the dispatching VM, engine diagnostics). **1.x is frozen at v1.0.13** on engine `0.1.0-beta.6`.

2.0.0 is verified by: the unit + 11 end-to-end tests (real emulated Dalvik: an off-thread executor task and `new Thread` recovered through the scheduler, stubs, a real `Cipher.doFinal` fed by crypto capture, decrypt capture on an emulated method, engine diagnostics, reflective framework access); a Gradle build with tests; and `scripts/smoke.sh` through the real jadx CLI (recovered dex byte-identical to the payload; the 1.x line recovers the same bytes on its own engine). **FaceTec acceptance (2026-10-15): PASS - 4 dex, 421 classDefs, the same four files (same content-hash names and sizes) as v1.0.x.** **Known, unexplained: the sweep took about 12.6 minutes** (candidate 1: 122 s, candidate 2: 67 s, candidate 3 `com.facetec.sdk.eu`: 565 s with 0 new dex) against roughly 4 minutes on the earlier 1.x run (judged from file timestamps; not logged). Candidate 3 shows two aborted `eu.<clinit>` runs, consistent with each reaching `maxMillis=300000`. Benchmarked and ruled out: the engine rewrite itself (old vs new engine, same speed on a 500k-invoke loop), `Hooks.after` (within noise), and the diagnostics sink (~0.5 us per event, ~3 s at this run's 5.8M events). To localize: re-run with `telemetry=false`, and run the same APK on the 1.x line (branch `1.x`, engine tag `beta6-baseline`). Vodafone and PayU have not been re-run on 2.0.0; the numbers below are from v1.0.x.

Earlier validation: three DexGuard targets with the GUI auto-sweep (ON_DEMAND) on jadx-emu beta.4 (v1.0.2-v1.0.4), and the headless path (`autoUnpack=true`) on FaceTec (v1.0.6 on beta.5: 4 dex, 421 classDefs, no time-limit hits; v1.0.13/1.0.x on beta.6: the same).

- FaceTec — 4 dex / 421 classDefs
- Vodafone (base APK) — 2 dex / 167 classDefs
- PayU / Drishti (`clockAdvanceMs=1000`) — 2 dex / 1,679 classDefs

Every jadx-emu API used is verified against the engine source (see [`CHANGES-FROM-ORIGINAL.md`](CHANGES-FROM-ORIGINAL.md) §8). The optional unidbg native backend in `native-unidbg/` is experimental and is not needed for DexGuard, whose DEX decryption is pure Dalvik. See [`USAGE.md`](USAGE.md) for options, [`TESTING.md`](TESTING.md) for the clean-run protocol, and [`CHANGES.md`](CHANGES.md) for the per-round changelog.

## Credits & license

Built on [jadx-emu](https://github.com/nitanmarcel/jadx-emu) by nitanmarcel, and forked from nitanmarcel's original [jadx-emu-dexguard-unpacker](https://github.com/nitanmarcel/jadx-emu-dexguard-unpacker). Licensed under **GPL-2.0** (see [`LICENSE`](LICENSE)), inherited from upstream.
