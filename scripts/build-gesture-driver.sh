#!/usr/bin/env bash
# Build the shell-side emulator driver, without adding a dependency to the Android app.
set -euo pipefail
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:?Set ANDROID_HOME or ANDROID_SDK_ROOT}}"
android_jar=$(find "$sdk/platforms" -name android.jar | sort -V | tail -1)
d8=$(find "$sdk/build-tools" -name d8 | sort -V | tail -1)
[ -n "$android_jar" ] && [ -n "$d8" ] || { echo 'Android platform and build tools are required' >&2; exit 1; }
out="${1:-build/gesture-driver}"
mkdir -p "$out/classes" "$out/dex"
compiler="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
"$compiler" --release 8 -classpath "$android_jar" -d "$out/classes" scripts/gesture-driver/YtInput.java
"${JAVA_HOME:+$JAVA_HOME/bin/}jar" cf "$out/input-classes.jar" -C "$out/classes" .
"$d8" --min-api 26 --lib "$android_jar" --output "$out/dex" "$out/input-classes.jar"
"${JAVA_HOME:+$JAVA_HOME/bin/}jar" cf "$out/yt-input.jar" -C "$out/dex" classes.dex
echo "$out/yt-input.jar"
