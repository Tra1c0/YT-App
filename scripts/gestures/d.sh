# shellcheck shell=bash
# YT round 12, package D: its gesture checks.
# Home: card swipes (left hides with an undo pill, right queues), the draft queue while nothing
# plays, cards held and dragged onto the bar or the mini player, the search icon dragged onto a
# card, and the same with the mini player docked.
# Sourced by crash-check.sh once per design with YT_GESTURES=yes ($design is the design); the
# helpers are in lib.sh.
# shellcheck source-path=SCRIPTDIR source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh" || return 0

# a fresh start on the home: no player, nothing left over from the checks before
d_home() {
  timeout 20 adb shell am force-stop "$PKG" || true
  timeout 30 adb shell am start -n "$PKG/$ACTIVITY" --ez yt_quiet true ${START_EXTRAS[@]+"${START_EXTRAS[@]}"} >/dev/null \
    || echo "start timed out"
  sleep "${YT_WAIT:-20}"
}

# taps the element with resource-id (test tag) $1; status 1 if it is not on screen
d_tap() {
  local at
  at=$(center_of "$1\$") || return 1
  # shellcheck disable=SC2086 # "x y"
  timeout 10 adb shell input tap $at || true
}

# "x y w" of the first home card on screen: the second if there is one, else the first
d_card() {
  local b l t r bt
  b=$(bounds_of 'yt_card_1$') || b=$(bounds_of 'yt_card_0$') || b=$(bounds_of 'yt_card_next$') || return 1
  read -r l t r bt <<< "$b"
  echo "$(( (l + r) / 2 )) $(( (t + bt) / 2 )) $(( r - l ))"
}

d_fail() {
  echo "FAIL ${design:-?} d: $*"
  gesture_fails=$(( ${gesture_fails:-0} + 1 ))
}

d_checks() {
  local card x y w reach bar h lens sx sy mini mx my
  d_home
  log_mark
  if ! card=$(d_card); then
    d_fail "no home card on screen (yt_card_*)"
    return 0
  fi
  read -r x y w <<< "$card"
  reach=$(( w * 6 / 10 ))
  [ "$reach" -lt "$(dp 140)" ] && reach=$(dp 140)

  # left: the card follows the finger off a field, flies out, the undo pill comes
  SHOT_STEPS="4 8" drag "$x" "$y" $(( x - reach )) "$y" 8 "$design-d-1-hide"
  sleep 1
  rawshot "$design-d-2-undo"
  expect_log YtHidden 'hide id='
  expect_log YtHaptic 'swipe_hide_'
  # the pill's actions are tagged (yt_undo_action, yt_pause_action)
  if d_tap yt_undo_action; then
    expect_log YtHidden 'unhide id='
  else
    d_fail "no undo pill after the swipe"
  fi
  sleep 1

  # right while nothing plays: into the draft, its pill comes
  log_mark
  card=$(d_card) && read -r x y w <<< "$card"
  SHOT_STEPS="8" drag "$x" "$y" $(( x + reach )) "$y" 8 "$design-d-3-queue"
  sleep 2
  rawshot "$design-d-4-draft"
  expect_log YtQueue 'draft n=1'
  if ! d_tap yt_draft_discard; then
    d_fail "no draft pill after the swipe"
  fi
  sleep 1

  # held and dragged onto the bar while nothing plays: into the draft (the bar takes the drop)
  log_mark
  if card=$(d_card) && bar=$(screen_size); then
    read -r x y w <<< "$card"
    read -r _ h <<< "$bar"
    SHOT_STEPS="6" hold_drag "$x" "$y" "$x" $(( h - $(dp 40) )) 10 "$design-d-6-lift"
    sleep 1
    expect_log YtDrag 'lift id='
    expect_log YtDrag 'drop bar|back'
    expect_log YtQueue 'draft n=1'
    d_tap yt_draft_discard >/dev/null || true
    sleep 1
  fi

  # held without moving: the card lifts and letting go opens what the long press opened
  log_mark
  if card=$(d_card); then
    read -r x y w <<< "$card"
    hold_drag "$x" "$y" "$x" "$y" 1
    sleep 1
    rawshot "$design-d-7-release"
    timeout 10 adb shell input keyevent KEYCODE_BACK || true
    sleep 1
  fi

  # the search icon held and dragged onto a card: a search for the card's channel name
  log_mark
  if lens=$(center_of '^(Suchen|Search)$') && card=$(d_card); then
    read -r sx sy <<< "$lens"
    read -r x y w <<< "$card"
    SHOT_STEPS="10" hold_drag "$sx" "$sy" "$x" "$y" 12 "$design-d-8-lens"
    expect_log YtSearchDrag 'lift'
    expect_log YtSearchDrag 'search name=|back'
    sleep 3
    shot "$design-d-9-search"
    texts | head -n 5
    d_home
  else
    mark "d: no search button or card, the search drag check is skipped"
  fi

  # with the mini player docked (the test video plays offline, so online cards wait in the
  # draft; an online player would take them at its end: "append pos=")
  if open_test_video; then
    timeout 10 adb shell input keyevent KEYCODE_BACK || true
    sleep 4
    log_mark
    if card=$(d_card); then
      read -r x y w <<< "$card"
      drag "$x" "$y" $(( x + reach )) "$y" 8
      sleep 0.3
      rawshot "$design-d-5-token"
      expect_log YtQueue 'append pos=|draft n='
      sleep 2
      d_tap yt_draft_discard >/dev/null || true
      # held and dragged onto the mini player: the magnet, the hover, the drop (an offline
      # player turns online cards away, so "rejected" and a spring back count too)
      log_mark
      if mini=$(center_of 'main_container$'); then
        read -r mx my <<< "$mini"
        card=$(d_card) && read -r x y w <<< "$card"
        SHOT_STEPS="10" hold_drag "$x" "$y" "$mx" "$my" 12 "$design-d-10-hover"
        expect_log YtDrag 'hover=mini'
        expect_log YtQueue 'drop (next|append|rejected)'
        expect_log YtHaptic 'threshold_on|land|reject'
      fi
    else
      d_fail "no home card under the mini player"
    fi
  else
    mark "d: no test video, the mini player check is skipped"
  fi
}

d_checks
