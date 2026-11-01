# shellcheck shell=bash
# YT round 12, package C: its gesture checks.
# The player gestures: scrubbing, the return point, the loop, the compass, sharing a moment.
# Sourced by crash-check.sh once per design with YT_GESTURES=yes ($design is the design); the
# helpers are in lib.sh. So far the time magnifier (levels, frames, cancelling), the rewind
# memory's chip, the explanation loop (marked on the time bar), hold and drag on the video
# (its compass, in the page and in fullscreen) and sharing onto the timeline; each check opens the test video anew (the app's parameters are in
# YtPlayerGestureTestHooks).
# shellcheck source-path=SCRIPTDIR source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh" || return 0

# a result line like expect_log's, for what the script works out itself
c_verdict() {
  gesture_checks=$(( ${gesture_checks:-0} + 1 ))
  local result="$1" what="$2" detail="$3"
  [ "$result" = OK ] || gesture_fails=$(( ${gesture_fails:-0} + 1 ))
  echo "$result ${design:-?} c: $what: $detail" | cut -c1-260
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    printf -- '- **%s** %s, c: %s: %s\n' "$result" "${design:-?}" "$what" "$(printf '%s' "$detail" | cut -c1-160)" >> "$GITHUB_STEP_SUMMARY"
  fi
}

