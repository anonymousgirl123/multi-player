#!/bin/sh
# docker-entrypoint.sh
#
# Routes the container invocation to the correct fat jar based on the first
# argument, forwarding any additional arguments to the JVM.
#
# Supported invocations:
#   same-process [N]        – run N players in one JVM (default: 2)
#   responder               – multi-process responder (listens on TCP port)
#   initiator               – multi-process initiator (connects to responder)
#
# All JVM flags can be prepended via the JAVA_OPTS environment variable:
#   docker run -e JAVA_OPTS="-Xmx256m -Dlog.level=DEBUG" player-messaging same-process

set -e

MODE="${1:-same-process}"

case "$MODE" in
  same-process)
    # Optional second argument: player count (e.g. docker run ... same-process 4)
    PLAYER_COUNT="${2:-}"
    exec java $JAVA_OPTS -jar /app/player-same-process.jar $PLAYER_COUNT
    ;;

  responder | initiator)
    exec java $JAVA_OPTS -jar /app/player-multi-process.jar "$MODE"
    ;;

  *)
    echo "Unknown mode: '$MODE'" >&2
    echo "Usage: docker run player-messaging [same-process [N] | responder | initiator]" >&2
    exit 1
    ;;
esac
