# shellcheck shell=bash
# YT round 12, package B: its gesture checks.
# Dragging the video down shrinks it under the finger into the mini player.
# Sourced by crash-check.sh once per design with YT_GESTURES=yes ($design is the design); the
# helpers are in lib.sh.
#
# YtMinimizeDrag logs (tag YtGesture): "minimize grab from=full|mini ...", per frame
# "minimize frame ... err=<px>" (how far the grabbed point is from the finger on screen),
# "minimize release ... to=dock|open|close ... maxErrOk=true|false" and, once the transition has
# finished, "minimize end p_end=1|0" or "minimize close". Each check below waits for those lines.
# The last check throws the mini player away, so the player is closed afterwards.
# shellcheck source-path=SCRIPTDIR source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh" || return 0

b_checks() {
  local size h x0 y0 mx my mid
  if ! open_test_video; then
    mark "B: no test video, the minimize checks are skipped"
    return 0
  fi
  sleep 3
  size=$(screen_size)
  read -r _ h <<< "$size"
  [ -n "$h" ] || { mark "B: no screen size"; return 0; }
  read -r x0 y0 <<< "$(center_of 'id/player$')" || return 0
  [ -n "$y0" ] || return 0

  # 1. down from the middle of the video, slowly: the video shrinks under the finger
  log_mark
  SHOT_STEPS="4 8 12" drag "$x0" "$y0" "$x0" $(( h * 88 / 100 )) 12 "$design-b-drag"
  expect_log YtGesture 'minimize grab from=full'
  # the frame lines measured the point under the finger: at most 8 px off
  expect_log YtGesture 'minimize release .*to=dock.* frames=[1-9][0-9]* maxErrOk=true'
  EXPECT_WAIT=5 expect_log YtGesture 'minimize end p_end=1'
  show_log YtGesture 6
  sleep 2
  shot "$design-b-4-docked"

  # 2. up from the docked player: it opens under the finger
  read -r mx my <<< "$(center_of 'id/main_container$')" || return 0
  [ -n "$my" ] || return 0
  log_mark
  drag "$mx" "$my" "$mx" $(( h * 25 / 100 )) 10
  expect_log YtGesture 'minimize grab from=mini'
  expect_log YtGesture 'minimize release .*to=open'
  EXPECT_WAIT=5 expect_log YtGesture 'minimize end p_end=0'
  sleep 2
  shot "$design-b-5-open"

  # 3. let go about a third of the way down, standing still: it opens again
  read -r x0 y0 <<< "$(center_of 'id/player$')" || return 0
  [ -n "$y0" ] || return 0
  mid=$(( y0 + (my - y0) * 35 / 100 ))
  log_mark
  drag "$x0" "$y0" "$x0" "$mid" 6
  expect_log YtGesture 'minimize release .*to=open'
  EXPECT_WAIT=5 expect_log YtGesture 'minimize end p_end=0'
  sleep 1

  # 4. a quick flick down: it docks with the flick's speed
  log_mark
  fling "$x0" "$y0" "$x0" $(( y0 + (my - y0) * 30 / 100 )) 60
  expect_log YtGesture 'minimize release .*to=dock'
  EXPECT_WAIT=5 expect_log YtGesture 'minimize end p_end=1'
  sleep 2

  # 5. the docked player pulled down to the screen's edge: thrown away, the player closes
  read -r mx my <<< "$(center_of 'id/main_container$')" || return 0
  [ -n "$my" ] || return 0
  log_mark
  drag "$mx" "$my" "$mx" $(( h - 4 )) 6
  expect_log YtGesture 'minimize release .*to=close'
  EXPECT_WAIT=5 expect_log YtGesture 'minimize close'
  sleep 2
  shot "$design-b-6-closed"
}

b_checks
