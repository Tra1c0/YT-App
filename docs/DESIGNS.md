# Designs in YT

Vorlagen: „YouTube neu gedacht“ (Design-Script) und die drei Konzepte „Movo“, „After“ und „Side“
(Video-UI eigenständige Designs), Stand 29.10.2026. Umschaltbar unter
Einstellungen → Design → Design. Alle vier Designs sind dunkel.

## Was gleich bleibt und was sich anpassen muss

Die Entwürfe zeigen generierte Beispielbilder. In der App kommen Bilder, Titel und Zahlen von YouTube.
Layout, Farben, Schriften, Abstände, Navigation und Hierarchie lassen sich nah an den Entwürfen bauen,
die Bildstimmung hängt aber vom jeweiligen Video ab.

YT hat kein Google-Konto. Daraus folgen feste Grenzen:
- Kommentare sind lesbar, aber nicht schreibbar und nicht likebar.
- Live-Chat und Zuschauer-Avatare von Livestreams liefert die verwendete Schnittstelle nicht.
- „Folgen“/„Abonnieren“ sind lokale Abos, „Sammlungen“ sind lokale Playlists.

## Umsetzung je Design

| Element | YouTube neu | Movo | After | Side |
| --- | --- | --- | --- | --- |
| Palette, Schrift, Wortmarke | Etappe 1 | Etappe 1 | Etappe 1 | Etappe 1 |
| Startseite | Überschrift, Themenfilter (Trend-Kategorien), großes Video, „Gerade live“ | Asymmetrisches Mosaik aus farbigen Karten (Kobalt, Pink, Limette) mit Titel im Cover | Register Auswahl/Filme/Live, großes Plakat mit schmalem Titel in Anton, Creator, Laufzeit, „Film ansehen“, „Für später“ (Weiterschauen) als kleine Poster | „Gute Bilder. Gute Gespräche.“, Register Für dich/Deine Leute, Beiträge: Creator zuerst, Video mit Play-Kreis, Titel, Herz, Merken, „Was hat euch daran berührt?“, Knopf „Beitrag“ |
| Videoseite | Titel, Creator, Aktionen, Register Kapitel/Transkript/Kommentare, „Als Nächstes“ | Großer Titel, Folgen/Merken/Teilen, Kapitel-Fläche in Lavendel, Diskussion | Aufbau wie YouTube neu in Bordeaux, Elfenbein und Tangerine | Titel, Creator, darunter die Gesprächsfläche (Griff zum Schließen) mit Gespräch (Kommentare, Zeitmarken springen) und Notizen (eigene, mit Videozeit), Eingabe „Dein Gedanke …“ unten |
| Zweite Ansicht | „Dein YouTube“: Verlauf, Downloads, Weiterschauen, farbige Sammlungen, Später ansehen | „Deine Merkliste.“ (Aufbau wie „Dein YouTube“ in Movo-Farben) | Live-Räume: Creator-Leiste wählt den Raum, große Raumkarte „ROOM 0X“ mit LIVE und Zuschauerzahl, nächste Räume, „Raum betreten“; „Deine Sammlung.“ in After-Farben | „Meine.“ (Aufbau wie „Dein YouTube“ in Side-Farben); Postfach = Abos |
| Navigation | Home, Shorts, Abos, Live, Du | Entdecken, Kanäle, Merkliste | Heute, Räume, Sammlung | Start, Postfach (Abo-Neuheiten), Meine |

**Nicht umsetzbar oder nur angenähert**
- Movo: freigestellte Personen, die aus dem Cover ragen. Angenähert durch Thumbnail plus Farbfläche und Titel.
- After: Chat-Vorschau und Teilnehmer-Avatare in Live-Räumen (keine Daten ohne Konto). Unter der Raumkarte
  stehen stattdessen die nächsten Räume, auf der Karte der Avatar des Creators.
- After: Die Plakat-Titel sind echte Videotitel und damit oft länger als im Entwurf; die Schriftgröße passt sich an.
- Side: „Dein Gedanke …“ als Kommentar senden; stattdessen wird es eine eigene Notiz mit Videozeit,
  nur auf dem Gerät. „Beitrag“ öffnet einen eingefügten YouTube-Link mit Gesprächsfläche.
- Side: Das Herz ist lokal und ohne Zahl (YouTube-Likes lassen sich ohne Konto nicht setzen);
  statt „24 Antworten“ im Feed steht „Zum Gespräch“, weil die Kommentarzahl erst auf der Videoseite geladen wird.
- YouTube neu: das echte YouTube-Logo nur, solange das Repo privat bleibt (Markenrecht).

## Dritte Runde

