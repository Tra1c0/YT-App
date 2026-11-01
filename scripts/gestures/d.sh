# shellcheck shell=bash
# YT round 12, package D: its gesture checks.
# Home: card swipes (left hides with an undo pill, right queues), the draft queue while nothing
# plays, cards held and dragged onto the bar or the mini player, the search icon dragged onto a
# card, the time box ("Heute habe ich … Minuten"), the intent chooser and the Feed-Regler, and the
# same with the mini player docked.
# Sourced by crash-check.sh once per design with YT_GESTURES=yes ($design is the design); the
# helpers are in lib.sh.
# shellcheck source-path=SCRIPTDIR source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh" || return 0

# a fresh start on the home: no player, nothing left over from the checks before
d_home() {
  local from
  from=$(wc -l < "logcat-${design:-standard}.txt" 2>/dev/null || echo 0)
  timeout 20 adb shell am force-stop "$PKG" || true
  timeout 30 adb shell am start -n "$PKG/$ACTIVITY" --ez yt_quiet true ${START_EXTRAS[@]+"${START_EXTRAS[@]}"} >/dev/null \
    || echo "start timed out"
  for _ in $(seq 1 30); do
    if tail -n +"$((from + 1))" "logcat-${design:-standard}.txt" | grep -a -q 'home fixture n='; then
      sleep 1
      return 0
    fi
    sleep 0.5
  done
  d_fail "the local home fixture did not load"
  return 1
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
  gesture_checks=$(( ${gesture_checks:-0} + 1 ))
  echo "FAIL ${design:-?} d: $*"
  gesture_fails=$(( ${gesture_fails:-0} + 1 ))
}

# status 0 once a line of tag $1 since log_mark matches $2 (up to 3 s), without a verdict
d_seen() {
  local waited=0
  while [ "$waited" -le 3 ]; do
    tag_messages "$1" | grep -a -q -E -- "$2" && return 0
    sleep 1
    waited=$((waited + 1))
  done
  return 1
}

# a horizontal swipe of the card at $1 $2 by $3 px (negative: left), finger by finger with
# screenshots ($4 the prefix); if the card's swipe did not log $5 ($6 its tag), once more as one
# quick "input swipe", so the log tells a gesture the app missed from one it never got right
d_swipe() {
  local x="$1" y="$2" by="$3" prefix="$4" tag="$5" pattern="$6" card
  SHOT_STEPS="4 8" drag "$x" "$y" $(( x + by )) "$y" 8 "$prefix"
  d_seen "$tag" "$pattern" && return 0
  show_log YtSwipe 6
  mark "d: the step-by-step swipe did not reach the card's swipe; again as one input swipe"
  card=$(d_card) && read -r x y _ <<< "$card"
  fling "$x" "$y" $(( x + by )) "$y" 350
  d_seen "$tag" "$pattern" || show_log YtSwipe 6
}

