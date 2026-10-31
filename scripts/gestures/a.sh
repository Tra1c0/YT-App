# shellcheck shell=bash
# YT round 12, package A: its gesture checks.
# One mini player per design: the docked mini player, its long-press peek, the drop target.
# Sourced by crash-check.sh once per design with YT_GESTURES=yes ($design is the design); the
# helpers are in lib.sh. Nothing to check yet.
# shellcheck source-path=SCRIPTDIR source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh" || return 0
