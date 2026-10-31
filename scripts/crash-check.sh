#!/usr/bin/env bash
# YT: starts the app in the emulator, once per design, and collects the crash, if any.
# Run by crash-check.yml. Environment: YT_DESIGN (a key, a comma list, "all" or empty = standard),
# YT_NIGHT (yes = dark mode), YT_LOCALE (an app locale such as de-DE, API 33+), YT_WAIT (seconds),
# YT_SIGNIN_PROBE (yes = also open the sign-in window and log what Google shows), YT_SCREENS (yes =
# per design: screenshot the start, open a video, screenshot the player and the mini player),
# YT_PIP (yes = per design: switch the sound wave on, open a video, go Home into picture-in-picture,
# log where the wave's window and the floating window are, screenshot; for the first design also
# take the permission back and give it again while the video floats),
# YT_LOCK (yes = install as from a downloaded APK file, as the user does, and walk through the
# permission to show over other apps: YT's switch and dialog, Android 15's lock on it, the unlock),
# YT_FLOAT (yes = per design: YT's own floating player with the wave: Home, a drag with the finger
# held and screenshots during it, a pixel check that the wave stays right below the video, the
# controls, back to the app, and closing by dropping it on the ✕).
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
SDK=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
if [ "${YT_LOCK:-no}" = "yes" ] && [ "${SDK:-0}" -lt 35 ]; then
  echo "::warning::The lock walk needs Android 15 (API 35) or newer; this emulator has API $SDK, so it is skipped"
  YT_LOCK=no
fi
if [ "${YT_LOCK:-no}" = "yes" ]; then
  # as the system's package installer marks an APK opened from the downloads (PACKAGE_SOURCE_DOWNLOADED_FILE)
  adb push "$APK" /data/local/tmp/yt.apk >/dev/null
  adb shell pm install -r --package-source 4 /data/local/tmp/yt.apk
else
  adb install -r "$APK"
fi
# an installation that failed would show as crashes in every design
if ! timeout 20 adb shell pm path "$PKG" >/dev/null 2>&1; then
  echo "::error::YT is not installed; nothing to check"
  exit 2
fi
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

