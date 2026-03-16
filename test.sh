#!/usr/bin/env bash
# ============================================================
# test.sh — Full Android test cycle orchestrator
#
# Runs the complete test pipeline:
#   1. Build APK
#   2. Install on device
#   3. Launch app
#   4. Monitor logcat for crashes/ANRs
#   5. Run UI Automator D-pad & focus tests
#   6. Take screenshots of every screen
#   7. Generate issue report
#   8. Prompt user for issues to fix
#   9. Save approved issues and exit
#
# Usage: ./test.sh       (auto-detects everything from your Android project)
#        ./test.sh -y    (skip confirmation prompt)
# ============================================================

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/config.sh"

# ---- Colors for terminal output ----
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m' # No Color

# ---- Helpers ----
log_info()  { echo -e "${CYAN}[INFO]${NC} $*"; }
log_ok()    { echo -e "${GREEN}[OK]${NC} $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*"; }
log_step()  { echo -e "\n${BOLD}=== $* ===${NC}"; }

# ============================================================
# Step 0: Show detected config and confirm
# ============================================================
echo ""
echo -e "${BOLD}=== Android D-pad Testing Toolkit ===${NC}"
echo ""
echo -e "  Package:    ${CYAN}${PACKAGE_NAME:-NOT DETECTED}${NC}"
echo -e "  Activity:   ${CYAN}${MAIN_ACTIVITY:-NOT DETECTED}${NC}"
echo -e "  APK Path:   ${CYAN}${APK_PATH:-NOT DETECTED}${NC}"
echo -e "  Device:     ${CYAN}${DEVICE_ID:-auto (first connected)}${NC}"
echo -e "  Project:    ${CYAN}${PROJECT_DIR:-.}${NC}"
echo -e "  Screens:    ${CYAN}${SCREENS[*]:-none}${NC}"
echo ""

# Bail if critical values are missing
if [ -z "$PACKAGE_NAME" ]; then
    log_error "Could not detect PACKAGE_NAME."
    log_error "Make sure you're running from an Android project root,"
    log_error "or set PACKAGE_NAME in config.sh manually."
    exit 1
fi

# Skip confirmation with -y flag
if [ "${1:-}" != "-y" ]; then
    echo -e "${BOLD}Press Enter to start testing, or Ctrl+C to edit config.sh${NC}"
    read -r
fi

# ---- Issue tracking ----
ISSUE_COUNT=0
ISSUES_TMP="${REPORTS_DIR}/.issues_tmp"