d_checks() {
  local card x y w reach bar h lens sx sy mini mx my
  d_home || return 1
  # Standard deliberately retains LibreTube's classic horizontal shelves, not the
  # eleven custom feeds' swipe cards. Exercise its real equivalent, the card menu.
  if [ "${design:-}" = standard ]; then
    if card=$(center_of 'id/thumbnail$'); then
      read -r x y <<< "$card"
      finger_down "$x" "$y"
      sleep 0.9
      finger_up "$x" "$y"
      gesture_checks=$((gesture_checks + 1))
      if bounds_of '^(Im Hintergrund abspielen|Play in background)$' >/dev/null; then
        echo 'OK standard d: classic feed card actions are reachable'
        rawshot "$design-d-card-actions"
      else
        d_fail 'classic card menu did not open'
      fi
      timeout 10 adb shell input keyevent KEYCODE_BACK || true
    else
      d_fail 'no classic feed card after loading the fixture'
    fi
    return
  fi
  log_mark
  if ! card=$(d_card); then
    d_fail "no home card on screen (yt_card_*)"
    return 0
  fi
  read -r x y w <<< "$card"
  reach=$(( w * 6 / 10 ))
  [ "$reach" -lt "$(dp 140)" ] && reach=$(dp 140)

  # left: the card follows the finger off a field, flies out, the undo pill comes
  d_swipe "$x" "$y" $(( -reach )) "$design-d-1-hide" YtHidden 'hide id='
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
  d_swipe "$x" "$y" "$reach" "$design-d-3-queue" YtQueue 'draft n=|append pos='
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
    # without the sheet, back left the app: the checks below need the home
    if ! timeout 20 adb shell dumpsys activity activities 2>/dev/null | grep -a -m1 "topResumedActivity" | grep -q "$PKG"; then
      mark "d: back left the app (no quick sheet was open); the home again"
      d_home
    fi
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
    d_fail "no search button or card for the search drag"
    # what the dump has as buttons with a description, to find the search button's
    ui | grep -a -o 'content-desc="[^"]\{1,40\}"' | sort -u | head -n 12 | tr '\n' ' '
    echo
  fi

  # the time box: the chip opens into the minutes row, 30 minutes plan at most 33 (at 1x)
  d_home
  log_mark
  if d_tap yt_timebox_chip; then
    sleep 1
    rawshot "$design-d-11-timebox"
    if d_tap yt_timebox_30; then
      EXPECT_WAIT=10 expect_log YtTimeBox 'total<=33|total<=3[0-2]|nothing to plan'
      sleep 4
      shot "$design-d-12-timebox-running"
      show_log YtSession 5
    else
      d_fail "the time box card has no 30-minute button (yt_timebox_30)"
    fi
    d_home
  else
    d_fail "no time box chip"
  fi

  # the intent chooser: opened by the test hook (it never opens by itself in the emulator),
  # "Musik hören" sets the intent, the chip on the home shows it and opens the chooser again
  timeout 20 adb shell am force-stop "$PKG" || true
  log_mark
  timeout 30 adb shell am start -n "$PKG/$ACTIVITY" --ez yt_quiet true --ez yt_intent_test true \
    ${START_EXTRAS[@]+"${START_EXTRAS[@]}"} >/dev/null || echo "start timed out"
  sleep "${YT_WAIT:-20}"
  if bounds_of 'yt_intent_chooser$' >/dev/null; then
    shot "$design-d-13-chooser"
    if d_tap yt_intent_music; then
      sleep 2
      expect_log YtIntent 'intent=MUSIC'
      shot "$design-d-14-music"
      d_tap yt_intent_chip || d_fail "no intent chip on the home after the choice (yt_intent_chip)"
      sleep 1
      if bounds_of 'yt_intent_chooser$' >/dev/null; then
        # back to "Einfach stöbern", the home as before
        d_tap yt_intent_browse || d_fail "the chooser has no browse choice (yt_intent_browse)"
        expect_log YtIntent 'intent=BROWSE'
      else
        d_fail "the intent chip does not open the chooser"
      fi
    else
      d_fail "the chooser has no music choice (yt_intent_music)"
    fi
  else
    d_fail "the intent chooser did not open (yt_intent_test)"
  fi

  # the Feed-Regler: its button in the design opens the sheet, a weight drag ranks the preview anew
  d_home
  log_mark
  if d_tap yt_feed_button; then
    sleep 1
    if bounds_of 'yt_feed_sheet$' >/dev/null; then
      rawshot "$design-d-15-feed"
      expect_log YtFeed 'sheet open'
      if d_tap yt_feed_weight_music_more; then
        sleep 1
        expect_log YtFeed 'rules saved'
        shot "$design-d-16-feed-weight"
        # back to the preset, so the next checks see the home as before
        d_tap yt_feed_reset || d_fail "the sheet has no reset (yt_feed_reset)"
      else
        d_fail "the sheet has no weight buttons (yt_feed_weight_music_more)"
      fi
      d_tap yt_feed_close || timeout 10 adb shell input keyevent KEYCODE_BACK || true
    else
      d_fail "the Feed-Regler did not open (yt_feed_sheet)"
    fi
  else
    d_fail "no Feed-Regler button on screen (yt_feed_button)"
  fi

  # with the mini player docked (the test video plays offline, so online cards wait in the
  # draft; an online player would take them at its end: "append pos="). The emulator's decoder
  # sometimes stalls on a start (h264 errors in the log): one more try
  if open_test_video || { mark "d: the test video once more"; open_test_video; }; then
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
    d_fail "no test video for the mini player check"
  fi
}

d_checks
