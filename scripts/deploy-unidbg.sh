#!/usr/bin/env bash
# Build + install the OPTIONAL unidbg backend jar next to the core jar (replaces older unidbg jars only).
# usage: deploy-unidbg.sh 1.0.6      (stays inert until nativeBackend=unidbg AND nativeMethods is set)
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"; . "$HERE/_env.sh"
ROOT="$(cd "$HERE/.." && pwd)"
export VERSION="${1:?usage: deploy-unidbg.sh <version>}"
( cd "$ROOT" && ./gradlew clean jar )        # core jar supplies the SPI on the native compile classpath
cd "$ROOT/native-unidbg"
[ -x ./gradlew ] || { cp ../gradlew ../gradlew.bat . && cp -r ../gradle . && chmod +x gradlew; }
./gradlew clean jar
rm -f "$EXT"/dexguard-unpacker-native-unidbg*.jar
cp -f "build/libs/dexguard-unpacker-native-unidbg-$VERSION.jar" "$EXT/"
ls -la "$EXT"/dexguard-unpacker-native-unidbg*.jar
echo "(a fat jar is several MB; ~15 KB means unidbg was NOT bundled)"
echo; bash "$HERE/audit.sh"
