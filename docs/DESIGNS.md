# Designs in YT

<!-- gen:designs-de -->
**14 Designs** (Einträge von `YtDesign`, in der Reihenfolge der Auswahl): `STANDARD`, `YOUTUBE`, `MOVO`, `AFTER`, `SIDE`, `CLAY`, `EDITORIAL`, `GLASS`, `POP`, `SHAPES`, `FROST`, `KIESEL`, `FREQUENCY`, `SYNTHWAVE`.
<!-- /gen:designs-de -->

Umschaltbar unter Einstellungen → Darstellung → Design. Die Abschnitte unten sind in der
Reihenfolge entstanden (Runde für Runde); spätere Abschnitte ergänzen frühere. Welche Designs hell
sein können, steht in FEATURES.md („Hell / Dunkel / System“).

Die ersten vier Designs entstanden nach den Vorlagen „YouTube neu gedacht“ (Design-Script) und den
drei Konzepten „Movo“, „After“ und „Side“ (Video-UI eigenständige Designs), Stand 29.10.2026; sie
sind dunkel.

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

## Bereich „Du“, Abo-Reiter und Live-Seite (Runde 7, Etappe 4)

Der Bereich „Du“ behält seine Reihenfolge (Überschrift, Schnellzugriff, Weiterschauen,
Sammlungen, Lesezeichen), aber Überschrift, Weiterschauen-Karte und Sammlungskarten kommen aus
dem Design (`ui/yt/YtLibraryDesigns.kt`); der Schnellzugriff nutzt die Aktionsknöpfe der
Videoseite. Der Abo-Reiter ist in allen Designs ein eigener Bildschirm (`ui/yt/YtSubscriptions.kt`):
Überschrift des Designs, eine Leiste deiner Kanäle in der Form des Designs, der Filter Alle /
Heute / Live in den Reitern des Designs, der Feed als Karten des Designs. Jede Karte lässt sich
nach rechts schieben, um sie zu merken (ein Lesezeichen wächst dahinter, Tick am Schwellwert),
langes Drücken öffnet die Schnellaktionen, Ziehen aktualisiert. Für YouTube neu ersetzt eine
Live-Seite die klassischen Trends (`ui/yt/YtLive.kt`): zuerst, was aus deinen Kanälen live ist,
dann alles Live in deiner Region, mit atmendem LIVE-Punkt, Zuschauerzahl und Regionswahl.

| Design | Überschrift | Weiterschauen | Sammlungen | Abo-Karten |
| --- | --- | --- | --- | --- |
| YouTube neu | fette Überschrift | Standbild mit Play und Akzentbalken, der sich füllt | Limette und Lavendel | Standbild, Avatar, Titel |
| Movo | fette Überschrift | wie YouTube neu | Limette und Lavendel | Mosaikkacheln, jede dritte Limette, neigen sich zum Finger |
| After | Anton-Versalien mit Akzentstrich | Plakat mit Letterbox, Titel in Anton, Akzentlinie als Fortschritt | Plakate mit Titel in Anton | Programmzeilen mit Letterbox-Bild |
| Side | fette Überschrift | wie YouTube neu | Limette und Lavendel | Beiträge: wer, was, Bild |
| Clay | Fraunces auf einem atmenden Knete-Blob | Knete-Rahmen, Rille mit rundem Knopf | puffige Karten in Creme und Mauve, runde Vorschauen | puffige Karten, die sich quetschen |
| Editorial | Kopfzeile in Mono, Doppellinie | Schwarz-Weiß-Bild mit Farbe unter dem Finger, Prozent in Mono | nummerierte Tabellenzeilen | nummerierte Zeilen, Bild nimmt Farbe an |
| Glas | Überschrift mit Glas-Unterzeile | Glas-Beschriftung über dem Bild hält den Fortschritt, quillt | Glaskacheln | Standbild mit Glas-Beschriftung |
| Pop | erstes Wort auf gelbem Blob | Blob mit gelber Fortschrittspille | farbige Karten, schief, richten sich beim Drücken auf | farbige Rahmen, schief |
| Formen | drei kleine Formen drehen sich | Widget-Form mit der Limetten-Welle als Fortschritt | wechselnde Widget-Formen | wechselnde Widget-Formen mit Farbpunkt |
| Frost | Jakarta in Weiß | Frostrahmen, der auftaut, weiße Linie | Frostkarten, die auftauen | Frostkarten, die auftauen |
| Kiesel | getippte Kontur-Überschrift | Pfirsichkarte, Lavendel-Kapsel füllt sich, Restzeit in schwarzer Kapsel | Lavendel, Pfirsich, Beige mit schwarzer Zähler-Kapsel | Creme-Zeilen mit Kapseln, Pfirsichpunkt für Neues |

## Miniplayer und klassische Bildschirme (Runde 8)

Der Miniplayer (`ui/yt/YtMiniPlayer.kt`) schwebt seit Runde 10 nicht mehr über der Leiste,
er dockt an ihr an (siehe unten).

