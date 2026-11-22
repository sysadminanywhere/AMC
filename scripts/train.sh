#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
"$root/scripts/build.sh"
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$root/build/classes" dev.amc.training.Train "$@"
