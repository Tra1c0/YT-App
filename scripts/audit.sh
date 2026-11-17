#!/bin/bash
# YT: cheap audit without model tokens. Usage: bash scripts/audit.sh <out-dir>
# Runs Android Lint (release) and an R8 usage report in this checkout, then writes
# <out-dir>/summary.txt (details next to it). Needs ANDROID_HOME and about 20 minutes.
set -u
OUT="${1:?usage: bash scripts/audit.sh <out-dir>}"; mkdir -p "$OUT"; OUT="$(cd "$OUT" && pwd)"
cd "$(dirname "$0")/.." || exit 1
G="./gradlew --no-daemon --no-configuration-cache -Dorg.gradle.jvmargs=-Xmx6g -Pkotlin.daemon.jvmargs=-Xmx3g"
$G :app:lintRelease > "$OUT/lint.log" 2>&1; echo "lint exit $?" > "$OUT/exits.txt"
cp app/proguard-rules.pro "$OUT/proguard.bak"
printf '\n-printusage %s/usage.txt\n' "$OUT" >> app/proguard-rules.pro
$G :app:minifyReleaseWithR8 > "$OUT/r8.log" 2>&1; echo "r8 exit $?" >> "$OUT/exits.txt"
cp "$OUT/proguard.bak" app/proguard-rules.pro
python3 -I scripts/audit_summary.py "$PWD" "$OUT" > "$OUT/summary.txt"
cat "$OUT/exits.txt" "$OUT/summary.txt"