# the time on the device, to line up with its log
mark() { echo "$(timeout 5 adb shell date +%H:%M:%S 2>/dev/null | tr -d '\r') $*"; }
shot() { mark "screenshot $1"; timeout 20 adb exec-out screencap -p > "screens/$1.png" || true; }
# the screen's elements as uiautomator sees them, one node per line
ui() {
  timeout 30 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
  timeout 20 adb exec-out cat /sdcard/ui.xml 2>/dev/null | grep -o '<node [^>]*>' || true
}
# the texts on the screen, and for a switch whether it is on and usable
texts() {
  ui | sed -n -E 's/.*text="([^"]*)".*class="([^"]*)".*checkable="([^"]*)" checked="([^"]*)".*enabled="([^"]*)".*/\1|\2|\3|\4|\5/p' \
    | awk -F'|' '$3 == "true" { print $1 " [" $2 " checked=" $4 " enabled=" $5 "]"; next } $1 != "" { print $1 }' \
    | head -n "${1:-30}"
}
# taps the first element whose text or description matches (case-insensitive), scrolling down up to $2 times
tap() {
  local pattern="$1" tries="${2:-0}" node b
  for _ in $(seq 0 "$tries"); do
    node=$(ui | grep -i -E "(text|content-desc)=\"($pattern)\"" | head -n 1)
    if [ -n "$node" ]; then
      b=$(printf '%s' "$node" | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/')
      set -- $b
      mark "tap \"$pattern\" at $(( ($1 + $3) / 2 )),$(( ($2 + $4) / 2 ))"
      timeout 10 adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
      return 0
    fi
    timeout 10 adb shell input swipe 540 1700 540 700 400 || true
    sleep 1
  done
  mark "not found: \"$pattern\""
  return 1
}
# the frame of a window by its title in dumpsys ("l t r b"), empty when it is not there
frame_of() {
  timeout 30 adb shell dumpsys window windows 2>/dev/null | grep -a -A40 "$1}" \
    | grep -a -m1 -o " frame=\[[0-9-]*,[0-9-]*\]\[[0-9-]*,[0-9-]*\]" | tr -c '0-9-\n' ' ' | awk '{print $1, $2, $3, $4}'
}
# a raw screenshot (for the pixel check) and a PNG of it (to publish)
rawshot() {
  mark "screenshot $1"
  timeout 20 adb exec-out screencap > "screens/$1.raw" || true
  python3 -I scripts/wave-check.py --png "screens/$1.raw" "screens/$1.png" 2>/dev/null || true
}
# back until YT is in front again (at most 5 times)
back_to_yt() {
  for _ in 1 2 3 4 5; do
    timeout 20 adb shell dumpsys activity activities 2>/dev/null | grep -a -m1 "topResumedActivity" | grep -q "$PKG" && return 0
    timeout 10 adb shell input keyevent KEYCODE_BACK || true
    sleep 2
  done
  return 1
}

if [ "${YT_LOCK:-no}" = "yes" ]; then
  mkdir -p screens
  timeout 20 adb logcat -c || true
  adb logcat -v threadtime > logcat-lock.txt 2>/dev/null &
  logpid=$!
  echo "::group::the permission to show over other apps, installed as from a downloaded file"
  echo "--- how Android sees the installation"
  timeout 20 adb shell dumpsys package "$PKG" 2>/dev/null | grep -a -i -E "packageSource|installerPackageName|initiatingPackageName|originatingPackageName" | head -8 || true
  echo "--- the flags of the lock (Android 15+)"
  timeout 20 adb shell aflags list 2>/dev/null | grep -a -i -E "extend_ecm|enhanced_confirmation" | head -8 || echo "no aflags"
  timeout 20 adb shell appops get "$PKG" SYSTEM_ALERT_WINDOW || true
  echo "--- YT's switch"
  timeout 30 adb shell am start -n "$PKG/com.github.libretube.ui.activities.SettingsActivity" --es redirect yt_player >/dev/null || true
  sleep 8
  tap "Schwebender Player mit Klangwelle|Floating player with sound wave" 10 || true
  sleep 3
  shot lock-1-yt-dialog
  texts 20
  echo "--- the system's list"
  tap "Zur Erlaubnis|To the permission" || true
  sleep 5
  shot lock-2-system-list
  tap "YT" 12 || true
  sleep 4
  shot lock-3-system-yt
  texts 20
  echo "--- the system's switch tapped"
  tap "Allow display over other apps|Einblendung über anderen Apps zulassen" || true
  sleep 3
  shot lock-4-restricted
  texts 20
  timeout 10 adb shell input keyevent KEYCODE_BACK || true
  sleep 2
  echo "--- back in YT (its dialog again), then the app's info"
  back_to_yt || mark "YT is not in front"
  sleep 3
  shot lock-5-yt-again
  texts 12
  tap "App-Info|App info" || true
  sleep 4
  tap "More options|Weitere Optionen" || true
  sleep 2
  shot lock-6-app-info-menu
  texts 12
  tap "Allow restricted settings|Eingeschränkte Einstellungen zulassen" || true
  sleep 4
  shot lock-7-allowed
  texts 12
  echo "--- the system's switch again"
  back_to_yt || mark "YT is not in front"
  sleep 3
  tap "Zur Erlaubnis|To the permission" || true
  sleep 5
  tap "YT" 12 || true
  sleep 4
  tap "Allow display over other apps|Einblendung über anderen Apps zulassen" || true
  sleep 3
  shot lock-8-system-on
  texts 12
  timeout 20 adb shell appops get "$PKG" SYSTEM_ALERT_WINDOW || true
  echo "--- back in YT: the switch should be on by itself"
  back_to_yt || mark "YT is not in front"
  sleep 3
  # the switch sits low in the list; a little further up, so it is on the screen
  timeout 10 adb shell input swipe 540 1600 540 900 400 || true
  sleep 2
  shot lock-9-yt-on
  texts 80 | grep -A2 -E "^(Schwebender Player mit Klangwelle|Floating player with sound wave)$" || echo "the wave's switch is not on the screen"
  kill "$logpid" 2>/dev/null; wait "$logpid" 2>/dev/null
  grep -a -E "YtPipWave|ENHANCED_CONFIRMATION|SHOW_ECM" logcat-lock.txt | cut -c1-240 | head -30 || true
  # a crash of YT on the way counts like one in a design
  awk -v pkg="Process: $PKG," '
    function flush() { if (keep) print block; block = ""; keep = 0; n = 0 }
    /FATAL EXCEPTION/ { flush(); block = $0; n = 1; next }
    n > 0 && n < 160 { block = block "\n" $0; n++; if (index($0, pkg)) keep = 1 }
    END { flush() }
  ' logcat-lock.txt | head -400 > crash-lock.txt || true
  if [ -s crash-lock.txt ]; then head -60 crash-lock.txt; crashed="$crashed lock"; else rm -f crash-lock.txt; fi
  echo "::endgroup::"
fi
for design in $DESIGNS; do
  # the emulator itself can die (its GPU emulation is fragile); that is no crash of the app
  if [ "$(timeout 15 adb get-state 2>/dev/null)" != "device" ]; then
    echo "::error::The emulator is gone before $design; the designs from here on are untested"
    exit 2
  fi
  # a fresh start: no settings, no saved crash, as after an installation
  timeout 60 adb shell pm clear "$PKG" >/dev/null || echo "pm clear timed out"
  # pm clear also forgets the app's own language; set it again
  if [ -n "${YT_LOCALE:-}" ]; then
    timeout 20 adb shell cmd locale set-app-locales "$PKG" --user current --locales "$YT_LOCALE" >/dev/null 2>&1 || true
  fi
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
  if [ "${YT_PIP:-no}" = "yes" ]; then
    mkdir -p screens
    # the permission the switch would ask for, given as the user would give it
    timeout 20 adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow || echo "appops failed"
    timeout 30 adb shell am start -n "$PKG/$ACTIVITY" --es videoId "${YT_VIDEO:-jNQXAC9IVRw}" \
      --ez yt_quiet true --ez yt_pip_test true || echo "video start timed out"
    sleep 18
    shot "$design-pip-0-player"
    mark "Home"
    timeout 10 adb shell input keyevent KEYCODE_HOME || true
    # a cold first start can be slow: several looks, not one
    sleep 3
    shot "$design-pip-1-floating-3s"
    sleep 5
    shot "$design-pip-2-floating-8s"
    sleep 7
    shot "$design-pip-3-floating-15s"
    if [ "$design" = "${DESIGNS%% *}" ]; then
      # the permission taken back, PiP anew: a toast, no wave; given while it floats: the wave comes
      timeout 30 adb shell am start -n "$PKG/$ACTIVITY" --ez yt_quiet true >/dev/null 2>&1 || true
      sleep 5
      timeout 20 adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW deny || true
      mark "Home without the permission"
      timeout 10 adb shell input keyevent KEYCODE_HOME || true
      sleep 3
      shot "$design-pip-4-denied"
      mark "permission given while floating"
      timeout 20 adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow || true
      sleep 4
      shot "$design-pip-5-given"
    fi
    echo "::group::$design: picture-in-picture and the wave's window"
    echo "--- app log"
    grep -a "YtPipWave" "logcat-$design.txt" | sed -E 's/^[0-9-]+ ([0-9:.]+) .*YtPipWave *: /\1 /' | head -60 || true
    echo "--- appops"
    timeout 20 adb shell appops get "$PKG" SYSTEM_ALERT_WINDOW || true
    echo "--- windows (wave, pip, bars)"
    timeout 30 adb shell dumpsys window windows > "dumpsys-window-$design.txt" 2>/dev/null || true
    grep -a -n -E "Window\{|mFrame|frame=|mHasSurface|isOnScreen|mViewVisibility|isVisible|mShownPosition|mAttrs|ty=|fl=|alpha=" "dumpsys-window-$design.txt" \
      | grep -a -i -B1 -A12 "YT wave" | cut -c1-260 | head -60 || true
    echo "--- pinned task"
    timeout 30 adb shell dumpsys activity activities 2>/dev/null | grep -a -i -E "pinned|mode=pinned|bounds=" | cut -c1-200 | head -20 || true
    timeout 30 adb shell dumpsys window displays 2>/dev/null | grep -a -i -E "pinned|PiP|mStableInsets|cutout|mBaseDisplayInfo" | cut -c1-240 | head -20 || true
    echo "::endgroup::"
    # back from the floating window for the next design
    timeout 30 adb shell am start -n "$PKG/$ACTIVITY" --ez yt_quiet true >/dev/null 2>&1 || true
    sleep 4
  fi
  if [ "${YT_FLOAT:-no}" = "yes" ]; then
    mkdir -p screens
    float_log() { grep -a -E "YtFloat|YtWave" "logcat-$design.txt" | sed -E 's/^[0-9-]+ ([0-9:.]+) [^:]*: /\1 /' | cut -c1-220; }
    timeout 20 adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow || echo "appops failed"
    timeout 30 adb shell am start -n "$PKG/$ACTIVITY" --es videoId "${YT_VIDEO:-jNQXAC9IVRw}" \
      --ez yt_quiet true --ez yt_pip_test true --ez yt_wave_test true || echo "video start timed out"
    sleep 18
    shot "$design-float-0-player"
    mark "Home"
    timeout 10 adb shell input keyevent KEYCODE_HOME || true
    sleep 4
    rawshot "$design-float-1-floating"
    echo "::group::$design: YT's own floating player and the wave"
    read -r l t r b <<< "$(frame_of "YT player")"
    size=$(float_log | grep -m1 "float added" | sed -E 's/.*video=([0-9]+) x ([0-9]+) strip=([0-9]+).*/\1 \2 \3/')
    read -r vw vh strip <<< "$size"
    echo "window: ${l:-?} ${t:-?} ${r:-?} ${b:-?}; video ${vw:-?} x ${vh:-?}, strip ${strip:-?}"
    echo "pinned task (should be none): $(timeout 20 adb shell dumpsys activity activities 2>/dev/null | grep -a -c 'mode=pinned')"
    float_ok=yes
    if [ -z "${l:-}" ] || [ -z "${vh:-}" ]; then
      echo "::error::$design: YT's floating player did not appear"
      float_ok=no
    else
      echo "--- at rest"
      python3 -I scripts/wave-check.py "screens/$design-float-1-floating.raw" "$l" "$t" "$((r - l))" "$vh" "$strip" || float_ok=no
      # a drag with the finger held: up and to the left, screenshots while it is down
      cx=$(( (l + r) / 2 )); cy=$(( t + vh / 3 ))
      timeout 10 adb shell input motionevent DOWN "$cx" "$cy" || true
      for i in $(seq 1 14); do
        timeout 10 adb shell input motionevent MOVE "$((cx - i * 22))" "$((cy - i * 45))" || true
        if [ "$i" = 7 ]; then
          read -r ml mt mr mb <<< "$(frame_of "YT player")"
          rawshot "$design-float-2-dragging"
        fi
      done
      read -r hl ht hr hb <<< "$(frame_of "YT player")"
      rawshot "$design-float-3-held"
      timeout 10 adb shell input motionevent UP "$((cx - 14 * 22))" "$((cy - 14 * 45))" || true
      sleep 2
      read -r al at ar ab <<< "$(frame_of "YT player")"
      rawshot "$design-float-4-released"
      echo "--- while dragging (finger down), window at ${ml:-?},${mt:-?}"
      [ -n "${ml:-}" ] && { python3 -I scripts/wave-check.py "screens/$design-float-2-dragging.raw" "$ml" "$mt" "$((mr - ml))" "$vh" "$strip" "$l" "$t" || float_ok=no; }
      echo "--- held at the end of the drag, window at ${hl:-?},${ht:-?}"
      [ -n "${hl:-}" ] && { python3 -I scripts/wave-check.py "screens/$design-float-3-held.raw" "$hl" "$ht" "$((hr - hl))" "$vh" "$strip" "$l" "$t" || float_ok=no; }
      echo "--- released (settled at a side), window at ${al:-?},${at:-?}"
      [ -n "${al:-}" ] && { python3 -I scripts/wave-check.py "screens/$design-float-4-released.raw" "$al" "$at" "$((ar - al))" "$vh" "$strip" || float_ok=no; }
      if [ "${ml:-$l}" = "$l" ] && [ "${mt:-$t}" = "$t" ]; then echo "::error::$design: the window did not move during the drag"; float_ok=no; fi
      # a tap shows the controls; the expand button (top left) opens YT again (before they hide)
      dp=$(timeout 5 adb shell wm density | grep -o '[0-9]*' | tail -n 1)
      off=$(( 24 * ${dp:-420} / 160 ))
      ax=$(( (al + ar) / 2 )); ay=$(( at + vh / 2 ))
      timeout 10 adb shell input tap "$ax" "$ay" || true
      sleep 1
      shot "$design-float-5-controls"
      timeout 10 adb shell input tap "$((al + off))" "$((at + off))" || true
      sleep 5
      shot "$design-float-6-back-in-app"
      if [ -n "$(frame_of "YT player")" ]; then echo "::error::$design: the floating player stayed after opening the app"; float_ok=no; fi
      timeout 20 adb shell dumpsys activity activities 2>/dev/null | grep -a -m1 "topResumedActivity" | cut -c1-160 || true
      # Home again, then drop it on the ✕ at the bottom
      timeout 10 adb shell input keyevent KEYCODE_HOME || true
      sleep 4
      read -r l t r b <<< "$(frame_of "YT player")"
      if [ -n "${l:-}" ]; then
        cx=$(( (l + r) / 2 )); cy=$(( t + vh / 3 ))
        sw=$(timeout 5 adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -n 1)
        sx=${sw%x*}; sy=${sw#*x}
        tx=$(( sx / 2 )); ty=$(( sy - (96 + 32) * ${dp:-420} / 160 ))
        timeout 10 adb shell input motionevent DOWN "$cx" "$cy" || true
        for i in $(seq 1 10); do
          timeout 10 adb shell input motionevent MOVE "$((cx + (tx - cx) * i / 10))" "$((cy + (ty - cy) * i / 10))" || true
        done
        shot "$design-float-7-over-close"
        timeout 10 adb shell input motionevent UP "$tx" "$ty" || true
        sleep 2
        if [ -n "$(frame_of "YT player")" ]; then echo "::error::$design: dropping on the ✕ did not close it"; float_ok=no; else echo "closed by the ✕"; fi
      else
        echo "::error::$design: no floating player on the second Home"
        float_ok=no
      fi
    fi
    echo "--- app log"
    float_log | head -60
    echo "floating player check: $float_ok"
    [ "$float_ok" = yes ] || crashed="$crashed $design-float"
    echo "::endgroup::"
    rm -f screens/*.raw
    timeout 30 adb shell am start -n "$PKG/$ACTIVITY" --ez yt_quiet true >/dev/null 2>&1 || true
    sleep 4
  fi
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