add_issue() {
    local type="$1"
    local screen="$2"
    local description="$3"
    local severity="$4"

    ISSUE_COUNT=$((ISSUE_COUNT + 1))
    cat >> "$ISSUES_TMP" <<EOF

[Issue #${ISSUE_COUNT}]
Type: ${type}
Screen: ${screen}
Description: ${description}
Severity: ${severity}
---
EOF
}

# ---- Cleanup trap ----
cleanup() {
    # Kill logcat watcher if running
    if [ -f "${LOGCAT_DIR}/.logcat_pid" ]; then
        local pid
        pid=$(cat "${LOGCAT_DIR}/.logcat_pid" 2>/dev/null || echo "")
        if [ -n "$pid" ]; then
            kill "$pid" 2>/dev/null || true
            rm -f "${LOGCAT_DIR}/.logcat_pid"
        fi
    fi
}
trap cleanup EXIT

# ============================================================
# Step 1: Validate prerequisites
# ============================================================
log_step "Step 1: Validating prerequisites"

if ! command -v adb &>/dev/null; then
    log_error "adb not found on PATH. Install Android SDK platform-tools."
    exit 1
fi
log_ok "adb found: $(command -v adb)"

# Check device connectivity
DEVICE_LIST=$(adb_cmd devices 2>/dev/null | tail -n +2 | grep -v "^$" || true)
if [ -z "$DEVICE_LIST" ]; then
    log_error "No Android devices connected. Connect a device or start an emulator."
    exit 1
fi

if [ -n "$DEVICE_ID" ]; then
    if ! echo "$DEVICE_LIST" | grep -q "$DEVICE_ID"; then
        log_error "Device '$DEVICE_ID' not found. Connected devices:"
        echo "$DEVICE_LIST"
        exit 1
    fi
    log_ok "Device found: $DEVICE_ID"
else
    log_ok "Using default device"
fi

# Check gradlew exists in the target project
if [ ! -f "${PROJECT_DIR}/gradlew" ]; then
    log_error "gradlew not found at ${PROJECT_DIR}/gradlew"
    log_error "Run this from your Android project root, or set PROJECT_DIR in config.sh."
    exit 1
fi
log_ok "gradlew found"

# ============================================================
# Step 2: Prepare report directories
# ============================================================
log_step "Step 2: Preparing report directories"

mkdir -p "$REPORTS_DIR" "$SCREENSHOT_DIR" "$LOGCAT_DIR"
rm -f "$REPORT_FILE" "$APPROVED_FILE" "$ISSUES_TMP"
touch "$ISSUES_TMP"
log_ok "Report directories ready: $REPORTS_DIR"

# ============================================================
# Step 3: Build APK
# ============================================================
log_step "Step 3: Building APK"

log_info "Running: $BUILD_CMD"
if ! (cd "$PROJECT_DIR" && eval "$BUILD_CMD"); then
    log_error "Build failed. Fix build errors and try again."
    exit 1
fi

if [ ! -f "$APK_PATH" ]; then
    log_error "APK not found at $APK_PATH after build."
    log_error "Check APK_PATH in config.sh."
    exit 1
fi
log_ok "APK built: $APK_PATH"

# ============================================================
# Step 4: Install APK on device
# ============================================================
log_step "Step 4: Installing APK on device"

if ! adb_cmd install -r "$APK_PATH"; then
    log_error "Failed to install APK on device."
    exit 1
fi
log_ok "APK installed: $PACKAGE_NAME"

# ============================================================
# Step 5: Start logcat monitoring (background)
# ============================================================
log_step "Step 5: Starting logcat monitor"

LOGCAT_BACKGROUND=1 "$SCRIPT_DIR/logcat_watch.sh" &
sleep 1
log_ok "Logcat monitor running in background"

# ============================================================
# Step 6: Launch the app
# ============================================================
log_step "Step 6: Launching app"

FULL_ACTIVITY="${PACKAGE_NAME}/${PACKAGE_NAME}${MAIN_ACTIVITY}"
log_info "Starting: $FULL_ACTIVITY"

adb_cmd shell am start -n "$FULL_ACTIVITY" -W 2>/dev/null || \
    adb_cmd shell am start -n "$FULL_ACTIVITY"

log_info "Waiting ${APP_LAUNCH_WAIT}s for app to initialize..."
sleep "$APP_LAUNCH_WAIT"

# Take launch screenshot
"$SCRIPT_DIR/screenshot.sh" "01_launch" || log_warn "Screenshot failed"
log_ok "App launched"

# ============================================================
# Step 7: Take screenshots of each configured screen
# ============================================================
log_step "Step 7: Capturing screenshots of all screens"

SCREEN_INDEX=2
SCREEN_TOTAL=${#SCREENS[@]}
SCREEN_COUNTER=0
for screen in "${SCREENS[@]}"; do
    SCREEN_COUNTER=$((SCREEN_COUNTER + 1))
    SCREEN_LABEL=$(echo "$screen" | sed 's/^\.//; s/Activity$//')
    FULL_SCREEN="${PACKAGE_NAME}/${PACKAGE_NAME}${screen}"

    log_info "[${SCREEN_COUNTER}/${SCREEN_TOTAL}] Navigating to: $SCREEN_LABEL"
    adb_cmd shell am start -n "$FULL_SCREEN" 2>/dev/null || {
        log_warn "Could not start activity: $FULL_SCREEN"
        continue
    }
    sleep "$SCREENSHOT_DELAY"

    PADDED_INDEX=$(printf "%02d" $SCREEN_INDEX)
    "$SCRIPT_DIR/screenshot.sh" "${PADDED_INDEX}_${SCREEN_LABEL}" || log_warn "Screenshot failed for $SCREEN_LABEL"
    SCREEN_INDEX=$((SCREEN_INDEX + 1))
done
log_ok "Screenshots captured: $SCREENSHOT_DIR"

# ============================================================
# Step 8: Build and install UI Automator test APK
# ============================================================
log_step "Step 8: Building UI Automator tests"

UITEST_DIR="$SCRIPT_DIR/uitests"
if [ -f "$UITEST_DIR/gradlew" ]; then
    log_info "Building test APK..."
    if (cd "$UITEST_DIR" && ./gradlew assembleAndroidTest -PtargetPackage="$PACKAGE_NAME" 2>&1); then
        # Install test APK
        if [ -f "$TEST_APK_PATH" ]; then
            adb_cmd install -r "$TEST_APK_PATH" || log_warn "Failed to install test APK"
            log_ok "Test APK installed"
        else
            log_warn "Test APK not found at $TEST_APK_PATH"
        fi
    else
        log_warn "Test APK build failed — skipping UI Automator tests"
    fi
else
    log_warn "uitests/gradlew not found — skipping UI Automator tests"
fi

# ============================================================
# Step 9: Run UI Automator tests
# ============================================================
log_step "Step 9: Running UI Automator tests"

TEST_OUTPUT="${REPORTS_DIR}/.test_output.txt"

if adb_cmd shell pm list packages | grep -q "$TEST_PACKAGE"; then
    log_info "Running D-pad navigation and focus tests..."

    # Run tests to file, with a background watcher that prints progress in real-time.
    # adb shell output contains \r (carriage returns) which we strip with tr.
    rm -f "$TEST_OUTPUT"
    touch "$TEST_OUTPUT"

    # Background progress watcher — tails the output file and prints test progress
    (
        _cur_test="" _cur_class="" _cur_num="" _total=""
        tail -f "$TEST_OUTPUT" 2>/dev/null | tr -d '\r' | while IFS= read -r line; do
            case "$line" in
                *"INSTRUMENTATION_STATUS: numtests="*)
                    _total="${line##*numtests=}" ;;
                *"INSTRUMENTATION_STATUS: current="*)
                    _cur_num="${line##*current=}" ;;
                *"INSTRUMENTATION_STATUS: class="*)
                    _cur_class="${line##*class=}"
                    _cur_class="${_cur_class##*.}" ;;
                *"INSTRUMENTATION_STATUS: test="*)
                    _cur_test="${line##*test=}" ;;
                *"INSTRUMENTATION_STATUS_CODE: 1"*)
                    if [ -n "$_cur_test" ] && [ -n "$_total" ]; then
                        printf "${CYAN}  [%s/%s]${NC} %s.%s ... " \
                            "$_cur_num" "$_total" "$_cur_class" "$_cur_test"
                    fi ;;
                *"INSTRUMENTATION_STATUS_CODE: 0"*)
                    [ -n "$_cur_test" ] && printf "${GREEN}PASS${NC}\n" ;;
                *INSTRUMENTATION_STATUS_CODE:\ -*)
                    [ -n "$_cur_test" ] && printf "${RED}FAIL${NC}\n" ;;
                *"INSTRUMENTATION_RESULT"*|*"Process crashed"*)
                    break ;;
            esac
        done
    ) &
    _watcher_pid=$!

    # Run the actual tests (output goes to file)
    adb_cmd shell am instrument -w \
        -e targetPackage "$PACKAGE_NAME" \
        -e class "${TEST_PACKAGE}.DpadNavTest,${TEST_PACKAGE}.FocusTest" \
        "${TEST_PACKAGE}.test/${TEST_RUNNER}" \
        > "$TEST_OUTPUT" 2>&1 || true

    # Give the watcher a moment to finish reading, then kill it
    sleep 1
    kill "$_watcher_pid" 2>/dev/null || true
    wait "$_watcher_pid" 2>/dev/null || true

    echo ""

    # Strip \r from the saved output so grep/sed work correctly
    tr -d '\r' < "$TEST_OUTPUT" > "${TEST_OUTPUT}.clean" && mv "${TEST_OUTPUT}.clean" "$TEST_OUTPUT"

    # Count passes and failures
    _pass_count=$(grep -c "INSTRUMENTATION_STATUS_CODE: 0" "$TEST_OUTPUT" 2>/dev/null || echo "0")
    _fail_count=$(grep -c "INSTRUMENTATION_STATUS_CODE: -" "$TEST_OUTPUT" 2>/dev/null || echo "0")
    _total_run=$((_pass_count + _fail_count))

    # Extract failed test details and create issues
    _current_class=""
    _current_test=""

    while IFS= read -r line; do
        case "$line" in
            *"INSTRUMENTATION_STATUS: class="*)
                _current_class="${line##*class=}"
                _current_class="${_current_class##*.}"
                ;;
            *"INSTRUMENTATION_STATUS: test="*)
                _current_test="${line##*test=}"
                ;;
            *INSTRUMENTATION_STATUS_CODE:\ -*)
                # This test failed — record an issue
                local_test="${_current_class}.${_current_test}"
                case "$_current_test" in
                    *dpad*|*Dpad*|*navigation*|*Navigation*|*reachable*|*Reachable*|*grid*|*Grid*)
                        add_issue "Navigation" "General" "D-pad test failed: $local_test" "Medium"
                        ;;
                    *focus*|*Focus*)
                        add_issue "Focus" "General" "Focus test failed: $local_test" "High"
                        ;;
                    *dialog*|*Dialog*|*back*|*Back*)
                        add_issue "UI" "Dialog" "Dialog/back test failed: $local_test" "Medium"
                        ;;
                    *)
                        add_issue "UI" "General" "Test failed: $local_test" "Medium"
                        ;;
                esac
                ;;
        esac
    done < "$TEST_OUTPUT"

    # Print summary
    if [ "$_fail_count" -eq 0 ] && [ "$_pass_count" -gt 0 ]; then
        log_ok "All $_pass_count/$_total_run tests passed"
    elif [ "$_fail_count" -gt 0 ]; then
        log_warn "$_fail_count/$_total_run test(s) failed"
    elif grep -q "FAILURES" "$TEST_OUTPUT" 2>/dev/null; then
        log_warn "Test failures detected (see output above)"
    fi
