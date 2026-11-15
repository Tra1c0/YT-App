# YT – Roadmap (noch nicht gebaute Funktionen)

Alles, was es noch nicht in der App gibt. Du sortierst; gebaut wird nur, was unter „Beschlossen“
steht oder was du direkt verlangst.
Nummern wie in den Vorschlägen im Chat. Aufwand geschätzt: S klein, M mittel, L groß.
Gebautes steht in [FEATURES.md](FEATURES.md).

## Beschlossen, noch nicht gebaut

- **1 (Rest)** Miniplayer zurückholen im After-Design; Pop/Glas/Frost springen nach dem Halten auf „Abos“ (S)
- **8** Intro je Kanal überspringen, abschaltbar, mit Toleranzreglern (S–M)
- **9** Outro je Kanal früher beenden, abschaltbar, mit Toleranzreglern (S–M)
- **12** Gesten-Randabstand, damit Wischgesten nicht mit der Zurück-Geste kollidieren (S)
- **18** Sammelseite für ausgeblendete Videos und pausierte Kanäle, mit Rückgängig und Ablaufdatum (S)

- **Minimieren nahtlos in die Navi-Leiste:** beim Runterziehen nimmt das Fenster Breite, Ecken und
  Farbe der Leiste an und geht nahtlos in sie über; die Seite darunter blendet weich aus statt als
  Platte mit harter Kante; keine „unsichtbare Wand“ (Abschneide-Linie nur beim Wegwerfen/Zurückholen).
  Gemeinsamer Teil + Formen gebaut (Build 134); offen: die übrigen Designs, als Nächstes
  Frequency und Synthwave (L, 2–3 Runden)

- **Musikmodus für die übrigen Designs:** die Oberfläche jedes Designs reagiert auf die Musik, mit
  eigener „Tanz-Sprache“ je Design. Klang-Motor ist fertig (mit Frequency gebaut); offen: noch nicht
  freigegeben (L)

## Eventuell / später (noch nicht entschieden)

- **Startbildschirm beim ersten Öffnen** (optional, überspringbar): was anders ist als bei YouTube,
  mit Designs und Animationen nach deinen Entwürfen; nutzt die Inhalte der Anleitung (M–L)
- **Browser-Erweiterung** für Firefox/Chrome mit den übertragbaren Funktionen (Titel, Gesehenes,
  Tempo/Intro/Outro je Kanal, Lautstärke, Kapitel-Kürzel …) (M, dauerhafte Pflege)
- **Akku-Limit, schlaue fps-Regel:** bei 720p die 30-fps-Grenze weglassen, wenn 720p nur mit 60 fps
  existiert (S, auf dem Handy prüfen)

## Noch nicht sortiert

### Tastatur-Anbindung (eigene KI-Tastatur)
- **Befehle von der Tastatur:** abspielen, ans Ende der Warteschlange, suchen, Pause/Weiter, News
  öffnen; abgesichert, sodass nur Apps mit deiner Signatur sie nutzen dürfen (S–M)
- **Kurzfassung für eingefügte YouTube-Links:** Kernaussagen + Link mit Sekunde in den Chat (M)
- **Markierter Text → „In YT suchen“ / „In YT-Warteschlange“** im Markier-Menü (S)
- **Aktuelles Video teilen:** Titel + Link mit aktueller Sekunde einfügen (S)
- **Feed-Thema per Diktat anlegen** (S)

### Wiedergabe
- **2** Kurzer Rücksprung beim Fortsetzen nach langer Pause (S)
- **4** Einschlaf-Timer „Am Ende dieses Videos“ und sanftes Ausblenden (S)
- **5** Untertitel-Sprache je Kanal merken (S)
- **6** Qualität je Kanal merken (S)
- **7** Pause bei Kopfhörer-/Bluetooth-Trennung, optional Fortsetzen beim Verbinden (S)
- **11** Ende der Warteschlange: anhalten statt Autoplay (S)
- **49** Weiterschauen über Geräte: Position aus dem YouTube-Verlauf übernehmen (M)
- **52** Lautstärke-Korrektur je Kanal in dB (S)
- **53** Stille/Schwarzbild am Ende überspringen (S)
- **54** „Nur Ton“ automatisch für Podcasts mit Standbild (M)
- **55** Untertitel-Stil: Größe, Hintergrund, Position; über dem Miniplayer ausblenden (S)
- **56** Schlaf-Erkennung: nachts nach 1 Std. ohne Berührung „Noch da?“ (S)

