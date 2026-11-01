#!/usr/bin/env bash
# Exercise a real signed update over both known baselines, with persistent local user data.
set -euo pipefail
pkg=app.tra1c0.yt
activity=com.github.libretube.ui.activities.MainActivity
data=/data/user/0/$pkg
candidate=$(find apk -maxdepth 1 -name '*.apk' | head -1)
[ -n "$candidate" ] || { echo 'No candidate APK' >&2; exit 2; }
adb wait-for-device
adb root >/dev/null
adb wait-for-device
[ "$(adb shell id -u | tr -d '\r')" = 0 ] || { echo 'Upgrade fixtures require a rooted emulator' >&2; exit 2; }

for baseline in upgrade-baselines/*/*.apk; do
  [ -f "$baseline" ] || { echo 'An upgrade baseline is missing' >&2; exit 2; }
  label=$(basename "$(dirname "$baseline")")
  work="build/upgrade-$label"
  mkdir -p "$work/before" "$work/after"
  adb uninstall "$pkg" >/dev/null 2>&1 || true
  adb install "$baseline"
  adb shell am start -n "$pkg/$activity" --ez yt_quiet true >/dev/null
  ready=no
  for _ in $(seq 1 30); do
    if adb shell "test -s $data/databases/LibreTubeDatabase"; then ready=yes; break; fi
    sleep 1
  done
  [ "$ready" = yes ] || { echo "::error::$label: baseline database never opened"; exit 1; }
  sleep 2
  adb shell am force-stop "$pkg"
  adb pull "$data/databases/." "$work/before/" >/dev/null
  adb pull "$data/shared_prefs/${pkg}_preferences.xml" "$work/before/preferences.xml" >/dev/null
  python3 - "$work/before" <<'SEED'
import sqlite3, sys, xml.etree.ElementTree as ET
from pathlib import Path
root = Path(sys.argv[1])
with sqlite3.connect(root / 'LibreTubeDatabase') as db:
    db.execute("INSERT OR REPLACE INTO localSubscription(channelId,name,avatar,verified) VALUES('UCytUpgrade','Update-Abo','',0)")
    db.execute("INSERT OR REPLACE INTO LocalPlaylist(id,name,thumbnailUrl,description) VALUES(9999,'Update-Playlist','','Bleibt erhalten')")
    db.execute("INSERT OR REPLACE INTO LocalPlaylistItem(id,playlistId,videoId,title,duration) VALUES(9999,9999,'ytUpgrade01','Update-Video',180)")
    db.execute("INSERT OR REPLACE INTO watchHistoryItem(videoId,title,duration,isShort) VALUES('ytUpgrade01','Update-Video',180,0)")
    db.execute("INSERT OR REPLACE INTO watchPosition(videoId,position) VALUES('ytUpgrade01',42000)")
    db.commit()
    db.execute('PRAGMA wal_checkpoint(TRUNCATE)')
tree = ET.parse(root / 'preferences.xml')
prefs = tree.getroot()
for key in ['yt_design','yt_design_motion','yt_upgrade_probe']:
    for item in list(prefs):
        if item.get('name') == key: prefs.remove(item)
ET.SubElement(prefs, 'string', name='yt_design').text = 'standard'
ET.SubElement(prefs, 'boolean', name='yt_design_motion', value='false')
ET.SubElement(prefs, 'string', name='yt_upgrade_probe').text = 'retained'
tree.write(root / 'preferences.xml', encoding='utf-8', xml_declaration=True)
SEED
  owner=$(adb shell stat -c %u:%g "$data" | tr -d '\r')
  context=$(adb shell ls -Zd "$data" | tr -d '\r' | awk '{for(i=1;i<=NF;i++) if($i ~ /^u:object_r:/){print $i;exit}}')
  adb push "$work/before/LibreTubeDatabase" /data/local/tmp/yt-upgrade.db >/dev/null
  adb push "$work/before/preferences.xml" /data/local/tmp/yt-upgrade.xml >/dev/null
  adb shell "rm -f $data/databases/LibreTubeDatabase-wal $data/databases/LibreTubeDatabase-shm; cat /data/local/tmp/yt-upgrade.db > $data/databases/LibreTubeDatabase; cat /data/local/tmp/yt-upgrade.xml > $data/shared_prefs/${pkg}_preferences.xml; chown $owner $data/databases/LibreTubeDatabase $data/shared_prefs/${pkg}_preferences.xml; chcon $context $data/databases/LibreTubeDatabase $data/shared_prefs/${pkg}_preferences.xml"

  # No uninstall or downgrade flag: this also proves the signing key and version code.
  adb install -r "$candidate"
  adb shell am start -W -n "$pkg/$activity" --ez yt_quiet true >/dev/null
  sleep 5
  adb shell pidof "$pkg" >/dev/null
  adb shell am force-stop "$pkg"
  adb pull "$data/databases/." "$work/after/" >/dev/null
  adb pull "$data/shared_prefs/${pkg}_preferences.xml" "$work/after/preferences.xml" >/dev/null
  python3 - "$work/after" "$label" <<'CHECK'
import json, sqlite3, sys, xml.etree.ElementTree as ET
from pathlib import Path
root, label = Path(sys.argv[1]), sys.argv[2]
with sqlite3.connect(root / 'LibreTubeDatabase') as db:
    checks = {
        'subscription': db.execute("SELECT name FROM localSubscription WHERE channelId='UCytUpgrade'").fetchone() == ('Update-Abo',),
        'playlist': db.execute('SELECT name FROM LocalPlaylist WHERE id=9999').fetchone() == ('Update-Playlist',),
        'playlist_video': db.execute('SELECT videoId FROM LocalPlaylistItem WHERE id=9999').fetchone() == ('ytUpgrade01',),
        'history': db.execute("SELECT title FROM watchHistoryItem WHERE videoId='ytUpgrade01'").fetchone() == ('Update-Video',),
        'position': db.execute("SELECT position FROM watchPosition WHERE videoId='ytUpgrade01'").fetchone() == (42000,),
    }
prefs = {e.get('name'): e.text if e.tag == 'string' else e.get('value') for e in ET.parse(root / 'preferences.xml').getroot()}
checks.update(design=prefs.get('yt_design') == 'standard', motion=prefs.get('yt_design_motion') == 'false', preferences=prefs.get('yt_upgrade_probe') == 'retained')
Path('upgrade-' + label + '.json').write_text(json.dumps(checks, indent=2) + '\n')
for name, passed in checks.items(): print(('OK' if passed else 'FAIL'), label, name)
if not all(checks.values()): raise SystemExit('Upgrade lost local user data')
CHECK
done
