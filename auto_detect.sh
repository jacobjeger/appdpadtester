#!/usr/bin/env bash
# ============================================================
# auto_detect.sh — Auto-detect Android project settings
#
# Scans the current project for AndroidManifest.xml, build.gradle,
# and connected devices to automatically fill in config values.
#
# Called by config.sh — you should not need to run this directly.
# User-set values in config.sh always take priority over detection.
# ============================================================

# Find the project root (directory containing gradlew)
auto_detect_project_dir() {
    # Check current directory first
    if [ -f "./gradlew" ]; then
        echo "."
        return
    fi
    # Check parent (toolkit might be in a subfolder)
    if [ -f "../gradlew" ]; then
        echo ".."
        return
    fi
    # Check grandparent
    if [ -f "../../gradlew" ]; then
        echo "../.."
        return
    fi
    echo "."
}

# Find AndroidManifest.xml in the project
_find_manifest() {
    local project_dir="${1:-.}"
    local manifest=""

    # Standard locations, in priority order
    for path in \
        "$project_dir/app/src/main/AndroidManifest.xml" \
        "$project_dir/src/main/AndroidManifest.xml"; do
        if [ -f "$path" ]; then
            echo "$path"
            return
        fi
    done

    # Search for it (limit depth to avoid build/ directories)
    manifest=$(find "$project_dir" -maxdepth 5 -name "AndroidManifest.xml" \
        -not -path "*/build/*" \
        -not -path "*/.gradle/*" \
        -not -path "*/uitests/*" \
        2>/dev/null | head -1)

    echo "$manifest"
}

# Detect package name from build.gradle or AndroidManifest.xml
auto_detect_package_name() {
    local project_dir="${1:-.}"
    local pkg=""

    # Priority 1: app/build.gradle.kts — look for namespace or applicationId
    for gradle_file in \
        "$project_dir/app/build.gradle.kts" \
        "$project_dir/app/build.gradle"; do
        if [ -f "$gradle_file" ]; then
            # Try applicationId first (most accurate for installed package)
            pkg=$(grep -oP 'applicationId\s*[=( ]+\s*"([^"]+)"' "$gradle_file" 2>/dev/null | \
                  grep -oP '"[^"]+"' | tr -d '"' | head -1)
            [ -n "$pkg" ] && echo "$pkg" && return

            # Try namespace
            pkg=$(grep -oP 'namespace\s*[=( ]+\s*"([^"]+)"' "$gradle_file" 2>/dev/null | \
                  grep -oP '"[^"]+"' | tr -d '"' | head -1)
            [ -n "$pkg" ] && echo "$pkg" && return
        fi
    done

    # Priority 2: AndroidManifest.xml — package attribute
    local manifest
    manifest=$(_find_manifest "$project_dir")
    if [ -n "$manifest" ] && [ -f "$manifest" ]; then
        pkg=$(grep -oP 'package="([^"]+)"' "$manifest" 2>/dev/null | \
              grep -oP '"[^"]+"' | tr -d '"' | head -1)
        [ -n "$pkg" ] && echo "$pkg" && return
    fi

    echo ""
}

# Detect the main/launcher activity from AndroidManifest.xml
auto_detect_main_activity() {
    local project_dir="${1:-.}"
    local manifest
    manifest=$(_find_manifest "$project_dir")

    if [ -z "$manifest" ] || [ ! -f "$manifest" ]; then
        echo ""
        return
    fi

    # Strategy: find the <activity> block that contains android.intent.action.MAIN
    # We use awk to find the activity name from the block containing MAIN action
    local activity
    activity=$(awk '
        /<activity/{
            # Extract android:name from the activity tag
            if (match($0, /android:name="([^"]+)"/, arr)) {
                current_activity = arr[1]
            }
            in_activity = 1
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

    # If the activity name doesn't start with a dot and isn't fully qualified,
    # make it relative
    if [ -n "$activity" ]; then
        echo "$activity"
    else
        # Fallback: assume .MainActivity
        echo ""
    fi
}

# Detect APK path — find the most recent debug APK
auto_detect_apk_path() {
    local project_dir="${1:-.}"

    # Standard path first
    local standard="$project_dir/app/build/outputs/apk/debug/app-debug.apk"
    if [ -f "$standard" ]; then
        echo "$standard"
        return
    fi

    # Search for any debug APK in build outputs
    local found
    found=$(find "$project_dir" -maxdepth 6 -name "*-debug.apk" \
        -path "*/build/outputs/*" \
        2>/dev/null | sort -t/ -k1 | head -1)

    if [ -n "$found" ]; then
        echo "$found"
        return
    fi

    # No APK found yet — return the standard path (build will create it)
    echo "$project_dir/app/build/outputs/apk/debug/app-debug.apk"
}

# Detect connected ADB device
auto_detect_device() {
    if ! command -v adb &>/dev/null; then
        echo ""
        return
    fi

    # Get list of connected devices (exclude header line and empty lines)
    local devices
    devices=$(adb devices 2>/dev/null | tail -n +2 | grep -E "\s+device$" | awk '{print $1}')

    local count
    count=$(echo "$devices" | grep -c . 2>/dev/null || echo "0")

    if [ "$count" -eq 1 ]; then
        echo "$devices"
    elif [ "$count" -gt 1 ]; then
        # Multiple devices — return the first one
        echo "$devices" | head -1
    else
        echo ""
    fi
}

# Detect all activities from AndroidManifest.xml for the SCREENS array
auto_detect_screens() {
    local project_dir="${1:-.}"
    local manifest
    manifest=$(_find_manifest "$project_dir")

    if [ -z "$manifest" ] || [ ! -f "$manifest" ]; then
        echo ""
        return
    fi

    # Extract all activity android:name values
    grep -oP '<activity[^>]+android:name="([^"]+)"' "$manifest" 2>/dev/null | \
        grep -oP '"[^"]+"' | tr -d '"' | while read -r name; do
        echo "$name"
    done
}

# ============================================================
# Main: Run all detections and fill in blank config values
# ============================================================
run_auto_detect() {
    local quiet="${1:-false}"

    # Detect project directory first (other detections depend on it)
    if [ -z "$PROJECT_DIR" ] || [ "$PROJECT_DIR" = "." ]; then
        local detected_dir
        detected_dir=$(auto_detect_project_dir)
        if [ -n "$detected_dir" ]; then
            PROJECT_DIR="$detected_dir"
        fi
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
            MAIN_ACTIVITY=".MainActivity"  # sensible default
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

    # Detect screens (only if SCREENS array is empty)
    if [ ${#SCREENS[@]} -eq 0 ] 2>/dev/null; then
        local detected_screens
        detected_screens=$(auto_detect_screens "$PROJECT_DIR")
        if [ -n "$detected_screens" ]; then
            while IFS= read -r screen; do
                SCREENS+=("$screen")
            done <<< "$detected_screens"
            [ "$quiet" != "true" ] && \
                echo "[auto-detect] Screens: ${SCREENS[*]}"
        fi
    fi

    # If SCREENS is still empty, default to main activity
    if [ ${#SCREENS[@]} -eq 0 ] 2>/dev/null; then
        SCREENS=("$MAIN_ACTIVITY")
    fi

    # Set BUILD_CMD default if empty
    if [ -z "$BUILD_CMD" ]; then
        BUILD_CMD="./gradlew assembleDebug"
    fi
}
