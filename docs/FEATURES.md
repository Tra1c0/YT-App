# YT – Funktionen

Alle Funktionen der App mit kurzer Erklärung. Die Liste wird bei jeder Änderung mitgepflegt
(siehe `CLAUDE.md`). Technische Details stehen in [ARCHITECTURE.md](ARCHITECTURE.md), die Designs
im Einzelnen in [DESIGNS.md](DESIGNS.md).

Status: **E** = im Emulator geprüft · **T** = durch Tests abgedeckt · **–** = noch nicht geprüft.
Getestet auf einem echten Handy wurde bisher nur, was du selbst benutzt hast.

Stand: Build 94.

## Designs und Aussehen

| Funktion | Was sie macht | Status |
| --- | --- | --- |
| 12 Designs | Standard, YouTube neu, Movo, After, Side, Clay, Editorial, Glas, Pop, Formen, Frost, Kiesel. Jedes hat eigene Startseite, Videoseite, Leiste, Kopfzeile, Miniplayer, Farben, Schriften, Animationen und Haptik. Einstellungen → Darstellung → Design. | E |
| Hell / Dunkel / System | Für die hellen Designs (Clay, Editorial, Glas, Pop, Kiesel): Hell, Dunkel oder dem Handy folgen. Nur die Farben ändern sich. | T |
| Kein Neustart beim Wechsel | Design, Hell/Dunkel und Animationen greifen sofort; nur wenn gerade ein Video spielt, kommt noch der Neustart-Dialog. Es werden nur Optionen gezeigt, die zum Design passen. | T |
| Animationen der Designs | Schaltet alle Design-Bewegungen ab, Zustände wechseln dann direkt. | E |
| Eigene Haptik | Jedes Design hat eigene Vibrationsmuster (Knacken bei Frost, Herzschlag bei Side, Schreibmaschine bei Editorial …). Abschaltbar unter Gesten & Interaktion. | T |
| Liquid Glass | Echtes Glas mit Unschärfe und Lichtbrechung bei Glas und Frost; die Lichtkante folgt der Neigung des Handys. | E |
| Klassische Bildschirme im Design | Einstellungen, Menüs, Dialoge und Sheets bekommen Ecken, Rahmen und Schrift des Designs. Kanalseite ohne große Lücken, Abonnenten nicht mehr abgeschnitten. | E |
| Lesbare Sheets | Texte in Feed-Regler, Konto-Menü, Halte-Menü, „Warum sehe ich das?“, Rückgängig-Pille und Kacheln werden gegen die Fläche geprüft, auf der sie stehen (mind. 4,5:1), in allen Designs hell und dunkel. | T |
| Farben aus dem Video | Optional nimmt der Player Farben aus dem Vorschaubild. | – |
| App-Symbol | Mehrere Symbole zur Auswahl. | E |

## Startseite und Feed

