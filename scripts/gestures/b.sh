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
#
# b_dock_checks then docks the player by each way there is (back, a slow drag, a flick, after the
# long-press peek, after a rotation, after a trip to the home screen) and checks where it rests:
# the foot of its box (main_container) on the top of the bar's view plus the design's dock
# offset. The app logs that as "dock check box=<foot> bar=<top> dock=<offset> err=<px>" (tag
# YtMini) each time the box moves; uiautomator's bounds of main_container and bottomNav tell
# the same independently.
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
  read -r x0 y0 <<< "$(center_of 'id/player$')"
  numbers "${x0:-}" "${y0:-}" || { mark "B: no picture of the player on screen, the minimize checks are skipped"; return 0; }

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
  read -r mx my <<< "$(center_of 'id/main_container$')"
  numbers "${mx:-}" "${my:-}" || { mark "B: no docked player on screen, the rest is skipped"; return 0; }
  log_mark
  drag "$mx" "$my" "$mx" $(( h * 25 / 100 )) 10
  expect_log YtGesture 'minimize grab from=mini'
  expect_log YtGesture 'minimize release .*to=open'
  EXPECT_WAIT=5 expect_log YtGesture 'minimize end p_end=0'
  sleep 2
  shot "$design-b-5-open"

  # 3. let go about a third of the way down, standing still: it opens again
  read -r x0 y0 <<< "$(center_of 'id/player$')"
  numbers "${x0:-}" "${y0:-}" || { mark "B: no picture of the open player on screen, the rest is skipped"; return 0; }
  mid=$(( y0 + (my - y0) * 35 / 100 ))
  log_mark
  drag "$x0" "$y0" "$x0" "$mid" 6
  expect_log YtGesture 'minimize release .*to=open'
  EXPECT_WAIT=5 expect_log YtGesture 'minimize end p_end=0'
  sleep 1

  # 4. a quick flick down: it docks with the flick's speed (the page first comes to rest: the
  # open release above may still spring its offsets back)
  sleep 2
  log_mark
  fling "$x0" "$y0" "$x0" $(( y0 + (my - y0) * 30 / 100 )) 60
  expect_log YtGesture 'minimize release .*to=dock'
  EXPECT_WAIT=5 expect_log YtGesture 'minimize end p_end=1'
  show_log YtGesture 4
  sleep 2

  # 5. the docked player pulled down to the screen's edge: thrown away, the player closes. Only
  # the docked player closes so (the open one docks): if the flick left it open, back docks it
  read -r mx my <<< "$(center_of 'id/main_container$')"
  numbers "${mx:-}" "${my:-}" || { mark "B: no player on screen, the close check is skipped"; return 0; }
  if [ "$my" -lt $(( h / 2 )) ]; then
    mark "B: the player is still open (main_container at y=$my); back docks it for the close check"
    timeout 10 adb shell input keyevent KEYCODE_BACK || true
    sleep 3
    read -r mx my <<< "$(center_of 'id/main_container$')"
    if ! numbers "${mx:-}" "${my:-}" || [ "$my" -lt $(( h / 2 )) ]; then
      mark "B: no docked player, the close check is skipped"
      return 0
    fi
  fi
  log_mark
  drag "$mx" "$my" "$mx" $(( h - 4 )) 6
  expect_log YtGesture 'minimize release .*to=close'
  EXPECT_WAIT=5 expect_log YtGesture 'minimize close'
  sleep 2
  shot "$design-b-6-closed"
}

# the place of the docked player after the way named by $1 ("back", "drag", ...)
b_dock_at() {
  local what="$1" line err dock box bar bb bt e slack=3
  sleep 3
  line=$(tag_messages YtMini | grep -a -E '^dock check ' | tail -n 1)
  if [ -z "$line" ]; then
    if b_docked_center >/dev/null; then
      # docked, but the box never published its place (or the bar is hidden: no tabs)
      mark "B dock ($what): docked, but the app logged no dock check"
      expect_log YtMini "^dock check .* after $what"
    else
      mark "B dock ($what): the player is not docked, nothing to check"
    fi
    return 0
  fi
  err=$(printf '%s\n' "$line" | sed -E 's/^.* err=(-?[0-9]+)$/\1/')
  dock=$(printf '%s\n' "$line" | sed -E 's/^.* dock=(-?[0-9]+) .*$/\1/')
  if numbers "$err" && [ "${err#-}" -le "$slack" ]; then
    expect_log YtMini "^dock check .* err=$err\$"
  else
    mark "B dock ($what): the box rests off its place: $line"
    expect_log YtMini "^dock check .* err=-?[0-$slack] after $what\$"
  fi
  # the same from the views as uiautomator sees them (translations included)
  if box=$(bounds_of 'id/main_container$') && bar=$(bounds_of 'id/bottomNav$') && numbers "${dock:-}"; then
    read -r _ _ _ bb <<< "$box"
    read -r _ bt _ _ <<< "$bar"
    e=$(( bb - bt - dock ))
    mark "B dock ($what): uiautomator main_container bottom=$bb, bottomNav top=$bt, dock=$dock: off by $e px"
    if [ "${e#-}" -gt "$slack" ]; then
      expect_log YtMini "^uiautomator: the box rests on the bar after $what\$"
    fi
  else
    mark "B dock ($what): no main_container or bottomNav in the view tree"
  fi
  rawshot "$design-b-dock-$what"
}

