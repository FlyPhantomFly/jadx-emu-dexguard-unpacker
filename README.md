# jadx-emu-dexguard-unpacker

A [jadx-emu](https://github.com/nitanmarcel/jadx-emu) extension that recovers the DEX files a **DexGuard**-protected app decrypts at runtime. It doesn't reverse DexGuard's encryption — it runs the app's own loader inside jadx-emu's Dalvik emulator and captures the plaintext DEX at the moment it's handed to a class loader, overlays the recovered classes, and keeps going for nested stages.

## How it works

The emulator provides a faithful, non-debuggable, correctly-signed Android environment so DexGuard's RASP/anti-tamper checks pass during emulation. A read-only **DEX-reference scan** (no decompilation) ranks loader candidates; running a candidate's entry points triggers its decrypt-and-load, and hooks on the in-memory / file-path / `DexFile` loaders (and reflective `Constructor.newInstance`) capture the plaintext DEX. A bounded fixpoint drives stage-2 classes revealed by earlier stages. Recovered DEX is header-repaired so baksmali/dex2jar/jadx accept it.

## Requirements

- **jadx** 1.5.6+ and **JDK 21** (to build).
- **jadx-emu with the rewritten hook API and `Hooks.after`.** This extension (2.x) is built and tested against jadx-emu `0.1.0-beta.6-fp1`: upstream's hook rewrite (commit `ba65619`) plus the return-value (`after`) hook seam. `Hooks.after` is not in a released jadx-emu yet (nitanmarcel/jadx-emu PR #6), so the engine is built from the `upstream-hooks` branch of the fork shown below. The 1.x line of this extension (v1.0.x) needs the older `0.1.0-beta.6` engine instead. The two are not interchangeable: a mismatched pair fails when the extension initializes (`NoSuchMethodError`).

The engine is labelled `-fp1` so that it never overwrites a `0.1.0-beta.6` that the 1.x line compiled against in your local Maven repository.

## Install

1. Build the engine and install it as the jadx-emu plugin. Keep exactly one jadx-emu jar installed.
   ```bash
   git clone --branch upstream-hooks https://github.com/FlyPhantomFly/jadx-emu.git
   cd jadx-emu
   sed -i 's/^version = .*/version = "0.1.0-beta.6-fp1"/' build.gradle.kts
   ./gradlew dist
   jadx plugins --install-jar build/dist/jadx-emu-0.1.0-beta.6-fp1.jar
   ```
2. Build this extension's jar (below) and drop it into jadx-emu's extensions directory — `<jadx config>/plugins-data/jadx-emu/extensions/` (on Linux `~/.config/jadx/plugins-data/jadx-emu/extensions/`):
   ```bash
   cp build/libs/dexguard-unpacker-*.jar ~/.config/jadx/plugins-data/jadx-emu/extensions/
   ```
3. Restart jadx-gui (extensions are discovered at startup via the `META-INF/services/jadx.plugins.emu.api.EmuExtension` entry). The log shows a `dexguard-unpacker v2.0.0` line when the extension loaded.

## Build

The extension compiles against the engine from your local Maven repository, so publish the engine first (same checkout and label as above):

```bash
(cd jadx-emu && ./gradlew publishToMavenLocal)
./gradlew test     # AOSP math, DEX repair, signature parser, the hook chain, and end-to-end runs of real emulated Dalvik
                   # (smali is assembled in the test): inline scheduler, reflective framework stubs, crypto/decrypt capture
./gradlew jar      # build/libs/dexguard-unpacker-<version>.jar   (VERSION env overrides the version)
```

`jadx-emu` is `compileOnly` and resolves from `mavenLocal()` (it is not on Maven Central at this label); `jadx-core` and the test libraries come from Maven Central. To build against a local engine checkout without publishing it, use a Gradle composite build. CI does the publish-then-build sequence above (`.github/workflows/build.yml`).

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

## Outputs per run

Under `<dataDir>/<inputHash>/` (the data dir is printed on startup):

- `hidden-<sha>.dex` — recovered DEX (header-repaired).
- `report.json` — recovered-dex summary + the emulated environment. Counts are read from disk, so they always match `ls` (including fixpoint-recovered dexes).
- `telemetry.json` — only with `telemetry=true`: which stubs fired, which returned UNKNOWN (un-foldable constants worth a new stub), unresolved classes/methods, and an event log — how to see *why* a run recovered what it did.
- `diagnosis.txt` — only when a run recovers 0 dex: the likely cause and what to change (also logged as a warning). A partial recovery logs its hints without writing the file.
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
captureCrypto=true            # observe the app's own Cipher output (doFinal/update); record key/iv/plaintext
feedDecryptedDex=true         # when a decrypted plaintext is a DEX, load it automatically
fixpoint=true                 # drive stage-2+ lifecycle classes revealed by earlier stages
fixpointMaxRounds=6
persistHiddenVm=false         # keep the hidden-dex VM (and its clinit state) across stages instead of rebuilding it per stage; opt-in

# sweep
scanMode=dex                  # dex (raw-DEX reference scan) | signature (emulator-source fallback)
sweepTopN=12                  # candidates the auto-sweep tries
sweepBudgetMs=0               # per-candidate wall-clock cap; 0 = unlimited/thorough (never cuts a decrypt mid-run)
sweepClasses=                 # optional comma-separated classes to always run (descriptor or dotted)
autoUnpack=false              # headless: run the sweep automatically during `jadx app.apk` (no GUI)

# custom (non-javax.crypto) decrypt capture
captureDecrypt=false
captureDecryptMethods=        # comma-separated full sigs, e.g. Lcom/foo/Bar;->a(Ljava/io/InputStream;IS)Ljava/io/InputStream;

# native (JNI) backend
nativeBackend=none            # none (replay only) | a provider id such as unidbg (see native-unidbg/)

# fake-device overrides (all optional; seeded from the manifest otherwise)
device.model=Pixel 7
device.fingerprint=google/panther/panther:13/...:user/release-keys
device.sdkInt=33
device.androidId=0123456789abcdef
device.installer=com.android.vending
# also: device.manufacturer, brand, device, product, hardware, tags, type, minSdk, targetSdk, debuggable
```

Tuning notes: leave `sweepBudgetMs=0` for a guaranteed-complete run; set e.g. `90000` for faster triage (a slow-but-productive loader is never starved — the budget only stops launching *more* work). The emulated app is non-debuggable, Play-installed and correctly-signed (`AppInfo` reads the real signing certificate and manifest SDK levels); edit `DeviceProfile` / `device.*` to change it.

### Native (JNI) decryption

DexGuard's DEX decryption does not need this. If a target's decryptor lives in a `.so`, you can drop a `native-replay.properties` in the data dir mapping native symbols (called directly or reflectively) to pre-captured results:

```properties
Lcom/foo/Bar;->decrypt=1234
Lcom/foo/Bar;->tag=release
```

Providing a replay file makes the extension wire a native bridge into the VMs; leave it out for DexGuard targets. To execute the real `.so` instead of replaying values, see the experimental unidbg backend in [`native-unidbg/`](native-unidbg/README.md) (not yet updated for the 2.x engine).

## Status

Validated on three DexGuard targets with the GUI auto-sweep (ON_DEMAND) on jadx-emu beta builds. The headless path (AUTO, `autoUnpack=true`) was validated on:

- FaceTec — 4 dex / 421 classDefs
- Vodafone (base APK) — 2 dex / 167 classDefs
- PayU / Drishti (`clockAdvanceMs=1000`) — 2 dex / 1,679 classDefs