Die klassischen Bildschirme (Einstellungen, Sheets, Dialoge, Menüs, Suche, Kanal, Playlist,
Verlauf, Downloads) bekommen über das Design-Overlay (`res/values/yt_classic.xml`) die Formen
und Manieren des Designs: Eckenradien von eckig (Editorial) über knapp (After) bis puffig (Clay,
Pop), Sheet-Oberkanten, Dialogecken, Knopfformen, Chips, Popup-Hintergründe, Toolbar-Titel,
Snackbars, Umrisse (Editorial schwarz, Glas und Frost weiß), Versalien bei After und Editorial.
Im Code (`ui/yt/YtClassic.kt`) kommen die Entscheidungen dazu, die ein Theme nicht treffen
kann: Einstellungszeilen als Karten des Designs mit dem Icon in einem Halo (Clay, Glas, Pop,
Formen, Frost, Kiesel), Zeilen ohne Icons, wo das Design druckt (After, Editorial), nummerierte
Einstellungen bei Editorial, Sheet-Optionen in Versalien ohne Icons bei After und Editorial.
Einstellungen, Hilfe und Über stehen zusätzlich am Ende von „Du“ in den Aktionsknöpfen des
Designs. Die Inventur aller Bedienelemente mit ihrem Stand steht in [BUTTONS.md](BUTTONS.md).

| Design | Ecken | Umriss | Icons in Einstellungen | Versalien | Besonderes |
| --- | --- | --- | --- | --- | --- |
| YouTube neu | 14 / 24 | keiner | ja | nein | Pillenknöpfe |
| Movo | 16 / 28 | keiner | ja | nein | Pillenknöpfe |
| After | 4 / 8 | feiner Rand | nein | ja | Knöpfe 4 dp, kondensierte Titel |
| Side | 18 / 30 | keiner | ja | nein | weiche Pillen |
| Clay | 26 / 36 | weiß | im Halo | nein | puffige Karten |
| Editorial | 0 | schwarz, 1 dp | nein, nummeriert | ja | eckige Sheets, Mono-Optionen |
| Glas | 22 / 32 | weiß 50 % | im Halo | nein | durchscheinende Sheets |
| Pop | 26 / 36 | keiner | im Halo | nein | runde Blobs |
| Formen | 24 / 36 | weiß 15 % | im Halo | nein | Nachtflächen |
| Frost | 22 / 32 | weiß 20 % | im Halo | nein | dunkle, durchscheinende Sheets |
| Kiesel | 22 / 30 | keiner | im Halo | nein | schwarze Kapselknöpfe |

## Miniplayer an der Leiste (Runde 10)

Minimiert sitzt der Player auf der Navigationsleiste statt über ihr zu schweben. Wie er
andockt, hängt vom Design ab:

- **Aufsatz** (YouTube neu, Movo, After, Editorial, Glas, Frost): so breit wie die Leiste, direkt
  auf ihr. Die Leiste macht ihre oberen Ecken eckig, Player und Leiste sind eine Fläche mit
  einer Naht. Bei Glas und Frost ist es eine Glasscheibe.
- **Ausbuchtung** (Side, Formen, Kiesel): ein schmalerer Körper wächst mit nach innen gewölbten
  Kurven aus der Leiste, bei Kiesel wie ein schwarzer Tropfen.
- **Angedrückt** (Clay, Pop): ein Blob, der auf die Blobs der Leiste gedrückt ist und sie
  überlappt, wie Knete oder Sirup.

Das Bild füllt seine Form (Zoom statt schwarzer Balken) und wird beim Vergrößern wieder
eingepasst. Maße in dp.

| Design | Andocken | Bildzuschnitt | Schrift | Play-Knopf | Fortschritt | Landung |
| --- | --- | --- | --- | --- | --- | --- |
| YouTube neu | Aufsatz, 64 dp | 16:9, Ecken 10 | Geist, Titel und Kanal | weißes Icon | rote Naht zur Leiste | keine |
| Movo | Aufsatz, 68 dp | 16:9, Ecken 14 | Geist fett | Limetten-Scheibe | Kobalt-Unterstrich | Leiste und Player sacken ein |
| After | Aufsatz, 58 dp | Cinemascope 2,39:1 | Anton in Versalien, Kanal gesperrt | Creme-Icon | orange Haarlinie | Titel steigt auf, Linie läuft ein |
| Side | Ausbuchtung | 16:9, Ecken 12 | Figtree | Pfirsich-Scheibe | Ring um den Knopf | Körper wächst aus der Leiste |
| Clay | angedrückt, 12 dp tief | 16:9, Ecken 20 | Nunito | Mauve-Blob, atmet beim Abspielen | dicker Mauve-Strang | quetscht sich, Leiste sackt ein |
| Editorial | Aufsatz, eckig | quadratisch, schwarzer Rahmen | „JETZT — KANAL“ in Mono, Titel Inter | schwarzes Icon | blaue Marke auf der schwarzen Linie | Linie zieht sich durch |
| Glas | Aufsatz aus Glas | 16:9, Ecken 16, Lichtkante | Google Sans | weiße Glasscheibe | Petrol-Naht | Leiste und Player federn |
| Pop | angedrückt | Pille mit pinkem Sticker-Schatten | Nunito extra fett | Pflaumen-Blob | pinker Strang | wackelt wie Gelee |
| Formen | Ausbuchtung | Pille vor drehender Limetten-Form | Google Sans | pinke Keks-Form, dreht sich | Limetten-Welle, fließt | Formen drehen sich ein |
| Frost | Aufsatz aus Frostglas, so breit wie die Leiste | 16:9, Ecken 14 | Jakarta | weiße Scheibe | orange Naht | friert ein |
| Kiesel | Ausbuchtung, schwarz | Kiesel-Pille | Jakarta fett | Lavendel-Scheibe | Creme-Ring | Kiesel fällt, hüpft, Leiste sackt ein |

Alles Bewegte hängt am Schalter „Animationen der Designs“. Der Emulator-Check
(`crash-check.yml`, Option „screens“) fotografiert Start, Player und Miniplayer je Design und
legt die Bilder als Vorab-Release `screens-<Lauf>` ab.

## Videoseite je Design (Runde 11)

