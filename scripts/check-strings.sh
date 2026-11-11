#!/usr/bin/env bash
# YT: every YT string needs its German twin. A key only in values/ shows English in the German
# app; one only in values-de/ crashes every other locale. Compared per yt_*.xml file, by name.
# Used by CI (.github/workflows/build.yml) and by scripts/docs_sync.py --check.
# Output uses GitHub annotations (::error::/::warning::); they read fine in a terminal too.
set -euo pipefail
cd "$(dirname "$0")/.."
res=app/src/main/res
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
# the names of the strings and plurals in one file, in file order (none if it is missing)
names() {
  [ -f "$1" ] || return 0
  grep -o -E '<(string|plurals) [^>]*name="[^"]+"[^>]*>' "$1" | grep -v 'translatable="false"' \
    | sed -E 's/.*name="([^"]+)".*/\1/' || true
}
failed=0
for f in $(for p in "$res"/values/yt_*.xml "$res"/values-de/yt_*.xml; do [ -e "$p" ] && basename "$p"; done | sort -u); do
  names "$res/values/$f" > "$tmp/en.txt"
  names "$res/values-de/$f" > "$tmp/de.txt"
  only_en=$(comm -23 <(sort -u "$tmp/en.txt") <(sort -u "$tmp/de.txt") | tr '\n' ' ')
  only_de=$(comm -13 <(sort -u "$tmp/en.txt") <(sort -u "$tmp/de.txt") | tr '\n' ' ')
  if [ -n "$only_en" ]; then failed=1; echo "::error file=$res/values-de/$f::$f: missing in values-de: $only_en"; fi
  if [ -n "$only_de" ]; then failed=1; echo "::error file=$res/values/$f::$f: missing in values: $only_de"; fi
  if [ -z "$only_en$only_de" ]; then
    # the same order makes the two files easy to compare by eye; a different one is no error
    cmp -s "$tmp/en.txt" "$tmp/de.txt" || echo "::warning file=$res/values-de/$f::$f: the same names, in another order"
    echo "$f: $(wc -l < "$tmp/en.txt") names in both"
  fi
done
[ "$failed" = 0 ] || { echo "::error::The English and German YT strings differ (see above)"; exit 1; }
