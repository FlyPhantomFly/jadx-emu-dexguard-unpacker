#!/usr/bin/env bash
# Build the jadx-emu engine, publish it to ~/.m2 and install it as the only jadx-emu plugin jar.
# Usage: scripts/deploy-engine.sh <path to your jadx-emu checkout>
# Run it before deploy.sh whenever the engine changed.
set -eu
ENGINE="${1:?usage: deploy-engine.sh <path to jadx-emu>}"
[ -f "$ENGINE/build.gradle.kts" ] || { echo "not a jadx-emu checkout: $ENGINE" >&2; exit 1; }
PLUGINS="$HOME/.config/jadx/plugins/installed"

echo "building the engine in $ENGINE"
( cd "$ENGINE" && ./gradlew --no-daemon clean test publishToMavenLocal shadowJar )

# the plugin is the fat jar that shadowJar builds (classifier "plugin")
JAR="$(ls -t "$ENGINE"/build/libs/jadx-emu-*-plugin.jar 2>/dev/null | head -n 1 || true)"
[ -n "$JAR" ] || { echo "no jadx-emu-*-plugin.jar in $ENGINE/build/libs" >&2; exit 1; }
JAR="$(cd "$(dirname "$JAR")" && pwd)/$(basename "$JAR")"
echo "plugin jar: $JAR ($(du -h "$JAR" | cut -f1))"

mkdir -p "$PLUGINS"
rm -f "$PLUGINS"/jadx-emu*.jar
if command -v jadx >/dev/null 2>&1; then
  jadx plugins --uninstall jadx-emu >/dev/null 2>&1 || true
  jadx plugins --install-jar "$JAR" || cp -f "$JAR" "$PLUGINS/jadx-emu.jar"
else
  cp -f "$JAR" "$PLUGINS/jadx-emu.jar"
fi

n="$(ls "$PLUGINS"/jadx-emu*.jar 2>/dev/null | wc -l)"
ls -la "$PLUGINS"/jadx-emu*.jar 2>/dev/null | sed 's/^/  /'
if [ "$n" -eq 1 ]; then echo "engine ok: exactly one jadx-emu jar"; else echo "!! expected one jadx-emu jar, found $n. Remove the extras." >&2; exit 1; fi
echo "Restart jadx. The log should say: jadx-emu <version> loaded ..."
echo "A NoSuchMethodError when an extension starts means it was built against a different engine than the installed one."
echo "Next: scripts/deploy.sh"