Unter dem Video hat jedes Design seinen eigenen Aufbau (`ui/yt/YtWatchPages.kt`; Movo und Side
behalten ihre Seiten aus Runde 7). Aktionen liegen in gleich breiten Reihen statt in einer
seitlichen Leiste, nichts wird am Rand abgeschnitten. Die Beschreibung zeichnet die Seite selbst
in der Schrift des Designs, mit funktionierenden Links und Zeitmarken; die klassische
LibreTube-Beschreibung bleibt versteckt, so steht nichts doppelt da.

| Design | Aufbau |
| --- | --- |
| YouTube neu | Titel, Metazeile mit „Mehr“ (Beschreibung klappt darunter auf), Kanalzeile, vier gleiche Aktionskacheln, Kommentar-Karte |
| After | Filmplakat: Titel in großen Versalien, Credits-Zeile, „Präsentiert von“, Filmstreifen, „Handlung“, „Szenen“, „Kritiken“ |
| Clay | Puffige Platte mit Titel und Kanal, Raster aus Knete-Kacheln (3 je Reihe), Beschreibung auf einer zweiten Platte |
| Editorial | Zeitungsartikel: Dachzeile, Schlagzeile, Byline mit „Folgen“, nummerierte Aktionstabelle mit Spaltenlinien, Fließtext mit Initiale, Inhaltsverzeichnis mit Punktlinie, Leserbriefe |
| Glas | Glasscheibe mit Titel, Kanal auf einer Glaspille, runde Glasknöpfe mit Beschriftung, Beschreibung auf Glas |
| Pop | Erstes Wort auf gelbem Marker, Kanal als schiefer Sticker, Blobs in zwei Reihen, Beschreibung als Sprechblase |
| Formen | Nachtseite, Reihe aus Material-Formen (Keks, Pille, Blob) als Knöpfe, Beschreibungskarte |
| Frost | Freier Titel, Kanal auf Frostkarte, Frost-Kreise nur mit Icons, Beschreibung auf Frost |
| Kiesel | Dashboard: drei Kiesel mit Aufrufen, Likes und Datum, schwarze Kanal-Kapsel, Aktionen als Kapsel-Raster (2 je Reihe), Beschreibung auf heller Karte |

Wo die Seite unter das Video läuft, zeichnet jedes Design einen eigenen Rand statt eines harten
Schnitts (`ui/yt/YtWatchEdge.kt`, `ui/views/YtEdgeScrollView.kt`): Kiesel eine runde Lippe, Pop
eine Welle, Formen Bögen, Clay eine Kante mit Schatten, Glas und Frost einen Froststreifen mit
Lichtkante, After einen langen Verlauf, Editorial eine gedruckte Linie, YouTube, Movo und Side
einen weichen Verlauf. Der Rand erscheint erst beim Scrollen; die Bildlaufleiste ist aus.

Nach der Prüfung von Build 51 (22 bestätigte Befunde) geändert:

- Beschreibung: Ein angetippter Link verschiebt den zugeklappten Text nicht mehr (eigene
  Link-Behandlung ohne Auswahl), die Schriftgröße folgt der Skalierung der Seite, und einfache
  Web-Adressen gehen denselben geprüften Weg wie die übrigen Links.
- Editorial: Aktionstabelle in Reihen zu dritt, damit „HERUNTERLADEN“ ganz zu lesen ist;
  im Inhaltsverzeichnis stehen die Zeiten bündig rechts, die Punktlinie füllt den Rest.
- After: Aktionen in bis zu zwei Reihen über die volle Breite statt einer seitlichen Leiste.
- Kiesel: das Datum im Kiesel in Kurzform („vor 3 Mon.“), damit es nicht abgeschnitten wird.
- Miniplayer: Landungen beginnen in Ruhelage (kein Sprung beim Andocken), eine laufende Landung
  bricht ab, wenn der Player wieder aufgezogen wird, der Rand unter dem Player schneidet die
  Landung nicht mehr ab, im Querformat verschwindet die Liste neben dem Video, im
  Bild-im-Bild-Modus hat das Video gerade Ecken, die Animationen ruhen, solange der Miniplayer
  nicht zu sehen ist, der Kiesel-Schatten erscheint auch auf Android 8 und 9, Rahmen und Ring
  spiegeln sich bei Sprachen, die von rechts nach links laufen, und der Audio-Miniplayer bleibt
  über der Leiste.

## Schwebender Player mit Klangwelle

Ist „Schwebender Player mit Klangwelle“ (Player-Einstellungen) an, spielt das Video beim
Verlassen der App (Home, Bild-im-Bild-Knopf) in YTs eigenem schwebenden Fenster weiter
(`ui/yt/YtFloatingPlayer.kt`) statt im Bild-im-Bild von Android. Grund: Android teilt einer App
die Lage ihres Bild-im-Bild-Fensters erst nach dem Loslassen mit; während des Ziehens verschiebt
die System-Shell nur dessen Oberfläche (AOSP, `PipTaskOrganizer.scheduleUserResizePip`). Eine
Welle in einem eigenen Fenster könnte also nur hinterherspringen. Im eigenen Fenster sind Video
und Welle eins: Sie bewegen sich in jedem Bild des Displays gemeinsam, mit 60 bis 120 Bildern
pro Sekunde, je nachdem, was das Display kann (das Fenster wünscht sich die höchste Rate bis
120 Hz, solange das Video läuft).

- Das Video bleibt im Medien-Dienst von YT; das Fenster übernimmt es mit
  `PlayerView.switchTargetView` (media3: erst die neue Fläche, dann die alte loslassen, ohne
  Lücke im Bild) und gibt es beim Zurückkehren genauso zurück.
