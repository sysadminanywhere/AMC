#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/classes
find amc-core/src/main/java amc-training/src/main/java amc-benchmark/src/main/java -name '*.java' > build/sources.txt
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -encoding UTF-8 -d build/classes @build/sources.txt
