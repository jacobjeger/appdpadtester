#!/usr/bin/env bash
# ============================================================
# auto_detect.sh — Auto-detect Android project settings
#
# Scans the current project for AndroidManifest.xml, build.gradle,
# and connected devices to automatically fill in config values.
#
# Called by config.sh — you should not need to run this directly.
# User-set values in config.sh always take priority over detection.
#
# macOS + Linux compatible (no grep -P, no GNU-only features).
# ============================================================

# The directory the user ran the command from
_USER_CWD="$(pwd)"

# The directory where the toolkit scripts live
_TOOLKIT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ============================================================
# Find the Android project root (directory containing gradlew)
# Searches: CWD, parent of CWD, parent of toolkit dir, etc.
# ============================================================
auto_detect_project_dir() {
    # Check the user's current working directory first
    if [ -f "$_USER_CWD/gradlew" ]; then
        echo "$_USER_CWD"
        return
    fi

    # Check parent of CWD (user might be in a subfolder)
    if [ -f "$_USER_CWD/../gradlew" ]; then
        echo "$(cd "$_USER_CWD/.." && pwd)"
        return
    fi

    # Check parent of toolkit dir (toolkit cloned into project subfolder)
    if [ -f "$_TOOLKIT_DIR/../gradlew" ]; then
        echo "$(cd "$_TOOLKIT_DIR/.." && pwd)"
        return
    fi

    # Check grandparent of toolkit dir
    if [ -f "$_TOOLKIT_DIR/../../gradlew" ]; then
        echo "$(cd "$_TOOLKIT_DIR/../.." && pwd)"
        return
    fi

    # Last resort: return CWD
    echo "$_USER_CWD"
}

# ============================================================
# Find AndroidManifest.xml in the project
# ============================================================
_find_manifest() {
    local project_dir="${1:-.}"

    # Standard locations first
    for path in \
        "$project_dir/app/src/main/AndroidManifest.xml" \
        "$project_dir/src/main/AndroidManifest.xml"; do
        if [ -f "$path" ]; then
            echo "$path"
            return
        fi
    done

    # Search for it (avoid build/ and .gradle/ directories)
    local manifest
    manifest=$(find "$project_dir" -maxdepth 5 -name "AndroidManifest.xml" \
        -not -path "*/build/*" \
        -not -path "*/.gradle/*" \
        -not -path "*/uitests/*" \
        2>/dev/null | head -1)

    echo "$manifest"
}

