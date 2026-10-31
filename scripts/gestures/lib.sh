# shellcheck shell=bash
# YT: helpers for the round-12 gesture checks (scripts/gestures/a.sh ... e.sh). crash-check.sh
# sources them once per design with YT_GESTURES=yes, after the screens, with the animations on.
# They build on crash-check.sh's own helpers (mark, shot, rawshot, ui, texts, tap, frame_of,
# back_to_yt, open_test_video) and variables (PKG, ACTIVITY, design, TEST_VIDEO), so they only
# work sourced from there. The scripts share its shell: no exit (return ends a script), no
# set -e, no ::group:: (the checks of a design already are one group).
#
#   open_test_video [am extras]     (crash-check.sh) opens the test video with further extras,
#                                   e.g. --ei yt_queue_test 3 --el yt_seek_test 300000; returns
#                                   once the app logs "test hooks done" (the queue is filled, the
#                                   seek made), status 1 if it did not open
#   anim_on / anim_off              the three animation scales to 1 or 0
#   bounds_of <regex> [n]           "l t r b" of the n-th (default first) element whose
#                                   resource-id, content-desc or text matches (awk regex, so ^ and
#                                   $ anchor: 'id/main_container$', '^Teilen$'); status 1 if none
#   center_of <regex> [n]           "x y" of the same element
#   finger_down / finger_move / finger_up <x> <y>
#                                   one touch event each (input motionevent), to build any gesture
#   drag <x1> <y1> <x2> <y2> <steps> [prefix]
#                                   finger down, <steps> moves, up; with a prefix a raw screenshot
#                                   after each move, screens/<prefix>-<step>.png (SHOT_STEPS="4 8 12"
#                                   only at those steps); the last one is taken before the finger lifts
#   hold_drag <same>                as drag, holding still 0.9 s after DOWN (past the long press)
#   fling <x1> <y1> <x2> <y2> [ms]  a quick swipe (default 80 ms), fast enough for a fling
#   screen_size / dp <n>            "w h" of the screen in px / <n> dp in px
#   log_mark                        expect_log and show_log look only at log lines from here on
#   expect_log <tag> <pattern>      OK if a line of <tag> since log_mark matches the grep -E
#                                   pattern (waits up to EXPECT_WAIT s, default 3); prints OK or
#                                   FAIL, adds the result to the job summary, and a FAIL fails the
#                                   design's check
#   show_log <tag> [n]              the last n (default 20) messages of <tag> since log_mark
#
# Timing: every input call costs about 0.15-0.4 s, so a drag of 12 steps takes 2-5 s. That suits
# checks that follow the finger; for speed use fling. Each touch event runs on its own, so the
# app sees a slow but real gesture: the floating player check moves Views this way; that Compose
# reacts the same is not checked yet.
#
# Log lines: the hooks log with Log.i under the tags YtGesture, YtMini, YtQueue, YtScrub, YtScene,
# YtHaptic and YtTest; most only with --ez yt_gesture_log true, which the first start of every
# design carries here (it stays on for the app's process).

if ! declare -F rawshot >/dev/null; then
  echo "scripts/gestures/lib.sh: source it from scripts/crash-check.sh (YT_GESTURES=yes)"
  # shellcheck disable=SC2317 # reached when run instead of sourced
  return 1 2>/dev/null || exit 1
fi

anim_scales() {
  local k
  for k in window_animation_scale transition_animation_scale animator_duration_scale; do
    timeout 10 adb shell settings put global "$k" "$1" || true
  done
}
anim_on() { mark "animations on"; anim_scales 1; sleep 1; }
anim_off() { mark "animations off"; anim_scales 0; }

bounds_of() {
  ui | RE="$1" N="${2:-1}" awk '
    # the value of one attribute of the node on this line ("" when it has none)
    function attr(name) {
      if (match($0, " " name "=\"[^\"]*\"")) return substr($0, RSTART + length(name) + 3, RLENGTH - length(name) - 4)
      return ""
    }
    {
      re = ENVIRON["RE"]
      if (attr("resource-id") ~ re || attr("content-desc") ~ re || attr("text") ~ re) {
        if (++seen < ENVIRON["N"] + 0) next
        b = attr("bounds"); gsub(/[^0-9-]+/, " ", b); sub(/^ +/, "", b); sub(/ +$/, "", b)
        print b; found = 1; exit
      }
    }
    END { exit found ? 0 : 1 }'
}