| Design | Hell/dunkel | Startseite | Navigation |
| --- | --- | --- | --- |
| Clay | hell | Karussell „Heute für dich“, Als Nächstes | 2 × 2 Ton-Blobs |
| Editorial | hell | Reiter, Filter, nummerierte Schwarz-Weiß-Liste, Shorts | angedockt, blauer Balken |
| Glas | hell | Suchfeld, Segmente, große Videos, Shorts | schwebendes Glas, wachsende Pille |
| Pop | hell | Blob-Reiter, gelbe Feature-Karte, Listenkarten, Shorts mit LIVE | Wellenband, gleitender Blob |
| Formen | dunkel | Widgets in Material-Formen, Weiterschauen mit Wellen-Fortschritt | Formen je Reiter |
| Frost | dunkel (Salbei) | Begrüßung, Zahlen des Tages, große Karte, Liste | dunkle Pille |
| Kiesel | hell, Schwarz (Inspirationsbild 8) | Begrüßung, gestapelte Erinnerung, Statistik der Woche, „Für dich“-Karten, schwarzes Kanal-Panel; Reiter „Entdecken“ mit Physik-Kieseln | schwarze Leiste, nur Icons, gleitender Punkt |

Videoseite und „Dein YouTube.“ nutzen bei allen sechs den Aufbau von „YouTube neu“ in den Farben
und der Schrift des Designs. Der Shorts-Reiter ersetzt die Trends.

## Bewegung

Alle Animationen der Designs lassen sich unter Darstellung → „Animationen der Designs“ abschalten;
dann wechseln Zustände direkt. „Animationen entfernen“ in den Android-Bedienungshilfen wirkt zusätzlich.

| Design | Charakter | Was sich bewegt |
| --- | --- | --- |
| YouTube neu | ruhig | Videos blenden mit kurzem Anstieg ein, beim Laden schimmern Platzhalter statt eines Kreises, Filter wechseln weich die Farbe |
| Movo | federnd | Mosaik-Blöcke wachsen herein, Karten neigen sich unter dem Finger wie Kacheln, gewähltes Cover wächst, die anderen treten zurück |
| After | filmisch | Kamerafahrt: das Plakat bleibt beim Scrollen mit halber Geschwindigkeit zurück und dunkelt ab, langsamer Zoom, Räume blenden über, LIVE atmet |
| Side | sanft | Beiträge gleiten herein, das Herz ploppt mit Ring und Funken, Gesprächsfläche zieht hoch |
| Clay | knetig | Blobs und Karten geben unter dem Finger nach und wackeln wie Gelee nach, Karussellkarten dehnen sich beim Wischen wie Teig, Play-Knopf atmet |
| Editorial | knapp | Einträge werden von links „gedruckt“, Schwarz-Weiß-Bilder nehmen beim Drücken Farbe an, blauer Balken gleitet ohne Federn |
| Glas | flüssig | echtes Glas (siehe unten): ziehbarer Tropfen in Leiste und Segmenten, Play-Knöpfe quellen unter dem Finger, Licht wandert langsam |
| Pop | verspielt | der Pflaumen-Blob ist ziehbar, dehnt sich wie Sirup und wackelt beim Landen, die Bandblase beult sich aus, Stern dreht sich beim Merken |
| Formen | lebendig | die aktive Form wächst aus einem Kreis (Wellen wachsen, Ecken werden eckig) und dreht sich ein, Welle läuft über den Fortschritt, Zahlen zählen hoch |
| Frost | flüssig | Milchglas-Karten über wanderndem Licht, Leiste aus dunklem Glas mit ziehbarem Tropfen, Zahlen zählen hoch |
| Kiesel | körperlich | Überschrift tippt sich als Kontur und füllt sich, Kiesel fallen zu einem Haufen, lassen sich greifen und werfen und folgen der Neigung des Handys; Balken wachsen, Zahlen zählen, der Erinnerungsstapel fächert sich auf, das schwarze Panel steigt hoch, Kreis-Ripple in der Leiste |

Extras in allen Designs: haptischer Tick, wenn etwas einrastet (Reiter, Segment, Karussellkarte,
gegriffener Kiesel), eine Bestätigung beim Liken; bei Glas und Frost wandert die Lichtkante auf dem
Glas mit der Neigung des Handys. Beides hängt am Schalter „Animationen der Designs“.

Kurven und Dauern folgen den Regeln aus Emil Kowalskis Animations-Skills: starkes Ease-out
(`cubic-bezier(0.23, 1, 0.32, 1)`) unter 300 ms für Eintritte und Antworten, Ease-in-out für
Bewegung auf dem Bildschirm, Federn für alles, was man anfassen kann, nie aus Größe 0.

