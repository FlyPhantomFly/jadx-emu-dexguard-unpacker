# Development workspace (3 separate repos)

We keep **three separate repositories** and do **not** merge them:

| repo | role | upstream |
|------|------|----------|
| `jadx-emu` | the host plugin (Dalvik emulator inside jadx) | `nitanmarcel/jadx-emu` |
| `jadx-emu-string-deobfuscator` | reference AUTO-mode extension | `nitanmarcel/jadx-emu-string-deobfuscator` |
| `jadx-emu-dexguard-unpacker` | **this project** (our extension) | your fork |

Reasons for keeping them separate rather than one monorepo:

- jadx-emu discovers extensions via `ServiceLoader` from its extensions dir; an extension is meant to be a standalone jar. Merging fights that design.
- `jadx-emu` and the string-deobfuscator are nitanmarcel's GPL-2.0 projects. Depending on them (and crediting them) keeps authorship clean; vendoring would muddy it.
- Separate repos track upstream releases cleanly (e.g. the next `0.1.0-beta.x`).

This project already builds against the published artifacts, which is the right default:

```kotlin
compileOnly("io.github.nitanmarcel:jadx-emu:0.1.0-beta.6-fp1")   // 2.x line; resolved from mavenLocal (scripts/deploy-engine.sh publishes it)
compileOnly("io.github.skylot:jadx-core:1.5.6")
```

**Two engine lines.** Upstream rewrote the hook API (commit `ba65619`), so the extension has two lines that cannot share an install: **2.x** needs jadx-emu `0.1.0-beta.6-fp1` (upstream `ba65619`+ plus the `Hooks.after` seam), **1.x** (frozen at v1.0.13) needs `0.1.0-beta.6` (the older `HookRegistry` engine plus `addReturn`). Keep the engine fork on a branch per line (`beta6-baseline` tag for 1.x, `upstream-hooks` for 2.x). Upstream also labels its own build `0.1.0-beta.6`, which is why ours is `-fp1`: publishing upstream's under that label would overwrite the beta.6 the 1.x line compiles against. If you use the composite build below, point `includeBuild("jadx-emu")` at the checkout that matches the extension line you are building.

## Local composite build (build/debug all three from their own checkouts)

Fork and clone the three repos side by side, then add a tiny **root** `settings.gradle.kts` that pulls each in as an included build. Gradle's composite-build **dependency substitution** automatically replaces the `io.github.nitanmarcel:jadx-emu` dependency above with your local `jadx-emu` checkout when it's present — so no edits to this project's `build.gradle.kts` are needed. Outside the workspace it falls back to the Maven artifact.

```
~/jadx-emu-workspace/
├── settings.gradle.kts            # the composite root (below)
├── jadx-emu/                      # your fork
├── jadx-emu-string-deobfuscator/  # your fork
└── jadx-emu-dexguard-unpacker/    # this project
```

Root `settings.gradle.kts`:

```kotlin
rootProject.name = "jadx-emu-workspace"
includeBuild("jadx-emu")
includeBuild("jadx-emu-string-deobfuscator")
includeBuild("jadx-emu-dexguard-unpacker")
```

Then from `~/jadx-emu-workspace`:

```bash
./gradlew :jadx-emu-dexguard-unpacker:jar     # builds our jar against the LOCAL jadx-emu fork
./gradlew build                               # build everything
```

Because the engine fork is now on the compile classpath, you can **step into jadx-emu source** while debugging and **prototype engine changes** (e.g. an `onNativeCall` seam, or an instruction-level scanner API) and test them end-to-end here before sending them upstream as a PR to `nitanmarcel/jadx-emu`.

## Update workflow

Each repo is updated independently:

```bash
# in each fork: pull upstream, keep your changes on a branch
cd jadx-emu && git fetch upstream && git merge upstream/master        # or rebase your branch
cd ../jadx-emu-dexguard-unpacker && git pull                          # your own history
```

- To track a new jadx-emu release *without* the workspace: bump the version in this project's `build.gradle.kts` (`io.github.nitanmarcel:jadx-emu:<new>`) and rebuild.
- To track it *with* the workspace: update the `jadx-emu` fork, and the composite build picks it up on the next build — no version bump needed locally.

Keep engine modifications in the `jadx-emu` fork (and upstream them); keep extension logic here. That separation is what makes each repo independently updatable as the project grows.
