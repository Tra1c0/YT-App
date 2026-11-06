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
# controls, back to the app, and closing by dropping it on the ✕), YT_VIDEO (the online video id
# of the player checks), YT_TEST_VIDEO (yes = per design: put scripts/fixtures/yt-test.mp4 into the
# app's own files and open it as a downloaded video; the screens then show it), YT_GESTURES (yes =
# per design: the gesture checks scripts/gestures/a.sh ... f.sh with the animations on).
set -u
PKG="app.tra1c0.yt"
ACTIVITY="com.github.libretube.ui.activities.MainActivity"
APK=$(ls apk/*.apk | head -n 1)
WAIT="${YT_WAIT:-20}"
case "$WAIT" in *[!0-9]*) echo "::warning::YT_WAIT=$WAIT is no number of seconds; 20 instead"; WAIT=20 ;; esac
# the id goes into a device shell command line: only what a video id holds
case "${YT_VIDEO:-}" in *[!A-Za-z0-9_-]*) echo "::warning::YT_VIDEO=$YT_VIDEO is no video id; the default instead"; YT_VIDEO="" ;; esac
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
# the floating check looks at the app's threads, the test video goes into the app's own files:
# root first (adbd restarts, which would cut a running log capture short), as the emulator image allows
# adb root fails now and then right after the boot ("closed", the device offline for a moment):
# a few tries, each waiting for adbd to come back
is_root() { [ "$(timeout 10 adb shell id -u 2>/dev/null | tr -d '\r')" = "0" ]; }
get_root() {
  local i
  for i in 1 2 3; do
    is_root && return 0
    timeout 20 adb root >/dev/null 2>&1 || true
    sleep $(( i * 3 ))
    timeout 30 adb wait-for-device || true
  done
  is_root
}
if [ "${YT_FLOAT:-no}" = "yes" ] || [ "${YT_TEST_VIDEO:-no}" = "yes" ]; then
  get_root || echo "::warning::No adb root after three tries"
fi
# an installation that failed would show as crashes in every design
if ! timeout 20 adb shell pm path "$PKG" >/dev/null 2>&1; then
  echo "::error::YT is not installed; nothing to check"
  exit 2
fi
# Error dialogs stay on: with them hidden, Android kills an app that does not respond at once.
# On the emulator that hits Glas and Frost on a cold start (their GPU programs are compiled over
# the emulator's slow GL pipe for seconds, while the main thread waits for the render thread;
# build 60 showed it too, a phone never does), so a watcher presses "Wait" on any
# not-responding dialog instead, and each such ANR is reported as a warning below.
adb shell settings put global hide_error_dialogs 0 || true
adb shell pm set-user-restriction no_system_error_dialogs 0 || true
adb shell cmd uimode night yes >/dev/null 2>&1 || true
adb shell cmd uimode night no >/dev/null 2>&1 || true
if [ "${YT_NIGHT:-no}" = "yes" ]; then adb shell cmd uimode night yes; fi
if [ -n "${YT_LOCALE:-}" ]; then
  adb shell cmd locale set-app-locales "$PKG" --user current --locales "$YT_LOCALE" || echo "app locale not set"
fi
RETRACE=$(ls "$ANDROID_HOME"/cmdline-tools/*/bin/retrace 2>/dev/null | head -n 1)
crashed=""
# designs whose gesture checks failed (the app kept running)
failed=""
# extras for every start of the app; the gesture checks log the gestures (YtTestHooks, emulator only)
START_EXTRAS=()
[ "${YT_GESTURES:-no}" = "yes" ] && START_EXTRAS+=(--ez yt_gesture_log true)