### Warteschlange und Playlists
- **57** Warteschlange als Playlist speichern, Duplikate in Playlists entfernen (S)
- **58** Warteschlange teilen (Link-Liste oder YouTube-Playlist-Link) (S)
- **59** „Später ansehen“ automatisch aufräumen (S)
- **60** Playlist-Fortschritt „12/40 gesehen“ mit Weiter-Knopf (S)

### Feed und Entdecken
- **16** Höchstens N Videos eines Kanals hintereinander (S)
- **17** Reuploads und Duplikate erkennen (S)
- **19** DeArrow auf den Design-Startseiten prüfen und anschließen (S–M)
- **20** „Später ansehen“ per Wisch (S)
- **21** „Neu seit letztem Öffnen“-Trennlinie in den Abos (S)
- **61** Kanäle gruppieren (Musik, Lernen, Gaming …) als Filter-Chips (M)
- **62** Upload-Benachrichtigung nur für ausgewählte Kanäle, mit Ruhezeiten (S–M)
- **63** „Nicht interessiert“ lernt, sichtbar im Feed-Regler (M)
- **64** Premieren und Livestreams ankündigen, mit Erinnerung (S)
- **65** Schlagwörter stummschalten mit Ablauf (z. B. Spoiler 7 Tage) (S)

### Player-Seite
- **23** Kapitel aus Kommentaren, wenn das Video keine hat (S)
- **24** Zeitstempel in Kommentaren/Beschreibung antippbar (S, Upstream prüfen)
- **27** Lesezeichen mit Notiz im Video, Liste unter „Du“ (M)
- **28** SponsorBlock-Hinweis „übersprungen: Sponsor, 0:42“ mit Rückgängig (S)

### Suche
- **29** Suchverlauf per Wisch löschen und pausieren (S)
- **30** Schnellfilter-Chips Shorts / Live (S)

### Downloads
- **32** Nur WLAN / nur beim Laden herunterladen (S)
- **33** Gesehene Downloads nach N Tagen löschen (S)
- **34** Platzanzeige für Downloads mit Sortierung nach Größe (S)
- **35** Offline-Paket aus der Warteschlange (M)

### Bedienung und Komfort
- **36** Zurück-Taste in Ebenen (Sheet → Player minimieren → Startseite → „Nochmal drücken“) (S)
- **37** Nur-Audio-Steuerung ±10 s in der Benachrichtigung (S)
- **38** Verständliche Fehlermeldungen statt technischer Texte (S)
- **39** Offline-Banner statt endlosem Lade-Kreis, Downloads anbieten (S)
- **40** Scrollposition der Startseite nach dem Player behalten (S–M)
- **41** App-Shortcuts auf dem Symbol (Abos, Weiterschauen, Suche, Downloads) (S)
- **42** Geteilte Links „In Warteschlange“ statt sofort abspielen (S)
- **66** Einhandmodus (M)
- **67** Wischen zwischen den Reitern der Startseite (S–M)
- **68** Teilen ohne Tracking-Parameter, Schnellaktion „mit Zeitstempel“ (S)
- **69** YouTube-Link in der Zwischenablage → Chip „Öffnen?“ (S)
- **70** Barrierefreiheit: Treffflächen, TalkBack, große Schrift je Design prüfen (M)

### Wohlbefinden
- **43** Sanfte Pausen-Erinnerung nach X Minuten (S)
- **44** Nachtmodus-Zeitfenster: Autoplay aus, Shorts aus, gedimmt (S–M)
- **45** Wochenrückblick, nur lokal (M)
- **46** Shorts global ausblenden (S)

### Daten und Stabilität
- **71** Automatisches lokales Backup, wöchentlich, letzte 3 behalten (S)
- **73** Import aus NewPipe/FreeTube/Takeout (S–M, Upstream prüfen)
- **74** Selbsttest nach einem Update (Player, Tonkette) mit Eintrag in der Diagnose (M)
- **75** Netzwechsel WLAN ↔ Mobil ohne Abbruch (S–M)
