# shellcheck shell=bash
# One UiAutomation connection for the entire check; normal animations keep running.
driver_start() {
  local jar="build/gesture-driver/yt-input.jar" line
  [ -f "$jar" ] || bash scripts/build-gesture-driver.sh >/dev/null || return 1
  adb push "$jar" /data/local/tmp/yt-input.jar >/dev/null || return 1
  coproc YT_INPUT { adb shell -T 'CLASSPATH=/data/local/tmp/yt-input.jar app_process /system/bin YtInput' 2>input-driver.log; }
  YT_INPUT_PID_SAVED=$YT_INPUT_PID
  exec {YT_INPUT_READ}<&"${YT_INPUT[0]}"
  exec {YT_INPUT_WRITE}>&"${YT_INPUT[1]}"
  while IFS= read -r -t 20 -u "$YT_INPUT_READ" line; do
    [ "$line" = 'YTINPUT READY' ] && return 0
  done
  echo 'UI driver did not start; see input-driver.log' >&2
  return 1
}

driver_command() {
  local line result
  printf '%s\n' "$*" >&"$YT_INPUT_WRITE" || return 1
  while IFS= read -r -t 10 -u "$YT_INPUT_READ" line; do
    case "$line" in
      'YTINPUT OK '*)
        result=${line#YTINPUT OK }
        [ -z "$result" ] || printf '%s' "$result" | base64 -d
        return 0 ;;
      'YTINPUT ERR '*)
        printf '%s' "${line#YTINPUT ERR }" | base64 -d >&2
        printf '\n' >&2
        return 1 ;;
    esac
  done
  echo "UI driver timed out for $1" >&2
  return 1
}

driver_stop() {
  if [ -n "${YT_INPUT_WRITE:-}" ]; then
    printf 'QUIT\n' >&"$YT_INPUT_WRITE" 2>/dev/null || true
    exec {YT_INPUT_WRITE}>&-
    exec {YT_INPUT_READ}<&-
  fi
  [ -z "${YT_INPUT_PID_SAVED:-}" ] || kill "$YT_INPUT_PID_SAVED" 2>/dev/null || true
}