# the docked player's middle, or nothing (status 1) when the player is not docked
b_docked_center() {
  local c y h
  c=$(center_of 'id/main_container$') || return 1
  read -r _ y <<< "$c"
  read -r _ h <<< "$(screen_size)"
  numbers "${y:-}" "${h:-}" && [ "$y" -gt $(( h / 2 )) ] || return 1
  echo "$c"
}

# opens the docked player by a tap; status 1 if there was none
b_open_docked() {
  local c
  c=$(b_docked_center) || { mark "B dock: no docked player to open"; return 1; }
  # shellcheck disable=SC2086 # two coordinates
  timeout 10 adb shell input tap $c || true
  sleep 2.5
}

b_dock_checks() {
  local h x0 y0 c rotation auto
  if ! open_test_video; then
    mark "B dock: no test video, the dock checks are skipped"
    return 0
  fi
  sleep 3
  read -r _ h <<< "$(screen_size)"
  numbers "${h:-}" || { mark "B dock: no screen size"; return 0; }

  # 1. back
  log_mark
  timeout 10 adb shell input keyevent KEYCODE_BACK || true
  b_dock_at back

  # 2. a slow drag down from the video
  b_open_docked || return 0
  read -r x0 y0 <<< "$(center_of 'id/player$')"
  if numbers "${x0:-}" "${y0:-}"; then
    log_mark
    drag "$x0" "$y0" "$x0" $(( h * 88 / 100 )) 10
    b_dock_at drag
  fi

  # 3. a flick down
  if b_open_docked; then
    read -r x0 y0 <<< "$(center_of 'id/player$')"
    if numbers "${x0:-}" "${y0:-}"; then
      log_mark
      fling "$x0" "$y0" "$x0" $(( y0 + h * 25 / 100 )) 60
      sleep 1
      # a flick that left it open: back docks it (the check is then that of back once more)
      b_docked_center >/dev/null || timeout 10 adb shell input keyevent KEYCODE_BACK || true
      b_dock_at fling
    fi
  fi

  # 4. after the long-press peek on the docked picture (designs without one ignore the hold)
  if c=$(center_of 'id/player$') && b_docked_center >/dev/null; then
    read -r x0 y0 <<< "$c"
    log_mark
    finger_down "$x0" "$y0"
    sleep 1.6
    finger_up "$x0" "$y0"
    sleep 1.5
    b_dock_at peek
  fi

  # 5. docked through a rotation and back (the activity keeps its orientation where it is locked)
  if b_docked_center >/dev/null; then
    rotation=$(timeout 5 adb shell settings get system user_rotation 2>/dev/null | tr -d '\r')
    auto=$(timeout 5 adb shell settings get system accelerometer_rotation 2>/dev/null | tr -d '\r')
    log_mark
    timeout 10 adb shell settings put system accelerometer_rotation 0 || true
    timeout 10 adb shell settings put system user_rotation 1 || true
    sleep 3
    rawshot "$design-b-dock-landscape"
    timeout 10 adb shell settings put system user_rotation "${rotation:-0}" || true
    timeout 10 adb shell settings put system accelerometer_rotation "${auto:-0}" || true
    b_dock_at rotation
  fi

  # 6. home and back (with a video playing, home may take it into picture-in-picture)
  if b_docked_center >/dev/null; then
    log_mark
    timeout 10 adb shell input keyevent KEYCODE_HOME || true
    sleep 4
    timeout 30 adb shell am start -n "$PKG/$ACTIVITY" >/dev/null || true
    sleep 3
    back_to_yt || true
    b_dock_at background
  fi
  show_log YtMini 6
}

b_checks
b_dock_checks