- Ziehen verschiebt (beim Loslassen rastet es an der näheren Seite ein, ein Wisch wirft es zur
  Seite), zwei Finger ändern die Größe, Tippen zeigt Wiedergabe, Öffnen und Schließen,
  Doppeltippen öffnet YT, auf das ✕ unten ziehen schließt (das Video pausiert).
- Ist der Schalter aus oder fehlt die Erlaubnis, gilt Androids Bild-im-Bild wie zuvor; die
  Welle hängt dann als eigenes Fenster an dessen Unterkante (`YtPipVisualizer.kt`, nur Rückfall).

Die Welle (`YtWaveView.kt`, Form in `YtWaveShape.kt`) kommt hinter der Unterkante des Videos
hervor, läuft an beiden unteren Ecken spitz zu und ist doppelt so dick wie die erste Fassung.
Sie ist gespiegelt: Bass in der Mitte, höhere Töne weiter außen, Höhen an den Spitzen; ihre
Tiefe an jeder Stelle folgt dem Band, das dort liegt. Nichts bewegt sich von selbst, derselbe
Ton gibt immer dasselbe Bild.

Die Analyse (`helpers/YtSpectrum.kt`, im Player abgegriffen in `YtAudioTap.kt`): 2048-Punkte-FFT
alle 512 Samples mit dem Blackman-Fenster des W3C-AnalyserNode; 16 Bänder gleichmäßig auf der
ERB-Skala des Gehörs (Glasberg & Moore) von 50 Hz bis 12 kHz, sodass der Sprachbereich die
meisten Bänder bekommt; jedes Band gegen seinen eigenen jüngsten Höchstwert (½ s gehalten, dann
24 dB/s fallend, nie mehr als 12 dB über dem Anteil des lautesten Bands; nach dem Vorbild der
automatischen Empfindlichkeit von Cava), 30 dB Anzeigebereich, Stille unter −70 dB zeigt nichts;
Anstieg 15 ms, Abfall 60 ms in Stream-Zeit. Jede Analyse trägt ihre Stelle im Ton; das Bild
nimmt für jeden Frame genau den Moment, der gerade zu hören ist, gemischt aus den zwei nächsten
Analysen. Wie weit der Ton dem Hören vorausläuft, liest YT am Audiopuffer des Players ab statt
ihn zu schätzen. JVM-Tests (`app/src/test/.../YtSpectrumTest.kt`, `YtWaveShapeTest.kt`) prüfen
das mit synthetischem Ton: Sinus, Sweep, Zweiklang, Vokale nach den Formanten von Peterson &
Barney, Zischlaut, Sprache über Musik, Stille, Einsatzzeit.

Die Erlaubnis „Über anderen Apps einblenden“: Der Schalter geht erst an, wenn sie wirklich
erteilt ist, und erklärt bis dahin den Weg in einem Dialog. Android 15 und neuer sperren sie für
Apps aus einer APK-Datei (YT wird so installiert): Der Schalter dort steht auf „Deaktiviert“, ein
Antippen meldet „App wurde Zugriff verweigert“. Der Dialog nennt den Umweg: den Schalter einmal
antippen, in der App-Info ⋮ → „Eingeschränkte Einstellungen zulassen“, dann einschalten. Im
Emulator (Android 15, installiert wie aus einer heruntergeladenen Datei) nachgestellt und
durchgespielt.

Die Welle wird auf der CPU in ein eigenes Bild gezeichnet, das die GPU nur noch platziert. Grund:
Ein Stack-Dump im Emulator zeigte, dass die GPU-Programme für die geflochtenen Linien beim ersten
Zeichnen gebaut werden, genau wenn das Fenster beim Verlassen der App erscheint; der Render-Thread
der App war damit Sekunden beschäftigt, und Android wartete beim Anhalten des App-Bildschirms
darauf (einmal bis zum „App reagiert nicht“). Mit dem CPU-Bild trat das nicht mehr auf.

Grenzen: Ob ein Gerät 120 Hz liefert, entscheiden Display, Energiesparmodus und Temperatur; die
App kann nur darum bitten. Die Werte der Analyse sind an synthetischem Ton abgestimmt; echte
Videos können Feinschliff brauchen. Beim Übergeben des Videos ins Fenster wartet media3 synchron
auf die Mediensitzung (bis zu 3 s); im langsamen Emulator war das spürbar, auf einem Handy sollte
es Millisekunden dauern (nicht gemessen).

## Shorts-Ansicht (Runde 12)

Shorts öffnen aus dem Shorts-Reiter und aus den Shorts-Reihen der Feeds in einer eigenen
Ansicht über der App (`ui/yt/shorts/`): ein Short pro Seite, nach oben wischen für den nächsten,
beginnend beim angetippten Short mit der Liste, aus der er kam; gegen Ende kommen die verwandten
Shorts des aktuellen dazu. Antippen pausiert, Doppeltipp gibt ein Herz (die Herzen von Side,
nur auf dem Gerät), der Kanalname öffnet den Kanal, rechts Teilen und „Im Videoplayer öffnen“.
Langes Drücken im Shorts-Reiter öffnet weiter direkt den Videoplayer; der Schalter
„Shorts-Ansicht“ unter Gesten & Interaktion stellt den alten Weg ganz wieder her.

Die Ansicht hat einen eigenen ExoPlayer, nicht den Wiedergabedienst: Der Dienst trägt Warteschlange,
Benachrichtigung und Hintergrundwiedergabe des Videoplayers, und ein Feed, der alle paar Sekunden
wechselt, würde beides bei jedem Wischen umschreiben. Die Ansicht spielt nur im Vordergrund, nimmt
den Audiofokus (Video- und Audioplayer pausieren) und holt die Daten der Nachbar-Shorts voraus.

