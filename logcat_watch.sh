#!/usr/bin/env bash
# ============================================================
# logcat_watch.sh — Background logcat monitor for the target app
#
# Filters logcat to the app package and captures:
#   - Fatal exceptions
#   - ANRs (Application Not Responding)
#   - ERROR-level log entries
#
# Usage:
#   Standalone:   ./logcat_watch.sh          (blocks, streams to terminal)
#   Background:   LOGCAT_BACKGROUND=1 ./logcat_watch.sh  (returns immediately)
#
# When backgrounded, the PID is saved to $LOGCAT_DIR/.logcat_pid
# so the calling script (test.sh) can kill it later.
# ============================================================

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/config.sh"

TIMESTAMP=$(date +%Y%m%d_%H%M%S)
LOGCAT_OUTPUT="${LOGCAT_DIR}/logcat_${TIMESTAMP}.log"
mkdir -p "$LOGCAT_DIR"

echo "[logcat_watch] Starting logcat monitor for: $PACKAGE_NAME"
echo "[logcat_watch] Output file: $LOGCAT_OUTPUT"

# Clear existing logcat buffer
adb_cmd logcat -c 2>/dev/null || true

# Try PID-based filtering first (more precise), fall back to grep
start_logcat() {
    local app_pid
    app_pid=$(adb_cmd shell pidof "$PACKAGE_NAME" 2>/dev/null || echo "")

    if [ -n "$app_pid" ]; then
        echo "[logcat_watch] Filtering by PID: $app_pid"
        adb_cmd logcat --pid="$app_pid" "*:W" 2>/dev/null | \
            grep -iE "(fatal|exception|anr|error|crash|FATAL EXCEPTION|ANR in)" | \
            while IFS= read -r line; do
                echo "$line" | tee -a "$LOGCAT_OUTPUT"
            done &
    else
        echo "[logcat_watch] App not running yet — using package name filter"
        adb_cmd logcat "*:E" 2>/dev/null | \
            grep -i "$PACKAGE_NAME" | \
            while IFS= read -r line; do
                echo "$line" | tee -a "$LOGCAT_OUTPUT"
            done &
    fi

    LOGCAT_PID=$!
    echo "$LOGCAT_PID" > "${LOGCAT_DIR}/.logcat_pid"
    echo "[logcat_watch] Monitoring started (PID: $LOGCAT_PID)"
}

start_logcat

# If run as background helper, return immediately; otherwise block
if [ "${LOGCAT_BACKGROUND:-0}" = "1" ]; then
    echo "[logcat_watch] Running in background mode"
    exit 0
else
    echo "[logcat_watch] Streaming to terminal. Press Ctrl+C to stop."
    wait "$LOGCAT_PID" 2>/dev/null || true
fi