else
    log_warn "Test package $TEST_PACKAGE not installed — skipping UI Automator tests"
fi

# ============================================================
# Step 10: Stop logcat and parse for crashes/ANRs
# ============================================================
log_step "Step 10: Analyzing logcat output"

cleanup  # Stop logcat watcher

# Find the latest logcat file
LOGCAT_FILE=$(ls -t "${LOGCAT_DIR}"/logcat_*.log 2>/dev/null | head -1 || echo "")

if [ -n "$LOGCAT_FILE" ] && [ -f "$LOGCAT_FILE" ]; then
    log_info "Parsing: $LOGCAT_FILE"

    # Scan for fatal exceptions
    while IFS= read -r line; do
        add_issue "Crash" "Runtime" "Fatal exception: $line" "High"
    done < <(grep -i "FATAL EXCEPTION" "$LOGCAT_FILE" 2>/dev/null || true)

    # Scan for ANRs
    while IFS= read -r line; do
        add_issue "Crash" "Runtime" "ANR detected: $line" "High"
    done < <(grep -i "ANR in" "$LOGCAT_FILE" 2>/dev/null || true)

    # Scan for other notable errors
    while IFS= read -r line; do
        # Skip lines already captured above
        if ! echo "$line" | grep -qiE "FATAL EXCEPTION|ANR in"; then
            add_issue "UI" "Runtime" "Error logged: $line" "Medium"
        fi
    done < <(grep -iE "NullPointerException|IllegalStateException|WindowManager|SecurityException" "$LOGCAT_FILE" 2>/dev/null || true)

    log_ok "Logcat analysis complete"
