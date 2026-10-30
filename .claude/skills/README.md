# Projekt-Skills

Design- und Animations-Skills für Claude Code, ausgewählt für diese Android-App
(Kotlin, Jetpack Compose). Kopiert aus den Original-Repositories, ohne Hooks und
ohne MCP-Server; Lizenzen in `_licenses/`.

| Skill | Quelle | Stand | Lizenz |
|---|---|---|---|
| emil-design-eng, animate, review-animations, improve-animations, find-animation-opportunities, apple-design, animation-vocabulary | github.com/emilkowalski/skills | a81dbca | MIT |
| impeccable (inkl. Android-Referenz) | github.com/pbakaus/impeccable | 02523ae (4.8.0) | Apache-2.0 |
| ui-ux-pro-max (inkl. Jetpack-Compose-Daten) | github.com/nextlevelbuilder/ui-ux-pro-max-skill | 28b3b8d | MIT |
| m3-expressive-compose | github.com/sanikantv/m3-expressive-skill | 816a53d | MIT |
| jetpack-compose-performance, edge-to-edge | github.com/android/skills | 6daa53e | Apache-2.0 |
| deferring-state-reads, optimizing-lazy-layouts, choosing-derivedstateof, ordering-modifier-chains, migrating-to-modifier-node, avoiding-subcomposition-pitfalls, testing-compose-in-release-mode, auditing-compose-performance | github.com/skydoves/compose-performance-skills | 1b32f81 | Apache-2.0 |

Nicht übernommen, weil nur für Web-Projekte: GSAP-Skills, Animaxxing (GSAP),
Motion AI Kit (Motion für React/JS plus gehostete MCP-Server), Vercels
web-design-guidelines; aus Emil Kowalskis Sammlung die Web-, Expo-, Swift- und
React-spezifischen Skills. Der Detektor-Hook von impeccable prüft nur
Web-Dateien (.tsx, .css, …) und ist deshalb nicht eingerichtet.

Hinweise zur zweiten Auswahl:

- `m3-expressive-compose` ist gegen material3 1.5.0-beta02 geprüft. Die App nutzt noch
  material3 aus der Compose-BOM 2025.08 (1.3.x); dort sind `MaterialShapes`,
  `MotionScheme.expressive()` usw. nicht öffentlich (siehe `references/api-status.md` des
  Skills). Bis zu einem Upgrade gilt der Skill für Gestaltungsregeln, nicht für API-Aufrufe.
- Aus `android/skills` nur Performance und Edge-to-Edge; Navigation 3 passt nicht zur
  Fragment-Navigation der App, die übrigen Bereiche (Engage SDK, Wear, TV …) sind hier unnötig.
- Aus skydoves' Sammlung 8 von 26: ohne HotSwan (Hot Reload) und ohne die Skills, die das
  Stability-Analyzer-Plugin oder ein Macrobenchmark-Modul voraussetzen.
- Nicht übernommen: `aldefy/compose-skill` (Motion-Referenz empfiehlt das interne
  `MotionTokens`, Tests gegen BOM 2024.10) und `Meet-Miyani/compose-skill` (Architektur-Kit,
  für das bestehende Projekt nicht nötig).

