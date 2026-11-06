# shellcheck shell=bash
# YT round 12: the end of a video with a queue, in the video player, with the screen off and in
# the audio player (the user's report: in audio mode or in the background the queue broke when
# the playing video ended). Once per job, on the first design with gesture checks: the playback
# is the same in every design.
#
# The test video gets three made-up videos queued after it (ytTestQ0001..3, downloads that play
# the test video's 180 s file). Each case seeks close to the end and waits for the service's
# autoplay to start the next one (log YtQueue "autoplay next=<id>", written by
# AbstractPlayerService.playNextVideo with the gesture log on), then checks that the app still
# runs. In the audio case the made-up videos have no audio file of their own: the offline player
# then plays the video file's sound (YtQueue "audio only from the video file"); before, it
# stopped the service at the next video.
# Sourced by crash-check.sh once per design with YT_GESTURES=yes; the helpers are in lib.sh.
# shellcheck source-path=SCRIPTDIR source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh" || return 0

# the app's process is there; a FAIL line like expect_log's otherwise
f_alive() {
  local result=OK
  timeout 20 adb shell pidof "$PKG" >/dev/null 2>&1 || { result=FAIL; gesture_fails=$(( ${gesture_fails:-0} + 1 )); }
  echo "$result ${design:-?} f: app running after $1"
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    printf -- '- **%s** %s, f: app running after %s\n' "$result" "${design:-?}" "$1" >> "$GITHUB_STEP_SUMMARY"
  fi
}

f_checks() {
  [ -n "${F_QUEUE_DONE:-}" ] && return 0
  F_QUEUE_DONE=yes
  local EXPECT_WAIT=40

  # 1. the video player in front: 8 s before the end, the next one follows
  log_mark
  if ! open_test_video --ei yt_queue_test 3 --el yt_seek_test 172000; then
    mark "f: no test video, the queue's end is not checked"
    return 0
  fi
  expect_log YtQueue 'autoplay next=ytTestQ0001 audio=false'
  sleep 3
  f_alive "the end of a video in the video player"

  # 2. the screen off (the video track rests, as in the background): the next one follows again
  log_mark
  timeout 30 adb shell am start -n "$PKG/$ACTIVITY" --ez yt_quiet true ${START_EXTRAS[@]+"${START_EXTRAS[@]}"} \
    --el yt_seek_test 172000 >/dev/null || mark "f: seek start timed out"
  if expect_log YtTest 'seek test from='; then
    timeout 10 adb shell input keyevent KEYCODE_SLEEP || true
    expect_log YtQueue 'autoplay next=ytTestQ0002'
    sleep 3
    timeout 10 adb shell input keyevent KEYCODE_WAKEUP || true
    sleep 1
    timeout 10 adb shell wm dismiss-keyguard || true
    sleep 2
    rawshot "$design-f-1-after-screen-off"
    f_alive "the end of a video with the screen off"
  fi
  back_to_yt || true

  # 3. the audio player: the test video anew, near its end, then over to the audio player
  log_mark
  if open_test_video --ei yt_queue_test 3 --el yt_seek_test 165000 --ez yt_audio_test true; then
    expect_log YtTest 'audio test switched=true'
    expect_log YtQueue 'autoplay next=ytTestQ0001 audio=true'
    expect_log YtQueue 'audio only from the video file id=ytTestQ0001'
    sleep 4
    rawshot "$design-f-2-audio-next"
    f_alive "the end of a video in the audio player"
  else
    mark "f: the test video did not open for the audio case"
  fi
  show_log YtQueue 12
  back_to_yt || true
}

f_checks
