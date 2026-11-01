# shellcheck shell=bash
# One UiAutomation connection for the entire check; normal animations keep running.
driver_start() {
  local jar="build/gesture-driver/yt-input.jar" line
  [ -f "$jar" ] || bash scripts/build-gesture-driver.sh >/dev/null || return 1
  adb push "$jar" /data/local/tmp/yt-input.jar >/dev/null || return 1
  coproc YT_INPUT { adb shell -T 'CLASSPATH=/data/local/tmp/yt-input.jar app_process -Xmx64m /system/bin YtInput' 2>input-driver.log; }
  YT_INPUT_PID_SAVED=$YT_INPUT_PID
  exec {YT_INPUT_READ}<&"${YT_INPUT[0]}"
  exec {YT_INPUT_WRITE}>&"${YT_INPUT[1]}"
  while IFS= read -r -t 20 -u "$YT_INPUT_READ" line; do
    line=${line%$'\r'}
    [ "$line" = 'YTINPUT READY' ] && return 0
  done
  echo 'UI driver did not start; see input-driver.log' >&2
  timeout 20 adb logcat -d -t 1500 >input-driver-system.log 2>&1 || true
  timeout 10 adb shell cat /proc/meminfo >input-driver-memory.log 2>&1 || true
  return 1
}

driver_command() {
  local line result request="${BASHPID}_${RANDOM}_${RANDOM}" driver_timeout=20 deadline remaining
  [ "$1" != DUMP ] || driver_timeout=40
  deadline=$((SECONDS + driver_timeout))
  printf '%s %s\n' "$request" "$*" >&"$YT_INPUT_WRITE" || return 1
  while [ "$SECONDS" -lt "$deadline" ]; do
    remaining=$((deadline - SECONDS))
    IFS= read -r -t "$remaining" -u "$YT_INPUT_READ" line || break
    line=${line%$'\r'}
    case "$line" in
      "YTINPUT $request OK "*)
        result=${line#"YTINPUT $request OK "}
        [ -z "$result" ] || printf '%s' "$result" | base64 -d
        return 0 ;;
      "YTINPUT $request ERR "*)
        printf '%s' "${line#"YTINPUT $request ERR "}" | base64 -d >&2
        printf '\n' >&2
        return 1 ;;
    esac
  done
  echo "UI driver timed out for $1" >&2
  return 1
}

driver_stop() {
  if [ -n "${YT_INPUT_WRITE:-}" ]; then
    (trap '' PIPE; printf 'QUIT\n' >&"$YT_INPUT_WRITE" 2>/dev/null || true)
    exec {YT_INPUT_WRITE}>&-
    exec {YT_INPUT_READ}<&-
  fi
  [ -z "${YT_INPUT_PID_SAVED:-}" ] || kill "$YT_INPUT_PID_SAVED" 2>/dev/null || true
}

driver_find() {
  local found attempt encoded
  encoded=$(printf '%s' "$1" | base64 -w0)
  for attempt in 1 2 3 4; do
    found=$(driver_command FIND "$encoded" "${2:-1}") || return 1
    if [[ "$found" =~ ^-?[0-9]+\ -?[0-9]+\ -?[0-9]+\ -?[0-9]+$ ]]; then
      printf '%s\n' "$found"
      return 0
    fi
    sleep 0.2
  done
  return 1
}
