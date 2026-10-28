#!/usr/bin/env bash
# crypto-vs-native.sh - triage whether an APK's decryption surface is javax.crypto (Java) or native (.so).
#
#   scripts/crypto-vs-native.sh <app.apk | dir-of-apks> [more...]
#
# HEURISTIC, not proof. It reports which crypto SURFACES are present (string refs in the DEX, symbols in
# the .so); it does NOT prove which one the actual decrypt path uses. Read it as:
#   javax.crypto present -> the unidbg-FREE CryptoCapture path is worth a run (nativeBackend=none)
#   native-only          -> the decryptor is in a .so: use the unidbg backend or on-device hooking; CryptoCapture won't see it
# Confirm the real path in jadx/IDA before committing.
set -u
command -v unzip >/dev/null || { echo "need: unzip" >&2; exit 1; }
STRINGS=$(command -v strings || true)

scan_apk() {
  local apk="$1"
  [ -f "$apk" ] || { echo "skip (not a file): $apk"; return; }
  local tmp; tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' RETURN

  # --- DEX side: every classes*.dex, grep the string pool ---
  unzip -o -q "$apk" 'classes*.dex' -d "$tmp" 2>/dev/null || true
  local dexes; dexes=$(ls "$tmp"/classes*.dex 2>/dev/null || true)
  local jc=0 ks=0 ll=0
  if [ -n "$dexes" ]; then
    jc=$(cat $dexes 2>/dev/null | grep -aoE 'javax/crypto/(Cipher|Mac|spec/[A-Za-z]+)'           | sort -u | wc -l | tr -d ' ')
    ks=$(cat $dexes 2>/dev/null | grep -aoE 'javax/crypto/spec/(SecretKeySpec|IvParameterSpec|GCMParameterSpec)' | sort -u | wc -l | tr -d ' ')
    ll=$(cat $dexes 2>/dev/null | grep -acE 'loadLibrary|Ljava/lang/System;->load'               | tr -d ' ')
  fi

  # --- native side: list .so, grep symbols for well-known crypto ---
  local sos; sos=$(unzip -Z1 "$apk" 2>/dev/null | grep -E '^lib/.*\.so$' || true)
  local nso; nso=$(printf '%s\n' "$sos" | grep -c . || true)
  local ncrypto=0 names="" dp=0
  if [ "$nso" -gt 0 ]; then
    names=$(printf '%s\n' "$sos" | sed 's#.*/##' | sort -u | tr '\n' ' ')
    dp=$(printf '%s\n' "$sos" | grep -ciE 'dexprotector|dexguard|pairip|shield|protect' || true)
    if [ -n "$STRINGS" ]; then
      unzip -o -q "$apk" 'lib/*/*.so' -d "$tmp" 2>/dev/null || true
      ncrypto=$(find "$tmp/lib" -name '*.so' -exec "$STRINGS" -a {} \; 2>/dev/null \
        | grep -acE 'EVP_(Decrypt|Encrypt|Cipher|aes)|AES_(set|decrypt|encrypt)|mbedtls_aes|CCCrypt|libcrypto|BoringSSL|chacha20|RC4_|crypto_' || true)
    fi
  fi

  # --- verdict ---
  echo "== $(basename "$apk")"
  echo "   DEX  javax.crypto refs     : Cipher/Mac/spec=$jc, key/iv specs=$ks"
  echo "   DEX  loadLibrary/System.load: $ll"
  echo "   .so  native libs            : $nso   ${names:+[ $names]}"
  local hint=""; [ -z "$STRINGS" ] && hint="   (install 'strings' for .so symbol scan)"
  echo "   .so  crypto symbols         : $ncrypto$hint"
  [ "$dp" -gt 0 ] && echo "   .so  protector-named libs   : $dp (dexprotector/shield/etc.)"
  local v
  if [ "$jc" -gt 0 ] && { [ "$nso" -eq 0 ] || [ "$ncrypto" -eq 0 ]; }; then
    v="LIKELY javax.crypto  -> run CryptoCapture (nativeBackend=none); watch for crypto.json"
  elif [ "$jc" -gt 0 ]; then
    v="BOTH surfaces present -> try CryptoCapture first; the decrypt MAY still be native"
  elif [ "$ncrypto" -gt 0 ] || { [ "$nso" -gt 0 ] && [ "$ll" -gt 0 ]; }; then
    v="LIKELY native        -> CryptoCapture can't see it; use the unidbg backend or on-device hooking of the .so"
  else
    v="INCONCLUSIVE         -> no obvious crypto surface; inspect in jadx/IDA"
  fi
  echo "   => $v"
}

[ $# -ge 1 ] || { echo "usage: $0 <app.apk | dir-of-apks> [...]" >&2; exit 1; }
for arg in "$@"; do
  if [ -d "$arg" ]; then
    while IFS= read -r a; do scan_apk "$a"; done < <(find "$arg" -type f -iname '*.apk')
  else
    scan_apk "$arg"
  fi
done
echo
echo "Heuristic: string/symbol presence != the decrypt path. 'javax.crypto' => CryptoCapture is worth a run;"
echo "'native' => the decryptor is in a .so (unidbg / on-device hooking). Verify the real path in jadx/IDA."
