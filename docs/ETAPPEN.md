# Etappen: SSI und Theme-Builder (TB)

Verbindlicher Fahrplan für die zwei großen Vorhaben. Jede Sitzung, die an SSI oder TB arbeitet,
liest diese Datei zuerst. Grundlage: die Anforderungsdateien des Nutzers (SSI, „Fluent Theme
Builder“), wie im Chat bewertet und angepasst. Sie sind Ideen, keine Vorgaben: was hier steht, gilt.

## Regeln

1. **Gleichschritt:** Etappe N+1 von SSI oder TB beginnt erst, wenn Etappe N von **beiden** abgenommen ist.
2. **Abnahme** einer Etappe = alle Punkte ihres Abnahme-Blocks erfüllt UND der Nutzer schreibt
   ausdrücklich „SSI-N abgenommen“ bzw. „TB-N abgenommen“. Keine Meldung heißt hier **nicht** abgenommen.
3. **Grenzen:** Eine Etappe baut nur ihren Umfang. Was unter „Nicht in dieser Etappe“ steht, wird
   nicht vorgezogen, auch nicht teilweise. Fehlerbehebungen an abgenommenen Etappen sind jederzeit erlaubt.
4. **Status** steht in der Tabelle unten; der Lead pflegt sie im selben Push wie die Arbeit.
5. Unabhängig davon laufen Ambilight und andere Roadmap-Punkte; sie sind kein Teil dieses Gleichschritts.

## Stand

| Etappe | Status |
| --- | --- |
| SSI-1 Kern | gebaut (Build 138, Fix in 140), wartet auf Prüfung und Abnahme durch den Nutzer |
| TB-1 Fundament | gebaut (Build 138, Fix in 140), wartet auf Prüfung und Abnahme durch den Nutzer |
| SSI-2 Präzision | wartet auf SSI-1 + TB-1 |
| TB-2 Animator | wartet auf SSI-1 + TB-1 |
| SSI-3 Belege | wartet auf Etappe 2 |
| TB-3 Vektor | wartet auf Etappe 2 |
| SSI-4 Ultra & Bild | wartet auf Etappe 3 |
| TB-4 Ausweitung | wartet auf Etappe 3 |

Gemeinsame Abnahme für jede Etappe: kompiliert, alle YT-Tests grün, Doku-Check grün, als Build
veröffentlicht, APK geprüft, auf dem Handy des Nutzers ohne Absturz und ohne offene blockierende
Meldung, Diagnose-Zeile der Etappe zeigt plausible Werte.

## SSI – Super Search Intelligence

### SSI-1 Kern
- Lokaler Stellen-Index (Videos + Abschnitte mit Start/Ende, Genauigkeit WORT/ZEILE/UNGEFÄHR) aus
  Archiv-Skripten und nachgeladenen Untertiteln; Wort-Zeitmarken aus YouTubes Auto-Untertiteln.
- Volltextsuche + Bedeutungssuche auf dem Gerät (Modell auf Wunsch geladen, mit Prüfsumme);
  ohne Modell nur Volltext, mit Hinweis. Zusammenführung per Reciprocal Rank Fusion.
- Absicht per Regeln: Zitat, Zeitraum, Kanal, Ausschluss, Sprache; harte Bedingungen werden eingehalten.
- Stufen „Sofort“ (nur lokal) und „Tief“ (plus YouTube-Suche, Untertitel nachladen, gedrosselt,
  Ergebnisse nach und nach, neue Anfrage bricht alte ab).
- Treffer: Zitat mit Markierung, Kanal, Zeit („≈“ wenn ungefähr), Genauigkeit, „Warum dieser Treffer?“,
  Start kurz vor der Aussage, ±30 s Kontext, Clip-Folge abspielen. Spracheingabe.
- Einstellungen (Modell, Index, Budget), Diagnose-Zeile „SSI“, Test-Gerüst für Benchmarks.
- **Nicht in dieser Etappe:** KI-Nachsortierung, Haltung/Widerspruch, Berichte, Bildsuche, Feedback-Lernen.
- **Abnahme:** 10 eigene Testanfragen des Nutzers (5 Zitate, 5 Fragen): bei mindestens 8 liegt eine
  richtige Stelle unter den ersten 5 Treffern; Zitate mit Wort-Zeitmarken starten höchstens 2 s vor der
  Aussage; „Sofort“ antwortet bei bis zu 50.000 Abschnitten in unter 1 s; keine erfundene Genauigkeit.

### SSI-2 Präzision
- KI-Nachsortierung der Top-20 („Tief“), sinnvolle Abschnittsgrenzen, geprüfte Start/Ende-Grenzen,
  getrennte Bewertungen (Relevanz, Zeitgenauigkeit), Feedback-Knöpfe mit lokalem Lernen,
  Benchmark mit echten, vom Nutzer geprüften Beispielen (Recall@K, nDCG, Zeitabweichung).