# ============================================================
# Detect package name from build.gradle or AndroidManifest.xml
# macOS-compatible: uses sed instead of grep -P
# ============================================================
auto_detect_package_name() {
    local project_dir="${1:-.}"
    local pkg=""

    # Priority 1: build.gradle.kts or build.gradle
    for gradle_file in \
        "$project_dir/app/build.gradle.kts" \
        "$project_dir/app/build.gradle"; do
        if [ -f "$gradle_file" ]; then
            # Try applicationId first
            pkg=$(sed -n 's/.*applicationId[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$gradle_file" | head -1)
            if [ -z "$pkg" ]; then
                # Groovy style: applicationId "com.example"
                pkg=$(sed -n 's/.*applicationId[[:space:]]*"\([^"]*\)".*/\1/p' "$gradle_file" | head -1)
            fi
            [ -n "$pkg" ] && echo "$pkg" && return

            # Try namespace
            pkg=$(sed -n 's/.*namespace[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$gradle_file" | head -1)
            if [ -z "$pkg" ]; then
                pkg=$(sed -n 's/.*namespace[[:space:]]*"\([^"]*\)".*/\1/p' "$gradle_file" | head -1)
            fi
            [ -n "$pkg" ] && echo "$pkg" && return
        fi
    done

    # Priority 2: AndroidManifest.xml
    local manifest
    manifest=$(_find_manifest "$project_dir")
    if [ -n "$manifest" ] && [ -f "$manifest" ]; then
        pkg=$(sed -n 's/.*package="\([^"]*\)".*/\1/p' "$manifest" | head -1)
        [ -n "$pkg" ] && echo "$pkg" && return
    fi

    echo ""
}

# ============================================================
# Detect the launcher activity from AndroidManifest.xml
# ============================================================
auto_detect_main_activity() {
    local project_dir="${1:-.}"
    local manifest
    manifest=$(_find_manifest "$project_dir")

    if [ -z "$manifest" ] || [ ! -f "$manifest" ]; then
        echo ""
        return
    fi

    # Find the <activity> that contains android.intent.action.MAIN
    local activity
    activity=$(awk '
        /<activity/{
            name = ""
            # Try to get name from this line
            if (match($0, /android:name="([^"]+)"/, arr)) {
                name = arr[1]
            }
            current_activity = name
            in_activity = 1
        }
        # Handle android:name on a separate line from <activity
        in_activity && /android:name=/ && current_activity == "" {
            if (match($0, /android:name="([^"]+)"/, arr)) {
                current_activity = arr[1]
            }
        }
        /android\.intent\.action\.MAIN/ && in_activity {
            print current_activity
            exit
        }
        /<\/activity>/ {
            in_activity = 0
            current_activity = ""
        }
    ' "$manifest" 2>/dev/null)

    if [ -n "$activity" ]; then
        echo "$activity"
    else
        echo ""
    fi
}

# ============================================================
# Detect APK path — find the most recent debug APK
# ============================================================
auto_detect_apk_path() {
    local project_dir="${1:-.}"

    # Standard path first
    local standard="$project_dir/app/build/outputs/apk/debug/app-debug.apk"
    if [ -f "$standard" ]; then
        echo "$standard"
        return
    fi

    # Search for any debug APK
    local found
    found=$(find "$project_dir" -maxdepth 6 -name "*-debug.apk" \
        -path "*/build/outputs/*" \
        2>/dev/null | head -1)

    if [ -n "$found" ]; then
        echo "$found"
        return
    fi

    # Return standard path (build will create it)
    echo "$project_dir/app/build/outputs/apk/debug/app-debug.apk"
}

# ============================================================
# Detect connected ADB device
# ============================================================
auto_detect_device() {
    if ! command -v adb &>/dev/null; then
        echo ""
        return
    fi

    local devices
    devices=$(adb devices 2>/dev/null | tail -n +2 | grep -E "[[:space:]]+device$" | awk '{print $1}')

    local count
    count=$(echo "$devices" | grep -c . 2>/dev/null || echo "0")

    if [ "$count" -eq 1 ]; then
        echo "$devices"
    elif [ "$count" -gt 1 ]; then
        echo "$devices" | head -1
    else
        echo ""
    fi
}

# ============================================================
# Detect all activities from AndroidManifest.xml
# ============================================================
auto_detect_screens() {
    local project_dir="${1:-.}"
    local manifest
    manifest=$(_find_manifest "$project_dir")

    if [ -z "$manifest" ] || [ ! -f "$manifest" ]; then
        echo ""
        return
    fi

    # macOS-compatible: use sed to extract activity names
    sed -n 's/.*<activity[^>]*android:name="\([^"]*\)".*/\1/p' "$manifest" 2>/dev/null
}

# ============================================================
# Main: fill in blank config values via auto-detection
# ============================================================
run_auto_detect() {
    local quiet="${1:-false}"

    # Detect project directory first
    if [ -z "$PROJECT_DIR" ]; then
        PROJECT_DIR=$(auto_detect_project_dir)
        [ "$quiet" != "true" ] && [ -n "$PROJECT_DIR" ] && \
            echo "[auto-detect] Project: $PROJECT_DIR"
    fi

    # Detect package name
    if [ -z "$PACKAGE_NAME" ]; then
        PACKAGE_NAME=$(auto_detect_package_name "$PROJECT_DIR")
        [ "$quiet" != "true" ] && [ -n "$PACKAGE_NAME" ] && \
            echo "[auto-detect] Package: $PACKAGE_NAME"
    fi

    # Detect main activity
    if [ -z "$MAIN_ACTIVITY" ]; then
        MAIN_ACTIVITY=$(auto_detect_main_activity "$PROJECT_DIR")
        if [ -z "$MAIN_ACTIVITY" ]; then
            MAIN_ACTIVITY=".MainActivity"
        fi
        [ "$quiet" != "true" ] && \
            echo "[auto-detect] Activity: $MAIN_ACTIVITY"
    fi

    # Detect APK path
    if [ -z "$APK_PATH" ]; then
        APK_PATH=$(auto_detect_apk_path "$PROJECT_DIR")
        [ "$quiet" != "true" ] && [ -n "$APK_PATH" ] && \
            echo "[auto-detect] APK: $APK_PATH"
    fi

    # Detect device
    if [ -z "$DEVICE_ID" ]; then
        DEVICE_ID=$(auto_detect_device)
        [ "$quiet" != "true" ] && [ -n "$DEVICE_ID" ] && \
            echo "[auto-detect] Device: $DEVICE_ID"
    fi

    # Detect screens
    if [ ${#SCREENS[@]} -eq 0 ] 2>/dev/null; then
        local detected_screens
        detected_screens=$(auto_detect_screens "$PROJECT_DIR")
        if [ -n "$detected_screens" ]; then
            while IFS= read -r screen; do
                [ -n "$screen" ] && SCREENS+=("$screen")
            done <<< "$detected_screens"
            [ "$quiet" != "true" ] && [ ${#SCREENS[@]} -gt 0 ] && \
                echo "[auto-detect] Screens: ${SCREENS[*]}"
        fi
    fi

    # Default screens to main activity
    if [ ${#SCREENS[@]} -eq 0 ] 2>/dev/null; then
        SCREENS=("$MAIN_ACTIVITY")
    fi

    # Default build command
    if [ -z "$BUILD_CMD" ]; then
        BUILD_CMD="./gradlew assembleDebug"
    fi
}
