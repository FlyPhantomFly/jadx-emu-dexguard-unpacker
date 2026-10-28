#!/usr/bin/env bash
# Build the jadx-emu ENGINE fork and install it as the ONLY jadx-emu plugin jar, then publish it to
# mavenLocal so this extension's deploy.sh can compile against it. The engine is a SEPARATE repo from
# this extension (keep them separate - see WORKSPACE.md).
#   usage: deploy-engine.sh <path-to-jadx-emu> [version]
#   e.g.   deploy-engine.sh ~/jadx-emu/jadx-emu
# The version is read from the checkout's build.gradle.kts (override with the 2nd argument). Run this BEFORE
# deploy.sh / baseline.sh whenever the engine changed. 2.x extension -> engine 0.1.0-beta.6-fp1; 1.x -> 0.1.0-beta.6.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"; . "$HERE/_env.sh"
ENGINE="${1:?usage: deploy-engine.sh <path-to-jadx-emu> [version]}"
[ -f "$ENGINE/build.gradle.kts" ] || { echo "not a jadx-emu checkout: $ENGINE" >&2; exit 1; }
VER="${2:-$(sed -n 's/^version = "\(.*\)"/\1/p' "$ENGINE/build.gradle.kts" | head -1)}"
[ -n "$VER" ] || { echo "no version in $ENGINE/build.gradle.kts" >&2; exit 1; }
PLUGINS="$HOME/.config/jadx/plugins/installed"

if [ -f "$ENGINE/src/main/kotlin/jadx/plugins/emu/api/Hooks.kt" ]; then
  if grep -q 'fun after(pattern' "$ENGINE/src/main/kotlin/jadx/plugins/emu/api/Hooks.kt"; then
    echo "== engine API: Hooks (rewritten, ba65619+) with after()   -> for the 2.x extension"
  else
    echo "== engine API: Hooks (rewritten, ba65619+) WITHOUT after() -> the 2.x extension needs it: run jadx-emu-hooks2-upgrades/apply.sh first" >&2
  fi
else
  echo "== engine API: HookRegistry (pre-ba65619)                  -> for the 1.x extension (v1.0.x)"
fi

echo "== building engine: $ENGINE (v$VER)"
# publishToMavenLocal -> deploy.sh (extension, built standalone) can resolve jadx-emu:$VER
# shadowJar          -> the fat jar jadx loads as the runtime plugin
( cd "$ENGINE" && ./gradlew --no-daemon clean test publishToMavenLocal shadowJar )

# the jadx PLUGIN is the fat jar: the -plugin (or -all) classifier, NOT the thin/sources/javadoc jars
JAR=$(ls -S "$ENGINE"/build/libs/jadx-emu-"$VER"-plugin.jar "$ENGINE"/build/libs/jadx-emu-"$VER"-all.jar 2>/dev/null | head -1 || true)
[ -n "${JAR:-}" ] || JAR=$(ls -S "$ENGINE"/build/libs/jadx-emu-"$VER"*.jar 2>/dev/null | grep -vE 'sources|javadoc' | head -1 || true)
[ -n "${JAR:-}" ] && [ -f "$JAR" ] || { echo "no plugin jar in $ENGINE/build/libs (looked for -plugin/-all)" >&2; exit 1; }
echo "== plugin jar: $JAR ($(du -h "$JAR" | cut -f1))"

mkdir -p "$PLUGINS"
echo "== removing old jadx-emu plugin jar(s) from $PLUGINS"
rm -f "$PLUGINS"/jadx-emu*.jar
if command -v jadx >/dev/null 2>&1; then
  jadx plugins --uninstall jadx-emu >/dev/null 2>&1 || true
  jadx plugins --install-jar "$JAR" || cp -f "$JAR" "$PLUGINS/jadx-emu.jar"
else
  cp -f "$JAR" "$PLUGINS/jadx-emu.jar"
fi

echo "== installed engine jar(s):"
ls -la --time-style=long-iso "$PLUGINS"/jadx-emu*.jar 2>/dev/null | sed 's/^/  /'
one=$(ls "$PLUGINS"/jadx-emu*.jar 2>/dev/null | wc -l)
[ "$one" -eq 1 ] && echo "engine: OK (exactly 1 jadx-emu jar)" || echo "engine: !! expected 1 jadx-emu jar, found $one - remove the extras"
echo
echo "published io.github.nitanmarcel:jadx-emu:$VER to ~/.m2 (deploy.sh will resolve it)"
echo "Restart jadx-gui; the log MUST read: jadx-emu $VER loaded. The extension and engine lines must match:"
echo "  2.x extension -> 0.1.0-beta.6-fp1     1.x extension (v1.0.x) -> 0.1.0-beta.6"
echo "A mismatch fails at init, loudly ('extension dexguard-unpacker failed to init', 0 active): 2.x on the old engine ->"
echo "NoSuchMethodError ...EmuExtensionContext.getHooks(); 1.x on the new engine -> NoSuchMethodError ...EngineContext.getAndroid()."
echo "Next: bash scripts/deploy.sh <extension version>"
