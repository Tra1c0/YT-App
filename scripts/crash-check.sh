#!/usr/bin/env bash
# YT: starts the app in the emulator, once per design, and collects the crash, if any.
# Run by crash-check.yml. Environment: YT_DESIGN (a key, a comma list, "all" or empty = standard),
# YT_NIGHT (yes = dark mode), YT_LOCALE (an app locale such as de-DE, API 33+), YT_WAIT (seconds),
# YT_SIGNIN_PROBE (yes = also open the sign-in window and log what Google shows), YT_SCREENS (yes =
# per design: screenshot the start, open a video, screenshot the player and the mini player).
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
# an overloaded emulator shows "System UI isn't responding" over everything; no use for a check.
# The setting is read on a configuration change, the user restriction right away.
adb shell settings put global hide_error_dialogs 1 || true
adb shell pm set-user-restriction no_system_error_dialogs 1 || true
adb shell cmd uimode night yes >/dev/null 2>&1 || true
adb shell cmd uimode night no >/dev/null 2>&1 || true
if [ "${YT_NIGHT:-no}" = "yes" ]; then adb shell cmd uimode night yes; fi
if [ -n "${YT_LOCALE:-}" ]; then
  adb shell cmd locale set-app-locales "$PKG" --user current --locales "$YT_LOCALE" || echo "app locale not set"
