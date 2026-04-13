#!/usr/bin/env bash
# =============================================================================
# run-same-process.sh
#
# Builds the project (if needed) and launches the same-process scenario.
# Both players run as virtual threads inside a single JVM process.
#
# Requirements: Java 21+ must be on $PATH.
#               Maven is NOT required — the included ./mvnw wrapper downloads it.
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$SCRIPT_DIR/target/player-same-process.jar"

# Build only when the jar is missing or sources are newer.
# Uses ./mvnw (Maven Wrapper) — no local Maven installation required.
if [[ ! -f "$JAR" ]]; then
    echo ">>> Building project …"
    "$SCRIPT_DIR/mvnw" -f "$SCRIPT_DIR/pom.xml" package -q --no-transfer-progress
fi

echo ">>> Starting same-process conversation …"
java -jar "$JAR"
