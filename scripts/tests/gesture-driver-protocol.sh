#!/usr/bin/env bash
# The snapshots run inside $(...), so both pipe descriptors must survive a subshell.
set -euo pipefail
. scripts/gestures/driver.sh
coproc YT_INPUT {
  while IFS= read -r line; do
    request=${line%% *}
    line=${line#* }
    case "$line" in
      QUIT) break ;;
      EMPTY) printf 'YTINPUT %s OK \n' "$request" ;;
      FAIL) printf 'YTINPUT %s ERR %s\n' "$request" "$(printf 'expected failure' | base64 -w0)" ;;
      ECHO*)
        printf 'an unrelated runtime diagnostic\n'
        printf 'YTINPUT stale_request OK %s\n' "$(printf 'wrong late answer' | base64 -w0)"
        printf 'YTINPUT %s OK %s\n' "$request" "$(printf '%s' "${line#ECHO }" | base64 -w0)" ;;
    esac
  done
}
YT_INPUT_PID_SAVED=$YT_INPUT_PID
exec {YT_INPUT_READ}<&"${YT_INPUT[0]}"
exec {YT_INPUT_WRITE}>&"${YT_INPUT[1]}"
trap driver_stop EXIT
[[ "$(driver_command ECHO 'hello world')" = 'hello world' ]]
[[ -z "$(driver_command EMPTY)" ]]
if driver_command FAIL 2>/dev/null; then echo 'An error response was accepted' >&2; exit 1; fi
[[ "$(driver_command ECHO 'still alive')" = 'still alive' ]]
echo 'Input driver protocol: subshell, empty response, diagnostics and errors passed'
