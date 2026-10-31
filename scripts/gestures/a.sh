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
    mark "a: no test video; the online video instead"
    open_online_video
    sleep 15
  fi
  sleep 4
  timeout 10 adb shell input keyevent KEYCODE_BACK || true
  sleep 3
  rawshot "$design-a-1-mini"
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
    mark "a: no main_container on screen"
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
    mark "a: no picture of the mini player on screen"
  fi
}

a_checks
