# Sicherheitsprüfung (Runde 9)

Geprüft wurde aus drei Angreifer-Sichten: eine fremde App auf demselben Handy oder ein Link, präparierte Dateien und Daten von außen (Backups, Takeout, YouTube-Metadaten, Server), und Netzwerk/WebViews/Anmeldung. Jeder Fund wurde im Code nachvollzogen, bevor er geschlossen wurde. Ein Penetrationstest auf echtem Gerät steht aus.

## Funde und Stand

| Nr | Bereich | Fund | Schwere | Stand |
|---|---|---|---|---|
| 1 | Backup | Präpariertes Backup setzt Sync-Server um, das Token geht an den Angreifer | hoch | geschlossen: Server, Sync-Typ und interne Schlüssel sind vor Wiederherstellung geschützt |
| 2 | Sync-Login | Knopf „Mit OIDC anmelden“ öffnete die Konto-Löschung | hoch | geschlossen |
| 3 | Sync-Login | Fremder Link `app.tra1c0.yt://login_callback?token=…` schiebt ein fremdes Konto unter (Login-CSRF) oder meldet ab | mittel | geschlossen: nur nach selbst gestarteter Anmeldung (10 Minuten), Token-Format geprüft |
| 4 | Anmeldefenster | Abbruch beim Abschließen ließ alle Google-Cookies unverschlüsselt im Fenster-Speicher | mittel | geschlossen: sofort nach dem Kopieren gelöscht, beim Schließen immer, nach einem Kill beim nächsten Start |
| 5 | Konto | Abmelden stoppte einen laufenden Import nicht, der die Sitzung zurückschreiben konnte | mittel | geschlossen: Abmelden bricht ab, alte Importe dürfen nichts mehr speichern |
| 6 | Netzwerk | Selbst installierte Zertifikate galten auch für Google/YouTube | mittel | geschlossen: dort nur System-Zertifikate, nur HTTPS |
| 7 | Netzwerk | Passwort und Token gingen an HTTP-Server im Internet unverschlüsselt | mittel | geschlossen: nur HTTPS, HTTP nur im eigenen Netz |
| 8 | Backup | Werte falschen Typs ließen die App bei jedem Start abstürzen | mittel | geschlossen: Lesen fällt auf Standardwerte zurück, Typen werden beim Wiederherstellen geprüft |
| 9 | Anmeldefenster | Fremde Seiten konnten unter der Anmeldeleiste erscheinen | niedrig–mittel | geschlossen: nur genau die Google-/YouTube-Seiten der Anmeldung, alles andere öffnet im Browser |
| 10 | Anmeldefenster | Bildschirmfotos, Aufnahmen, Overlays fremder Apps | niedrig | geschlossen: FLAG_SECURE, Overlays ausgeblendet, Tippen durch fremde Fenster verworfen |
| 11 | Testmodus | Jede App konnte den Testmodus einschalten, der Seitentext protokollierte | niedrig | geschlossen: nur im Emulator, protokolliert nur Adresse ohne Parameter, Titel, Felder |
| 12 | Einstiege | Jede App konnte den App-Prozess beenden (leerer Aufruf) oder per „x:y“ abstürzen lassen | niedrig | geschlossen |
| 13 | Einstiege | Einstellungs-Aufruf ohne Daten ließ die App abstürzen | niedrig | geschlossen |
| 14 | Einstiege | Jede App konnte das Design umstellen | niedrig | geschlossen: nur im Emulator |
| 15 | Links | Links in Beschreibungen/Kommentaren mit fremdem Schema stürzten die App ab | niedrig | geschlossen: nur Web-Links, nie Absturz |
| 16 | Import | Takeout-ZIP konnte den Speicher füllen, Abo-Flut | niedrig–mittel | geschlossen: Datei für Datei gestreamt, Grenzen für Entpacktes, Einträge, Abos, Playlists, Verlauf |
| 17 | Import | Kaputte Import-/Backup-Dateien beendeten die App | niedrig | geschlossen: Meldung statt Absturz, Backups auf 256 MB begrenzt |
| 18 | WebView | Absturz des PoToken-Renderers beendete die ganze App | niedrig | geschlossen |
| 19 | Backups | Sync-Token ging in Android-Backups | niedrig | geschlossen |
| 20 | Downloads | Dateinamen aus fremden Daten nicht bereinigt (heute nicht ausnutzbar) | niedrig | geschlossen: bereinigt, Pfad muss im Download-Ordner bleiben |
| 21 | Datenbank | `LIKE` statt `=` beim Wiedergabe-Fortschritt | Info | geschlossen |
| 22 | Downloads | Start-/Stopp-Meldungen für alle Apps sichtbar | Info | geschlossen: nur noch an die App selbst |

## Bewusst offen

- **Passkeys im App-Fenster:** Google erlaubt sie für google.com nur in Browsern. Der Takeout-Weg im Browser deckt das ab.
- **Abmelden bei Google:** Die App löscht die Sitzung lokal und ruft YouTubes Abmeldung auf, folgt aber keinen Weiterleitungen. Ob Google die Sitzung serverseitig sofort beendet, ist nicht geprüft. Sicher beenden: myaccount.google.com, Sicherheit, „Deine Geräte“.
- **OIDC-Rückruf über ein eigenes Schema:** Eine andere App könnte dasselbe Schema anmelden und einen echten Rückruf abfangen. Abhilfe wäre ein verifizierter HTTPS-App-Link, der eine Änderung am Sync-Server braucht.
- **Selbst installierte Zertifikate** gelten weiter für selbst gehostete Piped-/Sync-Server, damit private Server nutzbar bleiben.
- **Fremde Apps** können weiter ein Video zur Warteschlange hinzufügen oder eine Suche starten (Teilen-Funktion von LibreTube).
- **Gerootetes oder verseuchtes Handy:** Dagegen schützt keine App-Verschlüsselung.
