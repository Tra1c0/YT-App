# Backdrop (Liquid Glass)

Quelle: https://github.com/Kyant0/AndroidLiquidGlass, Bibliothek `backdrop` 2.0.1
(Commit 65ab177), Apache License 2.0 (siehe `LICENSE`).

- `app/src/main/java/com/kyant/backdrop/`: die Bibliothek, als Quellcode übernommen, weil die
  veröffentlichte Version Compose 1.12, Kotlin 2.4 und compileSdk 37 voraussetzt. Änderungen:
  Multiplatform-Teile (`expect`/`actual`) zu Android zusammengeführt, ohne die Abhängigkeiten
  `io.github.kyant0:shapes` (Linse nur für `CornerBasedShape`) und `org.jetbrains:annotations`,
  Kontext-Parameter in `LayerRecorder` durch einen normalen Parameter ersetzt.
- `app/src/main/java/com/github/libretube/ui/yt/liquid/`: Gesten, `DampedDragAnimation` und
  `InteractiveHighlight` aus der Katalog-App derselben Quelle, angepasst (Schalter für die
  Design-Animationen, `SystemClock` statt `kotlin.time.Clock`).