## Gesten (Runde 7)

Alles, was man anfasst, folgt dem Finger 1:1, gibt an Grenzen mit einem Gummiband nach statt
hart zu stoppen, tickt am Schwellwert und nimmt beim Loslassen die Geschwindigkeit des Fingers in
die Feder mit (nach Apples „Designing Fluid Interfaces“, gemeinsamer Baukasten in
`ui/yt/YtGestures.kt`). Ziehen zum Aktualisieren hat je Design ein eigenes Material
(`ui/yt/YtRefresh.kt`). Der Plan steht in [RUNDE-7.md](RUNDE-7.md).

| Design | Ziehen zum Aktualisieren | Gesten |
| --- | --- | --- |
| YouTube neu | roter Bogen, der sich mit dem Zug füllt | Filterpille gleitet zwischen den Begriffen, Filter bleiben beim Scrollen oben; langes Drücken auf ein Video öffnet Schnellaktionen in einem Sheet, das sich ziehen und wegwerfen lässt |
| Movo | Kobalt-Pille, die sich dehnt; beim Laden springt ein Limettenpunkt | Karte nach rechts wischen merkt sie (Lesezeichen wächst dahinter) |
| After | Filmvorlauf-Ring, beim Laden läuft der Sektor um | Plakate durchwischen (Bild bleibt mit halber Geschwindigkeit zurück, Titel blendet), Ziehen am oberen Rand dehnt das Plakat |
| Side | Aprikosenpunkt, der wächst und beim Laden hüpft | Beitrag nach rechts wischen herzt ihn, Doppeltipp aufs Bild herzt mit großem Herz |
| Clay | Tropfen Knete, der sich dehnt und nachwackelt | Play-Knopf bläht sich beim Halten auf und ploppt zurück; über die Punkte ziehen scrubbt das Karussell |
| Editorial | schwarze Linie aus der Mitte, darunter AKTUALISIEREN in Mono | Wischen blättert die Reiter (knapp, ohne Feder); Eintrag nach links schieben legt einen schwarzen MERKEN-Zettel frei |
| Glas | Glastropfen, der wächst | Langes Drücken auf ein Bild: eine Glaslupe mit Brechung und Farbsaum folgt dem Finger |
| Pop | Blob mit Stern, der beim Laden kreiselt | Stern anschnippen (dreht mit Schwung aus), Karten schwingen mit der Scrollgeschwindigkeit, gehaltene Karte bläht sich wie ein Ballon |
| Formen | Cookie, der sich mit dem Zug dreht | Die Welle unter „Weiterschauen“ ziehen springt genau in die Stelle des Videos; die Cookies drehen sich mit dem Scrollen, die Bilder darin bleiben aufrecht |
| Frost | Frostring, der zuzieht und atmet | Eine Karte drücken taut sie auf (Unschärfe und Schleier weichen langsam, frieren langsamer wieder zu); die Begrüßung schmilzt beim Scrollen |
| Kiesel | Kiesel, der von links hereinrollt | Erinnerungsstapel durchwischen (die nächste Karte kommt nach vorn, Schwung entscheidet); Balken antippen zeigt Tag und Zahl in einer Blase |

## Videoseite und Shorts je Design (Runde 7, Etappe 3)

Die Videoseite behält ihren Aufbau (Titel, Creator, Aktionen, Reiter, Kapitel, Als Nächstes),
aber jedes Design gibt den Teilen seine eigene Form und Bewegung (`ui/yt/YtWatchDesigns.kt`);
der Shorts-Reiter legt sein Raster im Material des Designs an (`ui/yt/YtShorts.kt`).

