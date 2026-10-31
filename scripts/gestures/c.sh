# shellcheck shell=bash
# YT round 12, package C: its gesture checks.
# The player gestures: scrubbing, the return point, the loop, the compass, sharing a moment.
# Sourced by crash-check.sh once per design with YT_GESTURES=yes ($design is the design); the
# helpers are in lib.sh. Nothing to check yet.
# shellcheck source-path=SCRIPTDIR source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh" || return 0
