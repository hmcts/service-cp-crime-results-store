#!/usr/bin/env sh
# Add any startup requirements in here
logmsg() {
    SCRIPTNAME=$(basename $0)
    echo "$SCRIPTNAME : $1"
}

# The one application jar in a directory; refuses (exit 1) when there is more than one, so a stale jar
# copied in beside the current one can never be the one that runs.
onejar() {
    COUNT=$(ls "$1"/*.jar 2>/dev/null | grep -vc 'plain')
    if [ "$COUNT" -gt 1 ]; then
        logmsg "ERROR - $COUNT jarfiles found in $1, expected one. Unable to start application" >&2
        exit 1
    fi
    ls "$1"/*.jar 2>/dev/null | grep -v 'plain' | head -n1
}

LOCALJARFILE=$(onejar ./build/libs) || exit 1
DOCKERJARFILE=$(onejar /app) || exit 1
export LOCALJARFILE DOCKERJARFILE

# exec, so the JVM is PID 1 and receives the container's SIGTERM directly: without it the shell
# dies and the JVM is killed a grace period later, so Spring's graceful shutdown never runs.
if [ -f "$DOCKERJARFILE" ]; then
    logmsg "Running docker java jarfile $DOCKERJARFILE"
    exec java -jar "$DOCKERJARFILE"
elif [ -f "$LOCALJARFILE" ]; then
    logmsg "Running local java jarfile $LOCALJARFILE"
    exec java -jar "$LOCALJARFILE"
else
    logmsg "ERROR - No jarfile found. Unable to start application"
    exit 1
fi