| Design | Aktionen | Abonnieren | Reiter | Kapitel | Shorts |
| --- | --- | --- | --- | --- | --- |
| YouTube neu | Pillen, die unter dem Finger nachgeben | Pille mit Ring, Wackeln und Haken | Unterstrich gleitet | Zeilen, aktuelles Kapitel im Akzentrahmen | – |
| After | Filmstreifen aus Bildfenstern, ein gedrücktes leuchtet | Rahmen in Kondensierter Schrift, Akzent wischt von links herein | Bildmarke (Dreieck) gleitet unter die Reiter | Szenen mit Timecode und Letterbox-Bild, Akzentbalken wächst | – |
| Clay | puffige runde Knöpfe, die sich quetschen | puffige Pille, mauve wenn abonniert | Knete-Platte mit erhabenem Blob, der federt | Knete-Chips in einer Reihe, das aktuelle größer mit Mauve-Ring | puffige Rahmen, die sich quetschen |
| Editorial | eine gedruckte Zeile in Mono, gedrücktes Wort invertiert | unterstrichenes Wort, invertiert wenn abonniert | nummerierte Abschnitte, dicke Linie springt knapp | Tabelle mit Haarlinien, Schwarz-Weiß-Bilder, aktuelle Zeile invertiert und farbig | drei Spalten Schwarz-Weiß, Farbe unter dem Finger, nummeriert |
| Glas | eine Glasleiste mit Icons, die beim Drücken aufquillt | Glaspille, die quillt und den Akzent annimmt | ziehbarer Glastropfen über den Reitern, Kommentare als Glasknopf | Glaskacheln, die quellen, aktuelle weiß gerahmt | Standbild mit Glas-Beschriftung, die es bricht |
| Pop | farbige Blobs, die beim Tippen wackeln | pinker Blob mit Burst, lila wenn abonniert | Blob-Chips, die hüpfen | schiefe Blobs, das aktuelle richtet sich auf und wächst | farbige Rahmen, schief, wackeln beim Tippen |
| Formen | Material-Formen in eigenen Farben, die sich beim Tippen um ein Achtel drehen | gelbe Pille, hüpft, Limette wenn abonniert | Limetten-Welle läuft unter dem Reiter und federt hinüber | Cookie-Bilder, das aktuelle dreht sich im Limettenring | wechselnde Widget-Formen auf dem Nachtverlauf |
| Frost | Frostkreise, die auftauen | weiße Pille, als Frostglas wenn abonniert | Frostspur mit weißem Segment, das gleitet | Frostzeilen, die auftauen, weißer Balken wächst | Frostkarten auf Salbei, die auftauen |
| Kiesel | Kapseln in Lavendel, Pfirsich und Beige, die sich quetschen | Creme-Kapsel auf dem schwarzen Panel, Lavendel wenn abonniert | beige Spur, schwarze Kapsel federt | Zeilen auf Creme, Pfirsichbalken wächst, Zeit in Lavendel-Kapsel | – |

## Liquid Glass (Glas und Frost)

Die Glasflächen nutzen Kyants Backdrop-Bibliothek (`third_party/backdrop/`): Unschärfe ab
Android 12, Lichtbrechung am Rand und chromatische Aberration ab Android 13, darunter eine fast
deckende Tönung. Die schwebende Leiste sieht den Bildschirm darunter, obwohl beide getrennte
Views sind: Sie zeichnet den Bildschirm-Container selbst (auf der Hardware-Leinwand nur Verweise
auf die schon aufgezeichneten Display-Listen) und wird im selben Frame neu gezeichnet, wenn sich
der Bildschirm ändert. Die Auswahl ist ein Glastropfen nach dem Katalog der Bibliothek: ruht als
weiße Pille der Referenz, lässt sich über die Reiter ziehen, dehnt sich mit seiner Geschwindigkeit,
quillt beim Festhalten auf, wird klar und vergrößert die Reiter darunter.

## Maße und Skalierung

Alle Design-Bildschirme folgen den vermessenen Referenzen und skalieren mit der Bildschirmbreite,
Details in [DESIGN-MASSE.md](DESIGN-MASSE.md). Jedes Design hat eine eigene schwebende
Navigationsleiste und eine eigene Kopfzeile. Die Leiste ist so gebaut, dass weitere Animationen
(z. B. ein wandernder Indikator) direkt auf ihrem Zustand aufsetzen können.

## Etappen

1. Design-Schalter, Paletten, Schriften, Wortmarken, beschriftete Navigation. **Erledigt.**
2. YouTube neu: Startseite, Videoseite, „Dein YouTube“ (neue Bildschirme in Jetpack Compose). **Erledigt.**
3. Movo: Mosaik-Startseite, Videoseite mit Kapitel-Fläche. **Erledigt.**
4. After: Plakat-Startseite, Live-Räume, Sammlung, Videoseite in After-Farben. **Erledigt.**
5. Side: Beitrags-Feed, Videoseite mit Gesprächsfläche und Notizen, „Meine.“ **Erledigt.**
6. Bewegung: Movo-Cover wächst leicht, die übrigen Karten treten zurück; After blendet Plakate ineinander
   und beim Start in die Wiedergabe über; die Side-Gesprächsfläche zieht unter dem Video hoch und lässt
   sich am Griff schließen; Cover geben beim Tippen leicht nach. Compose übernimmt die
   System-Animationsskala, „Animationen entfernen“ macht daraus direkte Wechsel. **Erledigt.**
   Ein echter Übergang des Covers in den Player (Shared Element) ist mit dem klassischen
   Player-Layout (MotionLayout) nicht möglich und wäre ein eigener Umbau.
