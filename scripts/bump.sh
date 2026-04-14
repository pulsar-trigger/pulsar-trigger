#!/usr/bin/env bash
set -euo pipefail

# Usage:
#   ./scripts/bump.sh "commit message"          # bump both Android + firmware
#   ./scripts/bump.sh -a "commit message"       # bump Android only
#   ./scripts/bump.sh -f "commit message"       # bump firmware only
#
# Bumps versions, builds, tests, commits, and pushes.

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

ANDROID_GRADLE="android/app/build.gradle.kts"
FW_INI="firmware/platformio.ini"

BUMP_ANDROID=true
BUMP_FIRMWARE=true

# ── Parse flags ──────────────────────────────────────────────────────
while getopts "af" opt; do
    case $opt in
        a) BUMP_FIRMWARE=false ;;
        f) BUMP_ANDROID=false ;;
        *) echo "Usage: $0 [-a|-f] \"commit message\""; exit 1 ;;
    esac
done
shift $((OPTIND - 1))

MSG="${1:-}"
SUMMARY_PARTS=()

# ── Android version bump ─────────────────────────────────────────────
if $BUMP_ANDROID; then
    CURRENT_CODE=$(grep -oP 'versionCode\s*=\s*\K\d+' "$ANDROID_GRADLE")
    CURRENT_NAME=$(grep -oP 'versionName\s*=\s*"\K[^"]+' "$ANDROID_GRADLE")

    IFS='.' read -r A_MAJOR A_MINOR A_PATCH <<< "$CURRENT_NAME"
    NEW_A_MINOR=$((A_MINOR + 1))
    NEW_A_NAME="${A_MAJOR}.${NEW_A_MINOR}.0"
    NEW_A_CODE=$((CURRENT_CODE + 1))

    echo "Android: $CURRENT_NAME ($CURRENT_CODE) → $NEW_A_NAME ($NEW_A_CODE)"

    sed -i "s/versionCode = $CURRENT_CODE/versionCode = $NEW_A_CODE/" "$ANDROID_GRADLE"
    sed -i "s/versionName = \"$CURRENT_NAME\"/versionName = \"$NEW_A_NAME\"/" "$ANDROID_GRADLE"

    echo "Building Android…"
    cd android
    JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug --quiet
    echo "Android build OK"

    echo "Testing Android…"
    JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest --quiet
    echo "Android tests OK"
    cd "$REPO_ROOT"

    SUMMARY_PARTS+=("Android v$NEW_A_NAME (code $NEW_A_CODE)")
fi

# ── Firmware version bump ────────────────────────────────────────────
if $BUMP_FIRMWARE; then
    # Read current firmware version from first occurrence
    FW_MAJOR=$(grep -oP -m1 'DFW_VERSION_MAJOR=\K\d+' "$FW_INI")
    FW_MINOR=$(grep -oP -m1 'DFW_VERSION_MINOR=\K\d+' "$FW_INI")
    FW_PATCH=$(grep -oP -m1 'DFW_VERSION_PATCH=\K\d+' "$FW_INI")

    NEW_FW_MINOR=$((FW_MINOR + 1))
    NEW_FW_NAME="${FW_MAJOR}.${NEW_FW_MINOR}.0"

    echo "Firmware: ${FW_MAJOR}.${FW_MINOR}.${FW_PATCH} → $NEW_FW_NAME"

    # Replace all occurrences across all envs
    sed -i "s/-DFW_VERSION_MINOR=${FW_MINOR}/-DFW_VERSION_MINOR=${NEW_FW_MINOR}/g" "$FW_INI"
    sed -i "s/-DFW_VERSION_PATCH=${FW_PATCH}/-DFW_VERSION_PATCH=0/g" "$FW_INI"

    echo "Testing firmware…"
    cd firmware
    ~/.platformio/penv/bin/pio test -e native --silent
    echo "Firmware tests OK"
    cd "$REPO_ROOT"

    SUMMARY_PARTS+=("Firmware v$NEW_FW_NAME")
fi

# ── Commit message ───────────────────────────────────────────────────
BUMP_LINE=$(IFS=', '; echo "${SUMMARY_PARTS[*]}")

if [[ -z "$MSG" ]]; then
    COMMIT_MSG="Bump $BUMP_LINE"
else
    COMMIT_MSG="$MSG

$BUMP_LINE"
fi

# ── Commit & push ────────────────────────────────────────────────────
git add -A
git commit -m "$COMMIT_MSG"
git push origin master

echo "Done: $BUMP_LINE"
