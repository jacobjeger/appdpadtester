#!/usr/bin/env bash
# ============================================================
# setup.sh — Interactive setup wizard for the D-pad testing toolkit
#
# Runs auto-detection, shows what was found, and lets you
# confirm or override each value. Saves results to config.sh.
#
# Usage: ./setup.sh           (interactive setup)
#        ./setup.sh --check   (just show detected values, don't save)
# ============================================================

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# ---- Colors ----
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

echo ""
echo -e "${BOLD}=== Android D-pad Testing Toolkit — Setup ===${NC}"
echo ""

# Source auto_detect directly (not config.sh, to avoid overwriting)
PROJECT_DIR=""
PACKAGE_NAME=""
MAIN_ACTIVITY=""
APK_PATH=""
DEVICE_ID=""
BUILD_CMD=""
SCREENS=()

source "$SCRIPT_DIR/auto_detect.sh"

# Run detection quietly, then show results ourselves
PROJECT_DIR=$(auto_detect_project_dir)
PACKAGE_NAME=$(auto_detect_package_name "$PROJECT_DIR")
MAIN_ACTIVITY=$(auto_detect_main_activity "$PROJECT_DIR")
APK_PATH=$(auto_detect_apk_path "$PROJECT_DIR")
DEVICE_ID=$(auto_detect_device)
BUILD_CMD="./gradlew assembleDebug"

# Detect screens
detected_screens=$(auto_detect_screens "$PROJECT_DIR")
if [ -n "$detected_screens" ]; then
    while IFS= read -r s; do
        SCREENS+=("$s")
    done <<< "$detected_screens"
fi

# ---- Show detected values ----
echo -e "${BOLD}Detected settings:${NC}"
echo ""
show_value() {
    local label="$1"
    local value="$2"
    if [ -n "$value" ]; then
        echo -e "  ${GREEN}✓${NC} $label: ${CYAN}$value${NC}"
    else
        echo -e "  ${RED}✗${NC} $label: ${RED}not detected${NC}"
    fi
}

show_value "Project root" "$PROJECT_DIR"
show_value "Package name" "$PACKAGE_NAME"
show_value "Main activity" "$MAIN_ACTIVITY"
show_value "APK path" "$APK_PATH"
show_value "Device" "$DEVICE_ID"
show_value "Build command" "$BUILD_CMD"

if [ ${#SCREENS[@]} -gt 0 ]; then
    echo -e "  ${GREEN}✓${NC} Screens (${#SCREENS[@]}):"
    for s in "${SCREENS[@]}"; do
        echo -e "      ${CYAN}$s${NC}"
    done
else
    echo -e "  ${YELLOW}!${NC} Screens: none detected (will use main activity)"
fi

# If --check mode, just show and exit
if [ "${1:-}" = "--check" ]; then
    echo ""
    echo -e "${BOLD}This is check-only mode. Run ./setup.sh without --check to save.${NC}"
    exit 0
fi

# ---- Interactive confirmation ----
echo ""
echo -e "${BOLD}Press Enter to accept each value, or type a new value to override:${NC}"
echo ""

prompt_value() {
    local label="$1"
    local current="$2"
    local varname="$3"

    echo -en "  $label [${CYAN}${current:-empty}${NC}]: "
    read -r input
    if [ -n "$input" ]; then
        eval "$varname=\"$input\""
    else
        eval "$varname=\"$current\""
    fi
}

prompt_value "Package name" "$PACKAGE_NAME" "PACKAGE_NAME"
prompt_value "Main activity" "${MAIN_ACTIVITY:-.MainActivity}" "MAIN_ACTIVITY"
prompt_value "APK path" "$APK_PATH" "APK_PATH"
prompt_value "Device ID (empty=auto)" "$DEVICE_ID" "DEVICE_ID"
prompt_value "Build command" "$BUILD_CMD" "BUILD_CMD"

# ---- Save to config.sh ----
echo ""
echo -e "${BOLD}Saving to config.sh...${NC}"

# Build SCREENS string for the config file
SCREENS_STR=""
for s in "${SCREENS[@]}"; do
    SCREENS_STR+="    \"$s\""$'\n'
done

cat > "$SCRIPT_DIR/config.sh" << CONFIGEOF
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
DEVICE_ID="${DEVICE_ID}"

# --- Target App ---
PACKAGE_NAME="${PACKAGE_NAME}"
MAIN_ACTIVITY="${MAIN_ACTIVITY}"
APK_PATH="${APK_PATH}"

# --- Build ---
PROJECT_DIR="${PROJECT_DIR}"
BUILD_CMD="${BUILD_CMD}"

# --- Reports ---
REPORTS_DIR="./reports"
SCREENSHOT_DIR="\${REPORTS_DIR}/screenshots"
LOGCAT_DIR="\${REPORTS_DIR}/logcat"
REPORT_FILE="\${REPORTS_DIR}/report.txt"
APPROVED_FILE="\${REPORTS_DIR}/approved.txt"
FIXES_FILE="\${REPORTS_DIR}/fixes.txt"

# --- Timeouts (seconds) ---
APP_LAUNCH_WAIT=5
LOGCAT_WATCH_DURATION=30
SCREENSHOT_DELAY=2

# --- Test Runner ---
TEST_RUNNER="androidx.test.runner.AndroidJUnitRunner"
TEST_PACKAGE="com.appdpadtester"
TEST_APK_PATH=""

# --- Screens to test ---
SCREENS=(
${SCREENS_STR})

# ============================================================
# Auto-detection: fills in any blank values from project files
# ============================================================
SCRIPT_DIR_CONFIG="\$(cd "\$(dirname "\${BASH_SOURCE[0]}")" && pwd)"
source "\$SCRIPT_DIR_CONFIG/auto_detect.sh"
run_auto_detect

# Set TEST_APK_PATH default after PROJECT_DIR is resolved
if [ -z "\$TEST_APK_PATH" ]; then
    TEST_APK_PATH="\$SCRIPT_DIR_CONFIG/uitests/build/outputs/apk/androidTest/debug/uitests-debug-androidTest.apk"
fi

# ============================================================
# Helper: wraps adb with optional device selection
# ============================================================
adb_cmd() {
    if [ -n "\$DEVICE_ID" ]; then
        adb -s "\$DEVICE_ID" "\$@"
    else
        adb "\$@"
    fi
}
CONFIGEOF

chmod +x "$SCRIPT_DIR/config.sh"

echo -e "${GREEN}✓${NC} Config saved!"
echo ""
echo -e "You're ready to go. Run: ${BOLD}./test.sh${NC}"
echo ""