| Funktion | Was sie macht | Status |
| --- | --- | --- |
| Startseite je Design | Abos, Charts (Musik, Podcasts, Trailer, Gaming), Live und Weiterschauen, je Design eigen dargestellt. | E |
| „Worauf hast du Lust?“ | Beim Öffnen (Kaltstart und nach 30 Min. Pause): Lernen, Musik hören, Meine Abos, Stöbern. Ein Chip auf der Startseite ändert die Wahl. Einstellbar: immer / nach 30 Min. / nie. | E |
| „Heute habe ich … Minuten“ | Stellt Videos zusammen, die in deine Zeit passen, mit sinnvollen Endpunkten (z. B. Kapitelende). Der Miniplayer zeigt die Restzeit, am Ende kommt „Zeit ist um · Noch ein Video?“. | E T |
| Feed-Regler | Detailliertes Menü für den Feed: Quellen und Gewichte, Region, Formate, Länge, Aktualität, Abwechslung, gesehene Videos, Kanäle, Wörter, Themen, Vorschau, „Warum sehe ich das?“. Alles läuft auf dem Handy, YouTube personalisiert nichts. | E T |
| Echtes Neuladen | Runterziehen lädt wirklich neu. Die ersten 50 Videos werden gemerkt, damit oben bis zu 20 neue stehen, höchstens eins pro Kanal unter den ersten zehn. | T |
| Karten wischen | Links = ausblenden (90 Tage, mit „Rückgängig“), rechts = ans Ende der Warteschlange. Spielt nichts, entsteht eine Warteschlange zum Abspielen. Reagiert nur auf klar seitliche Bewegungen: schräges Scrollen nach unten bricht ab und löst nichts aus. | E T |
| Kanal 1 Woche pausieren | Blendet einen Kanal 7 Tage auf den Startseiten aus, ohne ihn zu blockieren. Über die Rückgängig-Pille, das Halte-Menü oder den Feed-Regler. | T |
| Karte auf den Miniplayer ziehen | Karte gedrückt halten und auf den Miniplayer ziehen: „Als Nächstes“, oder ans Ende, wenn schon etwas wartet. | E T |
| Lupe auf eine Karte ziehen | Such-Symbol gedrückt halten und auf eine Karte ziehen: sucht nach dem Kanalnamen. | E T |
| Halte-Menü auf Karten | Speichern, Teilen, Kanal, Ans Ende, Ausblenden, Kanal pausieren, Warum sehe ich das. | E |
| Zeitangaben | „37 Min.“, „~3,5 Std.“, „4 Tage“, „3 Wochen“, „~2,5 Jahre“ statt Datum, auch in den klassischen Zeilen. | T |
| Kompakte Zahlen | 0–999 genau, dann „~1,5k“, „~27k“, „~1,2 Mio.“, „~1,1 Mrd.“ für Aufrufe und Abonnenten. | T |
| Abos ohne Live | Livestreams stehen im Live-Reiter, nicht oben in den Abos. | – |
| Abos-Reiter schnell | Zeigt sofort die gespeicherten Videos; sind sie älter als 3 Std., lädt er im Hintergrund nach (8 Kanäle gleichzeitig, nie doppelt). Der Lade-Kreis hört auch auf, wenn nichts Neues kam. | T |
| Abos „Alle“ gemischt | Bei jedem Öffnen und Ziehen neu zufällig sortiert, beim Scrollen stabil; Ungesehenes oben, Angefangenes danach, Gesehenes unten. „Heute“ bleibt nach Datum. | T |
| Ziehen zum Aktualisieren je Design | Jedes Design hat seinen eigenen Aktualisieren-Effekt. | E |

## Suche

| Funktion | Was sie macht | Status |
| --- | --- | --- |
| Eigene Suche je Design | Öffnet sich über dem Bildschirm im Stil des Designs (z. B. Glasfeld aus dem Knopf heraus), mit Verlauf, Vorschlägen und Filtern. Standard behält das alte Feld. | E T |
| Suchfeld wird Such-Knopf | Bei Glas, Frost und Formen verwandelt sich das Suchfeld der Seite beim Scrollen fließend in den Knopf oben. | T |

## Player

| Funktion | Was sie macht | Status |
| --- | --- | --- |
| Videoseite je Design | Eigener Aufbau unter dem Video mit Titel, Kanal, Aktionen, Beschreibung, Kapiteln. | E |
| Mag ich / Mag ich nicht | Like-Knopf gedrückt halten → zwei Kacheln. Mit YouTube-Anmeldung wird die Bewertung gesendet (ungetestet gegen den Server), sonst nur auf dem Handy gespeichert. | – |
| Zeit unter der Lupe | Beim Spulen den Finger nach oben ziehen: ½×, ¼×, ⅛×, dann einzelne Bilder. Optional zeigt das Video live die Stelle. | E T |
| Zurückspulen mit Gedächtnis | Nach einem Rücksprung bringt ein Chip dich zurück an die alte Stelle. | E T |
| Erklärschleife | Abschnitt A–B wiederholen, optional langsamer und mit Denkpause. Lange auf die Zeitleiste drücken oder über das Player-Menü. | E T |
| Halten und ziehen | Video gedrückt halten: es schrumpft unter den Finger, vier Kacheln zeigen deine Aktionen. 26 Aktionen, je Richtung einstellbar. Bleibt nicht mehr hängen, nachdem man das Video zum Minimieren gezogen hat. | E T |
| Teilen mit Zeitstempel | Teilen gedrückt halten und auf die Zeitleiste ziehen; die genaue Stelle bestimmst du in derselben Bewegung. | E T |
| Kommentare folgen der Szene | Spoilerschutz (spätere Kommentare verschwommen) oder „Diese Stelle“ (nur Kommentare zum aktuellen Abschnitt). | E T |
| Upstream-Funktionen | Qualität, Geschwindigkeit, Tonhöhe, Stille überspringen, Untertitel, Kapitel, SponsorBlock, DeArrow, Einschlaf-Timer, Statistiken, Wischgesten für Helligkeit und Lautstärke, Doppeltippen, Zoom. | – |
| Dislike-Zahlen | Werden von Return YouTube Dislike geholt, aber nur im Standard-Design angezeigt. | – |

## Miniplayer

