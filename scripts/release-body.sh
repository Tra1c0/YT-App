#!/usr/bin/env bash
# YT: writes the release body for CI. Usage: release-body.sh <notes file> <build> <commit> <signed with> <note>
# Main builds: the user-facing notes (release-notes/next.md, or "Keine Notizen."), then the
# marker the app cuts at, then the technical lines. Test builds (a note is given): as before.
set -euo pipefail
notes_file=$1 build=$2 commit=$3 signed=$4 note=${5:-}
details="YT build $build from commit $commit.
Signed with: $signed."
if [ -n "$note" ]; then
  printf '%s\n%s\n' "$details" "$note"
  exit 0
fi
if [ -f "$notes_file" ] && grep -q '[^[:space:]]' "$notes_file"; then
  # trailing blank lines and carriage returns off; the rest stays plain markdown
  notes=$(tr -d '\r' < "$notes_file" | sed -e :a -e '/^[[:space:]]*$/{$d;N;ba' -e '}')
else
  notes="Keine Notizen."
fi
printf '%s\n\n<!-- yt-details -->\n%s\n' "$notes" "$details"
