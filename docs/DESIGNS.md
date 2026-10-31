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

## Klangwelle unter dem Bild-im-Bild-Fenster

Unter dem schwebenden Bild-im-Bild-Fenster läuft eine Welle aus vielen feinen Lichtlinien,
blau oben, magenta unten (`ui/yt/YtPipVisualizer.kt`). Sie folgt dem Ton: der Bass schwingt die
langen Wellen, die Mitten die kurzen, die Höhen lassen die Linien zittern; pausiert schwingt sie
ruhig weiter. Der Ton wird im Player abgegriffen (`helpers/YtAudioTap.kt`, 8 Frequenzbänder aus
einer FFT über 1024 Werte) und jedem Band seine Stelle im Datenstrom mitgegeben, so passt die
Welle zu dem, was gerade zu hören ist, obwohl der Player den Ton eine Viertelsekunde vorher
bekommt. Diese Stelle wird aus der Menge des schon weitergereichten Tons und dem Puffer des
Lautsprechers (etwa 250 ms, mal Wiedergabetempo) bestimmt, auch wenn Bild-im-Bild mitten im
Video beginnt oder der Player nach einem Sprung oder Tempowechsel neu ansetzt; der Puffer ist
geschätzt, nicht gemessen, kleine Abweichungen je Gerät sind möglich. Einschalten unter Player, „Klangwelle unter Bild-im-Bild“; die nötige Erlaubnis steht
in [SICHERHEIT.md](SICHERHEIT.md). Grenzen: Android meldet nur die Endlage des Fensters, die
Welle blendet beim Ziehen aus und an der neuen Stelle ein; ist unter dem Fenster kein Platz,
wird sie flacher oder bleibt weg.

Die Erlaubnis „Über anderen Apps einblenden“: Der Schalter geht erst an, wenn sie wirklich
erteilt ist, und erklärt bis dahin den Weg in einem Dialog. Android 15 und neuer sperren sie für
Apps aus einer APK-Datei (YT wird so installiert) als „Eingeschränkte Einstellung“; der Dialog
nennt dann den Umweg: den grauen Schalter einmal antippen, in der App-Info ⋮ → „Eingeschränkte
Einstellungen zulassen“, dann einschalten. Fehlt die Erlaubnis später (entzogen, aus einer
Sicherung), sagt die Welle das beim Schweben einmal und erscheint, sobald sie erteilt ist, ohne
das Fenster zu verlassen. Sie blendet sich beim Zeichnen selbst ein, ohne Animator.

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
