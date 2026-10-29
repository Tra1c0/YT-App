# Maße aus den Referenzen

Stand 29.10.2026. Grundlage sind die vier Referenzbilder („YouTube, neu gedacht“, Movo, After, Side).
Jeder Bildschirm wurde mit einem dp-Raster vermessen, Bezugsbreite 393 dp (ein typisches Smartphone).
Schriftgrößen sind gegen die echten Schriftdateien geeicht: Textbreite im Bild ergibt die Größe in sp.

## Wie YT die Maße auf jedes Handy überträgt

- Die Design-Bildschirme (Compose) rechnen in dp der Bezugsbreite. `YtTheme` skaliert dp und sp mit
  Bildschirmbreite / 393, begrenzt auf 0,8 bis 1,3. Ein 360-dp-Handy zeigt alles 8 % kleiner,
  ein 412-dp-Handy 5 % größer, die Proportionen bleiben wie in der Referenz.
- Die Systemschriftgröße wirkt erst ab „groß“ (über 115 %), damit Bedienungshilfen weiter greifen.
- Die Referenzen setzen kleine Texte sehr klein (z. B. 9 sp bei After). YT übernimmt die Maße, setzt
  aber eine Untergrenze von 11 sp.
- Die Referenz-Handys sind gedrungener als echte Geräte. Listen haben daher unten mehr Platz;
  Abstände und Größen bleiben gleich.

## Gemeinsam

| Element | Wert |
| --- | --- |
| Schwebende Leiste | 12 dp Rand seitlich, 6 dp oben, 10 dp unten; Höhe 64 (YouTube neu), 68 (Movo), 54 (After), 66 (Side) dp; Ecken 24/26/22/26 dp |
| Kopfzeile | Logo-Marke, Wortmarke (Roboto Condensed 19 sp / Geist 800 30 sp / Fraunces 800 25 sp / Figtree 600 28 sp), Suche, Profilkreis |

## YouTube, neu gedacht

| Element | Wert |
| --- | --- |
| Überschrift | Geist 800, 40 sp, Zeilenhöhe 38 sp, x 15 dp |
| Filter | 34 dp hoch, Ecken 12 dp, 12 dp Abstand, Geist 500 13,5 sp |
| Großes Video | 4 dp Rand, 2:1, Ecken 14 dp; Avatar 40 dp, Titel Geist 600 14 sp, Metadaten 12,5 sp |
| Gerade live | Titel Geist 700 19 sp; Karten 204 × 121 dp, Ecken 10 dp, 13 dp Abstand; LIVE 20 dp hoch |
| Videoseite | Titel Geist 700 18,5 sp; Avatar 42 dp; Abonnieren 32 dp hoch, 13 sp; Aktionen 36 dp hoch; Register in drei Spalten, rote Linie 3 dp; Kapitel 111 × 62 dp, aktives Kapitel rot umrandet (1,5 dp, Ecken 10 dp) |
| Dein YouTube | Überschrift Geist 800 41 sp, Unterzeile 20 sp; Kacheln 50 dp, Ecken 12 dp; Weiterschauen 3:1, Fortschritt 6 dp; Sammlungen 146 dp hoch, Ecken 14 dp, Name Geist 900 28 sp, Collage 3 × 54 dp |

## Movo

| Element | Wert |
| --- | --- |
| Überschrift | Geist 900, 49 sp, Zeilenhöhe 45 sp, Laufweite −1,5 sp |
| Register | 32 dp hoch, Geist 600 14 sp, 9 dp Abstand |
| Mosaik | große Karte 202 × 325 dp, kleine 163 × 160/157 dp, breite 143 dp hoch, Abstand 8 dp, Ecken 16 dp |
| Kartentext | Titel Geist 900 25/21/23 sp, Creator 14 sp, Dauer-Chip 13,5 sp, Play-Kreis 36/32 dp |
| Videoseite | Titel Geist 900 36 sp; Avatar 38 dp; Folgen 30 dp hoch; Merken/Teilen 40 dp; „Im Video“ Ecken 20 dp, Titel 25 sp, Zeilen 44 dp mit 75-dp-Vorschau; Diskussion 18,5 sp mit erstem Kommentar |

## After

| Element | Wert |
| --- | --- |
| Plakat | 78 % der Höhe, reicht hinter die Kopfzeile; Register Geist 13 sp, 25 dp Abstand, Linie 47 × 2,5 dp |
| Plakattitel | Anton ab 63 sp, Zeilenhöhe 0,98, linke 66 % der Breite, wird kleiner bis vier Zeilen passen |
| Knopf | 166 × 35 dp, Ecken 9 dp, Elfenbein, Geist 600 13,5 sp |
| Für später | Überschrift 16 sp; Poster 177 × 105 dp, Ecken 10 dp, 6 dp Abstand |
| Live-Räume | Überschrift Geist 900 51 sp, Zeilenhöhe 40 sp; Creator-Kacheln 85 × 71 dp, Ecken 8 dp; Raumkarte 338 dp hoch, Ecken 16 dp; „ROOM 0X“ Geist Mono 13 sp, Laufweite 4,5 sp; Raumtitel Archivo Narrow 700 34 sp; Knopf 39 dp hoch, Ecken 11 dp |