center_of() {
  local b l t r bt
  b=$(bounds_of "$@") || { mark "not found: \"$1\""; return 1; }
  read -r l t r bt <<< "$b"
  echo "$(( (l + r) / 2 )) $(( (t + bt) / 2 ))"
}

finger_down() { timeout 10 adb shell input motionevent DOWN "$1" "$2" || true; }
finger_move() { timeout 10 adb shell input motionevent MOVE "$1" "$2" || true; }
finger_up() { timeout 10 adb shell input motionevent UP "$1" "$2" || true; }

# drag and hold_drag: the hold in seconds first, then their arguments
drag_held() {
  local hold="$1" x1="$2" y1="$3" x2="$4" y2="$5" steps="${6:-10}" prefix="${7:-}" i
  [ "$steps" -ge 1 ] 2>/dev/null || steps=1
  mark "drag $x1,$y1 to $x2,$y2 in $steps moves${prefix:+, shots $prefix}"
  finger_down "$x1" "$y1"
  if [ "$hold" != 0 ]; then sleep "$hold"; fi
  for i in $(seq 1 "$steps"); do
    finger_move "$(( x1 + (x2 - x1) * i / steps ))" "$(( y1 + (y2 - y1) * i / steps ))"
    if [ -n "$prefix" ] && { [ -z "${SHOT_STEPS:-}" ] || [[ " $SHOT_STEPS " == *" $i "* ]]; }; then
      rawshot "$prefix-$i"
    fi
  done
  finger_up "$x2" "$y2"
}
drag() { drag_held 0 "$@"; }
hold_drag() { drag_held 0.9 "$@"; }

fling() {
  mark "fling $1,$2 to $3,$4 in ${5:-80} ms"
  timeout 10 adb shell input swipe "$1" "$2" "$3" "$4" "${5:-80}" || true
}

screen_size() {
  timeout 5 adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -n 1 | tr 'x' ' '
}
dp() {
  local density
  density=$(timeout 5 adb shell wm density | grep -o '[0-9]*' | tail -n 1)
  echo $(( $1 * ${density:-420} / 160 ))
}

# the log of the current design (written while the app runs), from the last log_mark on
gesture_log() { tail -n +"$(( ${LOG_FROM:-0} + 1 ))" "logcat-${design:-standard}.txt" 2>/dev/null; }
log_mark() { LOG_FROM=$(wc -l 2>/dev/null < "logcat-${design:-standard}.txt" || echo 0); }

# the messages of one tag (threadtime format: "date time pid tid level tag: message")
tag_messages() { gesture_log | grep -a -E " [VDIWEF] $1 *: " | sed -E "s/^.* [VDIWEF] $1 *: //"; }

show_log() { tag_messages "$1" | tail -n "${2:-20}" | cut -c1-220; }

expect_log() {
  local tag="$1" pattern="$2" line="" waited=0 result
  while :; do
    line=$(tag_messages "$tag" | grep -a -E -- "$pattern" | tail -n 1)
    [ -n "$line" ] && break
    [ "$waited" -ge "${EXPECT_WAIT:-3}" ] && break
    sleep 1
    waited=$((waited + 1))
  done
  if [ -n "$line" ]; then result=OK; else result=FAIL; gesture_fails=$(( ${gesture_fails:-0} + 1 )); fi
  echo "$result ${design:-?} $tag /$pattern/: ${line:-no such line}" | cut -c1-260
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    if [ -z "${GESTURE_SUMMARY_STARTED:-}" ]; then
      printf '### Gesture checks\n\n' >> "$GITHUB_STEP_SUMMARY"
      GESTURE_SUMMARY_STARTED=yes
    fi
    # shellcheck disable=SC2016 # the backticks are Markdown
    printf -- '- **%s** %s, %s `%s`: %s\n' "$result" "${design:-?}" "$tag" "$pattern" "$(printf '%s' "${line:-no such line}" | cut -c1-160)" >> "$GITHUB_STEP_SUMMARY"
  fi
  [ "$result" = OK ]
}
