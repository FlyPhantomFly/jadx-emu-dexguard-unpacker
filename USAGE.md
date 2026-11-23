# Settings and troubleshooting

Settings live in `dexguard-unpacker.properties` in the extension's data directory, `~/.config/jadx/plugins-data/jadx-emu/data/dexguard-unpacker/` on Linux. jadx prints the path on startup. Every key is optional, and a `#` comment after a value is fine. `scripts/set-prop.sh <key> <value>` changes one key for you, and `--unset` removes it.

## Recovery

| key | default | what it does |
|---|---|---|
| scanMode | dex | `dex` ranks loader candidates from the raw dex, which is fast. `signature` falls back to the emulator's source view. |
| sweepTopN | 12 | How many ranked candidates the sweep tries. Raise it if the loader ranks low and gets missed. |
| sweepBudgetMs | 0 | Wall-clock cap per candidate. 0 means no cap. |
| sweepClasses | empty | Classes to always run, as descriptors or dotted names, on top of the ranking. |
| autoUnpack | false | Run the sweep during a headless `jadx app.apk`. |
| fixpoint | true | Also run the classes a recovered stage reveals, for multi-stage payloads. |
| fixpointMaxRounds | 6 | Limit on those extra rounds. |
| persistHiddenVm | false | Keep the hidden-dex VM and its static state between stages instead of rebuilding it each time. Check that the recovered count doesn't change before relying on it. |

## Emulation limits

Raise these if recovery is incomplete.

| key | default | what it does |
|---|---|---|
| maxMillis | 300000 | Time limit per call, in ms. 0 means none. A cut-off is logged as `HIT the maxMillis limit`. |
| maxSteps | 50000000 | Instruction limit per method. A step cut-off isn't logged separately. |
| maxDepth | 64 | Call depth limit. |
| clockAdvanceMs | 1 | How far the virtual clock jumps on each read. Set it to 1000 if a loader hits the time limit while spinning on a clock check, since that skips anti-analysis wait loops. Leave it at 1 otherwise, because a big jump can trip slow-run checks. |

## Crypto and custom decrypt capture

These are fallbacks. DexGuard normally hands the plaintext straight to the class loader.

| key | default | what it does |
|---|---|---|
| captureCrypto | true | Watch real `javax.crypto` output and feed any decrypted dex to the loader. `update()` chunks are joined with the `doFinal` block, so streamed ciphers are captured whole. Writes `crypto.json` when it fires. |
| feedDecryptedDex | true | Load a decrypted plaintext automatically when it is a dex. |
| captureDecrypt | false | Record the input and output of the custom decrypt methods listed next. |
| captureDecryptMethods | empty | Comma-separated full signatures, such as `Lcom/foo/Bar;->a(Ljava/io/InputStream;IS)Ljava/io/InputStream;`. `scripts/find-decryptors.sh` can suggest some for an app. |

## Other

| key | default | what it does |
|---|---|---|
| telemetry | false | Write `telemetry.json` and attach the engine's diagnostics to every VM, so a run that recovers nothing names the host calls the engine refused and the framework calls that had no stub. Turn it on when debugging. |
| nativeBackend | none | `none` wires no native bridge. Any other value is looked up in the engine's backend registry (e.g. `unidbg` from jadx-emu-native-unidbg, engine 0.3.0+). |
| nativeMethods | (empty) | JNI methods the engine may dispatch to the backend, `Lcls;->name` comma-separated (name only). Required for a backend to arm. |
| nativeLibs | (empty) | Libraries to load in the backend, full `.so` file names, main first. Required for a backend to arm. |
| nativeAbi | arm64-v8a | ABI of the libraries. |
| nativeApk | (input) | APK holding the libraries. Defaults to the jadx input; for `.apks` bundles the arm64 split is extracted automatically, or set it explicitly. |

The fake device is seeded from the app's manifest. Override it with `device.manufacturer`, `device.brand`, `device.model`, `device.device`, `device.product`, `device.hardware`, `device.fingerprint`, `device.tags`, `device.type`, `device.androidId`, `device.installer`, `device.sdkInt`, `device.minSdk`, `device.targetSdk` or `device.debuggable`.

