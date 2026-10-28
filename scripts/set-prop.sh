#!/usr/bin/env bash
# usage: set-prop.sh <key> <value>      set-prop.sh <key> --unset
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"; . "$HERE/_env.sh"
key="${1:?usage: set-prop.sh <key> <value|--unset>}"; val="${2:?usage: set-prop.sh <key> <value|--unset>}"
mkdir -p "$DATA"; touch "$PROPS"
sed -i "/^${key}=/d" "$PROPS"
if [ "$val" = "--unset" ]; then echo "$key: unset"; else echo "${key}=${val}" >> "$PROPS"; grep -E "^${key}=" "$PROPS"; fi
