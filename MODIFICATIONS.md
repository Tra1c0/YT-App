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
