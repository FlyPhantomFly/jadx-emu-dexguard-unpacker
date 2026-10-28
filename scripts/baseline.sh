#!/usr/bin/env bash
# One command to a KNOWN-GOOD state: clean everything, reset properties to the baseline, deploy core only.
# usage: baseline.sh 1.0.6
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"; . "$HERE/_env.sh"
VER="${1:?usage: baseline.sh <version>}"
bash "$HERE/clean.sh"
mkdir -p "$DATA"
printf 'telemetry=true\nnativeBackend=none\n' > "$PROPS"
rm -f "$DATA/native-replay.properties"
bash "$HERE/deploy.sh" "$VER"