**Zeitleiste (8.1):** versteckt, nur eine 2 dp feine Linie (abschaltbar). Ein Finger in der 48 dp
hohen Zone über der Navigationsleiste lässt die Leiste unter dem Finger aufwachsen; seitlich ziehen
springt genau an die Stelle, mit Zeit und, wo der Short Vorschaubilder hat, einem Standbild; Loslassen
springt dorthin, nach 1,2 s blendet die Leiste aus. Ein Tick pro Sekunde beim Ziehen.

**Autoscroll (8.2):** nach 1, 2, 3 oder 5 Wiedergaben weiter (Standard 1, weil ausdrücklich gewünscht;
„Aus“ wiederholt den Short), umschaltbar oben rechts. In den letzten 3 s läuft ein Ring mit
„Weiter in 3“; Antippen bleibt beim Short. Nie, solange ein Finger liegt, die Zeitleiste offen ist
oder pausiert wurde.

| Design | Leiste und Griff | Pillen | Seitenwechsel (Bewegung, Haptik) |
| --- | --- | --- | --- |
| Standard / YouTube neu | rot, runder Griff | dunkle Kapseln | ruhige Feder, heller Tick |
| Movo | Limette, kurze Kapsel als Griff | dunkle Kacheln, 14 dp | federnd, Anstieg und Klick |
| After | Orange, Bildfenster-Rahmen als Griff, Großbuchstaben | schwarzes Gate, fast eckig | Kamerafahrt (Bild bleibt mit halber Geschwindigkeit zurück, dunkelt ab), schwerer Aufsetzer |
| Side | Aprikose, runder Griff | Graphit, weich | ruhig, doppelter leiser Schlag |
| Clay | Rosé, Blob als Griff, dicke Leiste | Creme mit Mauve | knetig (Seite gibt nach), dumpfer Druck |
| Editorial | Blau, eckiger Griff, Mono-Zeit, Großbuchstaben | Papier mit Schwarz, eckig | knapp, ohne Feder, klarer Tick |
| Glas | Rot, runder Griff | helles Glas mit Petrol | flüssig, tiefer Tick mit Nachhall |
| Pop | Gelb, Blob als Griff | gelbe Blobs mit Pflaume | federnd, Klick und Hüpfer |
| Formen | Limette, Cookie, der sich mit der Stelle dreht | Nacht-Kapseln | federnd, Drehung |
| Frost | Orange, runder Griff | dunkler Frost auf Salbei | flüssig, Knistern |
| Kiesel | Lavendel, Kiesel als Griff | schwarzes Panel | körperlich, schwerer Aufsetzer |

Alle Pillen sind deckend oder kräftig getönt, ihr Text hält 4,5:1 über schwarzem und weißem Bild
(`YtShortsLookTest`). Mit „Animationen der Designs“ aus wechseln Seite, Leiste und Countdown direkt.

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

## Frequency (Runde 21)

Das 13. Design, eigenständig vor dem eigentlichen Musik-Modus: der Aufbau von **YouTube neu**
(große Überschrift, Themenfilter, die oben bleiben, große Videokarten, „Gerade live“, Videoseite
mit Titel, „Mehr“, Kanalzeile, Aktionen, Kommentar-Karte) mit den Material-Formen von **Formen**
(Keks- und Wellenformen, Widget-Ecken, verspielte Formfarben auf tiefer Nacht). Die Oberfläche
reagiert auf den Klang, der gerade läuft. Dunkel, beschriftete Leiste (Home, Shorts, Abos, Du),
Schrift Geist wie YouTube neu. Einstellungen → Darstellung → Design → „Frequency“.

**Palette** (`YtPalettes.Frequency`, alle Textrollen ≥ 4,5:1, `YtFrequencyPaletteTest`):
Nacht `#0B0A18`, Flächen `#17152B` / `#231F3F`, Text `#F4F2FF` / `#B7B2D6`, Signal-Koralle
`#FF4D6D` (Akzent, ein Gruß an YouTubes Rot; darauf dunkle Schrift `#170812`), Live `#E0234D`
(weiße Schrift), Formfarben Aqua `#5BE7DA` und Violett `#B3A6FF`, dazu Bernstein `#FFC46B`.

**Was auf den Klang reagiert** (`ui/yt/FrequencyPulse.kt`, Abbildungen in `FrequencyPulseMath.kt`
und `FrequencyMotion.kt`, Visualizer in `FrequencyVisuals.kt`; seit Runde 21b deutlich stärker):
Der Klang kommt über den Vertrag `ui/yt/sound/YtSound.kt`. Zwei Arten von Kanälen, bewusst
getrennt: **Bewegung** (Größe, Lage, Drehung, Form, Balken) folgt den ungebremsten Werten
`YtSound.motion` und dem Spektrum `YtSound.bands` (16 Bänder) schnell und wird auf den Bereich
gedehnt, in dem sich die Musik gerade bewegt (`FrequencyStretch`), damit auch gleichmäßig laute
Musik sichtbar pumpt. **Helligkeit** (Leuchten, Schleier, Zeitleiste, Aura-Licht) kommt aus dem
geschützten `YtSound.frame` und bleibt bei höchstens drei vollen Wechseln pro Sekunde; Licht-
streifen starten höchstens alle 0,45 s. Alles wird erst beim Zeichnen gelesen (eigene Ebenen,
keine Neuzusammensetzung, keine Allokation pro Bild); Schrift bewegt sich nie.