else
    log_warn "No logcat output found"
fi

# ============================================================
# Step 11: Generate report
# ============================================================
log_step "Step 11: Generating report"

{
    echo "============================================================"
    echo "  Android D-pad Testing Toolkit — Test Report"
    echo "  App: $PACKAGE_NAME"
    echo "  Device: ${DEVICE_ID:-default}"
    echo "  Date: $(date '+%Y-%m-%d %H:%M:%S')"
    echo "============================================================"
    echo ""

    if [ "$ISSUE_COUNT" -eq 0 ]; then
        echo "No issues found. All tests passed."
    else
        echo "Total issues found: $ISSUE_COUNT"
        echo ""
        cat "$ISSUES_TMP"
    fi

    echo ""
    echo "Screenshots saved to: $SCREENSHOT_DIR"
    echo "Logcat output saved to: $LOGCAT_DIR"
    echo "============================================================"
} > "$REPORT_FILE"

rm -f "$ISSUES_TMP"

# ============================================================
# Step 12: Print report to terminal
# ============================================================
log_step "TEST REPORT"
echo ""
cat "$REPORT_FILE"
echo ""

# ============================================================
# Step 13: Prompt user for issues to fix
# ============================================================
if [ "$ISSUE_COUNT" -eq 0 ]; then
    log_ok "No issues to review. Exiting."
    exit 0
fi

echo -e "${BOLD}Review ./reports/report.txt — enter issue numbers to fix, or 'all', or 'none':${NC}"
read -rp "> " user_input

case "$user_input" in
    none|NONE|n|N)
        log_info "No issues selected. Exiting."
        rm -f "$APPROVED_FILE"
        exit 0
        ;;
    all|ALL|a|A)
        log_info "All $ISSUE_COUNT issues selected."
        seq 1 "$ISSUE_COUNT" > "$APPROVED_FILE"
        ;;
    *)
        # Parse comma-separated numbers (e.g., "1,3,5" or "1, 3, 5")
        echo "$user_input" | tr ',' '\n' | tr -d ' ' | while read -r num; do
            if [[ "$num" =~ ^[0-9]+$ ]] && [ "$num" -ge 1 ] && [ "$num" -le "$ISSUE_COUNT" ]; then
                echo "$num"
            else
                log_warn "Skipping invalid issue number: $num"
            fi
        done > "$APPROVED_FILE"
        ;;
esac

# ============================================================
# Step 14: Save and exit
# ============================================================
if [ -f "$APPROVED_FILE" ] && [ -s "$APPROVED_FILE" ]; then
    APPROVED_COUNT=$(wc -l < "$APPROVED_FILE")
    log_ok "Saved $APPROVED_COUNT approved issue(s) to $APPROVED_FILE"
    log_info "Run ./fix.sh to review and apply fixes."
else
    log_info "No valid issues selected."
fi

exit 0
