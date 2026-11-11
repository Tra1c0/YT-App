# YT – Funktionen

Alle Funktionen der App mit kurzer Erklärung. Die Liste wird bei jeder Änderung mitgepflegt
(siehe `CLAUDE.md`). Technische Details stehen in [ARCHITECTURE.md](ARCHITECTURE.md), die Designs
im Einzelnen in [DESIGNS.md](DESIGNS.md).

Status: **E** = im Emulator geprüft · **T** = durch Tests abgedeckt · **–** = noch nicht geprüft.
Getestet auf einem echten Handy wurde bisher nur, was du selbst benutzt hast.

Die Liste beschreibt immer den Stand von `main` (den neuesten Build).

## Designs und Aussehen

| Funktion | Was sie macht | Status |
| --- | --- | --- |
| 14 Designs | Standard, YouTube neu, Movo, After, Side, Clay, Editorial, Glas, Pop, Formen, Frost, Kiesel, Frequency, Synthwave. Jedes hat eigene Startseite, Videoseite, Leiste, Kopfzeile, Miniplayer, Farben, Schriften, Animationen und Haptik. Einstellungen → Darstellung → Design. | E |
| Frequency | 13. Design: Aufbau von YouTube neu mit den Formen von Formen auf tiefer Nacht (Koralle, Aqua, Violett). Reagiert deutlich auf den Klang: Formen wachsen, federn und morphen (Keks/Blob/Squircle/Kreis), Spektrum-Balken in der Kopfzeile, Spektrum-Welle an Leiste und Miniplayer, Strahlen um den Play-Ring, fließende Blobs und Welle im Hintergrund, Lichtstreifen bei Schlägen, Equalizer im Deezer-Stil auf dem Abo-Knopf. Passt sich dem Handy an (Stufen hoch/mittel/niedrig nach Speicher, Kernen, Android, Wärme, Energiesparmodus). Helligkeitswechsel ≤ 3/s, Schrift ruhig; ohne Ton/Animationen ruht alles. Diagnose zeigt die Klang-Analyse live. | T |
| Synthwave | 14. Design im Neon-Chrom-Look: animierte Retro-Szene als Hintergrund (gestreifte Sonne, Drahtgitter-Berge, Palmen, Sterne, Neon-Gitter, das auf dich zufährt), Karten aus dunklem Glas mit Neonkante, Chrom-Wortmarke, Retro-Schrift (Audiowide). Reagiert dezent auf Musik (Bass beschleunigt das Gitter, Pegel lässt die Sonne glühen). Passt sich dem Handy an; ohne Animationen ein ruhiges Standbild. | T |
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
| Themen im Feed | Feed-Regler → „Themen“: eigene Themen mit Schlagwörtern (Chips, bis 12), Anteil-Regler 0–50 % („≈ N von 20 Videos“), Dauer Sitzung/24 h/7 Tage/unbegrenzt. Passende Videos (YouTube-Suche nach den Schlagwörtern) werden gleichmäßig beigemischt, mit Themen-Chip und „Warum sehe ich das?“. Ab dem 2. gesehenen Video eines Themas schlägt eine Pille ein neues Schlagwort vor (kein Dialog, keine Pause; × merkt sich die Ablehnung). Die alten einfachen Themen heißen jetzt „Suchen“. | T |
| News | Zwei Finger auf der Startseite auseinanderziehen öffnet ein Fenster mit News-Karten (seitlich wischen wie eine Diashow, Fortschritts-Segmente oben). Quellen: neueste Videos (≤ 7 Tage) von Nachrichtenkanälen aus den Abos und von eigenen Themen; Ausgeblendetes, pausierte Kanäle und Nie-Wörter gelten auch hier. Karte antippen = ansehen, nach oben wischen = ans Ende der Warteschlange, lange drücken = Halte-Menü; schließen mit Zusammenziehen, nach unten wischen, Zurück oder ×. Nachrichtenkanäle markieren im Halte-Menü oder Feed-Regler; Kanäle mit Videos der Kategorie „News & Politics“ werden beim Abspielen automatisch markiert. Die Karte unter deinen Fingern wächst dabei selbst zum Fenster und schrumpft beim Schließen zurück; beim Wischen Tiefe (Nachbarkarten treten zurück, Vorschaubild mit Parallaxe, Startseite dahinter rückt zurück und wird ab Android 12 weichgezeichnet); eigene Haptik je Design. Alle Designs außer Standard. | T |
| News: Geschichten | Dieselbe Nachricht von mehreren Kanälen wird eine Karte mit „N Quellen“ und Verlauf (Zeit · Kanal · Titel, neueste oben; Zeile antippen = ansehen). Was seit deinem letzten Blick dazukam, ist markiert („Neu“). „Alle anhängen“ hängt die ganze Geschichte an die Warteschlange. Zusammenlegen/Trennen über ⋮ oben oder Verlaufszeile lange drücken; wird etwa eine Woche gemerkt. Läuft nur auf dem Handy, nach Titelwörtern. | T |
| News: belegte Kurzfassungen | Nur mit eigenem API-Schlüssel. Einstellungen → Gesten & Interaktion → News → „Belegte Kurzfassungen“: Anbieter wählen (Aus / OpenAI (ChatGPT) / Anthropic (Claude) / Google Gemini / Mistral / OpenAI-kompatibel mit eigener Adresse, z. B. Groq, OpenRouter, Ollama im Heimnetz), Schlüssel eintragen, kostenlos testen, entfernen; Modell optional (leer = Standard). Knopf „Kurzfassung“ auf der Karte: 3–5 Kernaussagen aus den Untertiteln, jede mit Sekunde; Antippen spielt ab dort. Jede Aussage wird gegen das Transkript geprüft (Anthropic: eingebaute Belege; sonst wörtliches Zitat an der genannten Stelle), nicht belegbare fallen weg. Nur auf Knopfdruck (kostet Guthaben beim Anbieter), 7 Tage zwischengespeichert. Schlüssel je Anbieter verschlüsselt, nicht in Backups, in der Diagnose nur Anbieter + „gesetzt/nicht gesetzt“. Standard „Aus“: kein Netzverkehr. | T |
| Echtes Neuladen | Runterziehen lädt wirklich neu. Die ersten 50 Videos werden gemerkt, damit oben bis zu 20 neue stehen, höchstens eins pro Kanal unter den ersten zehn. | T |
| Karten wischen | Links = ausblenden (90 Tage, mit „Rückgängig“), rechts = ans Ende der Warteschlange. Spielt nichts, entsteht eine Warteschlange zum Abspielen. Reagiert nur auf klar seitliche Bewegungen: schräges Scrollen nach unten bricht ab und löst nichts aus. | E T |
| Kanal 1 Woche pausieren | Blendet einen Kanal 7 Tage auf den Startseiten aus, ohne ihn zu blockieren. Über die Rückgängig-Pille, das Halte-Menü oder den Feed-Regler. | T |
| Karte auf den Miniplayer ziehen | Karte gedrückt halten und auf den Miniplayer ziehen: „Als Nächstes“, oder ans Ende, wenn schon etwas wartet. | E T |
| Lupe auf eine Karte ziehen | Such-Symbol gedrückt halten und auf eine Karte ziehen: sucht nach dem Kanalnamen. | E T |
| Halte-Menü auf Karten | Speichern, Teilen, Kanal, Ans Ende, Ausblenden, Kanal pausieren, Warum sehe ich das. | E |
| Zeitangaben | „37 Min.“, „~3,5 Std.“, „4 Tage“, „3 Wochen“, „~2,5 Jahre“ statt Datum, auch in den klassischen Zeilen. | T |
| Kompakte Zahlen | 0–999 genau, dann „~1,5k“, „~27k“, „~1,2 Mio.“, „~1,1 Mrd.“ für Aufrufe und Abonnenten. | T |
| Gesehenes markieren | Angefangene Videos bekommen auf den Karten einen dünnen Fortschrittsbalken in der Akzentfarbe, gesehene werden leicht abgedunkelt und bekommen ein kleines „Gesehen“-Abzeichen im Stil des Designs. Karte gedrückt halten (auch beim Ziehen, auch ohne Zieh-Geste, auch im Abos-Reiter) macht den Balken groß und zeigt obenauf die Restzeit („noch 12 Min.“; kleine Bilder „12 Min.“/„1:05 h“), Längenangabe/Play-Knopf blenden so lange aus; Loslassen wieder schmal. Nur die gehaltene Karte. Abschaltbar unter Gesten & Interaktion → Startseite. | T |
| Gesehenes ausblenden | Schalter im Feed-Regler (Gesehene Videos): Videos, die zu 90 % oder bis in die letzten 30 Sek. gesehen sind, verschwinden von allen Startseiten, aus Abos, Live und Shorts; angefangene bleiben. Standard: aus. Nicht im Standard-Design (dort LibreTubes eigenes „Gesehenes ausblenden“). | T |
| Titel in normaler Schreibung | Titel ganz in Großbuchstaben erscheinen in normaler Schreibung, einzelne geschriene Wörter werden leiser; Abkürzungen wie NASA, GTA, 4K bleiben. Grenze: in einem deutschen Großbuchstaben-Titel werden Nomen klein. Nur Anzeige; Gesten & Interaktion → Startseite. | T |
| Clickbait-Satzzeichen glätten | „!!!“ → „!“, „???“ → „?“, „?!?!“ → „?!“, Emoji-Ketten → ein Emoji. Teilen, Playlists und Filter behalten den Originaltitel. Gesten & Interaktion → Startseite. | T |
| Wisch-Toleranz einstellen | Gesten & Interaktion → Startseite: „Wisch-Empfindlichkeit“ (streng … locker, Mitte = bisher), „Fein einstellen“ (Winkel, Abweichung, Weg), „Wischgesten kalibrieren“ (scrollen und wischen, die App schlägt Werte vor) und „Zurücksetzen“. | T |
| Abos ohne Live | Livestreams stehen im Live-Reiter, nicht oben in den Abos. | – |
| Abos-Reiter schnell | Zeigt sofort die gespeicherten Videos; sind sie älter als 3 Std., lädt er im Hintergrund nach (8 Kanäle gleichzeitig, nie doppelt). Der Lade-Kreis hört auch auf, wenn nichts Neues kam. | T |
| Abos „Alle“ gemischt | Bei jedem Öffnen und Ziehen neu zufällig sortiert, beim Scrollen stabil; Ungesehenes oben, Angefangenes danach, Gesehenes unten. „Heute“ bleibt nach Datum. | T |
| Ziehen zum Aktualisieren je Design | Jedes Design hat seinen eigenen Aktualisieren-Effekt. | E |