fi
RETRACE=$(ls "$ANDROID_HOME"/cmdline-tools/*/bin/retrace 2>/dev/null | head -n 1)
crashed=""
for design in $DESIGNS; do
  # the emulator itself can die (its GPU emulation is fragile); that is no crash of the app
  if [ "$(timeout 15 adb get-state 2>/dev/null)" != "device" ]; then
    echo "::error::The emulator is gone before $design; the designs from here on are untested"
    exit 2
  fi
  # a fresh start: no settings, no saved crash, as after an installation
  timeout 60 adb shell pm clear "$PKG" >/dev/null || echo "pm clear timed out"
  # pm clear ends the old process on its own time; a start right after it can be swept away
  sleep 3
  timeout 20 adb logcat -c || true
  # the log is written while the app runs, so it survives an emulator that dies on the way
  adb logcat -v threadtime > "logcat-$design.txt" 2>/dev/null &
  logpid=$!
  echo "$(date -u +%H:%M:%S) start $design"
  if [ "$design" = "standard" ]; then
    timeout 30 adb shell am start -n "$PKG/$ACTIVITY" --ez yt_quiet true || echo "start timed out"
  else
    timeout 30 adb shell am start -n "$PKG/$ACTIVITY" --es yt_design "$design" --ez yt_quiet true || echo "start timed out"
  fi
  sleep "$WAIT"
  if [ "${YT_SCREENS:-no}" = "yes" ]; then
    # the start, the player with a video, then the player minimized onto the bar
    mkdir -p screens
    timeout 20 adb exec-out screencap -p > "screens/$design-1-start.png" || true
    # straight to the running main screen (a link would start it anew, with the welcome sheet)
    timeout 30 adb shell am start -n "$PKG/$ACTIVITY" --es videoId "${YT_VIDEO:-jNQXAC9IVRw}" --ez yt_quiet true \
      || echo "video start timed out"
    sleep 18
    timeout 20 adb exec-out screencap -p > "screens/$design-2-player.png" || true
    timeout 10 adb shell input keyevent KEYCODE_BACK || true
    sleep 5
    timeout 20 adb exec-out screencap -p > "screens/$design-3-mini.png" || true
  fi
  if timeout 20 adb shell pidof "$PKG" >/dev/null 2>&1; then state="running"; else state="NOT RUNNING"; fi
  kill "$logpid" 2>/dev/null; wait "$logpid" 2>/dev/null
  # only crashes of this app count (other processes of the emulator crash too)
  awk -v pkg="Process: $PKG," '
    function flush() { if (keep) print block; block = ""; keep = 0; n = 0 }
    /FATAL EXCEPTION/ { flush(); block = $0; n = 1; next }
    n > 0 && n < 160 { block = block "\n" $0; n++; if (index($0, pkg)) keep = 1 }
    END { flush() }
  ' "logcat-$design.txt" | head -400 > "crash-$design.txt" || true
  grep -c "FATAL EXCEPTION" "logcat-$design.txt" | xargs -I{} echo "fatal exceptions of any process in the log: {}"
  grep -a -A2 "FATAL EXCEPTION" "logcat-$design.txt" | grep -a "Process:" | sed 's/^.*Process: /  crashed: /' | cut -c1-120 | sort | uniq -c || true
  if [ -s "crash-$design.txt" ] || [ "$state" != "running" ]; then crashed="$crashed $design"; fi
  echo "::group::$design: process after $WAIT s is $state"
  if [ -s "crash-$design.txt" ]; then
    head -60 "crash-$design.txt"
  else
    echo "no fatal exception in logcat"
    rm -f "crash-$design.txt"
    grep -n " E \| F " "logcat-$design.txt" | grep -i "$PKG\|libretube\|AndroidRuntime" | grep -v "jdwp\|JobScheduler\|ActivityManager:" | head -40 || true
  fi
  if [ "$state" != "running" ]; then
    # why the process went without a Java crash: native signal, low-memory kill, system kill
    echo "--- how the process ended"
    grep -a -E "Fatal signal|SIGSEGV|SIGABRT|SIGBUS|Abort message|backtrace:|#0[0-9] pc|lowmemorykiller|lmkd|Killing [0-9]+:$PKG|$PKG.*(died|crash)|Process $PKG|am_kill|am_proc_died|Out of memory|OutOfMemory|GL error|EGL_BAD|gralloc|ANR in" "logcat-$design.txt" | cut -c1-300 | head -60 || true
    echo "--- last lines before the end"
    tail -n 40 "logcat-$design.txt" | cut -c1-300
  fi
  echo "::endgroup::"
done
# the sign-in window: starts it fresh and logs what Google's page shows (title and first text)
if [ "${YT_SIGNIN_PROBE:-no}" = "yes" ] && [ "$(timeout 15 adb get-state 2>/dev/null)" = "device" ]; then
  timeout 60 adb shell pm clear "$PKG" >/dev/null || true
  sleep 3
  timeout 20 adb logcat -c || true
  adb logcat -v threadtime > logcat-signin.txt 2>/dev/null &
  logpid=$!
  timeout 30 adb shell am start -n "$PKG/$ACTIVITY" --ez yt_probe true || echo "start timed out"
  sleep 45
  if timeout 20 adb shell pidof "$PKG" >/dev/null 2>&1; then state="running"; else state="NOT RUNNING"; fi
  kill "$logpid" 2>/dev/null; wait "$logpid" 2>/dev/null
  echo "::group::sign-in window: process after 45 s is $state"
  grep -a "YtSignIn\|probe " logcat-signin.txt | sed 's/^.*YtSignIn *: //' | cut -c1-600 | head -30 || true
  echo "--- deaths and kills"
  grep -a -E "Fatal signal|SIGSEGV|SIGABRT|SIGTRAP|Abort message|aw_browser_terminator|Render process|render process|lowmemorykiller|Killing [0-9]+:$PKG|$PKG.*(died|crash)|Process $PKG|WIN DEATH|ANR in" logcat-signin.txt | cut -c1-300 | head -40 || true
  grep -a -A60 "FATAL EXCEPTION" logcat-signin.txt | head -80 > crash-signin.txt || true
  if [ -s crash-signin.txt ]; then cat crash-signin.txt; crashed="$crashed signin"; else rm -f crash-signin.txt; fi
  echo "::endgroup::"
fi

if [ -n "$crashed" ]; then
  if [ -n "$RETRACE" ] && [ -f apk/mapping.txt ]; then
    echo "::group::Retraced crashes"
    cat crash-*.txt > crashes.txt
    timeout 300 "$RETRACE" apk/mapping.txt crashes.txt | head -600 || echo "retrace did not finish in time"
    echo "::endgroup::"
  fi
  echo "::error::The app crashed in:$crashed"
  exit 1
fi
echo "No crash in any of the designs: $DESIGNS"
