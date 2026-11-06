# shellcheck shell=bash
# YT round 12, the Shorts view (8.1 the hidden timeline, 8.2 autoscroll). Online videos do not
# play in the emulator, so the view opens on the test video and two made-up videos of the queue
# test (downloads that play the test video's 180 s file), through YtShortsTestHooks
# (--ei yt_shorts_test 3).
#
# Per design: the view opens and plays (log YtShorts "ready id=ytTestVid01"); a finger on the
# bottom edge opens the timeline and a drag to the side scrubs and seeks there, ticking once a
# second (YtHaptic "shorts_second_<design>"); the bar fades out again; a swipe up brings the
# next short with the design's page texture (YtHaptic "shorts_page_<design>"); back closes the
# view. Screenshots: <design>-shorts-1-view, -2-scrub-<step> (the open timeline under the
# finger), -3-faded, -4-next.
# Once per job (the first design): autoscroll. The second short is sought 5 s before its end;
# the countdown shows (-5-countdown) and the view moves on to the third (log YtShorts
# "autoscroll from=ytTestQ0001").
# Sourced by crash-check.sh once per design with YT_GESTURES=yes; the helpers are in lib.sh.
# shellcheck source-path=SCRIPTDIR source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh" || return 0

# the app's process is there; a FAIL line like expect_log's otherwise
shorts_alive() {
  local result=OK
  timeout 20 adb shell pidof "$PKG" >/dev/null 2>&1 || { result=FAIL; gesture_fails=$(( ${gesture_fails:-0} + 1 )); }
  echo "$result ${design:-?} shorts: app running after $1"
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    printf -- '- **%s** %s, shorts: app running after %s\n' "$result" "${design:-?}" "$1" >> "$GITHUB_STEP_SUMMARY"
  fi
}

shorts_checks() {
  local EXPECT_WAIT=30 size w h y x1 x2
  log_mark
  if ! open_test_video --ei yt_queue_test 2 --ei yt_shorts_test 3; then
    mark "shorts: no test video, the Shorts view is not checked"
    return 0
  fi
  expect_log YtShorts 'open n=3 start=ytTestVid01'
  expect_log YtShorts 'ready id=ytTestVid01' || true
  sleep 2
  rawshot "$design-shorts-1-view"

  size=$(screen_size)
  # shellcheck disable=SC2086 # two numbers
  set -- $size
  w=${1:-0}
  h=${2:-0}
  if ! numbers "$w" "$h" || [ "$w" -le 0 ]; then
    mark "shorts: no screen size"
    back_to_yt || true
    return 0
  fi
  # inside the 48 dp zone whatever the navigation bar is (24 dp gesture bar or 48 dp buttons),
  # and above it, so the system takes no swipe along the bar
  y=$(( h - $(dp 60) ))
  x1=$(( w / 5 ))
  x2=$(( w * 7 / 10 ))

  # 8.1: the timeline grows in under the finger, the drag scrubs, letting go seeks
  local EXPECT_WAIT=5
  SHOT_STEPS="8" drag "$x1" "$y" "$x2" "$y" 10 "$design-shorts-2-scrub"
  expect_log YtShorts 'timeline open=true'
  expect_log YtShorts 'seek to=[0-9]+'
  expect_log YtHaptic 'shorts_second_'
  sleep 2
  rawshot "$design-shorts-3-faded"
  expect_log YtShorts 'timeline open=false'

  # the next short, with the page texture of the design
  EXPECT_WAIT=20
  fling $(( w / 2 )) $(( h * 3 / 5 )) $(( w / 2 )) $(( h / 5 )) 120
  expect_log YtShorts 'ready id=ytTestQ0001'
  expect_log YtHaptic 'shorts_page_'
  sleep 1
  rawshot "$design-shorts-4-next"

  # 8.2, once per job: near the end of the second short the countdown, then on to the third
  if [ -z "${SHORTS_AUTO_DONE:-}" ]; then
    SHORTS_AUTO_DONE=yes
    log_mark
    timeout 30 adb shell am start -n "$PKG/$ACTIVITY" --ez yt_quiet true ${START_EXTRAS[@]+"${START_EXTRAS[@]}"} \
      --el yt_shorts_seek 175000 >/dev/null || mark "shorts: seek start timed out"
    if expect_log YtTest 'shorts seek from='; then
      sleep 3
      rawshot "$design-shorts-5-countdown"
      expect_log YtShorts 'autoscroll from=ytTestQ0001'
      expect_log YtShorts 'ready id=ytTestQ0002'
    fi
  fi

  show_log YtShorts 16
  log_mark
  EXPECT_WAIT=5
  timeout 10 adb shell input keyevent KEYCODE_BACK || true
  expect_log YtShorts 'closed'
  sleep 1
  shorts_alive "the Shorts view"
  back_to_yt || true
}

shorts_checks