| Teil | Effekt | reagiert auf | Stärke |
| --- | --- | --- | --- |
| Kekse (Avatare, Filter-Keks, Weiterschauen, Abos-Kanäle, Shorts-Kopf) | wachsen, federn, drehen, verwandeln sich (Keks → Blob → Squircle → Kreis) | Beat (Bass + Pegel), Schläge | bis 16 % größer, Morph-Zyklus 4–12 s |
| Aktionsknöpfe der Videoseite | Formen-Morph, Wachsen, Federsprung nacheinander, Lichtstreifen | Beat, Schläge, Höhen-Sprünge | bis 18 % größer, 5 dp Sprung, Streifen ≤ 32 % Weiß |
| Drei Formen neben „Hör rein.“ / Mediathek-Überschrift | springen nacheinander, verwandeln sich | Schläge, Beat | 20 %, 5 dp |
| Logo in der Kopfzeile | Keks wächst, morpht, Balken hüpfen nacheinander, Schleier | Bass/Mitten/Höhen, Schläge | 20 %, Balken über volle Höhe |
| Kopfzeile | Spektrum-Balken zwischen Wortmarke und Knöpfen, Knöpfe federn | 16 Bänder, Schläge | volle Höhe bei lauten Stellen |
| Leiste | Spektrum-Welle an der Unterkante, Icons hüpfen nacheinander, aktiver Keks morpht | Bänder, Schläge, Beat | Welle bis 10 dp, 4 dp Sprung |
| Miniplayer | Spektrum-Welle auf der Naht, morphender Ring mit Spektrum-Strahlen um Play, Leuchten über dem gespielten Teil, Kopf schwillt | Bänder, Beat, Pegel, Schläge | Welle 8 dp, Strahlen 7 dp |
| Hintergrund (Startseite, Mediathek, Shorts) | drei Lichter wandern, zähe Blobs fließen zusammen (Bass) und trennen sich (Mitten), große weiche Spektrum-Welle unten | Bass, Mitten, Höhen, Pegel | Blobs mit RuntimeShader ab Android 13, sonst Verläufe; Helligkeit niedrig, damit Text 4,5:1 behält |
| Abonnieren-Pille | Leuchten wächst | Pegel (geschützt), Beat | Alpha 0,12–0,35 |
| Erste Karte, Weiterschauen-Widget | Lichtstreifen | Schläge, Höhen-Sprünge | schwach (Text bleibt lesbar) |
| Zeitleiste des Players | gespielter Teil Richtung Weiß | Höhen + Lichtstreifen (geschützt) | max. 45 % |

**Je nach Handy** (`FrequencyTiers.pick`, alle 5 s neu): *hoch* (≥ 6 GB RAM, ≥ 6 Kerne, Android
10+, nicht heiß) alles in der Bildrate des Displays, Blobs per Shader ab Android 13; *mittel*
(weniger RAM/Kerne, mäßig warm) 3 Blobs aus Verläufen, 12 Balken, höchstens 60 Schritte/s;
*niedrig* (Energiesparmodus, sehr warm, wenig RAM) nur Wachsen und Visualizer (8 Balken), kein
Morph, kein Federn, keine Lichtstreifen, keine Blobs, halbe Rate (≈ 30/s). Größen in dp der
Designs (wachsen mit der Bildschirmbreite) oder Anteilen der Fläche.

In Stille, bei pausiertem Video, mit „Animationen der Designs“ aus oder Androids „Animationen
entfernen“ (Skala 0; eine verkürzte Skala wie 0,5x zählt als an) ruht alles in einem fertigen,
ruhigen Zustand (Balken als sanfter Bogen, Formen in ihrer eigenen Gestalt); die Analyse ist dann
aus, ebenso im Hintergrund und in jedem anderen Design. Im Ruhezustand läuft keine Bildschleife.
Diagnose (Einstellungen → Diagnose) zeigt live die Zeilen „Sound“ (hört die Oberfläche zu, Player,
spielt, vorne, Bewegung, Analyse, Engine läuft, Bilder/s, Maxima der letzten 10 s) und „Frequency“
(Halter, Schleife, Stufe).

| Bereich | Frequency |
| --- | --- |
| Startseite | „Hör rein.“ mit drei atmenden Formen, Filterreihe mit Aqua-Keks (Feed-Regler) und Korallenpille, Weiterschauen-Widget mit Wellen-Fortschritt (die Welle läuft nur mit Klang; ziehen springt an die Stelle), große Karten in Widget-Ecken mit Keks-Avatar, „Gerade live“ („Alle“ wählt den Live-Filter), Shorts in Formen, „Mehr für dich“ |
| Kopfzeile | Logo-Keks mit Equalizer, Wortmarke „frequency“ in Geist, Aqua-Keks (Suche), violettes Quadrat (Profil) |
| Leiste | beschriftet, Nacht wie der Miniplayer (eine Fläche beim Andocken), aktiver Reiter auf einem Keks in seiner Formfarbe |
| Videoseite | Aufbau von YouTube neu, Aktionen als Formen in Aqua/Violett/Koralle/Bernstein, Avatar im Aqua-Keks, Korallen-Abo-Pille (violett, wenn abonniert), Formen-Welle unter den Reitern, Kapitel als Keks-Bilder, Rand: kleine Bögen mit Korallenlinie |
| Miniplayer „Pulsnaht“ | Aufsatz wie YouTube neu (Nadel näht die Naht ein, gleiche Vorschau mit „Als Nächstes“), Haptik wie ein Beat (Kick + Hi-Hat) |
| Suche | Aqua-Feld, in das der Keks fliegt (Formen), Filter von YouTube neu (gewählter auf Koralle), Verlauf als bunte Chips |
| Shorts | Raster in Formen-Rahmen über der Aura; Shorts-Ansicht: Korallenleiste, drehender Keks als Griff, Seitenwechsel als Kick |
| Abos / Du | Aura, Kanäle in atmenden Keksen, Karten in Widget-Ecken; Weiterschauen mit Keks-Play und schimmerndem Fortschritt, Sammlungen in Aqua und Violett |
| Wischen, Halten, Ziehen | Keks dreht sich beim Wischen (Aqua merken, Koralle ausblenden), Kekskacheln beim Halten, Korallenrahmen beim Anheben, Keks-Lupe |
| Gesehen-Abzeichen, Rückgängig-Pille, Schnellaktionen, „Worauf hast du Lust?“ | Korallen-Keks, Nachtpille mit Korallenrand, Flächen der Palette, bunte Kacheln mit Keks |
| Klassische Bildschirme | Ecken 20 / 32, Pillenknöpfe, feiner Korallenrand, Icons im Halo, Geist |