If an app's decryptor is in a .so, a `native-replay.properties` file in the data directory can map native symbols to results you captured elsewhere, one `Lcom/foo/Bar;->decrypt=1234` line per symbol. It makes the extension wire in a native bridge, so leave it out for ordinary DexGuard apps.

## Reading a run

`telemetry.json` counters worth knowing:

- `dex.loaded`: dex files the loaders received.
- `crypto.captured` and `crypto.fedDex`: real `javax.crypto` results seen, and how many were fed back as dex.
- `decrypt.captured`: custom decrypt calls recorded.
- `sched.run` and `sched.call`: off-thread, executor and handler bodies that ran inline.
- `hook.failed`: one of this extension's handlers threw and was skipped.
- `engine.<KIND>`: what the engine refused, such as `HOST_DENIED`, `MISSING_STUB`, `HANDLER_FAILED` or `ABORTED`. A second form with `@ Lcls;->m()V` names the method.

`scripts/results.sh` prints these and a one-line verdict on which path produced the dex.

## Troubleshooting

| symptom | likely cause | what to do |
|---|---|---|
| Fewer dex than expected, and the telemetry shows a `limit:` event | A loader hit the time limit | Set `clockAdvanceMs=1000` first, then `maxMillis=0`. |
| Fewer dex and no `limit:` event | A silent `maxSteps` cut-off, or an unknown branch | Raise `maxSteps`, for example to 500000000. If it's still short, look at the class. |
| `no candidates` or `emulator source is EMPTY` | The input is an XAPK, App Bundle or split APK | Extract it and open the base APK. |
| `native bridge ACTIVE` when you didn't expect it | `nativeBackend` or `native-replay.properties` is set | Run `scripts/baseline.sh`. |
| Results differ between runs | A stale jar or a second checkout | Run `scripts/audit.sh`, then `scripts/baseline.sh`. |
| No `crypto.json` | DexGuard decrypts through the class loader, not `javax.crypto` | Expected. Crypto capture is only a fallback. |
| The console is quiet for minutes during a sweep | A slow candidate | It isn't hung. Every 20 seconds the log prints `auto-sweep [N/M] still working` while a candidate runs over 15 seconds. |
| `diagnosis:` reports refused host calls (`HOST_DENIED`) | The loader needs a call the host policy denies | The inline scheduler already covers Thread, Executor and Handler. Otherwise allow the call in the jadx-emu host policy or register a hook or stub. |
| `extension 'dexguard-unpacker' failed to init`, `0 active`, `NoSuchMethodError` | The extension was built against a different engine than the installed one | Run `scripts/deploy-engine.sh`, then `scripts/deploy.sh`. |
| `SMOKE: FAIL` | Something between the engine, the extension and the settings is broken | Read the lines `smoke.sh` prints. It puts your properties back either way. |
| A run recovers nothing and the app only encrypts strings | There's no hidden dex to find | `diagnosis.txt` says what the run saw. |

## Scripts

All in `scripts/`:

- `deploy-engine.sh <jadx-emu path>` builds the engine, publishes it to `~/.m2` and installs it as the only jadx-emu plugin jar.
- `deploy.sh [version]` tests, builds and installs the extension as the only dexguard-unpacker jar, then runs `audit.sh`.
- `baseline.sh [version]` cleans everything, resets the properties and deploys.
- `clean.sh` removes the build output, the installed jar, recovered output and jadx's cache. It keeps your properties.
- `audit.sh` is read-only. It checks the installed jars, the live settings and for duplicate checkouts.
- `smoke.sh` runs a ten-second check through real jadx on a tiny synthetic app. No APK needed.
- `results.sh [log]` summarises the newest run. With `EXPECT=4` it prints PASS or FAIL.
- `set-prop.sh <key> <value|--unset>` edits one setting.
- `find-decryptors.sh [run-dir]` lists custom decrypt methods in a run's recovered dex, as a ready-to-paste `captureDecryptMethods` line.
- `crypto-vs-native.sh <apk|dir>` guesses whether an app decrypts in Java or in a .so.