# the time on the device, to line up with its log
mark() { echo "$(timeout 5 adb shell date +%H:%M:%S 2>/dev/null | tr -d '\r') $*"; }
shot() { dismiss_anr; mark "screenshot $1"; timeout 20 adb exec-out screencap -p > "screens/$1.png" || true; }
# Android's "… isn't responding" dialog lies over the screen and takes keys like Back: press
# "Wait". uiautomator cannot read the screen while the app hangs, so the button is found from the
# dialog's frame: "Wait" is the last of its rows (Android 14: title, Close app, Wait).
# Another app's dialog (the emulator's launcher, Quickstep, hangs under load) is closed by
# stopping that app instead: no tap, since a tap would cut off a gesture the checks are making,
# and the dialog would come back every few seconds while the launcher stays hung.
dismiss_anr() {
  local title f other
  title=$(timeout 15 adb shell dumpsys window windows 2>/dev/null | grep -a -o "Application Not Responding: [A-Za-z0-9._]*" | head -n 1)
  [ -n "$title" ] || return 0
  other=${title#Application Not Responding: }
  if [ -n "$other" ] && [ "$other" != "$PKG" ]; then
    mark "not-responding dialog ($title): stopping $other"
    timeout 10 adb shell am force-stop "$other" || true
    sleep 1
    return 0
  fi
  f=$(frame_of "$title")
  [ -n "$f" ] || return 0
  set -- $f
  [ $(( $4 - $2 )) -gt 40 ] || return 0
  mark "not-responding dialog ($title): Wait"
  timeout 10 adb shell input tap $(( $1 + ($3 - $1) * 3 / 10 )) $(( $4 - ($4 - $2) / 5 )) || true
  sleep 2
}
# the screen's elements as uiautomator sees them, one node per line. The dump waits until the
# screen has been still for a second; a screen that keeps changing (a running animation, a
# playing video's clock) fails it with "could not get idle state". The old file goes first, so
# such a failure yields nothing instead of an earlier screen; once that happens the dump is
# tried again with the animations off for the moment. Its own notes go to stderr, since callers
# read its output.
ui_dump() {
  local out
  timeout 10 adb shell rm -f /sdcard/ui.xml >/dev/null 2>&1 || true
  # a dump that never finds the screen idle can hang until the timeout without a word (Glas
  # redraws all the time); only "dumped to" counts as done
  out=$(timeout 25 adb shell uiautomator dump /sdcard/ui.xml 2>&1 | tr -d '\r')
  case "$out" in
    *"dumped to"*) ;;
    *) printf '%s\n' "${out:-no dump within 25 s}" | grep -a -i -m1 -E 'error|exception|no dump' || echo "no dump: ${out:0:80}" ;;
  esac
}
ui() {
  local err scales k
  err=$(ui_dump)
  if [ -n "$err" ]; then
    # a not-responding dialog takes the place of the app's window in the dump
    dismiss_anr >&2
    scales=""
    for k in window_animation_scale transition_animation_scale animator_duration_scale; do
      scales="$scales $(timeout 10 adb shell settings get global "$k" 2>/dev/null | tr -d '\r')"
      timeout 10 adb shell settings put global "$k" 0 >/dev/null 2>&1 || true
    done
    sleep 1
    err=$(ui_dump)
    # shellcheck disable=SC2086 # three values
    set -- $scales
    for k in window_animation_scale transition_animation_scale animator_duration_scale; do
      case "${1:-}" in ''|null) timeout 10 adb shell settings delete global "$k" >/dev/null 2>&1 || true ;;
        *) timeout 10 adb shell settings put global "$k" "$1" >/dev/null 2>&1 || true ;; esac
      shift || true
    done
    mark "ui dump: animations off for a moment${err:+, still: $err}" >&2
  fi
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
  dismiss_anr
  mark "screenshot $1"
  timeout 20 adb exec-out screencap > "screens/$1.raw" || true
  python3 -I scripts/wave-check.py --png "screens/$1.raw" "screens/$1.png" 2>/dev/null || true
}
# presses "Wait" on any not-responding dialog during the whole check (see above)
anr_watch() {
  while true; do
    sleep 3
    timeout 15 adb shell dumpsys window windows 2>/dev/null | grep -a -q "Application Not Responding" && dismiss_anr
  done
}
anr_watch > anr-watch.txt 2>&1 &
ANR_WATCH=$!
trap 'kill "$ANR_WATCH" 2>/dev/null || true' EXIT
# the local test video: into the app's private files after its first start (pm clear wipes them,
# so once per design), with the owner and SELinux label of the app's own files, so the app reads
# it like one of its downloads. Needs adb root, which the emulator images allow.
TEST_VIDEO_SRC="scripts/fixtures/yt-test.mp4"
TEST_VIDEO=""
push_test_video() {
  local dir="/data/user/0/$PKG/files" ref owner label
  TEST_VIDEO=""
  if ! is_root; then
    # adbd restarts for root, which ends the running log capture: start it again, appending
    if ! get_root; then
      echo "::warning::No adb root: the test video cannot go into the app's files"
      return 1
    fi
    if [ -n "${logpid:-}" ] && ! kill -0 "$logpid" 2>/dev/null; then
      adb logcat -v threadtime >> "logcat-${design:-standard}.txt" 2>/dev/null &
      logpid=$!
    fi
  fi
  # the files dir appears on the app's first use of it; until then its data dir has the same owner and label
  ref="$dir"
  timeout 10 adb shell "test -d $dir" || ref="/data/user/0/$PKG"
  owner=$(timeout 10 adb shell stat -c %u:%g "$ref" 2>/dev/null | tr -d '\r')
  label=$(timeout 10 adb shell ls -Zd "$ref" 2>/dev/null | tr -d '\r' \
    | awk '{ for (i = 1; i <= NF; i++) if ($i ~ /^u:object_r:/) { print $i; exit } }')
  if [ -z "$owner" ] || [ -z "$label" ]; then
    echo "::warning::Owner (${owner:-?}) or SELinux label (${label:-?}) of $ref unknown: no test video"
    return 1
  fi
  if ! timeout 60 adb push "$TEST_VIDEO_SRC" "$dir/yt-test.mp4" >/dev/null; then
    echo "::warning::The test video could not be pushed"
    return 1
  fi
  if ! timeout 20 adb shell "chown $owner $dir $dir/yt-test.mp4 && chmod 771 $dir && chmod 600 $dir/yt-test.mp4 && chcon $label $dir $dir/yt-test.mp4"; then
    echo "::warning::The test video's owner or label could not be set"
    return 1
  fi
  mark "test video: $(timeout 10 adb shell ls -lZ "$dir/yt-test.mp4" 2>/dev/null | tr -d '\r')"
  TEST_VIDEO="$dir/yt-test.mp4"
}
# opens the test video the way the downloads screen opens a downloaded one (YtTestHooks, emulator
# only); further extras for the hooks may follow, e.g. --ei yt_queue_test 3 or --el yt_seek_test 60000.
# Returns once the app logs "test hooks done" (the video plays, the extras ran), at most
# OPEN_WAIT s (default 60). Status 1 if there is no test video, the app logs that it failed, or
# it logs nothing at all within 15 s: a build without the hooks (main before round 12) ignores
# the parameter, so the callers then go on without the test video.
# shellcheck disable=SC2120 # the gesture checks pass extras
open_test_video() {
  local log="logcat-${design:-standard}.txt" from waited=0 lines failed
  if [ -z "$TEST_VIDEO" ]; then mark "no test video on the device"; return 1; fi
  from=$(wc -l < "$log" 2>/dev/null || echo 0)
  mark "open the test video $*"
  timeout 30 adb shell am start -n "$PKG/$ACTIVITY" --ez yt_quiet true ${START_EXTRAS[@]+"${START_EXTRAS[@]}"} \
    --es yt_test_video "$TEST_VIDEO" "$@" >/dev/null || echo "test video start timed out"
  while :; do
    lines=$(tail -n +"$((from + 1))" "$log" 2>/dev/null | grep -a -E " [IW] YtTest *: " || true)
    if printf '%s\n' "$lines" | grep -a -q -E " I YtTest *: test hooks done"; then
      mark "test video ready"
      return 0
    fi
    failed=$(printf '%s\n' "$lines" | grep -a -E " W YtTest *: test video" | tail -n 1 | sed -E 's/^.* W YtTest *: //')
    if [ -n "$failed" ]; then
      mark "test video failed: $(printf '%s' "$failed" | cut -c1-160)"
      return 1
    fi
    if [ -z "$lines" ] && [ "$waited" -ge 15 ]; then
      mark "the app logs nothing about the test video (a build without YtTestHooks?)"
      return 1
    fi
    if [ "$waited" -ge "${OPEN_WAIT:-60}" ]; then
      mark "test video not ready after $waited s"
      return 1
    fi
    sleep 1
    waited=$((waited + 1))
  done
}
# the online video in the player, as the screens did before the test video
open_online_video() {
  timeout 30 adb shell am start -n "$PKG/$ACTIVITY" --es videoId "${YT_VIDEO:-jNQXAC9IVRw}" --ez yt_quiet true \
    || echo "video start timed out"
}
# back until YT is in front again (at most 5 times)
back_to_yt() {
  for _ in 1 2 3 4 5; do
    timeout 20 adb shell dumpsys activity activities 2>/dev/null | grep -a -m1 "topResumedActivity" | grep -q "$PKG" && return 0
    dismiss_anr; timeout 10 adb shell input keyevent KEYCODE_BACK || true
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
  dismiss_anr; timeout 10 adb shell input keyevent KEYCODE_BACK || true
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
# the emulator itself can die (its GPU emulation is fragile, run 42285503145 lost it 2 minutes
# in); that is no crash of the app. Then adb is restarted, and if the device stays away the
# emulator is started again as the workflow started it (YT_EMU_OPTIONS), at most twice per job,
# set up again (installation, root, dark mode), and the design it took with it runs again.
device_ok() {
  local waited=0
  while [ "$waited" -lt "$1" ]; do
    [ "$(timeout 10 adb get-state 2>/dev/null)" = "device" ] && return 0
    sleep 5
    waited=$(( waited + 5 ))
  done
  return 1
}
revives=0
revive_emulator() {
  local waited=0 k
  [ "$revives" -lt 2 ] || return 1
  revives=$(( revives + 1 ))
  echo "::warning::The emulator is gone ($1); attempt $revives to bring it back"
  timeout 20 adb kill-server >/dev/null 2>&1 || true
  timeout 20 adb start-server >/dev/null 2>&1 || true
  if ! device_ok 45; then
    if [ -z "${YT_EMU_OPTIONS:-}" ] || [ ! -x "${ANDROID_HOME:-}/emulator/emulator" ]; then
      echo "no emulator command (YT_EMU_OPTIONS, ANDROID_HOME) to start it again"
      return 1
    fi
    pkill -f qemu-system >/dev/null 2>&1 || true
    sleep 3
    # shellcheck disable=SC2086 # the options are words
    "$ANDROID_HOME/emulator/emulator" -port 5554 -avd "${YT_AVD:-test}" $YT_EMU_OPTIONS > "emulator-restart-$revives.txt" 2>&1 &
    device_ok 300 || { echo "the new emulator did not come up"; tail -n 20 "emulator-restart-$revives.txt"; return 1; }
  fi
  while [ "$(timeout 10 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; do
    [ "$waited" -ge 300 ] && { echo "no boot within 300 s"; return 1; }
    sleep 5
    waited=$(( waited + 5 ))
  done
  timeout 10 adb shell input keyevent 82 >/dev/null 2>&1 || true
  for k in window_animation_scale transition_animation_scale animator_duration_scale; do
    timeout 10 adb shell settings put global "$k" 0 >/dev/null 2>&1 || true
  done
  timeout 180 adb install -r "$APK" >/dev/null || { echo "installing again failed"; return 1; }
  if [ "${YT_FLOAT:-no}" = "yes" ] || [ "${YT_TEST_VIDEO:-no}" = "yes" ]; then get_root || true; fi
  timeout 10 adb shell settings put global hide_error_dialogs 0 >/dev/null 2>&1 || true
  if [ "${YT_NIGHT:-no}" = "yes" ]; then timeout 10 adb shell cmd uimode night yes >/dev/null 2>&1 || true; fi
  echo "the emulator is back"
}
# shellcheck disable=SC2206 # the designs are words
todo=($DESIGNS)
next=0
retried=""
while [ "$next" -lt "${#todo[@]}" ]; do
  design="${todo[$next]}"
  next=$(( next + 1 ))
  if [ "$(timeout 15 adb get-state 2>/dev/null)" != "device" ] && ! revive_emulator "before $design"; then
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
    timeout 30 adb shell am start -n "$PKG/$ACTIVITY" --ez yt_quiet true ${START_EXTRAS[@]+"${START_EXTRAS[@]}"} || echo "start timed out"
  else
    timeout 30 adb shell am start -n "$PKG/$ACTIVITY" --es yt_design "$design" --ez yt_quiet true ${START_EXTRAS[@]+"${START_EXTRAS[@]}"} || echo "start timed out"
  fi
  sleep "$WAIT"
  if [ "${YT_TEST_VIDEO:-no}" = "yes" ]; then
    mkdir -p screens
    push_test_video || true
    # with the screens the video opens there (after the start screen's shot); otherwise here,
    # so the gesture checks begin with it
    if [ -n "$TEST_VIDEO" ] && [ "${YT_SCREENS:-no}" != "yes" ]; then
      # a build without the hooks gets the online video, so the gesture checks start in the player
      # shellcheck disable=SC2119
      if ! open_test_video; then
        echo "::warning::$design: the test video did not open; the online video instead"
        open_online_video
      fi
      sleep 15
      shot "$design-0-test-video"
    fi
  fi
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
  # the floating player once (on the first design): its checks take a few minutes
  if [ "${YT_FLOAT:-no}" = "yes" ] && [ "$design" = "${DESIGNS%% *}" ]; then
    mkdir -p screens
    float_log() { grep -a -E "YtFloat|YtWave" "logcat-$design.txt" | sed -E 's/^[0-9-]+ ([0-9:.]+) [^:]*: /\1 /' | cut -c1-220; }
    timeout 20 adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow || echo "appops failed"
    timeout 30 adb shell am start -n "$PKG/$ACTIVITY" --es videoId "${YT_VIDEO:-jNQXAC9IVRw}" \
      --ez yt_quiet true --ez yt_pip_test true --ez yt_wave_test true || echo "video start timed out"
    sleep 18
    shot "$design-float-0-player"
    fpid=$(timeout 10 adb shell pidof "$PKG" | tr -d '\r')
    newest=$(timeout 10 adb shell ls -t /data/anr 2>/dev/null | tr -d '\r' | head -n 1)
    mark "Home"
    timeout 10 adb shell input keyevent KEYCODE_HOME || true
    sleep 1
    # what the main thread does 1 s after Home (a stack dump, as Android takes it for an ANR);
    # writing it can take seconds on a slow emulator, so wait for the new file
    if [ -n "${fpid:-}" ]; then
      timeout 10 adb shell kill -3 "$fpid" || true
      dump=""
      for _ in $(seq 1 20); do
        sleep 1
        dump=$(timeout 10 adb shell ls -t /data/anr 2>/dev/null | tr -d '\r' | head -n 1)
        [ -n "$dump" ] && [ "$dump" != "${newest:-}" ] && break
        dump=""
      done
      sleep 1
      echo "::group::$design: threads 1.5 s after Home ($dump)"
      if [ -n "${dump:-}" ]; then
        for thread in '"main"' '"RenderThread"' '"ExoPlayer:Playback"'; do
          timeout 20 adb shell cat "/data/anr/$dump" 2>/dev/null \
            | awk -v t="$thread" 'index($0, t) == 1 {p=1} p{print} p&&/^$/{exit}' | head -n 40 | cut -c1-180
        done
      fi
      echo "::endgroup::"
    fi
    sleep 1
    rawshot "$design-float-1-floating"
    echo "::group::$design: why the floating player shows or not (window, process, frames)"
    timeout 30 adb shell dumpsys window windows 2>/dev/null | grep -a -A70 "YT player}" \
      | grep -a -E "mHasSurface|isReadyForDisplay|mDrawState|isOnScreen|isVisible|mViewVisibility|fl=|Frames|mShownAlpha|mLastHidden|mWindowRemovalAllowed|mOrientationChanging|mDestroying|mAnimatingExit|mHidden|HIDE" \
      | cut -c1-220 | head -24 || true
    fpid=$(timeout 10 adb shell pidof "$PKG" | tr -d '\r')
    echo "pid ${fpid:-none}"
    timeout 30 adb shell dumpsys activity processes "$PKG" 2>/dev/null \
      | grep -a -i -E "ProcessRecord|curAdj|setAdj|procState|adjType|hasOverlayUi|overlay|frozen|freez|cached|foregroundServices|hasShownUi" | cut -c1-200 | head -24 || true
    timeout 30 adb shell dumpsys gfxinfo "$PKG" 2>/dev/null | grep -a -E "Total frames|Janky|Window:|---PROFILEDATA" | head -12 || true
    echo "--- the app's own warnings"
    [ -n "${fpid:-}" ] && timeout 20 adb logcat -d --pid="$fpid" "*:W" 2>/dev/null | tail -n 30 | cut -c1-220 || true
    echo "--- what the main thread was doing, if Android saw it hang (ANR trace)"
    if timeout 20 adb logcat -d 2>/dev/null | grep -a -q "ANR in app.tra1c0.yt\|ANR in Window.*tra1c0"; then
      timeout 20 adb root >/dev/null 2>&1 || true
      sleep 3
      anr=$(timeout 20 adb shell ls -t /data/anr 2>/dev/null | tr -d '\r' | head -n 1)
      echo "trace: ${anr:-none}"
      [ -n "${anr:-}" ] && timeout 20 adb shell cat "/data/anr/$anr" 2>/dev/null \
        | awk '/^"main"/{p=1} p{print} p&&/^$/{exit}' | head -n 60 | cut -c1-200
      timeout 20 adb shell cat "/data/anr/$anr" 2>/dev/null | awk '/^"RenderThread"/{p=1} p{print} p&&/^$/{exit}' | head -n 30 | cut -c1-200
    else
      echo "no ANR"
    fi
    echo "--- the system about the app (freezer, kills, windows)"
    timeout 20 adb logcat -d 2>/dev/null | grep -a -i -E "freez|tra1c0|YT player" | grep -a -v -E "YtFloat|YtWave" | tail -n 30 | cut -c1-220 || true
    echo "::endgroup::"
    echo "::group::$design: YT's own floating player and the wave"
    read -r l t r b <<< "$(frame_of "YT player")"
    # the newest size the window logged (added, a drag, a release)
    float_size() { float_log | grep -E "float (added|drag|released)" | tail -n 1 | sed -E 's/.*video=([0-9]+) x ([0-9]+) strip=([0-9]+).*/\1 \2 \3/'; }
    read -r vw vh strip <<< "$(float_size)"
    echo "window: ${l:-?} ${t:-?} ${r:-?} ${b:-?}; video ${vw:-?} x ${vh:-?}, strip ${strip:-?}"
    pinned=$(timeout 20 adb shell dumpsys activity activities 2>/dev/null | grep -a -c 'mode=pinned' || true)
    echo "pinned tasks (must be none): ${pinned:-0}"
    float_ok=yes
    if [ "${pinned:-0}" != "0" ]; then echo "::error::$design: Android's picture-in-picture started as well"; float_ok=no; fi
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
      read -r vw vh strip <<< "$(float_size)"
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
      # the controls hide after 3 s; a slow emulator takes a while to show them, so no
      # screenshot in between (it would cost that time)
      sleep 1
      timeout 10 adb shell input tap "$((al + off))" "$((at + off))" || true
      sleep 1
      shot "$design-float-5-after-open"
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
    # straight to the running main screen (a link would start it anew, with the welcome sheet);
    # the local test video when there is one, since online videos do not play in the emulator;
    # the online one when it does not open (a build without the hooks), so the shots still show
    # the player and the mini player instead of the home screen and the launcher
    # shellcheck disable=SC2119
    if [ -n "$TEST_VIDEO" ] && open_test_video; then
      :
    else
      [ -n "$TEST_VIDEO" ] && echo "::warning::$design: the test video did not open; the online video instead"
      open_online_video
    fi
    sleep 18
    timeout 20 adb exec-out screencap -p > "screens/$design-2-player.png" || true
    dismiss_anr; timeout 10 adb shell input keyevent KEYCODE_BACK || true
    sleep 5
    timeout 20 adb exec-out screencap -p > "screens/$design-3-mini.png" || true
  fi
  # round 12: the gesture checks of the packages (scripts/gestures/a.sh ... f.sh, shorts.sh and search.sh, helpers in lib.sh),
  # with the animations on as on a phone; the runner turns them off for everything else
  if [ "${YT_GESTURES:-no}" = "yes" ]; then
    mkdir -p screens
    echo "::group::$design: gesture checks"
    gesture_fails=0
    # shellcheck disable=SC2034 # read by lib.sh
    LOG_FROM=0
    # shellcheck source=scripts/gestures/lib.sh
    . scripts/gestures/lib.sh
    anim_on
    # shellcheck source=/dev/null
    for f in scripts/gestures/[a-f].sh scripts/gestures/shorts.sh scripts/gestures/search.sh; do . "$f"; done
    anim_off
    rm -f screens/*.raw
    echo "gesture checks failed: $gesture_fails"
    echo "::endgroup::"
    if [ "$gesture_fails" != 0 ]; then
      echo "::error::$design: $gesture_fails gesture checks failed"
      failed="$failed $design"
    fi
  fi
  if [ -n "$TEST_VIDEO" ]; then
    echo "::group::$design: test hooks"
    grep -a -E " [VDIWEF] YtTest *: " "logcat-$design.txt" | sed -E 's/^[0-9-]+ ([0-9:.]+) [^:]*: /\1 /' | cut -c1-220 | head -40 || true
    echo "::endgroup::"
    TEST_VIDEO=""
  fi
  if timeout 20 adb shell pidof "$PKG" >/dev/null 2>&1; then state="running"; else state="NOT RUNNING"; fi
  kill "$logpid" 2>/dev/null; wait "$logpid" 2>/dev/null
  # the emulator went during this design: no verdict on the app; once more after the revival
  if [ "$state" != "running" ] && [ "$(timeout 15 adb get-state 2>/dev/null)" != "device" ]; then
    echo "::warning::$design: the emulator went during the run; its log is in logcat-$design.txt"
    mv -f "logcat-$design.txt" "logcat-$design-emulator-gone.txt" 2>/dev/null || true
    case " $retried " in
      *" $design "*) ;;
      *) retried="$retried $design"; todo=("${todo[@]:0:$next}" "$design" "${todo[@]:$next}") ;;
    esac
    continue
  fi
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
  anrs=$(grep -a -c "ANR in $PKG" "logcat-$design.txt" || true)
  [ "${anrs:-0}" -gt 0 ] && echo "::warning::$design: Android saw the app not responding $anrs time(s) (on the emulator mostly the GPU programs of a cold start; see the thread stacks)"
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
    # an ANR leaves the stacks of the app's threads in /data/anr (readable as root, which the
    # emulator images allow): where the main thread waited, and what the render thread did
    timeout 20 adb root >/dev/null 2>&1 || true
    for f in $(timeout 10 adb shell ls -t /data/anr 2>/dev/null | tr -d '\r' | head -n 4); do
      timeout 20 adb shell cat "/data/anr/$f" 2>/dev/null > anr-dump.txt || continue
      grep -q "Cmd line: $PKG" anr-dump.txt || continue
      echo "--- main thread in /data/anr/$f"
      awk '/^"main"/{p=1} p{print} p&&/^$/{exit}' anr-dump.txt | head -n 60 | cut -c1-220
      echo "--- render thread"
      awk '/^"RenderThread"/{p=1} p{print} p&&/^$/{exit}' anr-dump.txt | head -n 30 | cut -c1-220
      break
    done
    rm -f anr-dump.txt
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

if [ -n "$failed" ]; then
  echo "::error::Gesture checks failed in:$failed (no crash; see the FAIL lines)"
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
[ -n "$failed" ] && exit 1
echo "No crash in any of the designs: $DESIGNS"