Nicht umgesetzt: kein Suchfeld auf der Startseite (also keine Verwandlung in den Such-Knopf wie
bei Formen); ohne Klang-Engine (Vertrag liefert nur Stille) ist alles im Ruhezustand.

## Synthwave (Runde 24)

Das 14. Design, „Neon-Chrom“ nach den beiden Vorlagen des Besitzers (gestreifte Retro-Sonne über
Drahtgitter-Bergen; Outrun mit Palmen und Neon-Gitter bis zum Horizont). Eine animierte Szene ist
der **ganze Hintergrund** der Startseite (auch hinter der Kopfzeile, wie das Plakat bei After) und
liegt gedimmt hinter Abos, Du und dem Shorts-Reiter. Karten und Pillen sind dunkles Glas mit
Neonkante (Magenta → Cyan), Knöpfe haben ein weiches Neon-Leuchten, die Wortmarke „SYNTHWAVE“ ist
Chrom. Überschriften in **Audiowide** (Retro-Schrift, SIL OFL, 70 KB, `fonts/Audiowide-OFL.txt`),
alles zum Lesen in Geist. Beschriftete Leiste (Home, Shorts, Abos, Du).
Einstellungen → Darstellung → Design → „Synthwave“.

**Palette** (`YtPalettes.Synthwave`, `YtSynthwavePaletteTest`): Nacht `#0D0620`, Flächen
`#1A0F36` / `#271848`, Text `#FBF4FF` / `#CDBDEB`, Neon-Magenta `#FF3FD8` (Akzent, darauf dunkle
Schrift `#14051F`), Neon-Cyan `#36E4FF`, Violett `#A98BFF`, Sonnen-Orange `#FFB04A` (dunkle Schrift
auf allen Neons), Live `#D4145A` (weiße Schrift). Sonne `#FFE45E` → `#FF9E3D` → `#FF4F7B` →
`#FF2E97`. Das Glas der Karten (`SynthwaveGlass`, 86–90 % Deckkraft) hält jede Textrolle auch über
dem hellsten Punkt der Szene (Oberkante der Sonne) bei mindestens 4,5:1; auf den geteilten
Bildschirmen (Du, Abos, Shorts), wo Wörter direkt auf dem Hintergrund stehen, liegt ein
Nachtschleier von 86 % über der Szene. Kein Wort steht auf der Szene selbst.

**Die Szene** (`ui/yt/SynthwaveScene.kt`, reine Mathematik in `SynthwaveMath.kt`, getestet in
`SynthwaveMathTest`). Gezeichnet nur in der Zeichenphase in einer eigenen Ebene: keine
Neuzusammensetzung pro Bild, keine Allokation pro Bild (Pfade, Pinsel und Sterne einmal je Größe).

| Ebene | Bewegung | reagiert auf | Stärke |
| --- | --- | --- | --- |
| Sternenhimmel | Sterne funkeln langsam (0,12–0,55 Hz je Stern) | Höhen (geschützt): tieferes Funkeln | Tiefe 0,30 → 0,65 |
| Sonne mit Schlitzen | Schlitze wandern langsam nach unten, wachsen aus dem Nichts, verlassen die Sonne unten (nahtlos) | – (immer ruhig) | 0,11 Schlitze/s |
| Halo und ein feiner Ring | Halo leuchtet sanft | Pegel (geschützt, ≤ 3 Wechsel/s) | Alpha 0,50 → 0,72 |
| Drahtgitter-Berge | 4–7 Konturlinien, hinten violett, vorne elektrisch blau | Bänder (Bewegung): jede Linie wird etwas höher | bis 7 % |
| Neon-Boden | Gitter fährt endlos auf den Betrachter zu (Perspektive 1/Tiefe, nahtlos) | Bass (Bewegung): schneller | 0,55 → 1,3 Reihen/s |
| Dunst am Horizont, Sonnenlicht auf dem Boden | Licht am Horizont | Pegel (geschützt) | leicht |
| Palmen links und rechts | schlanke Silhouetten nur an den Rändern (je Seite ≤ 27 % der Breite, vom Rand angeschnitten, nie über der Sonnenmitte), leicht gebogener, sich verjüngender Stamm (≤ 44 % der Höhe), viele gefiederte, an den Spitzen hängende Wedel (Krone ≤ 40 % der Breite), fast schwarzes Violett mit feinem Magenta-Saum zur Sonne; wiegen sich sehr langsam (≤ 1,2°, ≤ 0,1 Hz) | – (nur mit Animationen) | – |
| Parallaxe | Szene hebt sich beim Scrollen leicht, nahe Ebenen mehr | Scrollen | höchstens 40 dp |

