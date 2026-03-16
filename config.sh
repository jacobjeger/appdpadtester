#!/usr/bin/env bash
# ============================================================
# config.sh — Project configuration for Android D-pad Testing Toolkit
#
# ** ZERO CONFIG REQUIRED **
# All values below are auto-detected from your Android project.
# Just run ./test.sh from your project root — it works automatically.
#
# Override any value by uncommenting and setting it below.
# Your values always take priority over auto-detection.
# ============================================================

# --- Device ---
# ADB device serial. Leave empty = auto-detect (uses first connected device).
DEVICE_ID=""

# --- Target App ---
# Leave empty = auto-detected from build.gradle / AndroidManifest.xml.
PACKAGE_NAME=""
MAIN_ACTIVITY=""
APK_PATH=""

# --- Build ---
# Leave empty = auto-detected (looks for gradlew in current or parent dirs).
PROJECT_DIR=""
BUILD_CMD=""

# --- Reports (these defaults are fine for most projects) ---
REPORTS_DIR="./reports"
SCREENSHOT_DIR="${REPORTS_DIR}/screenshots"
LOGCAT_DIR="${REPORTS_DIR}/logcat"
REPORT_FILE="${REPORTS_DIR}/report.txt"
APPROVED_FILE="${REPORTS_DIR}/approved.txt"
FIXES_FILE="${REPORTS_DIR}/fixes.txt"

# --- Timeouts (seconds) ---
APP_LAUNCH_WAIT=5
LOGCAT_WATCH_DURATION=30
SCREENSHOT_DELAY=2

# --- Test Runner (don't change unless you know what you're doing) ---
TEST_RUNNER="androidx.test.runner.AndroidJUnitRunner"
TEST_PACKAGE="com.appdpadtester"
TEST_APK_PATH=""

# --- Screens to test ---
# Leave empty = auto-detected from AndroidManifest.xml.
# Or list specific activities:
#   SCREENS=(".MainActivity" ".SettingsActivity" ".SearchActivity")
SCREENS=()

# ============================================================
# Auto-detection: fills in any blank values from project files
# ============================================================
SCRIPT_DIR_CONFIG="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR_CONFIG/auto_detect.sh"
run_auto_detect

# Set TEST_APK_PATH default after PROJECT_DIR is resolved
if [ -z "$TEST_APK_PATH" ]; then
    TEST_APK_PATH="$SCRIPT_DIR_CONFIG/uitests/build/outputs/apk/androidTest/debug/uitests-debug-androidTest.apk"
fi

# ============================================================
# Helper: wraps adb with optional device selection
# Usage: adb_cmd install foo.apk
# ============================================================
adb_cmd() {
    if [ -n "$DEVICE_ID" ]; then
        adb -s "$DEVICE_ID" "$@"
    else
        adb "$@"
    fi
}
