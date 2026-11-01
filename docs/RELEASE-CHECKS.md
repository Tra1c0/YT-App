# Release-Prüfungen

## Ein Build, ein Prüfgegenstand

`Build YT` führt alle App-Unit-Tests aus, einschließlich der Warteschlangen-Tests im `util`-Paket. Englische und deutsche `yt_*.xml`-Ressourcen müssen dieselben Schlüsselnamen enthalten.

Danach baut der Workflow die Release-APK und den ausschließlich für Emulatorprüfungen bestimmten Eingabetreiber. Ein unveränderliches Actions-Artefakt enthält APK, R8-Mapping, Treiber, Commit-Metadaten und Prüfsummen. Die wiederverwendbare Emulatorprüfung lädt dieses Artefakt, kontrolliert Prüfsummen und Commit und checkt die passenden Testskripte aus.

Eine Veröffentlichung hängt vom Ergebnis der Prüfungen ab. `main` erfordert die vollständige Design- und Kompatibilitätsprüfung sowie den persönlichen Signaturschlüssel für eine stabile Veröffentlichung. Andere Branches erzeugen als Vorschau gekennzeichnete Pre-Releases. Ein fehlgeschlagener Lauf hinterlässt seine Diagnose-Artefakte und veröffentlicht keine stabile APK.

`SHA256SUMS` im Release enthält die APK-Prüfsumme. `build.json` nennt Commit, Buildnummer und öffentliche Signaturinformationen; der Keystore wird nicht veröffentlicht.

## Prüfungen

| Bereich | Inhalt |
| --- | --- |
| JVM | Vorhandene App-Tests, Warteschlange, Feed-Regeln, Gestenmathematik, Versionsvergleich und Grafik-Rückfallmodus |
| A | Miniplayer andocken, Vorschau, Trefferflächen und designabhängige Aktionen |
| B | Unter dem Finger verkleinern und öffnen, langsam loslassen, schnelles Wischen und schließen |
| C | Spul-Lupe, Einzelbilder, Abbruch, Rücksprung-Chip, Passage wiederholen, Richtungsgesten und Teilen mit Zeitmarke |
| D | Karten ausblenden und rückgängig machen, Warteschlange, Ziehen, Feed-Regler, Interessen und Zeitbudget; Standard prüft seine klassischen Kartenaktionen |
| E | Zeitbezogene Kommentare, Spoilerschutz und Szenenwechsel |
| Kompatibilität | Start, lokale Wiedergabe, Bild-in-Bild samt Rückkehr, Miniplayer, Bibliothek und Einstellungen in allen Ansichten auf Android 8 und 16 |
| Barrierefreiheit | Alle Ansichten mit 180 % Systemschrift und ausgeschalteten Systemanimationen auf API 34; beschriftete, auswählbare Navigationstabs |
| Update | Installation über Build 64 und den letzten stabilen Build; Abos, Playlists samt Videos, Verlauf, Position und Einstellungen bleiben erhalten |

Auf Arbeitsbranches laufen zunächst A–E für YouTube neu und Glas. Mit `full_validation=true` und auf `main` kommen alle anderen Ansichten und die Kompatibilitätsprüfungen hinzu.

## Verlässliche Eingaben und Testdaten

`scripts/gesture-driver/YtInput.java` läuft unter `app_process` auf dem Emulator, außerhalb der App. Ein Prozess hält die gesamte Folge von DOWN/MOVE/UP samt ursprünglichem Zeitstempel zusammen. Bildschirmabfragen lesen die aktuelle Accessibility-Hierarchie und warten nicht auf eine animationsfreie Oberfläche. Die Animationen bleiben während der Gestenprüfung eingeschaltet.

Der dreiminütige lokale Testfilm, feste Feed-Metadaten und Kommentar-Fixtures machen die Bedienprüfungen unabhängig von wechselnden YouTube-Charts. Die Testparameter akzeptiert die App ausschließlich auf dem Android-Emulator. Reale Netzwerkwiedergabe ist davon getrennt zu prüfen.

Ein fehlender Prüfgegenstand zählt als Fehler. „Kein Player gefunden“ oder eine fehlende Testdatei dürfen keinen grünen Gestentest ergeben. App-Abstürze, fehlgeschlagene Gesten und ANRs werden getrennt ausgegeben. App-Hänger sind Fehler; vorhandene Thread-Dumps werden für die Diagnose gesammelt.

## Läufe und Diagnose

- **Automatisch:** Push auf `main` oder `r12/**`; PR nach `main` baut und prüft ohne Veröffentlichung.
- **Vollständig:** Actions → Build YT → Run workflow → `Full validation` aktivieren.
- **Vorhandenes Release prüfen:** Actions → Crash check → Run workflow; Buildnummer oder vollständigen Release-Tag und gewünschte Designs angeben. Damit lässt sich auch eine ältere APK mit aktuellen Diagnoseskripten untersuchen; dieser manuelle Lauf ersetzt nicht die Prüfung des Veröffentlichungslaufs.

Die Artefakte enthalten Testberichte, Logcat, Screenshots und gegebenenfalls ANR- sowie Update-Berichte. Für visuelle Vergleiche denselben Testfilm, dasselbe Design, dieselbe Schriftgröße und denselben Bildschirmzustand verwenden. Emulator-Screenshots belegen die Darstellung; sie ersetzen keine Messung der Bildrate auf einem Handy.
