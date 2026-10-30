# Installation und Play Protect

## Fester Signaturschlüssel (einmalig)

Ohne festen Schlüssel signiert jeder Build mit einem neuen Wegwerf-Schlüssel. Android lässt eine App nur mit demselben Schlüssel aktualisieren, deshalb musste jede neue Version die alte ersetzen (deinstallieren).

1. Workflow „Create signing key“ einmal laufen lassen (Actions, „Run workflow“). Er erzeugt den Schlüssel bei GitHub.
2. In der Zusammenfassung des Laufs den Wert kopieren und als Repository-Secret `YT_SIGNING` speichern (Settings, Secrets and variables, Actions, New repository secret; gibt es das Secret schon, dort „Update secret“). Der Wert ist der Schlüsselspeicher selbst; sein Passwort ist fest, weil es ohnehin im selben Secret läge.
3. Den Lauf löschen, damit der Wert nirgends sichtbar bleibt.
4. Ab dem nächsten Build steht in den Release-Notizen „personal key (SHA-256 …)“. Diese Version ein letztes Mal nach dem Deinstallieren installieren, danach installiert jede neue Version über die alte.

Vor diesem letzten Deinstallieren ein Backup anlegen (Einstellungen, Backup & Wiederherstellen), danach wiederherstellen.

Der Schlüssel gilt rund 30 Jahre. Geht das Secret verloren, ist ein neuer Schlüssel nötig und einmal Deinstallieren fällig.

Lässt sich der Schlüssel im Secret nicht öffnen, bricht der Build nicht ab: Er signiert mit einem Wegwerf-Schlüssel, nennt den Grund in den Release-Notizen und erscheint nur als Vorab-Release, nie als neuester Build. „Create signing key“ erkennt einen kaputten Schlüssel und erzeugt dann einen neuen; einen funktionierenden lässt er stehen.

## Play-Protect-Warnungen

Play Protect warnt bei Apps, die nicht aus dem Play Store kommen, deren Entwickler Google nicht kennt oder deren Signaturschlüssel unbekannt ist. Bei einer selbst gebauten App mit wenigen Installationen ist das normal; ein Fund von Schadcode ist damit nicht gemeint.

- **Installieren:** In der Warnung „Weitere Details“, dann „Trotzdem installieren“. Wer will, lässt die App von Google prüfen („App scannen“).
- **Fester Schlüssel:** Ab dem festen Schlüssel ist jede Version für Play Protect dieselbe App vom selben Absender statt einer neuen unbekannten.
- **Entwickler-Registrierung (kostenlos):** Google verlangt künftig, dass Apps außerhalb des Play Stores einem verifizierten Entwickler gehören. Für Schüler und Hobby-Entwickler gibt es ein kostenloses Konto ohne Ausweis für bis zu 20 Geräte. Registriert werden Paketname `app.tra1c0.yt` und der SHA-256-Fingerabdruck des Schlüssels. Laut Google gilt die Pflicht zuerst in Brasilien, Indonesien, Singapur und Thailand (ab 30.09.2026), weltweit ab 2027.
- **Fehlalarm melden:** Google hat ein Einspruchsformular für Play-Protect-Warnungen.
- **Ausschalten:** Play Protect lässt sich in den Play-Store-Einstellungen abschalten. Davon rate ich ab, weil der Schutz dann für alle Apps fehlt.
