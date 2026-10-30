#!/usr/bin/env bash
# YT: starts the app in the emulator, once per design, and collects the crash, if any.
# Run by crash-check.yml. Environment: YT_DESIGN (a key, a comma list, "all" or empty = standard),
# YT_NIGHT (yes = dark mode), YT_LOCALE (an app locale such as de-DE, API 33+), YT_WAIT (seconds).
set -u
PKG="app.tra1c0.yt"
ACTIVITY="com.github.libretube.ui.activities.MainActivity"
APK=$(ls apk/*.apk | head -n 1)
WAIT="${YT_WAIT:-20}"
DESIGNS="${YT_DESIGN:-standard}"
[ "$DESIGNS" = "all" ] && DESIGNS="standard youtube movo after side clay editorial glass pop shapes frost kiesel"
DESIGNS="${DESIGNS//,/ }"
echo "APK: $APK, designs: $DESIGNS, night: ${YT_NIGHT:-no}, locale: ${YT_LOCALE:-device}"
adb wait-for-device
adb shell input keyevent 82 || true
adb install -r "$APK"
if [ "${YT_NIGHT:-no}" = "yes" ]; then adb shell cmd uimode night yes; fi
if [ -n "${YT_LOCALE:-}" ]; then
  adb shell cmd locale set-app-locales "$PKG" --user current --locales "$YT_LOCALE" || echo "app locale not set"
fi
RETRACE=$(ls "$ANDROID_HOME"/cmdline-tools/*/bin/retrace 2>/dev/null | head -n 1)
crashed=""
for design in $DESIGNS; do
  # a fresh start: no settings, no saved crash, as after an installation
  adb shell pm clear "$PKG" >/dev/null
  adb logcat -c || true
  if [ "$design" = "standard" ]; then
    adb shell am start -W -n "$PKG/$ACTIVITY" | grep -E "Status|TotalTime|Error" || true
  else
    adb shell am start -W -n "$PKG/$ACTIVITY" --es yt_design "$design" | grep -E "Status|TotalTime|Error" || true
  fi
  sleep "$WAIT"
  adb logcat -d -v threadtime > "logcat-$design.txt" || true
  if adb shell pidof "$PKG" >/dev/null 2>&1; then state="running"; else state="NOT RUNNING"; fi
  grep -n -A160 "FATAL EXCEPTION" "logcat-$design.txt" | head -400 > "crash-$design.txt" || true
  if [ -s "crash-$design.txt" ] || [ "$state" != "running" ]; then crashed="$crashed $design"; fi
  echo "::group::$design: process after $WAIT s is $state"
  if [ -s "crash-$design.txt" ]; then
    if [ -n "$RETRACE" ] && [ -f apk/mapping.txt ]; then
      "$RETRACE" apk/mapping.txt "crash-$design.txt" | head -300
    else
      cat "crash-$design.txt"
    fi
  else
    echo "no fatal exception in logcat"
    rm -f "crash-$design.txt"
    grep -n " E \| F " "logcat-$design.txt" | grep -i "$PKG\|libretube\|AndroidRuntime" | grep -v "jdwp\|JobScheduler\|ActivityManager:" | head -40 || true
  fi
  echo "::endgroup::"
done
if [ -n "$crashed" ]; then
  echo "::error::The app crashed in:$crashed"
  exit 1
fi
echo "No crash in any of the designs: $DESIGNS"