| Funktion | Was sie macht | Status |
| --- | --- | --- |
| Miniplayer je Design | Dockt an der Leiste an, jedes Design mit eigener Form, Animation und Haptik (Glas nach deiner Mint-Vorlage). | E T |
| Video folgt dem Finger | Video nach unten ziehen → schrumpft unter dem Finger in den Miniplayer; hochziehen öffnet es; Miniplayer runterziehen schließt. | E T |
| Miniplayer im Querformat | Dockt nach dem Drehen (in beide Richtungen) sauber an der Leiste an; Leiste und Miniplayer weichen seitlichen Systemleisten und der Kamera aus. | – |
| Vorschau beim Gedrückthalten | Miniplayer gedrückt halten → leicht vergrößert, mit dem nächsten Video; Loslassen darauf spielt es. | E |
| Schwebender Player mit Klangwelle | Beim Verlassen der App spielt das Video in einem eigenen Fenster weiter, darunter eine Klangwelle, die auf Stimmen und Frequenzen reagiert. Braucht „Über anderen Apps einblenden“. | E T |

## Shorts

| Funktion | Was sie macht | Status |
| --- | --- | --- |
| Shorts-Ansicht | Vertikal wischen, ein Short pro Seite. Gedrückt halten öffnet ihn im normalen Player. | E |
| Versteckte Zeitleiste | Finger an den unteren Rand → Zeitleiste erscheint, seitlich ziehen springt genau zur Stelle. | E T |
| Automatisch weiter | Nach 1, 2, 3 oder 5 Wiederholungen weiter, mit Countdown. | E |

Bekannt: Wischen zum nächsten Short hat im Emulator nicht reagiert; auf dem Handy prüfen.

## Wiedergabe

| Funktion | Was sie macht | Status |
| --- | --- | --- |
| Warteschlange | Als Nächstes / Ans Ende, bleibt beim Antippen anderer Videos erhalten. | E T |
| Nur Audio und Hintergrund | Spielt nur den Ton, auch die Warteschlange weiter; bei Downloads ohne eigene Tonspur den Ton der Videodatei. | E T |
| Kein Absturz im Hintergrund | Der Dienst bleibt beim Wechsel zum nächsten Video im Vordergrund; sonst pausiert er mit „Wiedergabe pausiert – zum Fortsetzen tippen“. | T |
| Livestreams erholen sich | Bis zu drei Neuversuche; die Fehlermeldung nennt den genauen Code. | T |
| Downloads | Videos und Ton herunterladen und offline abspielen. | E |

## Konto, Updates, Sicherheit

| Funktion | Was sie macht | Status |
| --- | --- | --- |
| YouTube-Anmeldung | Echte Google-Seite mit Autofill; Daten übernehmen (Abos, Playlists, Später ansehen, Mag ich, Verlauf) oder Takeout-Import. | – |
| Update in der App | „Download“ lädt mit Fortschrittsleiste, prüft Prüfsumme und Schlüssel und installiert. Danach erscheint „YT wurde aktualisiert – tippen zum Öffnen“ (kein Selbststart mehr, den Sicherheits-Apps abwürgen). Meldet Play Protect zwischendurch einen Fehler, steht „Wird installiert…“ statt „fehlgeschlagen“. Nur neuere Builds gelten als Update. | T |
| Sicherheit | 22 Befunde geschlossen, Anmeldung verschlüsselt gespeichert und nicht im Backup, nur HTTPS. Details in [SICHERHEIT.md](SICHERHEIT.md). | T |

## Ideen (noch nicht gebaut)

Viel Wirkung bei wenig neuem Code. Sortiert nach Wirkung pro Aufwand.

1. **Dislike-Zahlen in den Designs**: die Zahl ist schon da (Return YouTube Dislike), nur nicht in den Design-Seiten. Als Schätzung beschriften.
2. **Lautstärke angleichen**: Androids `LoudnessEnhancer` plus Schalter; gleiche Lautstärke für Musik, Podcasts und Videos.
3. **Kapitel aus Kommentaren**: fehlen Kapitel, Zeitmarken aus einem Kommentar übernehmen (Parser gibt es schon).
4. **Geschwindigkeit pro Kanal merken**: z. B. Podcasts automatisch 1,5×.
5. **DeArrow in den Design-Startseiten**: prüfen und anschließen, falls es dort noch fehlt.
6. **Automatisch Audio bei Musik mit Bildschirm aus**: spart Akku und Daten.
7. **Gesehenes dezent markieren**: Karten leicht abdunkeln, mit Fortschrittsbalken.
8. **Wisch-Toleranz kalibrieren**: in den Einstellungen die eigene Toleranz für die Karten-Wischgesten einstellen und feinjustieren (vom Nutzer gewünscht, für später).