Ohne Ton fährt das Gitter ruhig weiter, die Sonne scrollt, die Sterne funkeln. Der Klang kommt
über den Puls von Frequency (`FrequencyPulse`, hört jetzt für jedes klangreaktive Design zu,
`YtDesign.soundReactive`): Helligkeit nur aus dem geschützten Kanal, Bewegung aus den schnellen.

**Je nach Handy** (`FrequencyTiers` wie bei Frequency, alle 5 s neu; `SynthwaveTiers`): *hoch*
alles in der Bildrate des Displays (64 Sterne, 15 Reihen, 21 Linien, 7 Bergkonturen, Leuchtlinien,
2 Palmen je Seite: eine nahe, eine kleinere ferne); *mittel* 40 Sterne, 11 Reihen, 15 Linien,
5 Konturen, 1 Palme je Seite, ohne Leuchtlinien, höchstens 60 Bilder/s; *niedrig* /
Energiesparmodus: 28 Sterne, 4 Konturen, 1 Palme je Seite, stiller Himmel, stille Sonne und stille
Palmen, nur der Boden fährt (≈ 30 Bilder/s), kein Klang. Jede Palme liegt in einer eigenen kleinen
Ebene, die nur bei einer Größenänderung neu gezeichnet wird; Parallaxe und Wiegen verschieben bzw.
drehen nur die Ebene. Mit „Animationen der Designs“ aus oder Androids „Animationen entfernen“ steht ein
fertiges, ruhiges Bild (volle Szene, nichts bewegt sich). Im Hintergrund, in einem anderen Design
und solange die Videoseite die Startseite ganz verdeckt, läuft keine Bildschleife. Diagnose zeigt
eine Zeile „Synthwave“ (Halter, Schleife, Stufe, Sterne, Reihen, Palmen).

| Bereich | Synthwave |
| --- | --- |
| Startseite | Szene über den ganzen Bildschirm; die Liste beginnt direkt unter der Kopfzeile (kleiner Abstand) mit „NACHTFAHRT“ auf Glas, Filterreihe aus Glaspillen (gewählte Magenta mit Leuchten, Feed-Regler als Cyan-Scheibe), beim Anheften ein Glasband mit Neonkante; Weiterschauen, große Videokarten aus Glas (die erste leuchtet), „Gerade live“, Shorts, „Mehr für dich“; Abschnittstitel auf kleinen Glasplatten mit Neonröhre |
| Kopfzeile | gestreifte Sonne im Chromring als Logo, Wortmarke „SYNTHWAVE“ in Chrom mit Magenta-Leuchten, runde Glasknöpfe mit Neonring (Suche Cyan, Profil Magenta) |
| Leiste | Nachtglas wie der Miniplayer (eine Fläche beim Andocken), Neonkante oben, aktiver Reiter mit Cyan-Icon und Magenta-Neonröhre, die sanft mit dem Pegel leuchtet |
| Videoseite | Titel über einem Neon-Horizont mit kleiner Sonne, Beschreibung und Kommentare auf Glas, Aktionen als Neonkacheln in einer Reihe (nichts abgeschnitten), Abo-Pille Magenta mit Leuchten (abonniert: Glas mit Cyan-Kante), Neonröhre unter den Reitern, Kapitel als Neon-Straße; Rand unter dem Video: Neon-Horizont |
| Miniplayer „Neonkante“ | Nachtglas mit Neonkante und Leuchten, Magenta-Naht, darüber Sonnenuntergangs-Licht (Pegel) und eine kleine gestreifte Sonne am Kopf; Landung wie ein Auto, das eine Bodenwelle nimmt, danach läuft einmal Licht über die Kante; Haptik wie ein Synthesizer (Kick mit Anschwellen, Arpeggio) |
| Suche | Feld schaltet sich ein wie ein alter Bildschirm (eine Linie öffnet sich), Neonkante mit Leuchten, Filter von YouTube neu (gewählter Magenta), Verlauf als Nachtchips mit Neonrand, Zeilen mit feiner Neonlinie |
| Shorts | Reiter: Szene gedimmt, Shorts in Glaskarten mit Neonrand; Shorts-Ansicht: Magenta-Leiste, eckiger Griff, Seitenwechsel als Synth-Stoß |
| Abos / Du | gedimmte Szene, Kanäle in Neonringen, Karten aus Glas, „Deine Mixtapes.“ in Audiowide, Weiterschauen mit Cyan-Play und Neon-Fortschritt, Sammlungen mit Neonkante |
| Wischen, Halten, Ziehen | Wischen: Neonscheibe mit Speed-Lines auf der Nacht, Haptik als Arpeggio; Anheben mit Cyan-Rand; Halte-Kacheln Nacht mit Neonkante und Leuchten; Lupe als Glaskreis mit Cyan-Ring |
| Gesehen-Abzeichen, Rückgängig-Pille, Themen-Chip, „Worauf hast du Lust?“ | Magenta-Etikett in Großbuchstaben, Nachtpille mit Neonkante und Leuchten, Magenta-Etikett, dunkle Kacheln mit Magenta-Kante und Audiowide-Frage |
| Aktualisieren | eine gestreifte Sonne geht über einem Neon-Horizont auf; beim Laden wandern ihre Schlitze |
| Klassische Bildschirme | Ecken 14 / 24, Pillenknöpfe, Magenta-Rand, Titel in Audiowide, Geist |

Nicht umgesetzt: kein echter Unschärfe-Effekt hinter dem Glas (getöntes Glas statt Blur); die
Zeitleiste des Players bleibt ruhig Magenta; ohne Klang-Engine fährt die Szene ruhig weiter.