- **Nicht in dieser Etappe:** Haltung/Widerspruch, Berichte, Bildsuche.
- **Abnahme:** Benchmark-Werte besser als SSI-1 auf denselben Beispielen; mindestens 9 von 10 Testanfragen
  mit richtiger Stelle unter den ersten 3.

### SSI-3 Belege
- Haltung (stützt, widerlegt, schränkt ein, zitiert, bespricht, unklar) mit wörtlich geprüftem Beleg;
  Widerspruch ≠ Widerlegung; gleiche Quelle zählt nicht doppelt; Verlauf einer Person über die Zeit;
  Vergleich nebeneinander, Zeitleiste, Belegbericht, Export, gespeicherte Recherchen.
- **Nicht in dieser Etappe:** Bildsuche, externe Faktenprüfung.
- **Abnahme:** keine Einordnung ohne geprüftes Zitat; der Nutzer bestätigt 5 Recherchen als korrekt belegt.

### SSI-4 Ultra & Bild
- Stufe „Ultra“, externe Faktenprüfung über die Websuche des KI-Anbieters (optional, kostet),
  Bildsuche über Gemini (nur mit Gemini-Schlüssel, Zeiten als ungefähr markiert, Standbild als Hinweis),
  Aussagen-Netz, Quellenvielfalt, Ressourcen-Steuerung nach Wärme/Akku.
- **Abnahme:** wird vor Beginn mit dem Nutzer festgelegt.

## TB – Theme-Builder („Eigenes“ Design)

### TB-1 Fundament
- 15. Design „Eigenes“, gezeichnet aus einer Theme-Datei (Tokens, Komponenten als Knotenbaum,
  Layout Fluss/Raster/frei, Größenregeln, Breakpoints); Start aus einem bestehenden Design möglich.
- Gerendert: Startseite (Karten, Liste/Raster), Kopfzeile, Chips, Leiste, Miniplayer-Optik;
  klassische Bildschirme nur über Stil-Tokens.
- Editor: Zoom/Pan, Auswahl, Mehrfachauswahl, Drag mit erhaltenem Griffpunkt, magnetisches Einrasten
  mit Hysterese und Hilfslinien, Griffe, Zahlenfeld, Farben/Verläufe/Pipette, Ecken einzeln,
  Schatten, Typografie, Kontrast- und 48-dp-Warnungen, Vorschau Breite/Ausrichtung/Schrift/Hell-Dunkel.
- Undo/Redo, Autosave, Versionen, Theme-Liste, Import/Export (.yttheme), sicherer Modus, Ruckel-Messung.
- **Nicht in dieser Etappe:** Animator (außer Aufnehm-/Ablege-Bewegung des Editors), Vektorpfade,
  Morphing, Videoseite/Player/Shorts-Gestaltung, Komponenten-Bibliothek.
- **Abnahme:** Drag folgt dem Finger ohne Sprung und ohne Hinterherziehen; kein Hin-und-her beim
  Einrasten; P95 der Bildzeit beim Ziehen innerhalb des Bildbudgets des Handys (Messung im Editor);
  Theme übersteht Neustart, Export/Import und einen erzwungenen Absturz (sicherer Modus greift);
  Undo stellt jeden Schritt exakt wieder her.

### TB-2 Animator
- Vorlagen (einfacher Modus), Parameter (erweitert), Federn (unterbrechbar, getrennt von Zeit-Animationen),
  Timeline mit Keyframes, Kurveneditor, Zustände (Idle/Pressed/…), Auslöser an YTs vorhandenen Haken,
  vorhersehbare Regeln bei mehreren Animationen auf derselben Eigenschaft.
- **Nicht in dieser Etappe:** Bewegungspfade, Aufnahme, Morphing.
- **Abnahme:** gleiche Laufzeit bei 60 und 120 Hz; Unterbrechen ohne Sprung; gespeicherte Animationen
  laufen nach Neustart identisch.

### TB-3 Vektor
- Zeichenstift, Bézier, Boolesche Operationen, Masken, Morphing (inkompatible Formen als „angenähert“
  markiert), Bewegungspfade, Bewegungsaufnahme.
- **Abnahme:** wird vor Beginn mit dem Nutzer festgelegt.

### TB-4 Ausweitung
- Videoseite, Player-Bedienelemente (Funktion bleibt erhalten), Shorts, Komponenten mit Varianten und
  Überschreibungen, Vorschläge, Barrierefreiheits-Prüfungen, Leistungsmodi.
- **Abnahme:** wird vor Beginn mit dem Nutzer festgelegt.
