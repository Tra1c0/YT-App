# shellcheck shell=bash
# YT round 12, package E: its gesture checks.
# The comments that follow the scene.
# Sourced by crash-check.sh once per design with YT_GESTURES=yes ($design is the design); the
# helpers are in lib.sh.
#
# The test video has no comments, so the check brings five (YtSceneTestHooks): one at 0:10, one at
# 1:30, one at 2:40, one without a time and a viewer timeline (0:00 / 0:45 / 1:30 / 2:15). With
# spoiler protection at the start, those at 1:30 and 2:40 are hidden (blurred, before Android 12
# the pill "Spoiler bei …"). A seek to 1:40 reveals nothing at once; after 5 s of playback there
# the mark follows and only 2:40 stays hidden. Then the chip "Diese Stelle" shows the scene.
# The video starts at 0:00 (yt_seek_test 0): an earlier run left a saved position, which would count as seen.
# shellcheck source-path=SCRIPTDIR source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh" || return 0

scene_fixture() {
  local v="ytTestVid01"
  link() { printf '<a href=\\"https://www.youtube.com/watch?v=%s&amp;t=%s\\">%s</a>' "$v" "$1" "$2"; }
  comment() {
    printf '{"author":"@%s","commentId":"yt-e-%s","commentText":"%s","commentedTime":"vor 1 Tag","commentorUrl":"/channel/UCytTest","hearted":false,"likeCount":%s,"pinned":false,"thumbnail":"","verified":false,"replyCount":0}' \
      "$1" "$2" "$3" "$4"
  }
  printf '[{"commentCount":5,"comments":[%s,%s,%s,%s,%s]}]' \
    "$(comment lena 1 "Ab $(link 10 0:10) geht es los" 40)" \
    "$(comment jonas 2 "Bei $(link 90 1:30) passiert es!" 30)" \
    "$(comment mia 3 "Das Ende bei $(link 160 2:40) hätte ich nie erwartet" 20)" \
    "$(comment ben 4 "Tolles Video" 10)" \
    "$(comment kim 5 "0:00 Intro<br>0:45 Teil 1<br>1:30 Teil 2<br>2:15 Ende" 5)"
}

e_checks() {
  local fixture
  fixture=$(scene_fixture | base64 -w0)
  log_mark
  open_test_video --el yt_seek_test 0 --es yt_comments_scene spoiler --es yt_comments_fixture "$fixture" --ez yt_comments_open true \
    || { mark "e: no test video, no comment checks"; return 0; }
  expect_log YtScene 'comments sheet opened=true'
  sleep 3
  rawshot "$design-e-1-blur"
  EXPECT_WAIT=8 expect_log YtScene 'hidden=2 of 5'

  # a jump forward reveals nothing by itself, 5 s of playback there do
  log_mark
  timeout 30 adb shell am start -n "$PKG/$ACTIVITY" --ez yt_quiet true --el yt_seek_test 100000 >/dev/null || true
  EXPECT_WAIT=15 expect_log YtScene 'seek to=(99|100)[0-9]{3} '
  sleep 7
  rawshot "$design-e-2-unlocked"
  EXPECT_WAIT=8 expect_log YtScene 'hidden=1 of 5'

  log_mark
  tap 'Diese Stelle|This scene' || true
  EXPECT_WAIT=5 expect_log YtScene 'chip mode=scene'
  sleep 2
  rawshot "$design-e-3-scene"
  show_log YtScene 10

  # leave it as the other checks expect it: off, the sheet closed
  timeout 30 adb shell am start -n "$PKG/$ACTIVITY" --ez yt_quiet true --es yt_comments_scene off >/dev/null || true
  sleep 1
  timeout 10 adb shell input keyevent KEYCODE_BACK || true
  sleep 1
  back_to_yt || true
}

e_checks
