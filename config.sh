#!/usr/bin/env bash
# ============================================================
# config.sh — Project configuration for Android D-pad Testing Toolkit
#
# Edit these values for your target Android project.
# All other scripts source this file as their single source of truth.
# ============================================================

# --- Device ---
# ADB device serial number. Leave empty to use the default connected device.
DEVICE_ID=""

# --- Target App ---
PACKAGE_NAME="com.example.megalife.f1"
MAIN_ACTIVITY=".MainActivity"            # Relative to PACKAGE_NAME
APK_PATH="./app/build/outputs/apk/debug/app-debug.apk"

# --- Build ---
PROJECT_DIR="."                          # Root of the Android project to build
BUILD_CMD="./gradlew assembleDebug"      # Command to build the target APK

# --- Reports ---
REPORTS_DIR="./reports"
SCREENSHOT_DIR="${REPORTS_DIR}/screenshots"
LOGCAT_DIR="${REPORTS_DIR}/logcat"
REPORT_FILE="${REPORTS_DIR}/report.txt"
APPROVED_FILE="${REPORTS_DIR}/approved.txt"
FIXES_FILE="${REPORTS_DIR}/fixes.txt"

# --- Timeouts (seconds) ---
APP_LAUNCH_WAIT=5                        # Wait after app launch before testing
LOGCAT_WATCH_DURATION=30                 # Max seconds for logcat monitoring
SCREENSHOT_DELAY=2                       # Pause between screenshots

# --- Test Runner ---
TEST_RUNNER="androidx.test.runner.AndroidJUnitRunner"
TEST_PACKAGE="com.appdpadtester"         # Package of the UI Automator test APK
TEST_APK_PATH="./uitests/build/outputs/apk/androidTest/debug/uitests-debug-androidTest.apk"

# --- Screen List ---
# Add activity names (relative to PACKAGE_NAME) to test.
# test.sh will launch each one, take screenshots, and check focus.
SCREENS=(
    ".MainActivity"
    # ".SettingsActivity"
    # ".SearchActivity"
)

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