## Suche

| Funktion | Was sie macht | Status |
| --- | --- | --- |
| Eigene Suche je Design | Öffnet sich über dem Bildschirm im Stil des Designs (z. B. Glasfeld aus dem Knopf heraus), mit Verlauf, Vorschlägen und Filtern. Standard behält das alte Feld. | E T |
| Gesehenes in der Suche | Suchergebnisse zeigen dieselbe Markierung wie die Karten (Balken in der Akzentfarbe, gesehene abgedunkelt mit Abzeichen). | T |
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
| Dislike-Zahlen | In allen Designs mit Like-Knopf: eine kleine grün-rote Leiste am Like-Knopf zeigt das Verhältnis (grün-lastig = positiv). Gedrückt halten, auf „Mag ich nicht“ ziehen und kurz warten: ein Tick, dann steht die geschätzte Zahl in der Kachel. Zahlen von Return YouTube Dislike (Schätzung), nur wenn die Einstellung an ist. | T |
| Kein Hänger nach Tempo/Tonhöhe | Schnelles Drehen an Geschwindigkeit und Tonhöhe führt nicht mehr zu „Unexpected runtime error“ mit stehendem Bild: Änderungen höchstens alle 150 ms, sichere Werte, und bei so einem Fehler startet das Video an derselben Stelle von selbst neu (bis zu 3×). | T |
| Geschwindigkeit je Kanal | Im Geschwindigkeitsmenü „Für diesen Kanal merken“: gilt dann automatisch für jedes Video des Kanals. Liste mit Entfernen unter Einstellungen → Player → „Geschwindigkeit je Kanal“. | T |
| Kapitel per Doppeltipp | Gesten & Interaktion → „Doppeltipp springt zum Kapitel“: Aus / Kapitel, wenn vorhanden / Nur am äußersten Rand (15 %). Rechts nächstes Kapitel, links Anfang des Kapitels (in den ersten 3 s das vorige); das Overlay zeigt den Kapitelnamen. Ohne Kapitel, bei Live und im letzten Kapitel ±N s. Braucht den normalen Doppeltipp. | T |
| Qualität bei wenig Akku | Player-Einstellungen → Akku: unter der Schwelle (15/20/30 %, Standard 20) ohne Ladegerät oder im Energiesparmodus (auch am Ladegerät) höchstens 480p (360/720 wählbar) und 30 fps, ab dem ersten Bild, auch pausiert und sofort nach Einstellungsänderungen; ab Schwelle + 5 % wieder volle Qualität. Auch im Shorts-Feed, nicht für Downloads; bei 720p laufen 60-fps-Videos in 480p. Eine im Player gewählte Qualität gilt nur für genau diese Auflösung; das Qualitätsmenü zeigt „Akku-Limit: 480p · 30 fps“, solange es greift. | T |
| Akku-Hinweis | Bei 20, 15, 10, 5 % schiebt sich das Video kurz nach innen, der Rand zeigt die Farbe (Grün→Gelbgrün, →Gelb, →Orange, →Rot, fließend), dazu eine Vibration je Stand (–, – –, - - —, an- und abschwellend). Ohne Animationen nur ein Farbrahmen; ohne Haptik, im Energiesparmodus oder lautlos vibriert Android nicht – dann ist der Rahmen deutlicher und hält länger. Im Hintergrund/PiP nichts. Abschaltbar. | T |
| Akku-Hinweis-Vorschau | Player-Einstellungen → Akku → „Vorschau“: echte Animation, Farbverlauf und Vibration für 20/15/10/5 % einzeln oder „Alle nacheinander“, auf dem Vorschaubild des zuletzt gesehenen Videos; folgt den Einstellungen und dem Gerät (Animationen, Haptik, Energiesparmodus, lautlos – jeweils mit Hinweis); der große Prozentwert ist als „nur in der Vorschau“ markiert. | T |

