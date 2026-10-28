#!/usr/bin/env bash
# Build the CORE jar and install it as the ONLY dexguard-unpacker jar (removes every other one,
# including any unidbg jar), then audit.   usage: deploy.sh 1.0.6
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"; . "$HERE/_env.sh"
ROOT="$(cd "$HERE/.." && pwd)"
export VERSION="${1:?usage: deploy.sh <version>   e.g. deploy.sh 1.0.6}"
mkdir -p "$EXT"
( cd "$ROOT" && ./gradlew clean test jar )
rm -f "$EXT"/dexguard-unpacker*.jar
cp -f "$ROOT/build/libs/dexguard-unpacker-$VERSION.jar" "$EXT/"
echo; bash "$HERE/audit.sh"
echo; echo "Restart jadx-gui; the log must show:  dexguard-unpacker v$VERSION | nativeBackend=none ..."
