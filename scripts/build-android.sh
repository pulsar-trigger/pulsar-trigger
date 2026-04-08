#!/usr/bin/env bash
set -euo pipefail

# Usage: ./scripts/build-android.sh
# Builds the Android debug APK and runs unit tests.

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT/android"

echo "Building…"
JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk ./gradlew assembleDebug --quiet
echo "Build OK"

echo "Testing…"
JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk ./gradlew testDebugUnitTest --quiet
echo "Tests OK"