## Miniplayer

| Funktion | Was sie macht | Status |
| --- | --- | --- |
| Miniplayer je Design | Dockt an der Leiste an, jedes Design mit eigener Form, Animation und Haptik (Glas nach deiner Mint-Vorlage). | E T |
| Video folgt dem Finger | Video nach unten ziehen → schrumpft unter dem Finger in den Miniplayer; hochziehen öffnet es; Miniplayer runterziehen schließt. | E T |
| Miniplayer im Querformat | Dockt nach dem Drehen (in beide Richtungen) sauber an der Leiste an; Leiste und Miniplayer weichen seitlichen Systemleisten und der Kamera aus. | – |
| Videoseite im Querformat | Vollbild im Querformat verlassen (Knopf, Wischen, Zurück-Taste/-Geste) baut die Seite kurz neu und bleibt danach im Fenster, auch beim Scrollen (erst Hochformat oder bewusstes Vollbild heben das auf): Video auf einer Seite, Details scrollbar, Vorschläge daneben; der Streifen an der Kamera ist eingefärbt, die Startseite scheint nicht mehr durch. Danach im Hochformat lässt sich das Video wieder normal runterziehen. | T |
| Vorschau beim Gedrückthalten | Miniplayer gedrückt halten → leicht vergrößert, mit dem nächsten Video; Loslassen darauf spielt es. | E |
| Miniplayer zurückholen | Bis 10 Min. nach dem Wegwischen „Abos“ in der Leiste gedrückt halten: kurze Vibration, das Video kommt an derselben Stelle mit Warteschlange als angedockter Miniplayer zurück: er schiebt sich hinter der Leiste hervor und landet mit der Animation des Designs (bei ausgeschalteten Animationen sofort). Alle Designs mit Abos-Reiter, auch Standard; After fehlt noch. | T |
| Weiches Schließen | Weggewischter Miniplayer bleibt 60 s pausiert im Hintergrund (ohne Ton, ohne Benachrichtigung, Medientasten wirkungslos); „Abos“ halten holt ihn in dieser Zeit sofort ohne Neuladen zurück. Danach freigegeben, Zurückholen bis 10 Min. mit Neustart. Nicht für Livestreams und Downloads. | T |
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
| Positionen sicher gespeichert | In jedem Player (Seite, Miniplayer, PiP, schwebend, Hintergrund, Warteschlange, Downloads) alle 5 s und sofort bei Pause, Spulen, Videowechsel, Ende, Fehler und Schließen; beim Wechsel immer der Stand des alten Videos. Höchstens ~5 s Verlust bei einem Absturz. Fertige Videos (letzte Minute bzw. ≥ 95 %) starten bei 0, Livestreams nie gespeichert. Nicht für den Shorts-Feed. | T |
| Warteschlange | Als Nächstes / Ans Ende, bleibt beim Antippen anderer Videos erhalten. | E T |
| Warteschlange überlebt Neustart | Wird laufend gesichert und kommt nach Schließen, Wegwischen, Handy-Neustart oder Update automatisch zurück (ohne Abspielen); Pille „Weiter: … · N in der Warteschlange“ auf der Startseite setzt genau dort fort. Ein neu gestartetes Video läuft zuerst, die Warteschlange folgt. Weg nur durch „Leeren“ oder wenn sie zu Ende gespielt ist. Ausnahme: ein Download ersetzt eine wartende Online-Warteschlange. | T |
| Nur Audio und Hintergrund | Spielt nur den Ton, auch die Warteschlange weiter; bei Downloads ohne eigene Tonspur den Ton der Videodatei. | E T |
| Kein Absturz im Hintergrund | Der Dienst bleibt beim Wechsel zum nächsten Video im Vordergrund; sonst pausiert er mit „Wiedergabe pausiert – zum Fortsetzen tippen“. | T |
| Livestreams erholen sich | Bis zu drei Neuversuche; die Fehlermeldung nennt den genauen Code. | T |
| Downloads | Videos und Ton herunterladen und offline abspielen. | E |
| Lautstärke angleichen | Bringt Videos auf etwa −14 LUFS (einstellbar −16 bis −12, höchstens +12 dB), hebt leise Stellen sanft an (abschaltbar) und begrenzt bei −1 dBTP, damit nichts übersteuert. Globale Lautstärke −10 bis +6 dB. Einstellungen → Audio & Video → Lautstärke; ganz aus = Ton unverändert (ab dem nächsten Video). | T |
| Musik im Hintergrund nur als Audio | Geht YT bei einem Musikvideo in den Hintergrund, lädt nur noch der Ton; beim Zurückkommen ist das Bild wieder da. Erkannt an markierten Kanälen, „- Topic“, VEVO, „… Music“, Kategorie Musik. Abschaltbar unter Player. | T |
| Musik von vorn | Musikvideos beginnen bei 0:00 statt an der gemerkten Stelle; ein Link mit Zeit (t=) springt weiterhin dorthin. Abschaltbar unter Player. | T |

