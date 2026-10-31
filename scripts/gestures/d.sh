# shellcheck shell=bash
# YT round 12, package D: its gesture checks.
# Home: card swipes (left hides with an undo pill, right queues), the draft queue while nothing
# plays, and the same with the mini player docked.
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
  local card x y w reach
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
  if bounds_of 'yt_undo_pill$' >/dev/null; then
    tap 'Rückgängig|Undo' && expect_log YtHidden 'unhide id='
  else
    d_fail "no undo pill after the swipe"
  fi
  sleep 1

  # right while nothing plays: into the draft, its pill comes
  log_mark
  card=$(d_card) && read -r x y w <<< "$card"
  SHOT_STEPS="8" drag "$x" "$y" $(( x + reach )) "$y" 8 "$design-d-3-queue"
  sleep 1.5
  rawshot "$design-d-4-draft"
  expect_log YtQueue 'draft n=1'
  if bounds_of 'yt_draft_pill$' >/dev/null; then
    tap 'Warteschlange verwerfen|Discard queue'
  else
    d_fail "no draft pill after the swipe"
  fi
  sleep 1

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
      tap 'Warteschlange verwerfen|Discard queue' >/dev/null || true
    else
      d_fail "no home card under the mini player"
    fi
  else
    mark "d: no test video, the mini player check is skipped"
  fi
}

d_checks
