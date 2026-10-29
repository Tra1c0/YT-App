# YT

Eigene YouTube-App für Android: werbefrei, ohne Google-Konto, mit eigenem Design und eigenen Funktionen.

YT basiert auf [LibreTube](https://github.com/libre-tube/LibreTube) (GPLv3) und übernimmt dessen
Fixes regelmäßig. Alles, was YT gegenüber LibreTube ändert, steht in [MODIFICATIONS.md](MODIFICATIONS.md).
Das ursprüngliche LibreTube-README liegt unter [README.LibreTube.md](README.LibreTube.md).

## Installieren

1. Unter **Releases** die neueste `YT-….apk` herunterladen.
2. Auf dem Handy öffnen und die Installation aus dieser Quelle erlauben.
3. Neue Builds installieren sich über die alte Version; Einstellungen und Abos bleiben.
   Das klappt nur, wenn alle Builds mit dem persönlichen Schlüssel signiert sind (siehe unten).

YT hat eine eigene Paket-ID (`app.tra1c0.yt`) und läuft deshalb neben YouTube, dem gepatchten
YouTube und LibreTube.

**Abos übernehmen:** In Google Takeout „YouTube und YouTube Music“ → nur „Abos“ exportieren,
dann in YT unter Einstellungen → Sichern und Wiederherstellen die CSV-Datei importieren.

## Signaturschlüssel

Android installiert ein Update nur, wenn es mit demselben Schlüssel signiert ist wie die installierte
App. Der persönliche Schlüssel liegt deshalb **nicht** im Repo, sondern in zwei Repository-Secrets
(Settings → Secrets and variables → Actions):

| Secret | Inhalt |
| --- | --- |
| `YT_KEYSTORE_BASE64` | Der Keystore (PKCS12, Alias `yt`) als Base64-Text |
| `YT_KEYSTORE_PASSWORD` | Das Passwort des Keystores |

Fehlen die Secrets, signiert der Build mit einem Wegwerf-Schlüssel. So eine APK lässt sich installieren,
aber nicht als Update über einen anderen Build.

## Bauen

Jeder Push auf `main` baut in GitHub Actions eine Release-APK und veröffentlicht sie als
Release `build-<Nummer>`. Lokal: `./gradlew assembleRelease` (JDK 17, Android SDK).

## Upstream-Fixes übernehmen

```bash
git remote add upstream https://github.com/libre-tube/LibreTube.git   # einmalig
git fetch upstream master
git merge upstream/master
```

Konflikte sind vor allem in `app/build.gradle.kts`, den Workflows und diesem README zu erwarten.

## Lizenz

GNU General Public License v3.0 oder später, wie LibreTube. Siehe [LICENSE](LICENSE).
