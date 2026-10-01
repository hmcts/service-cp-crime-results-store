#!/usr/bin/env sh
# Add any startup requirements in here
logmsg() {
    SCRIPTNAME=$(basename $0)
    echo "$SCRIPTNAME : $1"
}

export LOCALJARFILE=$(ls ./build/libs/*.jar 2>/dev/null | grep -v 'plain' | head -n1)
export DOCKERJARFILE=$(ls /app/*.jar 2>/dev/null | grep -v 'plain' | head -n1)

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
fi
