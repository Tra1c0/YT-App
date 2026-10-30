# YouTube-Konto (Runde 9)

## Was es gibt

| Weg | Wo | Was passiert |
|---|---|---|
| Profilknopf | Kopfzeile jedes Designs | Öffnet das Konto-Blatt: Anmelden, Daten übernehmen, Takeout, Abmelden, darunter Einstellungen, Hilfe, Spenden, Über |
| Kontokarte | Bereich „Du“ | Ein Tipp: Anmelden oder Konto-Blatt |
| Erster Start | einmal nach der Installation | Konto-Blatt mit „Willkommen bei YT“ und „Nicht jetzt“ |
| Einstellungen | Konto → YouTube | YouTube-Konto, Daten übernehmen, Takeout, Abmelden |

## Anmeldefenster

- Zeigt Googles eigene Seite (accounts.google.com). Die Adresse steht immer oben mit Schloss.
- Androids Autofill arbeitet auf der Seite: Der Passwortmanager füllt aus, bietet das Speichern an und fragt, wenn er so eingestellt ist, nach dem Fingerabdruck.
- Bestätigung auf dem Handy, Sicherheitsschlüssel und Bestätigungscodes laufen, wie Google sie anbietet.
- Passkeys gehen in einem App-Fenster für google.com nicht. Dafür gibt es den Takeout-Weg im Browser.
- Google lehnt Anmeldungen in eingebetteten App-Fenstern offiziell ab. Das Fenster tritt deshalb mit der Kennung von Chrome auf und versucht bei einer Ablehnung einmal die Desktop-Seite. Klappt auch das nicht, erklärt es den Takeout-Weg. Ob Google das Fenster annimmt, kann sich jederzeit ändern.

## Import auf Anfrage

Nach der Anmeldung fragt die App, was übernommen werden soll: Abos, Playlists, Später ansehen, „Mag ich“-Videos, Wiedergabeverlauf (bis 1000 Einträge, mit Wiedergabeposition). Der Import läuft im Hintergrund weiter, auch wenn das Fenster zu ist. Ein zweiter Import füllt gleichnamige Playlists auf statt Kopien anzulegen. Gespeichert wird in der App (oder auf dem gewählten Sync-Server).

Takeout: Im Browser `takeout.google.com` mit YouTube vorausgewählt öffnen, als ZIP exportieren, die ZIP in der App wählen. Erkannt werden Abo-CSV, Playlist-CSVs und der Verlauf als JSON oder HTML. Playlist-Videos aus Takeout holen ihre Titel einzeln nach, das dauert länger.

Direkt aus der YouTube-App lesen geht nicht: Android trennt die Daten jeder App von allen anderen.

## Sicherheit

- Die Sitzung (die youtube.com-Cookies) liegt nur verschlüsselt in `yt_account.xml`, mit einem AES-Schlüssel aus dem Android Keystore, der das Gerät nicht verlassen kann.
- Der Cookie-Speicher des Anmeldefensters wird nach jeder Anmeldung und jedem Abbruch gelöscht.
- Sitzung und Fenster-Speicher sind von Backups und Geräteumzug ausgeschlossen.
- Die Sitzung geht nur an youtube.com. Weiterleitungen folgt der Client nicht.
- Abmelden löscht die Sitzung hier und beendet sie bei YouTube.

## Sync-Konto (Piped / LibreTube-Sync)

Der Login-Dialog hat Autofill-Hinweise für Benutzername und Passwort, einen Knopf „Starkes Passwort vorschlagen“ und bietet nach erfolgreichem Login das Speichern im Passwortmanager an. Passkeys unterstützen diese Server nicht.
