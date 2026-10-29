# Schriften

| Schrift | Einsatz in YT | Lizenz |
| --- | --- | --- |
| Geist (400, 500, 600, 700) | Fließtext, Titel, Überschriften | SIL OFL 1.1, `Geist-OFL.txt` |
| Geist Mono (400, 500) | Zahlen: Laufzeiten, Zeitstempel | SIL OFL 1.1, `Geist-OFL.txt` |
| Doto (700, runde Punkte) | Punktmatrix-Akzent: App-Name und Seitentitel oben | SIL OFL 1.1, `Doto-OFL.txt` |

Quelle: https://github.com/google/fonts (`ofl/geist`, `ofl/geistmono`, `ofl/doto`).
Die statischen Schnitte in `app/src/main/res/font/` sind aus den variablen Schriften erzeugt
(fontTools `varLib.instancer`).

Zusätzlich für die Designs (Design-Schalter unter Darstellung):

| Schrift | Einsatz | Lizenz |
| --- | --- | --- |
| Geist 800/900 | Plakative Überschriften in „YouTube neu“ und „Movo“ | SIL OFL 1.1, `Geist-OFL.txt` |
| Anton | Schmale Plakat-Titel in „After“ | SIL OFL 1.1, `Anton-OFL.txt` |
| Figtree (400–700) | Weiche, ruhige Schrift in „Side“ | SIL OFL 1.1, `Figtree-OFL.txt` |
| Fraunces ExtraBold (800) | Wortmarke „after“ | SIL OFL 1.1, `Fraunces-OFL.txt` |
| Archivo Narrow Bold (700) | Raumtitel in „After“ (Live-Räume) | SIL OFL 1.1, `ArchivoNarrow-OFL.txt` |
| Roboto Condensed Bold (700) | Wortmarke in „YouTube neu“ | Apache 2.0, `RobotoCondensed-Apache-2.0.txt` |

Fraunces, Archivo Narrow und Roboto Condensed sind statische Schnitte von Google Fonts (fonts.gstatic.com).

Dritte Runde (Clay, Editorial, Glas, Pop, Formen, Frost):

| Schrift | Einsatz | Lizenz |
| --- | --- | --- |
| Nunito (400–900) | „Clay“ und „Pop“ | SIL OFL 1.1, `Nunito-OFL.txt` |
| Inter (400–900) | „Editorial“ | SIL OFL 1.1, `Inter-OFL.txt` |
| IBM Plex Mono Medium | Laufzeiten und LIVE in „Editorial“ | SIL OFL 1.1, `IBMPlexMono-OFL.txt` |
| Google Sans (400, 500, 700) | „Glas“ und „Formen“ | SIL OFL 1.1, `GoogleSans-OFL.txt` |
| Plus Jakarta Sans (400–800) | „Frost“ | SIL OFL 1.1, `PlusJakartaSans-OFL.txt` |

Google Sans und Inter sind auf lateinische Zeichen reduziert (fontTools subset), um die APK klein zu halten.
