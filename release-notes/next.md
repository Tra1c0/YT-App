## Behoben
- Eigenes Design: Hinter der Startseite schien die klassische Seite durch (die Karten wirkten überlappt); sie hat jetzt einen deckenden Hintergrund.
- Synthwave: Abschnitts-Überschriften werden nicht mehr abgeschnitten („GERADE LIVE“ statt „GERADE…“).
- SSI: Der Hintergrundlauf holt fehlende Vektoren zuerst nach und läuft beim Laden in Folge weiter, bis der Rückstand weg ist; „Sofort“ wartet nicht mehr auf den Archiv-Abgleich.
- Backup: SSI-Modell, SSI-Index und Absturzberichte sind ausgenommen, damit das Android-Backup unter 25 MB bleibt.
- Mehrere Stellen, an denen die Oberfläche ihren Zustand verlor oder sich nicht aktualisierte; Frequency-Effekte sind auf älteren Android-Versionen abgesichert.
- Archiv: unvollständige Text-Sicherungen werden nachgeholt.

## Neu
- SSI: „Zeitraum im Index“ (14/30/60/90 Tage oder unbegrenzt, Standard 30) und Obergrenze von 250.000 Abschnitten; bewertete, gespeicherte, gesehene und verschwundene Videos bleiben.
- Speicher-Seite zeigt Archiv, SSI-Modell, SSI-Index und eigene Designs; Modell und Index lassen sich dort leeren.
- Archiv-Listen: „Mehr laden“ zeigt alle Einträge.
- Diagnose: neue Zeilen „Start“ und „Abruf-Gate“; SSI zeigt Rückstand, Rechentempo, Zeitraum und Speicher.

## Geändert
- Ein fehlerhafter Baustein kann den App-Start nicht mehr zum Absturz bringen; SSI- und Ambilight-Ebene entstehen erst bei Bedarf.
- Archiv, SSI und Abo-Scan rufen im Hintergrund nacheinander ab (gemeinsame Pause nach einer Bot-Prüfung).
- Die SSI-Datenbank wird auf Version 3 gehoben; ein älterer Build kann sie danach nicht mehr öffnen.
