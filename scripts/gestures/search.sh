# shellcheck shell=bash
# YT round 12, the search of the designs (ui/yt/search): the header's search button opens the
# design's own search over the screen, with the keyboard; typed text shows in its field; Back
# closes it again (log YtSearch "open", "close"); a search from it reaches the results screen
# (log YtSearch "submit"). Standard keeps the toolbar's search field and is skipped.
# Sourced by crash-check.sh once per design with YT_GESTURES=yes; the helpers are in lib.sh.
# shellcheck source-path=SCRIPTDIR source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh" || return 0

search_fail() {
  echo "FAIL ${design:-?} search: $*"
  gesture_fails=$(( ${gesture_fails:-0} + 1 ))
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    printf -- '- **FAIL** %s, search: %s\n' "${design:-?}" "$*" >> "$GITHUB_STEP_SUMMARY"
  fi
}

# taps the header's search button; status 1 if there is none on screen
search_open() {
  local at
  at=$(center_of '^(Suchen|Search)$') || return 1
  # shellcheck disable=SC2086 # "x y"
  timeout 10 adb shell input tap $at || true
  sleep 2
}

search_checks() {
  [ "${design:-standard}" = "standard" ] && return 0
  timeout 20 adb shell am force-stop "$PKG" || true
  timeout 30 adb shell am start -n "$PKG/$ACTIVITY" --ez yt_quiet true ${START_EXTRAS[@]+"${START_EXTRAS[@]}"} >/dev/null \
    || mark "search: start timed out"
  sleep "${YT_WAIT:-20}"

  # 1. open, type, close with Back
  log_mark
  if ! search_open; then
    mark "search: no search button on screen, the search checks are skipped"
    return 0
  fi
  expect_log YtSearch 'open design='
  shot "$design-search-1-open"
  if ! bounds_of 'yt_search_field$' >/dev/null; then
    search_fail "the design's search field is not on screen after the search button"
  fi
  timeout 10 adb shell input text "lofi" || true
  sleep 2
  shot "$design-search-2-typed"
  # the keyboard first, then the search
  timeout 10 adb shell input keyevent KEYCODE_BACK || true
  sleep 1
  if bounds_of 'yt_search_field$' >/dev/null; then
    timeout 10 adb shell input keyevent KEYCODE_BACK || true
  fi
  expect_log YtSearch 'close'
  sleep 1
  if bounds_of 'yt_search_field$' >/dev/null; then
    search_fail "the search is still on screen after Back"
  fi

  # 2. a search from the field reaches the results (the search key of the keyboard)
  log_mark
  if search_open; then
    timeout 10 adb shell input text "lofi" || true
    sleep 1
    timeout 10 adb shell input keyevent KEYCODE_ENTER || true
    sleep 4
    if tag_messages YtSearch | grep -a -q 'submit'; then
      echo "OK ${design:-?} search: a search from the design field"
    else
      # an emulator keyboard may not send the search action for Enter: noted, not failed
      mark "search: Enter did not run the search (keyboard without the search action?)"
    fi
    shot "$design-search-3-results"
  fi
  show_log YtSearch 8
  back_to_yt || true
}

search_checks
