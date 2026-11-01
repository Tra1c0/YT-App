# YT

Eigene YouTube-App für Android 8 und neuer, mit zwölf Ansichten, eigener Warteschlange und lokalen Feed-Regeln. Ohne Google-Konto nutzbar.

YT basiert auf [LibreTube](https://github.com/libre-tube/LibreTube) (GPLv3). Änderungen am Fork stehen in [MODIFICATIONS.md](MODIFICATIONS.md); das ursprüngliche README liegt unter [README.LibreTube.md](README.LibreTube.md).

## Installieren und aktualisieren

1. Im Browser mit dem GitHub-Konto anmelden, das Zugriff auf dieses private Repository hat.
2. Unter [Releases → Latest](https://github.com/Tra1c0/YT-App/releases/latest) die `YT-….apk` herunterladen und öffnen.
3. Falls Android danach fragt, der verwendeten Download-App die Installation erlauben.

Updates über die vorhandene App installieren. Paket-ID `app.tra1c0.yt` und persönlicher Signaturschlüssel bleiben gleich; Einstellungen, Abos, Playlists und Verlauf bleiben erhalten. YT kann neben YouTube und LibreTube installiert sein.

**In der App:** Einstellungen → **Updates auf GitHub** öffnet die Release-Seite. Für das private Repository erfolgt der Download über die GitHub-Sitzung des Browsers; Zugangsdaten werden dafür nicht in der APK gespeichert.

Als **Pre-release** markierte `check-…`-Builds sind Vorschauen. Die Release-Notizen nennen, welche Prüfungen bestanden wurden. Stabile `build-…`-Versionen werden erst nach den vollständigen Prüfungen veröffentlicht.

## Funktionen und Bedienung

- **Design:** Standard, YouTube neu, Movo, After, Side, Clay, Editorial, Glas, Pop, Formen, Frost und Kiesel. Wechsel unter Einstellungen → Design. Schriftgröße und reduzierte Animationen respektieren die Android-Einstellungen. Glas nutzt bei Software-Grafik, wenig Arbeitsspeicher oder Energiesparbetrieb eine leichtere Darstellung.
- **Player:** Vollbild, Bild-in-Bild, Hintergrundwiedergabe, Downloads, Wischgesten, präzises Spulen, Rücksprung-Chip und Wiederholung einer markierten Passage. Gesten und Haptik sind unter **Gesten & Interaktion** einstellbar.
- **Warteschlange:** Videos als Nächstes oder am Ende einfügen; ohne laufenden Player zunächst sammeln. Die gestalteten Feeds bieten Kartenwischen, Rückgängig und Ziehen auf den Miniplayer. Die Standardansicht behält ihre klassischen Kartenmenüs.
- **Feed:** Die gestalteten Startseiten bieten Interessenwahl, Feed-Regler und ein optionales Zeitbudget. Die Sortierung erfolgt lokal auf dem Handy.
- **Lokale Herzen:** Auf der Videoseite und im Side-Feed markieren; Markierungen bleiben auf dem Gerät.
- **Kommentare:** Lesen, Zeitmarken anspringen, Szenenfilter und optionaler Spoilerschutz. Notizen in Side werden lokal gespeichert.
- **Daten übernehmen:** Einstellungen → Sichern und Wiederherstellen; Google-Takeout-Abos als CSV oder den vorhandenen Konto-/Takeout-Import verwenden. Details: [Konto und Import](docs/KONTO.md).

## Builds und Prüfungen

GitHub Actions baut eine APK und prüft genau dieses Artefakt vor der Veröffentlichung. Branch-Builds prüfen zunächst YouTube neu und Glas. **Run workflow → Full validation** prüft alle zwölf Ansichten und die Android-Kompatibilität; auf `main` ist diese Prüfung automatisch verpflichtend.

Prüfumfang, Testdaten und Fehlersuche: [Release-Prüfungen](docs/RELEASE-CHECKS.md).

Lokal mit JDK 17 und Android SDK 36:

```sh
./gradlew :app:testReleaseUnitTest assembleRelease
```

Die APK liegt unter `app/build/outputs/apk/release/`. Ein lokal ohne persönlichen Schlüssel gebautes APK verwendet die Debug-Signatur und aktualisiert keine persönlich signierte Installation.

## Signatur und Upstream

Die CI verwendet den vorhandenen Keystore aus dem Repository-Secret `YT_SIGNING`. Die älteren Secrets `YT_KEYSTORE_BASE64` und `YT_KEYSTORE_PASSWORD` werden weiterhin unterstützt. Der Keystore gehört nicht in den Quellcode. Details zur einmaligen Einrichtung: [Installation](docs/INSTALLATION.md).

Upstream-Fixes weiter aus LibreTube übernehmen:

```sh
git remote add upstream https://github.com/libre-tube/LibreTube.git
git fetch upstream master
git merge upstream/master
```

Lizenz: GNU GPLv3 oder später, siehe [LICENSE](LICENSE).
