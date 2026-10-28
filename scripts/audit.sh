#!/usr/bin/env bash
# Read-only CONFOUND AUDIT: what jadx will load + which settings are live. Run before every test.
HERE="$(cd "$(dirname "$0")" && pwd)"; . "$HERE/_env.sh"
warn=0; w() { echo "  !! $*"; warn=$((warn+1)); }

echo "== extensions dir: $EXT"
ls -la --time-style=long-iso "$EXT" 2>/dev/null | sed 's/^/  /' || echo "  (missing)"
core=$(ls "$EXT"/dexguard-unpacker-*.jar 2>/dev/null | grep -vc 'native-unidbg' || true)
uni=$(ls "$EXT"/dexguard-unpacker-native-unidbg*.jar 2>/dev/null | wc -l)
[ "$core" -eq 1 ] || w "expected exactly 1 core jar, found $core"
[ "$uni" -eq 0 ] || echo "  note: $uni unidbg jar(s) present - inert unless nativeBackend=unidbg AND nativeMethods is set"

echo "== live properties: $PROPS"
if [ -f "$PROPS" ]; then grep -vE '^[[:space:]]*(#|$)' "$PROPS" | sed 's/^/  /'; else echo "  (none - all defaults)"; fi
grep -qE '^nativeBackend=unidbg' "$PROPS" 2>/dev/null && w "nativeBackend=unidbg is ON"
[ -f "$DATA/native-replay.properties" ] && w "native-replay.properties exists - this forces a native bridge ACTIVE"

echo "== checkouts"
co=$(ls -d "$HOME"/jadx-emu/jadx-emu-dexguard-unpacker*/ 2>/dev/null || true)
echo "$co" | sed 's/^/  /'
[ "$(echo "$co" | grep -c .)" -le 1 ] || w "multiple checkouts - build and deploy from ONE of them only"

echo
[ "$warn" -eq 0 ] && echo "audit: CLEAN" || echo "audit: $warn warning(s) above"
