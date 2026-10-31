# shellcheck shell=bash
# YT round 12, package C: its gesture checks.
# The player gestures: scrubbing, the return point, the loop, the compass, sharing a moment.
# Sourced by crash-check.sh once per design with YT_GESTURES=yes ($design is the design); the
# helpers are in lib.sh. So far the time magnifier (levels, frames, cancelling) and the rewind
# memory's chip; each check opens the test video anew (the app's parameters are in
# YtPlayerGestureTestHooks).
# shellcheck source-path=SCRIPTDIR source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh" || return 0

# a result line like expect_log's, for what the script works out itself
c_verdict() {
  local result="$1" what="$2" detail="$3"
  [ "$result" = OK ] || gesture_fails=$(( ${gesture_fails:-0} + 1 ))
  echo "$result ${design:-?} c: $what: $detail" | cut -c1-260
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    printf -- '- **%s** %s, c: %s: %s\n' "$result" "${design:-?}" "$what" "$(printf '%s' "$detail" | cut -c1-160)" >> "$GITHUB_STEP_SUMMARY"
  fi
}

# the test video with the controls up; prints "left top right bottom" of the time bar
c_open_with_bar() {
  # the controls go up before "test hooks done", which open_test_video waits for
  open_test_video --ez yt_controls_test true "$@" || return 1
  bounds_of 'id/exo_progress$'
}

# how far the position moved per px of finger at 1x and at 1/8, from the "move" lines
c_ratio() {
  tag_messages YtScrub | awk '
    $1 == "move" {
      dx = 0; dms = 0; lv = ""
      for (i = 2; i <= NF; i++) {
        split($i, kv, "=")
        if (kv[1] == "dx") dx = kv[2] + 0
        else if (kv[1] == "dms") dms = kv[2] + 0
        else if (kv[1] == "level") lv = kv[2]
      }
      if (dx == 0) next
      if (dx < 0) dx = -dx
      if (dms < 0) dms = -dms
      px[lv] += dx; ms[lv] += dms
    }
    END {
      if (px["1/1"] == 0 || px["1/8"] == 0 || ms["1/1"] == 0) { print "none"; exit }
      printf "%.3f %.1f %.1f\n", (ms["1/8"] / px["1/8"]) / (ms["1/1"] / px["1/1"]), ms["1/1"] / px["1/1"], ms["1/8"] / px["1/8"]
    }'
}

c_checks() {
  local b l t r bt x y i up step ratio rest xy

  # 1. the magnifier: 100 px to the right at the bar, up into the eighth, 100 px more
  log_mark
  if ! b=$(c_open_with_bar --el yt_seek_test 60000); then
    mark "c: no test video or no time bar, the player checks are skipped"
    return 0
  fi
  read -r l t r bt <<< "$b"
  y=$(( (t + bt) / 2 ))
  x=$(( l + (r - l) * 3 / 10 ))
  up=$(dp 125)
  finger_down "$x" "$y"
  for i in 1 2 3 4 5; do finger_move $(( x + 20 * i )) "$y"; done
  x=$(( x + 100 ))
  for i in 1 2 3 4 5; do finger_move "$x" $(( y - up * i / 5 )); done
  for i in 1 2 3 4 5; do finger_move $(( x + 20 * i )) $(( y - up )); done
  rawshot "$design-c-1-lens"
  finger_up $(( x + 100 )) $(( y - up ))
  expect_log YtScrub 'level=1/8'
  expect_log YtScrub 'scrub end target=[0-9]+ level=1/8'
  ratio=$(c_ratio)
  read -r ratio rest <<< "$ratio"
  if [ "$ratio" != none ] && awk -v r="$ratio" 'BEGIN { exit !(r > 0 && r <= 0.2) }'; then
    c_verdict OK "an eighth as fast up there" "ratio $ratio (ms per px at 1x and 1/8: $rest)"
  else
    c_verdict FAIL "an eighth as fast up there" "ratio ${ratio:-none} $rest"
  fi

  # 2. single frames: up past the last line, three steps of 12 dp; and the exact seek
  log_mark
  if b=$(c_open_with_bar --el yt_seek_test 60000); then
    read -r l t r bt <<< "$b"
    y=$(( (t + bt) / 2 ))
    x=$(( l + (r - l) / 2 ))
    up=$(dp 175)
    step=$(( $(dp 12) + 1 ))
    finger_down "$x" "$y"
    for i in 1 2 3 4 5 6; do finger_move "$x" $(( y - up * i / 6 )); done
    for i in 1 2 3; do finger_move $(( x + step * i )) $(( y - up )); done
    rawshot "$design-c-2-frames"
    finger_up $(( x + step * 3 )) $(( y - up ))
    expect_log YtScrub 'level=frame'
    expect_log YtScrub 'scrub end target=[0-9]+ level=frame frames=3 '
  fi

  # 3. cancelling: to the right, then far below the bar; letting go there seeks nowhere
  log_mark
  if b=$(c_open_with_bar --el yt_seek_test 60000); then
    read -r l t r bt <<< "$b"
    y=$(( (t + bt) / 2 ))
    x=$(( l + (r - l) / 4 ))
    finger_down "$x" "$y"
    for i in 1 2 3 4; do finger_move $(( x + 40 * i )) "$y"; done
    for i in 1 2 3; do finger_move $(( x + 160 )) $(( y + $(dp 70) * i / 3 )); done
    finger_up $(( x + 160 )) $(( y + $(dp 70) ))
    expect_log YtScrub 'scrub end target=none .*cancelled=true'
  fi

  # 4. the return point: a jump back of 10 s, the chip, a tap on it goes back exactly
  log_mark
  if open_test_video --el yt_seek_test 90000 --el yt_back_test 10000; then
    expect_log YtTest 'back test from=[0-9]+ to=[0-9]+'
    # the chip shows 0.7 s after the jump, for 5 s: find it, take the shot, tap
    sleep 0.8
    expect_log YtGesture 'return chip shown return=[0-9]+'
    if xy=$(center_of 'id/yt_return_chip$'); then
      read -r x y <<< "$xy"
      rawshot "$design-c-3-chip"
      timeout 10 adb shell input tap "$x" "$y" || true
      expect_log YtGesture 'return tap to=[0-9]+'
    else
      c_verdict FAIL "the return chip" "not on the screen"
    fi
  fi
}

c_checks
