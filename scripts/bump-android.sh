#!/usr/bin/env bash
set -euo pipefail

# Usage: ./scripts/bump-android.sh "commit message"
# Bumps Android versionCode +1 and versionName minor +1, builds, tests, commits, and pushes.

GRADLE_FILE="android/app/build.gradle.kts"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

# ── Parse current version ─────────────────────────────────────────────
CURRENT_CODE=$(grep -oP 'versionCode\s*=\s*\K\d+' "$GRADLE_FILE")
CURRENT_NAME=$(grep -oP 'versionName\s*=\s*"\K[^"]+' "$GRADLE_FILE")

IFS='.' read -r MAJOR MINOR PATCH <<< "$CURRENT_NAME"
NEW_MINOR=$((MINOR + 1))
NEW_NAME="${MAJOR}.${NEW_MINOR}.0"
NEW_CODE=$((CURRENT_CODE + 1))

echo "Version: $CURRENT_NAME ($CURRENT_CODE) → $NEW_NAME ($NEW_CODE)"

# ── Apply version bump ────────────────────────────────────────────────
sed -i "s/versionCode = $CURRENT_CODE/versionCode = $NEW_CODE/" "$GRADLE_FILE"
sed -i "s/versionName = \"$CURRENT_NAME\"/versionName = \"$NEW_NAME\"/" "$GRADLE_FILE"

# ── Build ─────────────────────────────────────────────────────────────
echo "Building…"
cd android
JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug --quiet
echo "Build OK"

# ── Test ──────────────────────────────────────────────────────────────
echo "Testing…"
JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest --quiet
echo "Tests OK"
cd "$REPO_ROOT"

# ── Commit message ────────────────────────────────────────────────────
MSG="${1:-Bump Android version to $NEW_NAME}"
# Append version trailer if the user provided a custom message
if [[ "${1:-}" != "" ]]; then
    MSG="$MSG

Bump Android version to $NEW_NAME (versionCode $NEW_CODE)"
fi

# ── Commit & push ─────────────────────────────────────────────────────
git add -A
git commit -m "$MSG"
git push origin master

echo "Done: v$NEW_NAME (versionCode $NEW_CODE)"
