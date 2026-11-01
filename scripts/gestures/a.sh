# shellcheck shell=bash
# YT round 12, package A: its gesture checks.
# One mini player per design: the docked mini player, its long-press peek, the drop target.
# Sourced by crash-check.sh once per design with YT_GESTURES=yes ($design is the design); the
# helpers are in lib.sh.
# shellcheck source-path=SCRIPTDIR source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh" || return 0

a_checks() {
  local box logged c x y
  log_mark
  # the test video with three videos queued after it (the preview shows the next one), docked
  if ! open_test_video --ei yt_queue_test 3; then
    gesture_fail "a: no test video for the mini player checks"
    return 1
  fi
  sleep 4
  timeout 10 adb shell input keyevent KEYCODE_BACK || true
  sleep 3
  rawshot "$design-a-1-mini"
  # Glas: how far the bar's slab grows behind the docked player (YtLiquidNav)
  tag_messages YtMini | grep -a 'slab grow=' | tail -n 2
  expect_log YtMini '^dock '
  expect_log YtHaptic 'mini_dock'

  # the drop target is the docked box: the bounds it publishes are those of main_container
  # (2 px of slack for rounding)
  if box=$(bounds_of 'id/main_container$'); then
    logged=$(tag_messages YtMini | grep -a -E '^bounds ' | tail -n 1)
    if printf '%s\n' "$logged" | awk -v want="$box" '{ split(want, w, " "); d = 0
        for (i = 1; i <= 4; i++) { e = $(i + 1) - w[i]; if (e < 0) e = -e; if (e > d) d = e }
        exit (NF == 5 && d <= 2) ? 0 : 1 }'; then
      expect_log YtMini '^bounds '
    else
      mark "a: published \"$logged\", main_container is \"$box\""
      expect_log YtMini "^bounds ${box// / }\$"
    fi
  else
    gesture_fail "a: no main_container on screen"
  fi

  # a long press on the picture: the preview grows (the finger stays still), letting go
  # elsewhere than on its parts closes it again
  if c=$(center_of 'id/player$'); then
    read -r x y <<< "$c"
    finger_down "$x" "$y"
    sleep 1.6
    rawshot "$design-a-2-peek"
    expect_log YtMini 'peek=1'
    expect_log YtHaptic 'mini_peek'
    finger_up "$x" "$y"
    sleep 1.2
    rawshot "$design-a-3-after"
    expect_log YtMini 'peek=0 target=none'
  else
    gesture_fail "a: no picture of the mini player on screen"
  fi
}

# Glas, the Mint reference: no play or close button; a double tap toggles play (the glass glyph
# flashes), the preview's pill toggles on release, its row plays the next video, the glass
# arrows play the previous and next video. Positions from the docked strip: the strip's foot
# stays on the bar while it grows 2.3 times (YtGlassLayout).
a_glass_checks() {
  [ "${design:-}" = glass ] || return 0
  local box l t r b x y pill row arrow
  if ! box=$(bounds_of 'id/player$'); then
    gesture_fail "a-glass: no picture of the mini player on screen"
    return 0
  fi
  read -r l t r b <<< "$box"
  x=$(( (l + r) / 2 ))
  y=$(( (t + b) / 2 ))
  expect_log YtHaptic 'mini_dock_glass'

  log_mark
  driver_command DOUBLE "$x" "$y"
  sleep 0.15
  rawshot "$design-a-glass-1-flash"
  sleep 1
  expect_log YtMini 'play toggled'
  expect_log YtHaptic 'mini_play_glass'
  # back to playing for the rest
  driver_command DOUBLE "$x" "$y"
  sleep 1.2

  # hold, move onto the pill ("Wiedergabe"), let go: play toggles
  pill=$(( b - $(dp 89) ))
  log_mark
  finger_down "$x" "$y"
  sleep 1.6
  rawshot "$design-a-glass-2-peek"
  # Leave every expanded action before entering the pill. The preview can grow a
  # part underneath the initial finger; that part deliberately stays unarmed.
  finger_move "$x" "$(( b - $(dp 150) ))"
  finger_move "$x" "$pill"
  sleep 0.3
  rawshot "$design-a-glass-3-pill"
  finger_up "$x" "$pill"
  sleep 1.2
  expect_log YtMini 'peek=0 target=pill'
  expect_log YtMini 'play toggled'
  expect_log YtHaptic 'mini_peek_full_glass'

  # hold, move onto the next video's row, let go: it plays. The press starts on the strip's foot
  # under the tab row's rim: the row grows over the strip's middle, and a part that grew under
  # the resting finger does not act until the finger moved onto it from elsewhere
  row=$(( b - $(dp 40) ))
  log_mark
  finger_down "$x" "$(( b - $(dp 3) ))"
  sleep 1.6
  finger_move "$(( x + $(dp 30) ))" "$row"
  sleep 0.3
  rawshot "$design-a-glass-4-row"
  finger_up "$(( x + $(dp 30) ))" "$row"
  sleep 2
  expect_log YtMini 'peek=0 target=next'
  expect_log YtQueue 'play next from mini'

  # the glass arrow beside the picture's right edge
  sleep 3
  if box=$(bounds_of 'id/player$'); then
    read -r l t r b <<< "$box"
    arrow=$(( r - $(dp 29) ))
    log_mark
    timeout 10 adb shell input tap "$arrow" "$(( b - $(dp 41) ))" || true
    sleep 1.5
    expect_log YtHaptic 'mini_button_glass'
    rawshot "$design-a-glass-5-arrow"
  fi
}

a_checks
a_glass_checks
