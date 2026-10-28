#!/usr/bin/env bash
# find-decryptors.sh - list candidate custom decrypt method signatures in a run's recovered dex, so
# you can arm captureDecryptMethods for THIS app. DexGuard renames per app, so the sigs differ every
# time - this re-derives them from the recovered loader stages instead of hardcoding.
#   usage: find-decryptors.sh [run-dir]        (default: newest run under the data dir)
# Needs baksmali on PATH. Heuristic: methods containing an XOR op with a decryptor-like shape
# (return String/Object/byte[]/char[]/InputStream), excluding ClassLoader plumbing. Verify in jadx.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"; . "$HERE/_env.sh"
command -v baksmali >/dev/null || { echo "need: baksmali on PATH" >&2; exit 1; }
dir="${1:-$(ls -1dt "$DATA"/*/ 2>/dev/null | head -1)}"
[ -n "$dir" ] && [ -d "$dir" ] || { echo "no run dir (pass one, or run a sweep first)" >&2; exit 1; }
dexes=$(ls "$dir"hidden-*.dex 2>/dev/null || true)
[ -n "$dexes" ] || { echo "no hidden-*.dex in $dir" >&2; exit 1; }
echo "== scanning recovered dex in: $dir"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
i=0
for dex in $dexes; do
  i=$((i+1)); out="$tmp/d$i"; baksmali d "$dex" -o "$out" 2>/dev/null || continue
  for f in $(grep -rl 'xor-int' "$out" 2>/dev/null); do
    cls=$(awk '/^\.class/{for(j=1;j<=NF;j++)if($j~/^L.*;$/)print $j}' "$f")
    awk -v C="$cls" '/^\.method/{m=$NF} /xor-int/{if(m){print C"->"m; m=""}}' "$f"
  done
done | sort -u \
  | grep -vE '->(findClass|findLibrary|loadClass|<init>|<clinit>)' \
  | grep -E '\)(Ljava/lang/String;|Ljava/lang/Object;|\[B|\[C|Ljava/io/InputStream;)$' \
  > "$tmp/sigs" || true
n=$(wc -l < "$tmp/sigs" | tr -d ' ')
if [ "$n" -eq 0 ]; then
  echo "no decryptor-shaped xor methods found."
  echo "widen: re-run without the shape filter, or open the loader class (has findClass/loadClass) in jadx."
  exit 0
fi
echo "== $n candidate decryptor method(s) (verify in jadx before trusting):"
sed 's/^/  /' "$tmp/sigs"
echo
echo "arm them with:"
echo "  bash scripts/set-prop.sh captureDecrypt true"
printf "  bash scripts/set-prop.sh captureDecryptMethods '%s'\n" "$(paste -sd, "$tmp/sigs")"
