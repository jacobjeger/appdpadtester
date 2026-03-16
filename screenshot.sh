#!/usr/bin/env bash
# ============================================================
# screenshot.sh — Capture a screenshot from the connected device
#
# Usage: ./screenshot.sh [screen_name]
#
# Takes a screenshot via ADB, pulls it to ./reports/screenshots/
# with a timestamped filename: YYYYMMDD_HHMMSS_screenname.png
# ============================================================

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/config.sh"

SCREEN_NAME="${1:-unknown}"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
FILENAME="${TIMESTAMP}_${SCREEN_NAME}.png"
DEVICE_PATH="/sdcard/screenshot_tmp.png"
LOCAL_PATH="${SCREENSHOT_DIR}/${FILENAME}"

mkdir -p "$SCREENSHOT_DIR"

# Capture screenshot on device
if ! adb_cmd shell screencap -p "$DEVICE_PATH"; then
    echo "[screenshot] ERROR: Failed to capture screenshot on device"
    exit 1
fi

# Pull to local machine
if ! adb_cmd pull "$DEVICE_PATH" "$LOCAL_PATH" > /dev/null 2>&1; then
    echo "[screenshot] ERROR: Failed to pull screenshot from device"
    exit 1
fi

# Clean up device temp file
adb_cmd shell rm -f "$DEVICE_PATH" 2>/dev/null || true

echo "[screenshot] Saved: $LOCAL_PATH"
