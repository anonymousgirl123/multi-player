#!/usr/bin/env bash
# =============================================================================
# run-multi-process.sh
#
# Builds the project (if needed) and launches the multi-process scenario.
# Each player runs in its own JVM process (different PID) and communicates
# with the other player over a local TCP socket on port 9090.
#
# Requirements: Java 21+ must be on $PATH.
#               Maven is NOT required — the included ./mvnw wrapper downloads it.
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$SCRIPT_DIR/target/player-multi-process.jar"

# Build only when the jar is missing or sources are newer.
# Uses ./mvnw (Maven Wrapper) — no local Maven installation required.
if [[ ! -f "$JAR" ]]; then
    echo ">>> Building project …"
    "$SCRIPT_DIR/mvnw" -f "$SCRIPT_DIR/pom.xml" package -q --no-transfer-progress
fi

echo ">>> Starting PlayerB (responder) in the background …"
java -jar "$JAR" responder &
RESPONDER_PID=$!

echo ">>> Starting PlayerA (initiator) …"
java -jar "$JAR" initiator
INITIATOR_EXIT=$?

# Wait for the responder process to finish cleanly.
wait "$RESPONDER_PID"
RESPONDER_EXIT=$?

echo ""
echo ">>> Both players have finished."
echo "    Initiator exit code : $INITIATOR_EXIT"
echo "    Responder exit code : $RESPONDER_EXIT"
