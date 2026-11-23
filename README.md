# jadx-emu-dexguard-unpacker

An extension for [jadx-emu](https://github.com/FlyPhantomFly/jadx-emu) that recovers the DEX files a DexGuard-protected app decrypts at runtime.

It doesn't break DexGuard's encryption. It runs the app's own loader in the emulator and captures the plaintext dex when the loader receives it, then keeps going in case that dex loads another one.

## How it works

A quick scan of the raw dex ranks the classes most likely to load hidden code. The sweep runs the top ones in the emulator, which acts like a normal, correctly signed, non-debuggable Android device so DexGuard's anti-tamper checks pass. Hooks on the in-memory, file-path and `DexFile` loaders (and reflective `Constructor.newInstance`) catch the plaintext dex. Recovered files get their headers repaired so baksmali, dex2jar and jadx accept them.

## Install

You need jadx 1.5.6 or newer with the jadx-emu 0.2.0 plugin installed (any later engine works too; this tree is tested against 0.4.1).

```
./scripts/deploy-engine.sh ~/github/jadx-emu    # builds the engine, publishes it to ~/.m2, installs the plugin jar
./scripts/deploy.sh                             # tests, builds and installs this extension
./scripts/smoke.sh                              # must end with SMOKE: PASS
```

Use the path to your own jadx-emu checkout. Restart jadx afterwards. The log should show `jadx-emu 0.2.0 loaded: 1 extension(s) found ..., 1 active` and `dexguard-unpacker v2.3.0`.

## Use

In jadx-gui, open the APK, right-click a class and pick **DexGuard: auto-sweep** from the Jadx Emu menu. It ranks the loader candidates and runs the best ones. Recovered dex files are added to the project.

The other menu items are narrower: scan for loader classes (just the ranking), unpack from the selected class or method, repackage the APK with the recovered dex (unsigned, so sign it with `apksigner` before installing), and cancel a running unpack.

To run it without the GUI, set `autoUnpack=true` in the properties file and run jadx on the APK. The sweep runs during decompilation and logs where the files went.

```
jadx -v -d out app.apk
```

If the app comes as an XAPK or split bundle, extract it and open the base APK, the one with `classes.dex`.

## What you get

Each run writes to `~/.config/jadx/plugins-data/jadx-emu/data/dexguard-unpacker/<input hash>/`:

- `hidden-<sha>.dex`, the recovered dex files
- `report.json`, what was recovered and the emulated environment
- `telemetry.json`, counters for the run (only with `telemetry=true`)
- `diagnosis.txt`, written when nothing was recovered, with the likely cause and what to try
- `crypto/crypto.json`, when real `javax.crypto` decryption was seen

## Native decryptor detection (return to Java)

Before each run the extension scans the APK's `lib/arm64-v8a/*.so` for the fingerprint of DexGuard's
centralized **return-to-Java** routine - the one place a .so's decryption funnels back into Java. The
fingerprint is the six `movz`+`movk` constant pairs the routine builds (0x55AA55AA, 0xAA55AA55,
0x2A55AA55 into x10/x12/x13), field-proven by a YARA rule across 27k+ DexGuard APKs over five months.

A hit is logged (pattern, file offset, memory address), recorded under `returnToJava` in `report.json`,
and turns into a diagnosis line. Note that actually running the library needs a native-backend adapter
module for this extension; none has been shipped since 2.1.0, so the diagnosis only points there.

## Settings

Everything is optional and lives in `dexguard-unpacker.properties` next to those folders. [USAGE.md](USAGE.md) lists every setting and a troubleshooting table.

## Limits

Many DexGuard apps only encrypt strings and never load hidden dex. On those the run recovers nothing, and `diagnosis.txt` says what it saw. OnePay is one of them.

## Native decryption

When a decryptor lives in a .so, the extension can run it through the engine's native backend. Set in `dexguard-unpacker.properties`:

| key | example | what it does |
|---|---|---|
| nativeBackend | unidbg | id of the registered engine backend (`jadx-emu-native-unidbg` registers `unidbg`). `none` keeps the validated bridge-less path. |
| nativeMethods | Lcom/foo/Bar;->read,Lcom/foo/Baz;->dec | JNI methods the engine may dispatch (name only; all overloads run). |
| nativeLibs | libb4f7.so,libc7d5.so | libraries to load in the backend, main library first. |
| nativeAbi | arm64-v8a | ABI of the libraries. |
| nativeApk | /path/…/split_config.arm64_v8a.apk | the APK that contains the libraries. Defaults to the jadx input; for a bundled app point it at the arm64 split (`.apks` bundles are auto-extracted). |

Requires the jadx-emu 0.3.0+ engine with the unidbg extension installed; the bridge resolves at the first VM build, so extension load order doesn't matter. Note that DexGuard's loaders often invoke their natives reflectively, which the engine's host policy denies — allow `java.lang.reflect.Method;->invoke` in the host policy (or hook it) when a run shows `HOST_UNKNOWN_INPUT @ java/lang/reflect/Method;->invoke` in the telemetry.

On FaceTec the sweep takes about 12 minutes, against about 4 on the 1.x releases. The engine rewrite, `Hooks.after` and the diagnostics sink were ruled out. The cause hasn't been found.

## Tested on

FaceTec (4 dex, 421 classDefs), Vodafone (2 dex, 167 classDefs) and PayU (2 dex, 1,679 classDefs, with `clockAdvanceMs=1000`). The Vodafone and PayU numbers are from the 1.0.x releases.

## Credits and license

Forked from nitanmarcel's jadx-emu-dexguard-unpacker and extended. GPLv2, see `LICENSE` and `NOTICE`.
