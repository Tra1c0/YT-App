# Änderungen gegenüber LibreTube

YT ist ein veränderter Fork von LibreTube (GPLv3). Basis: LibreTube `master`, Commit 9380ab045
(Version 32.1, Stand 2026-10-29). Jede Änderung ist zusätzlich in der Git-Historie nachvollziehbar.

| Datum | Änderung | Dateien |
| --- | --- | --- |
| 2026-10-29 | App-Name „YT“, Paket-ID `app.tra1c0.yt`, Versionscode aus der CI-Laufnummer | `app/build.gradle.kts` |
| 2026-10-29 | Signatur aus Repository-Secrets, sonst Debug-Schlüssel | `app/build.gradle.kts` |
| 2026-10-29 | Update-Prüfung zeigt auf das YT-Repo | `app/src/main/java/com/github/libretube/api/ExternalApi.kt` |
| 2026-10-29 | Eigener Build-Workflow statt der LibreTube-Workflows | `.github/workflows/` |
| 2026-10-29 | Eigenes README, LibreTube-README umbenannt | `README.md`, `README.LibreTube.md` |
| 2026-10-29 | Kein Willkommensbildschirm: YT startet direkt, lokal ohne Konto | `ui/activities/MainActivity.kt` |
| 2026-10-29 | Neues Akzent-Theme „Pink (YT)“ (Material 3 Fidelity aus #FF5C8A) als Standard, dunkles Theme als Standard | `res/values/yt_colors.xml`, `res/values*/themes.xml`, `helpers/ThemeHelper.kt`, `res/values/array.xml`, `res/xml/appearance_settings.xml`, `ui/base/BaseActivity.kt` |
| 2026-10-29 | Schriften: Geist für Text, Geist Mono für Laufzeiten, Doto (Punktmatrix) für den Titel oben | `res/font/`, `res/values/yt_type.xml`, `res/values*/themes.xml`, `res/layout/activity_main.xml`, `res/layout/video_row.xml`, `res/layout/trending_row.xml`, `fonts/` |
| 2026-10-29 | Untere Navigation ohne Beschriftung, App-Name „YT“ im Titel | `res/layout/activity_main.xml`, `helpers/ThemeHelper.kt` |
| 2026-10-29 | Wischgesten: Lautstärke links, Helligkeit rechts | `ui/views/CustomExoPlayerView.kt`, `res/layout/player_gesture_controls_view.xml` |
| 2026-10-29 | Farben aus dem Video: Player-Seite übernimmt per Verlauf die Hauptfarbe des Thumbnails (abschaltbar unter Darstellung) | `helpers/YtVideoColors.kt`, `ui/fragments/PlayerFragment.kt`, `res/xml/appearance_settings.xml`, `res/values*/yt_strings.xml` |