## Side

| Element | Wert |
| --- | --- |
| Überschrift | Figtree 600 32 sp, Zeilenhöhe 33 sp, x 22 dp |
| Register | Pille 30 dp hoch, Figtree 14 sp |
| Beitrag | Avatar 42 dp, Name 14 sp, Kontext 12,5 sp; Bild 365 × 184 dp, Ecken 12 dp, Play-Kreis 50 dp; Titel Figtree 500 19 sp; Aktionszeile 11–21 dp |
| Beitrag-Knopf | 50 dp, Apricot |
| Gesprächsfläche | Ecken oben 24 dp, Griff 41 × 4 dp; Kommentare: Avatar 43 dp, Name 14 sp, Text 13 sp; Eingabe 42 dp hoch, Senden 38 dp |

## Runde 3: Clay, Editorial, Glas, Pop

Die vier Referenzen sind Vollbild-Screens ohne Gerät, 880 px breit (2,24 px je dp).

| Design | Element | Wert |
| --- | --- | --- |
| Clay | Kopfzeile | Logo 48 × 33 dp, Wortmarke 26 sp, Knöpfe 48 dp (Creme, Schatten) |
| Clay | Karussell | Karte 275 × 316 dp, Ecken 28 dp, Nachbarn 88 % groß, 3° geneigt; Pfeile 44 dp; Play 72 dp; Titel Nunito 900 20,5 sp |
| Clay | Als Nächstes | 360 × 111 dp, Ecken 22 dp, Vorschau 152 × 89 dp, Titel 13 sp |
| Clay | Navigation | 2 × 2 Blobs, 357 × 215 dp, untere Reihe liegt 27 dp auf der oberen, Icons 38 dp, Nunito 900 15,5 sp |
| Editorial | Reiter | Inter 16 sp (aktiv fett), blaue Linie 3 dp auf schwarzer 1-dp-Linie; Filter 12,5 sp |
| Editorial | Liste | Nummer Inter 900 29 sp, Bild 317 × 103 dp schwarz-weiß ohne Ecken, Titel 14 sp, Linie 1 dp schwarz |
| Editorial | Shorts | Karten 174 × 83 dp; Laufzeit IBM Plex Mono 11 sp auf Schwarz, LIVE auf Blau |
| Editorial | Navigation | angedockt, 44 dp, Inter 14 sp, blauer Balken 3 dp über dem aktiven Reiter |
| Glas | Suche und Reiter | Suchfeld 38 dp, Segmente 31 dp (124/101/117 dp breit), Google Sans 13–14 sp |
| Glas | Video | 355 × 175 dp, Ecken 12 dp, Glas-Play 56 dp, Avatar 42 dp, Titel 15,5 sp |
| Glas | Shorts | Karten 173 × 132 dp, Aufruf-Chip mit Auge |
| Glas | Navigation | schwebend 59 dp, Ecken 30 dp; aktiver Reiter wächst von 49 dp Kreis auf 107 dp Pille |
| Pop | Reiter | Blobs 99/78/74 × 50 dp, Nunito 900 14,5 sp |
| Pop | Feature-Karte | Gelb, Ecken 22 dp, oben rechts 60 dp; Bild 165 dp hoch; Titel Nunito 900 23 sp; Stern im Cookie 46 dp |
| Pop | Listenkarte | 99 dp hoch, Ecken 22 dp, Vorschau 180 × 84 dp |
| Pop | Shorts | Karten 148 × 158 dp, rosa Aufruf-Pille, LIVE-Karte |
| Pop | Navigation | Wellenband 58 dp, Pflaumen-Blob 100 × 68 dp gleitet zum aktiven Reiter |

Formen und Frost sind aus den Inspirationsbildern abgeleitet, nicht vermessen: Formen übernimmt
Formen und Farben aus Bild 5 und die Wellen-Zeitleiste aus Bild 6, Frost das Salbei-Glas und die
dunkle Pillen-Leiste aus Bild 7.

## Nicht übernommen

- Status- und Geräteleisten der Mockups (Uhrzeit, Notch) gehören zum System.
- Eigene Player-Steuerung je Design (Play-Kreis, Zeitleiste in der Videofläche): der Player bleibt
  der klassische, nur die Farbe der Zeitleiste folgt dem Design.
- Shorts-Reiter in „YouTube neu“: YT hat keinen Shorts-Feed. In den Designs der dritten Runde zeigt
  der Shorts-Reiter die Shorts aus den Abos.
- Cast-Knopf (Clay, Editorial, Glas, Pop): YT kann nicht auf Fernseher streamen, der Knopf entfällt.
- „Für dich“: YT hat ohne Konto keine Empfehlungen; „Für dich“ mischt Abos und Trends.
