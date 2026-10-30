#!/usr/bin/env bash
# YT: starts the app in the emulator and collects the crash, if any. Run by crash-check.yml.
set -u
PKG="app.tra1c0.yt"
APK=$(ls apk/*.apk | head -n 1)
echo "APK: $APK, design: ${YT_DESIGN:-standard}"
adb wait-for-device
adb shell input keyevent 82 || true
adb install -r "$APK"
adb logcat -c || true
if [ -n "${YT_DESIGN:-}" ]; then
  adb shell am start -W -n "$PKG/com.github.libretube.ui.activities.MainActivity" --es yt_design "$YT_DESIGN"
else
  adb shell am start -W -n "$PKG/com.github.libretube.ui.activities.MainActivity"
fi
sleep 30
adb logcat -d -v threadtime > logcat.txt || true
echo "::group::Process after 30 s"
adb shell pidof "$PKG" && echo "running" || echo "NOT RUNNING"
echo "::endgroup::"
echo "::group::Fatal exception"
grep -n -A160 "FATAL EXCEPTION" logcat.txt | head -400 > crash.txt || true
if [ -s crash.txt ]; then cat crash.txt; else echo "no fatal exception in logcat"; fi
echo "::endgroup::"
if [ -s crash.txt ] && [ -f apk/mapping.txt ]; then
  echo "::group::Retraced"
  RETRACE=$(ls "$ANDROID_HOME"/cmdline-tools/*/bin/retrace 2>/dev/null | head -n 1)
  if [ -n "$RETRACE" ]; then "$RETRACE" apk/mapping.txt crash.txt | head -300; else echo "retrace not found"; fi
  echo "::endgroup::"
fi
echo "::group::Errors of the app"
grep -n " E \| F " logcat.txt | grep -i "$PKG\|libretube\|AndroidRuntime\|yt" | head -120 || true
echo "::endgroup::"
