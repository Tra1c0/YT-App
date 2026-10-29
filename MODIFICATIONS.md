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
| 2026-10-29 | Player-Icons (Play, Pause, Vor/Zurück, Vollbild, Schließen, Warteschlange, Einstellungen, Sperre, Neustart) durch Phosphor-Icons ersetzt (MIT, `licenses/Phosphor-Icons-MIT.txt`) | `res/drawable/ic_*.xml` |
| 2026-10-29 | Player-Zeiten und Vorschauzeit in Geist Mono | `res/values/style.xml`, `res/layout/exo_styled_player_control_view.xml` |
| 2026-10-29 | Feed-Karten: abgesetzte Fläche mit 22dp-Ecken, Thumbnails 16dp bzw. 12dp | `res/drawable/yt_feed_card.xml`, `res/layout/trending_row.xml`, `res/layout/video_row.xml` |
| 2026-10-29 | Einstellungen gruppiert in „Alltag“ (Design, Player, SponsorBlock, Sichern) und „Für Geeks“ (Allgemein, Audio und Video, Benachrichtigungen, Konto) | `res/xml/settings.xml`, `res/values*/yt_strings.xml` |
| 2026-10-29 | Design-Schalter mit vier Designs („YouTube neu“, „Movo“, „After“, „Side“): Paletten, Schriften (Anton, Figtree, Geist 800/900), Wortmarken, beschriftete Navigation; Designs erzwingen dunkles Theme | `helpers/YtDesign.kt`, `helpers/ThemeHelper.kt`, `ui/base/BaseActivity.kt`, `ui/activities/MainActivity.kt`, `ui/preferences/AppearanceSettings.kt`, `res/values/yt_designs.xml`, `res/values/yt_arrays.xml`, `res/font/`, `res/xml/appearance_settings.xml`, `docs/DESIGNS.md` |
| 2026-10-29 | Jetpack Compose für die Design-Bildschirme; Startseite „YouTube neu gedacht“ (Überschrift, Themenfilter, großes Video, „Gerade live“, weitere Videos) | `app/build.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `ui/yt/`, `ui/fragments/HomeFragment.kt`, `helpers/ImageHelper.kt`, `res/values*/yt_strings.xml` |
| 2026-10-29 | „Dein YouTube.“ (Design YouTube neu): Verlauf, Downloads, Weiterschauen mit Fortschritt, farbige Sammlungskarten, gemerkte Playlists | `ui/yt/YouTubeLibrary.kt`, `ui/yt/YtLibrary.kt`, `ui/fragments/LibraryFragment.kt`, `res/values*/yt_strings.xml` |
| 2026-10-29 | Videoseite (Design YouTube neu): Titel, Metadaten, Creator mit Abonnieren, Aktionen (Gefällt mir, Speichern, Teilen, Herunterladen, Nur Audio), Register Kapitel/Beschreibung/Kommentare, Kapitel-Liste mit aktivem Kapitel | `ui/yt/YouTubeWatch.kt`, `ui/yt/YtWatch.kt`, `ui/fragments/PlayerFragment.kt`, `res/values*/yt_strings.xml` |
| 2026-10-29 | Design Movo: Mosaik-Startseite (Entdecken/Folge ich, Karten in Kobalt, Pink, Limette), Videoseite mit „Folgen“, Merken/Teilen und lavendelfarbener „Im Video“-Kapitelfläche; Tab-Namen und Zeitleisten-Farbe je Design | `ui/yt/MovoHome.kt`, `ui/yt/MovoWatch.kt`, `ui/yt/YtHome.kt`, `ui/yt/YtWatch.kt`, `ui/yt/YtTheme.kt`, `helpers/YtDesign.kt`, `ui/activities/MainActivity.kt`, `ui/fragments/PlayerFragment.kt`, `res/values*/yt_strings.xml` |
