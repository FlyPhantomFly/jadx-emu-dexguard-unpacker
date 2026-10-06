# jadx-emu-dexguard-unpacker

A [jadx-emu](https://github.com/nitanmarcel/jadx-emu) extension that unpacks the DEX files a DexGuard-protected app decrypts at runtime. It does not reverse DexGuard's encryption — it runs the app's own loader inside jadx-emu's Dalvik emulator and captures the plaintext DEX at the moment it is handed to a class loader, then overlays the recovered classes and keeps going for nested stages.

## Build

```
./gradlew jar          # builds build/libs/dexguard-unpacker-*.jar
./gradlew test         # runs the AOSP-math / DEX-helper unit tests
```

Dependencies (`jadx-emu`, `jadx-core`) are `compileOnly` and resolve from Maven Central + Google's Maven. The jar is discovered by jadx-emu through the `META-INF/services/jadx.plugins.emu.api.EmuExtension` service entry; load it with jadx-emu's extension mechanism (see the jadx-emu docs for the extension directory on your platform).

## Usage

Right-click in the decompiled view and choose one of:

- **DexGuard: unpack from this class** — runs the class's `<clinit>` plus common entry points (`attachBaseContext`, `onCreate`, ContentProvider `attachInfo`) and dumps whatever DEX it loads.
- **DexGuard: unpack from this method** — runs the selected method (falls back to the class entry points if the method id can't be resolved).
- **DexGuard: scan for loader classes** — ranks classes that look like loaders so you know where to start. This decompiles classes, so it can take a while.
- **DexGuard: repackage APK with recovered dex** — writes an *unsigned* `*-unpacked.apk` with the recovered DEX appended; re-sign with `apksigner` before installing.
- **DexGuard: cancel running unpack** — cancels the in-flight run.

Recovered files land in the extension data dir as `hidden-<sha256>.dex` (header-repaired so baksmali/dex2jar accept them), alongside a `report.json`. In the GUI they are added to the project automatically; on the CLI the paths are logged so you can add them yourself.

## Tuning for anti-tamper (RASP) checks

The emulated environment reports a non-debuggable, Play-installed, correctly-signed app (`AppInfo` reads the real signing certificate and manifest min/target SDK). Edit `DeviceProfile` to change the fake device, installer, or SDK levels.

### Native (JNI) decryption

If the decryption routine lives in a `.so`, drop a `native-replay.properties` file into the extension data dir mapping method symbols to pre-captured results, e.g.

```
Lcom/foo/Bar;->decrypt=1234
Lcom/foo/Bar;->tag=release
```

Reflectively-invoked native methods will then return those values. Running the real `.so` in-process (unidbg) needs an engine-level native-call hook in jadx-emu — see `NativeBridge.attachUnidbgSeam()`.

## Status

See `CHANGES.md` for the full list of recent changes and their confidence levels. The code targets `jadx-emu` `0.1.0-beta.4`.

## Configuration (optional)

Drop a `dexguard-unpacker.properties` file in the extension data dir to tune behaviour without editing source. All keys are optional:

```
telemetry=true                 # write telemetry.json per run (hooks fired, UNKNOWN folds, unresolved)
verbose=false
maxSteps=50000000              # emulation limits
maxMillis=120000
maxDepth=64
captureCrypto=true             # observe Cipher.doFinal and capture key/iv/plaintext
feedDecryptedDex=true          # when a decrypted plaintext is a DEX, load it automatically
fixpoint=true                  # drive stage-2+ lifecycle classes revealed by earlier stages
fixpointMaxRounds=6
# fake-device overrides (all optional)
device.model=Pixel 7
device.fingerprint=google/panther/panther:13/TQ3A.230805.001/...:user/release-keys
device.sdkInt=33
device.androidId=0123456789abcdef
device.installer=com.android.vending
```

## Outputs per run

Everything lands under `<dataDir>/<inputHash>/`:

- `hidden-<sha>.dex` — recovered DEX files (header-repaired).
- `report.json` — recovered-dex summary + the fake env.
- `telemetry.json` — only when `telemetry=true`; which stubs fired, which returned UNKNOWN (an un-foldable constant worth a new stub), unresolved classes/methods, and an event log. This is how you see *why* a run came up empty instead of guessing.
- `crypto/crypto.json` + `crypto/plain-<sha>.bin` — when crypto capture sees decryption: the transformation, key, IV, and plaintext hashes; non-DEX plaintexts (decrypted strings/resources) are dumped as `.bin`, DEX plaintexts arrive as `hidden-*.dex`.

## Capturing custom (non-javax.crypto) decryption with Frida

# To-Do
Some targets decrypt strings/resources with a homemade transform inside a custom ClassLoader (e.g. FaceTec's `cdSS.a` / `lg.write`). Those methods only run during *live* class-loading, so the emulator never reaches them and the in-process `DecryptCapture` can't observe them. Frida scripts being written.

## Sweep tuning

- `scanMode` = `dex` (default, raw-DEX reference scan - fast, quiet, finds reflective loaders) or `signature` (emulator-source fallback).
- `sweepBudgetMs` = per-candidate wall-clock budget in ms; `0` (default) = unlimited/thorough. A decrypt in flight is never cut mid-run - the budget only stops launching *more* work for a candidate, so it's safe to set (e.g. `sweepBudgetMs=90000`) for faster triage without starving a slow-but-productive loader. For a guaranteed-complete run, leave it `0`.
- Recovered-dex counts in the log and `report.json` are read from the output dir on disk, so they always match `ls` (including dexes pulled via the fixpoint).
