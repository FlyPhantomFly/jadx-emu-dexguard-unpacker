#!/usr/bin/env bash
# 10-second post-deploy smoke test through the REAL jadx + plugin + extension - no APK needed.
#
# fixtures/smoke.dex is a tiny "protected app": com.smoke.Loader's static initializer hands a task to an Executor (which the
# engine's host policy denies), and the task AES-decrypts fixtures/payload.dex. PASS means the whole chain worked: the engine
# loaded, the extension initialized, the inline scheduler ran the task, CryptoCapture caught the decrypt, and the recovered
# dex is BYTE-IDENTICAL to the payload. It does not replace a real-target run (FaceTec: EXPECT=4 results.sh).
#   usage: smoke.sh            (restores your dexguard-unpacker.properties afterwards)
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"; . "$HERE/_env.sh"
FIX="$HERE/../fixtures"
[ -f "$FIX/smoke.dex" ] && [ -f "$FIX/payload.dex" ] || { echo "missing fixtures in $FIX" >&2; exit 2; }
command -v jadx >/dev/null || { echo "jadx not on PATH" >&2; exit 2; }
want=$(sha256sum "$FIX/payload.dex" | cut -d' ' -f1); tag="hidden-${want:0:12}.dex"

mkdir -p "$DATA"
bak=$(mktemp); had=0; if [ -f "$PROPS" ]; then cp "$PROPS" "$bak"; had=1; fi
restore() { if [ "$had" -eq 1 ]; then cp "$bak" "$PROPS"; else rm -f "$PROPS"; fi; rm -f "$bak"; }
trap restore EXIT
for d in "$DATA"/*/; do [ -f "${d}$tag" ] && rm -rf "$d"; done        # no stale pass from an earlier smoke run
printf 'autoUnpack=true\ntelemetry=true\nnativeBackend=none\nsweepClasses=com.smoke.Loader\n' > "$PROPS"

out=$(mktemp -d); log="$out/jadx.log"
echo "== running jadx on fixtures/smoke.dex"
timeout 300 jadx -v -d "$out/src" "$FIX/smoke.dex" > "$log" 2>&1 || true
grep -E 'jadx-emu [^ ]+ loaded|dexguard-unpacker v' "$log" | sed 's/^/  /'

got=""; for d in "$DATA"/*/; do [ -f "${d}$tag" ] && got="${d}$tag"; done
if [ -n "$got" ] && cmp -s "$got" "$FIX/payload.dex"; then
  c() { python3 -c "import json,sys; print(json.load(open('${got%/*}/telemetry.json'))['counters'].get('$1',0))" 2>/dev/null || echo "?"; }
  echo "  recovered: $got"
  echo "  counters : sched.run=$(c sched.run)  crypto.fedDex=$(c crypto.fedDex)  dex.loaded=$(c dex.loaded)  engine.HOST_DENIED=$(c engine.HOST_DENIED)"
  echo "SMOKE: PASS (recovered dex is byte-identical to the payload)"
else
  echo "SMOKE: FAIL - no byte-identical recovery. Last relevant log lines:"
  grep -E 'ERROR|Exception|NoClassDef|NoSuchMethod|failed to init|extension\(s\) found|auto-sweep' "$log" | tail -12 | sed 's/^/  /'
  if grep -q 'NoSuchMethodError' "$log"; then
    echo "  hint: NoSuchMethodError at init = the extension and engine are on different lines (2.x needs jadx-emu 0.1.0-beta.6-fp1,"
    echo "        1.x needs 0.1.0-beta.6). See TESTING.md 'Engine prerequisite'; run scripts/deploy-engine.sh on the matching engine."
  fi
  echo "  full log: $log"
  exit 1
fi