# the test video with the controls up; prints "left top right bottom" of the time bar and
# nothing else (callers read it with $( ), so open_test_video's own lines go to stderr). The
# hook logs where the bar lies once the controls are up ("controls test shown=true bar=l t r b"):
# a playing video's clock keeps the screen from being still, which uiautomator's dump waits
# for, so the dump is only the fallback.
c_open_with_bar() {
  local bar
  # the controls go up before "test hooks done", which open_test_video waits for
  open_test_video --ez yt_controls_test true "$@" >&2 || return 1
  # Measure the current view, after layout. The initial hook's bounds can be stale
  # after a resized video or a fullscreen transition.
  bar=$(bounds_of 'id/exo_progress$') || return 1
  local four='^-?[0-9]+ -?[0-9]+ -?[0-9]+ -?[0-9]+$'
  if ! [[ "$bar" =~ $four ]]; then
    bar=$(bounds_of 'id/exo_progress$') || { mark "c: no time bar on the screen" >&2; return 1; }
  fi
  [[ "$bar" =~ $four ]] || return 1
  echo "$bar"
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
    gesture_fail "c: no test video or time bar"
    return 0
  fi
  read -r l t r bt <<< "$b"
  y=$(( (t + bt) / 2 ))
  x=$(( l + (r - l) * 3 / 10 ))
  up=$(dp 125)
  # down and out of the slop in one call: a late first move would mark a loop instead
  finger_down_move "$x" "$y" $(( x + 40 )) "$y"
  for i in 3 4 5; do finger_move $(( x + 20 * i )) "$y"; done
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
    finger_down_move "$x" "$y" "$x" $(( y - up / 6 ))
    for i in 2 3 4 5 6; do finger_move "$x" $(( y - up * i / 6 )); done
    for i in 1 2 3; do finger_move $(( x + step * i )) $(( y - up )); done
    rawshot "$design-c-2-frames"
    finger_up $(( x + step * 3 )) $(( y - up ))
    expect_log YtScrub 'level=frame'
    expect_log YtScrub 'scrub end target=[0-9]+ level=frame frames=3 '
  else
    gesture_fail "c: required test video or control missing for check 2"
  fi

  # 3. cancelling: to the right, then far below the bar; letting go there seeks nowhere
  log_mark
  if b=$(c_open_with_bar --el yt_seek_test 60000); then
    read -r l t r bt <<< "$b"
    y=$(( (t + bt) / 2 ))
    x=$(( l + (r - l) / 4 ))
    finger_down_move "$x" "$y" $(( x + 40 )) "$y"
    for i in 2 3 4; do finger_move $(( x + 40 * i )) "$y"; done
    for i in 1 2 3; do finger_move $(( x + 160 )) $(( y + $(dp 70) * i / 3 )); done
    finger_up $(( x + 160 )) $(( y + $(dp 70) ))
    expect_log YtScrub 'scrub end target=none .*cancelled=true'
  else
    gesture_fail "c: required test video or control missing for check 3"
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
  else
    gesture_fail "c: required test video or control missing for check 4"
  fi

  # 5. the explanation loop: hold the bar at 30 % (A), drag about 5 s to the right (B), let go;
  # the video jumps to A and comes back there after B; then the chip's x ends it
  log_mark
  if b=$(c_open_with_bar --el yt_seek_test 60000); then
    read -r l t r bt <<< "$b"
    y=$(( (t + bt) / 2 ))
    x=$(( l + (r - l) * 3 / 10 ))
    # 5 s of the 180 s test video, in px of the bar
    step=$(( (r - l) * 5 / 180 / 4 + 1 ))
    finger_down "$x" "$y"
    sleep 0.9
    for i in 1 2 3 4; do finger_move $(( x + step * i )) "$y"; done
    rawshot "$design-c-4-loop-mark"
    finger_up $(( x + step * 4 )) "$y"
    expect_log YtLoop 'mark start editing=false'
    expect_log YtLoop 'set a=[0-9]+ b=[0-9]+'
    EXPECT_WAIT=12 expect_log YtLoop 'pass=2'
    if b=$(bounds_of 'id/yt_loop_chip$'); then
      rawshot "$design-c-5-loop-chip"
      read -r l t r bt <<< "$b"
      # the x sits at the chip's right end, inside its 7 dp padding
      timeout 10 adb shell input tap $(( r - $(dp 24) )) $(( (t + bt) / 2 )) || true
      expect_log YtLoop 'end \(cleared\)'
    else
      c_verdict FAIL "the loop chip" "not on the screen"
    fi
  else
    gesture_fail "c: required test video or control missing for check 5"
  fi

  # 6. hold and drag on the video (the defaults: up = fullscreen, down = minimize, there: leave it)
  log_mark
  if open_test_video --el yt_seek_test 60000; then
    up=$(dp 110)
    # left of the middle, clear of the play button should the controls be up
    if b=$(bounds_of 'id/player$'); then
      read -r l t r bt <<< "$b"
      x=$(( l + (r - l) * 3 / 10 ))
      y=$(( (t + bt) / 2 ))
      # up past the ring, back to the centre, let go: nothing runs
      finger_down "$x" "$y"
      sleep 0.9
      for i in 1 2 3 4; do finger_move "$x" $(( y - up * i / 4 )); done
      for i in 3 2 1 0; do finger_move "$x" $(( y - up * i / 4 )); done
      finger_up "$x" "$y"
      expect_log YtGesture 'hold begin'
      expect_log YtGesture 'hold dir=UP stage=ARMED'
      expect_log YtGesture 'hold cancel cancelled=false stage=CENTRE'
      # up again and let go there: fullscreen
      finger_down "$x" "$y"
      sleep 0.9
      for i in 1 2 3 4 5 6; do finger_move "$x" $(( y - up * i / 6 )); done
      rawshot "$design-c-6-compass"
      finger_up "$x" $(( y - up ))
      expect_log YtGesture 'hold run action=fullscreen dir=UP fullscreen=false'
      EXPECT_WAIT=5 expect_log YtGesture 'hold done action=fullscreen'
    else
      c_verdict FAIL "hold and drag" "no player on the screen"
    fi
    # in fullscreen (it may have turned the screen): down leaves it
    sleep 2
    if b=$(bounds_of 'id/player$'); then
      read -r l t r bt <<< "$b"
      x=$(( l + (r - l) * 3 / 10 ))
      y=$(( (t + bt) / 2 ))
      finger_down "$x" "$y"
      sleep 0.9
      for i in 1 2 3 4 5 6; do finger_move "$x" $(( y + up * i / 6 )); done
      rawshot "$design-c-7-compass-fullscreen"
      finger_up "$x" $(( y + up ))
      expect_log YtGesture 'hold run action=minimize dir=DOWN fullscreen=true'
      EXPECT_WAIT=5 expect_log YtGesture 'hold done action=minimize'
    else
      c_verdict FAIL "hold and drag in fullscreen" "no player on the screen"
    fi
  else
    gesture_fail "c: required test video or control missing for check 6"
  fi

  # 7. sharing onto the timeline: hold Share, let go short of the bar (nothing), then drag it
  # onto the bar, up into the finer levels, and let go there (the system's share sheet)
  log_mark
  if b=$(c_open_with_bar --el yt_seek_test 60000); then
    read -r l t r bt <<< "$b"
    if xy=$(center_of '^(Share|Teilen)$'); then
      read -r x y <<< "$xy"
      bt=$(( (t + bt) / 2 ))
      # half way up: still below the zone
      finger_down "$x" "$y"
      sleep 0.9
      for i in 1 2 3; do finger_move "$x" $(( y - (y - bt) * i / 8 )); done
      finger_up "$x" $(( y - (y - bt) * 3 / 8 ))
      expect_log YtShare 'share begin'
      EXPECT_WAIT=4 expect_log YtShare 'share cancelled'
      sleep 1
      # onto the bar at 40 %, then up for the finer levels
      log_mark
      up=$(dp 100)
      step=$(( l + (r - l) * 4 / 10 ))
      finger_down "$x" "$y"
      sleep 0.9
      for i in 1 2 3 4 5 6 7 8; do finger_move $(( x + (step - x) * i / 8 )) $(( y - (y - bt) * i / 8 )); done
      for i in 1 2 3; do finger_move $(( step + i * 20 )) $(( bt - up * i / 3 )); done
      rawshot "$design-c-8-share"
      finger_up $(( step + 60 )) $(( bt - up ))
      expect_log YtShare 'share pin at=[0-9]+ '
      expect_log YtShare 'level=1/[248] '
      expect_log YtShare 'share end t=[0-9]+ '
      EXPECT_WAIT=4 expect_log YtShare 'share t=[0-9]+'
      sleep 2
      rest=$(timeout 10 adb shell dumpsys activity activities 2>/dev/null | grep -a -E 'mResumedActivity|topResumedActivity' | head -n 2 | tr -s ' ' | cut -c1-200)
      if printf '%s' "$rest" | grep -q -i 'chooser\|resolver'; then
        c_verdict OK "share sheet" "$rest"
      else
        c_verdict FAIL "share sheet" "${rest:-no resumed activity}"
      fi
      rawshot "$design-c-9-share-sheet"
      timeout 10 adb shell input keyevent KEYCODE_BACK || true
      sleep 1
    else
      c_verdict FAIL "share onto the timeline" "no Share action on the screen"
    fi
  else
    gesture_fail "c: no test video or time bar for sharing"
  fi
}

c_checks
