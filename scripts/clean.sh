#!/usr/bin/env bash
# Remove ALL builds (every checkout), deployed jars (core + unidbg), recovered output, and jadx's cache.
# Keeps your .properties. Use baseline.sh for a full known-good reset.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"; . "$HERE/_env.sh"
ROOT="$(cd "$HERE/.." && pwd)"
( cd "$ROOT" && ./gradlew --stop >/dev/null 2>&1 ) || true
for d in "$HOME"/jadx-emu/jadx-emu-dexguard-unpacker*/ "$ROOT"/; do
  rm -rf "$d/build" "$d/.gradle" "$d/native-unidbg/build" "$d/native-unidbg/.gradle"
done
rm -f "$EXT"/dexguard-unpacker*.jar
[ -d "$DATA" ] && find "$DATA" -mindepth 1 -maxdepth 1 -type d -exec rm -rf {} +
rm -rf "$HOME/.cache/jadx"
echo "clean: builds (all checkouts), jars, recovered output, jadx cache removed."
echo "In jadx-gui use File > Open on the APK - never reload a saved project."
