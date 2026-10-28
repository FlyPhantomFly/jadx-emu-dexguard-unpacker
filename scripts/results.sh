#!/usr/bin/env bash
# Summarise the newest run. usage: [EXPECT=4] results.sh [saved-jadx-gui-log]
HERE="$(cd "$(dirname "$0")" && pwd)"; . "$HERE/_env.sh"
dir=$(ls -1dt "$DATA"/*/ 2>/dev/null | head -1)
[ -n "$dir" ] || { echo "no run output under $DATA"; exit 1; }
echo "== newest run: $dir"
n=$(ls "$dir"hidden-*.dex 2>/dev/null | wc -l)
ls -la "$dir"hidden-*.dex 2>/dev/null | awk '{print "  "$5" B\t"$9}'
echo "  hidden dex on disk: $n"
python3 - "$dir" << 'PY'
import json, os, sys
d = sys.argv[1]
try:
    r = json.load(open(os.path.join(d, "report.json")))
    print("  report.json: recoveredCount=%s totalClassDefs=%s" % (r.get("recoveredCount"), sum(x.get("classDefs", 0) for x in r.get("dex", []))))
except Exception as e:
    print("  report.json: unreadable (%s)" % e)
try:
    t = json.load(open(os.path.join(d, "telemetry.json")))
    c = t.get("counters", {})
    print("  telemetry: dex.loaded=%s unresolved=%s" % (c.get("dex.loaded"), t.get("unresolved")))
    # --- which path produced the dex: crypto (#2) vs custom decrypt (#2) vs loader (#7) ---
    cr = {k: v for k, v in c.items() if k.startswith("crypto.")}
    sc = {k: v for k, v in c.items() if k.startswith("sched.")}
    dc = {k: v for k, v in c.items() if k.startswith("decrypt.")}
    print("  path counters: crypto=%s sched=%s decrypt=%s" % (cr or "none", sc or "none", dc or "none"))
    cj = os.path.exists(os.path.join(d, "crypto", "crypto.json"))
    dj = os.path.exists(os.path.join(d, "decrypt", "decrypt-capture.json"))
    print("  artifacts: crypto.json=%s decrypt-capture.json=%s" % (cj, dj))
    if cr.get("crypto.fedDex", 0):       v = "javax.crypto fed dex (CryptoCapture / #2 after-hook fired)"
    elif dc.get("decrypt.captured", 0):  v = "custom decrypt observed (DecryptCapture / #2 after-hook fired)"
    elif c.get("dex.loaded", 0):         v = "loader path (InMemoryDexClassLoader/DexFile / #7); no javax.crypto on the dex path"
    else:                                v = "no dex recovered"
    print("  => path: %s" % v)
    lim = [e for e in t.get("events", []) if e.startswith("limit")]
    print("  telemetry: LIMIT HITS = %d" % len(lim))
    for e in lim: print("    " + e)
except Exception as e:
    print("  telemetry.json: n/a (%s)" % e)
PY
if [ -n "${1:-}" ] && [ -f "$1" ]; then
  echo "== log: $1"
  grep -E 'dexguard-unpacker v|running from|native bridge ACTIVE' "$1" | sed 's/^/  /'
  grep -E 'auto-sweep \[|-> [0-9]+ new dex|HIT the|INCOMPLETE|hidden dex file' "$1" | sed 's/^/  /'
fi
if [ -n "${EXPECT:-}" ]; then
  if [ "$n" -eq "$EXPECT" ]; then echo "RESULT: PASS ($n dex)"; else echo "RESULT: FAIL (got $n, expected $EXPECT)"; exit 2; fi
fi
