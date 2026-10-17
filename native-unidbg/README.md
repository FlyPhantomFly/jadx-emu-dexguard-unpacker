# native-unidbg (optional backend)

unidbg-backed native (JNI) execution for the engine's `NativeBridge` seam, so the unpacker can run a
target's native `.so` decryptor headless. See `../NATIVE-BACKEND-SCOPE.md` for the design.

**Surgical by design.** Only the native methods you list in `nativeMethods` are executed through
unidbg; every other native call returns `NotHandled` (→ UNKNOWN), so a protected app's RASP native
calls behave exactly as they do without this backend. If `nativeMethods` is empty, **no bridge is
wired at all**. (A bridge that intercepts every native call changes the engine's dispatch and breaks
emulation of protected apps — this is enforced deliberately.)

Still a bring-up: the static JNI calls are confirmed against unidbg; the instance-method and
`newObject` paths are best-effort (`NEEDS-VERIFICATION`), and there's no JNI-callback support yet.

**Packaging:** the jar bundles unidbg and its dependencies (a thin jar is ~15 KB and cannot run - the host does
not have unidbg on its classpath). Use `bash scripts/deploy-unidbg.sh <ver>`; it prints the jar size.

## Build (separate from the core)

```bash
cd ..            && ./gradlew jar          # core jar first (provides the SPI)
cd native-unidbg && ./gradlew jar          # the backend jar (needs internet: Maven Central + jitpack)
```
(Copy the parent wrapper once if needed: `cp ../gradlew ../gradlew.bat . && cp -r ../gradle .`)

## Test a .so OUTSIDE jadx first (fast iteration)

Before wiring it into jadx, confirm unidbg loads your `.so` and the marshaling is right:

```bash
./gradlew run --args="/path/libfoo.so Lcom/foo/Bar; decrypt ([B)[B 0011223344"
# UNIDBG_VERBOSE=1 ./gradlew run --args="..."   # unidbg's own instruction/JNI trace
```
It prints the return value (byte[] as hex, String as text). Iterate here until it's correct.

## Use in jadx

Deploy **both** jars to the extensions dir, then set in
`<jadx config>/plugins-data/jadx-emu/data/dexguard-unpacker/dexguard-unpacker.properties`:

```properties
nativeBackend=unidbg
nativeMethods=Lcom/foo/Bar;->decrypt     # REQUIRED - the decryptor(s) to run; comma-separated
nativeAbi=arm64-v8a
nativeLibs=libfoo.so                      # optional; primary first. empty = all <abi> .so
nativeSdk=23
nativeVerbose=false
```

On startup the core logs `native bridge ACTIVE (unidbg)` and the backend logs each executed call
(`unidbg: Lcom/foo/Bar;->decrypt([B)[B -> byte[...]`), so you can see exactly what ran and what it
returned. **Integration point to verify:** whether jadx-emu puts this second jar on the same
classloader so `ServiceLoader` finds the provider — if not, merge into the core jar or add it to jadx's
classpath.