## Konto, Updates, Sicherheit

| Funktion | Was sie macht | Status |
| --- | --- | --- |
| YouTube-Anmeldung | Echte Google-Seite mit Autofill; Daten übernehmen (Abos, Playlists, Später ansehen, Mag ich, Verlauf) oder Takeout-Import. | – |
| Update in der App | „Download“ lädt mit Fortschrittsleiste, prüft Prüfsumme und Schlüssel und installiert mit Androids Bestätigungsdialog. Bricht Play Protect ab, versucht YT es bis zu zweimal selbst neu („Neuer Versuch (2 von 3)…“), danach öffnet es die geprüfte APK mit dem Android-Installer. „Fehlgeschlagen“ nur, wenn nichts mehr geht. Danach „YT wurde aktualisiert – tippen zum Öffnen“. Nur neuere Builds gelten als Update. | T |
| Anleitung | Einstellungen → „Anleitung“ (ganz oben): was YT anders macht und wie man es benutzt, 8 aufklappbare Abschnitte, Suche (ohne Umlaute/Groß-Klein), Pfad zur Einstellung und Knöpfe „Zur Einstellung“ (scrollt zur Einstellung, die Zeile leuchtet ~1,6 s auf; bei Animationen aus kurz statisch) / „Öffnen“. Schlicht, in allen Designs gleich. | T |
| Benachrichtigung bei Updates | Prüft im Hintergrund (alle 3/6/12/24 Std., Standard 6; nur mit Internet und genug Akku, kein Google-Dienst), ob ein neuer Build da ist, und meldet „YT Build N ist da“ mit den ersten Neuigkeiten; Antippen oder „Jetzt laden“ öffnet das Update-Fenster. Einmal pro Build. Einstellungen → „Bei neuen Versionen benachrichtigen“ / „Wie oft prüfen“ / ggf. „Benachrichtigungen erlauben“. Diagnose zeigt den letzten Lauf. | T |
| Neuigkeiten beim Update | Das Update-Fenster zeigt, was neu, behoben und geändert ist – für alle Builds seit der installierten Version (neueste zuerst, höchstens 10), scrollbar, die Knöpfe bleiben sichtbar. Commit und „Von dir signiert ✓“ unter „Details“. Ohne Notizen: „Neue Version verfügbar.“ | T |
| Absturz-Report | Nach einem Absturz beim nächsten Start einmal „YT ist beim letzten Mal abgestürzt“ mit „Bericht kopieren“ / „Teilen“; die letzten 3 Berichte bleiben nur auf dem Gerät, Tokens werden geschwärzt. | T |
| Diagnose | Einstellungen → „Diagnose“: Build, Android/Gerät, Design, Sync-Server, Netz, letztes Update-Ergebnis, Konto ja/nein, Warteschlange, letzter Absturz, Zeiten der letzten 3 Miniplayer-Rückholungen – als Text zum Kopieren, ohne Konto- oder Tokendaten. | T |
| Speicher | Einstellungen → „Speicher“: Gesamtgröße, dazu Vorschaubilder, Web-Cache (Anmeldung bleibt), alte Update-Dateien, sonstiger Cache, Absturzberichte – je „Leeren“ oder „Alles Überflüssige leeren“; Downloads nur angezeigt. Abos, Verlauf, Einstellungen und Anmeldung bleiben immer. | T |
| Sicherheit | 22 Befunde geschlossen, Anmeldung verschlüsselt gespeichert und nicht im Backup, nur HTTPS. Details in [SICHERHEIT.md](SICHERHEIT.md). | T |

## Ideen (noch nicht gebaut)

Alle offenen Ideen stehen in [ROADMAP.md](ROADMAP.md): beschlossen, eventuell/später, noch nicht sortiert.
