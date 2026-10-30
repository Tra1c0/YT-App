---
name: m3-expressive-compose
description: >
  Build Material 3 Expressive UI in Jetpack Compose with code that compiles against
  androidx.compose.material3 1.5.0-beta02. Knows which Expressive APIs are stable, which still
  need @OptIn(ExperimentalMaterial3ExpressiveApi::class), and which old APIs are deprecated.
  Use for MaterialExpressiveTheme, MotionScheme springs, MaterialShapes and shape morphing,
  emphasized typography, ButtonGroup, ToggleButton, SplitButton, FAB menu, floating toolbars,
  flexible app bars, ShortNavigationBar, WideNavigationRail, segmented lists, wavy progress and
  LoadingIndicator, or when asked to make a Compose screen "expressive" or review it against M3.
---

# Material 3 Expressive for Jetpack Compose

M3 Expressive is an update to Material 3, not a new version. In Compose it ships in
`androidx.compose.material3` **1.5.0**. This skill is checked against **1.5.0-beta02**. Every
Kotlin block in `references/` is copied from a module that compiles against that version
(`verify/` in the skill's repo).

## Rules that matter most

1. **Check the version first.** Read `material3` in `libs.versions.toml` or `build.gradle(.kts)`.
   - `1.5.0-beta02` or newer: use the code in this skill as is.
   - `1.4.x` stable: only `ShortNavigationBar` and `WideNavigationRail` are public. The rest of
     Expressive (theme, motion scheme, emphasized type, new shapes) is `internal` or missing and
     will not compile. Ask before bumping to 1.5.0.
   - A 1.5.0 alpha: API names and opt-ins moved between alphas. Suggest beta02.
   - A Compose BOM alone: check which material3 it resolves to (`./gradlew :app:dependencies`).
     Pin `material3` explicitly to get 1.5.0.
2. **Don't add opt-ins you don't need.** In beta02 almost all Expressive APIs are stable. Only
   `LoadingIndicator`, `ContainedLoadingIndicator`, `LoadingIndicatorDefaults`, `MaterialShapes`
   and its `toShape()` / `toPath()` helpers, and one `DropdownMenuItem` overload still need
   `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`. Full table: `references/api-status.md`.
3. **Use `MaterialExpressiveTheme`, not `MaterialTheme`, at the root.** Its defaults are
   `expressiveLightColorScheme()` and `MotionScheme.expressive()`. Pass your own scheme for dark
   mode and dynamic color. See `references/theme.md`.
4. **Animate with the theme's springs.** Use `MaterialTheme.motionScheme.*Spec()` instead of
   `tween(300)`. *Spatial* specs (size, position, corners, morph progress) may bounce. *Effects*
   specs (color, alpha) never overshoot. See `references/motion.md`.
5. **Prefer the Expressive component over the old one.** See the mapping table below.
6. **Don't use deprecated forms.** `ListItem(headlineContent = …)`, the old `SearchBar`/
   `DockedSearchBar`, `TopSearchBar` and the non-state `Slider` overloads are deprecated in beta02.
   See `references/api-status.md` § Deprecated.
7. **Stay inside the theme.** Use colors from `MaterialTheme.colorScheme`, text styles from
   `MaterialTheme.typography`, shapes from `MaterialTheme.shapes` or `MaterialShapes`. No raw hex
   colors or hand-picked `sp` sizes in components.

## Old component → Expressive component

| Instead of | Use (1.5.0) | Notes |
|---|---|---|
| `Button(shape = …)` | `Button(shapes = ButtonDefaults.shapes())` | Corners morph on press. Sizes XS 32, S 40, M 56, L 96, XL 136dp |
| `SingleChoiceSegmentedButtonRow` | Connected `ToggleButton`s with `ButtonGroupDefaults.connected*ButtonShapes()` | Segmented buttons still work; this is the Expressive look |
| Row of buttons | `ButtonGroup { clickableItem(…) }` | Neighbours react to press; overflow menu built in |
| Button + dropdown arrow | `SplitButtonLayout` + `SplitButtonDefaults.LeadingButton/TrailingButton` | |
| Speed-dial FAB | `FloatingActionButtonMenu` + `ToggleFloatingActionButton` | 2 to 6 items |
| `SmallFloatingActionButton` | `FloatingActionButton` 56dp, `MediumFloatingActionButton` 80dp, `LargeFloatingActionButton` 96dp | |
| `BottomAppBar` | `HorizontalFloatingToolbar` (+ FAB) or `FlexibleBottomAppBar` | Floating toolbar hides on scroll |
| `MediumTopAppBar` / `LargeTopAppBar` | `MediumFlexibleTopAppBar` / `LargeFlexibleTopAppBar` | Adds `subtitle` and `titleHorizontalAlignment` |
| `NavigationBar` | `ShortNavigationBar` | 64dp |
| `NavigationRail`, `ModalNavigationDrawer` | `WideNavigationRail` | Collapses and expands in place |
| Grouped `ListItem`s in a `Card` | `SegmentedListItem` + `ListItemDefaults.segmentedShapes(index, count)` | Settings screens |
| `CircularProgressIndicator()` (indeterminate) | `LoadingIndicator()` (opt-in) or `CircularWavyProgressIndicator()` | |
| `LinearProgressIndicator(progress)` | `LinearWavyProgressIndicator(progress)` | Long, visible progress |
| `SearchBar(query, onQueryChange, …)` | `SearchBar(state, inputField)` + `ExpandedFullScreenSearchBar` / `AppBarWithSearch` | Old overload deprecated |

## Workflow

**Building a screen or component**
1. Check the material3 version (rule 1). If it is below 1.5.0, say so before writing code.
2. Make sure the app root uses `MaterialExpressiveTheme` (`references/theme.md`).
3. Open `references/components.md`, find the component, and start from its snippet.
4. Use theme springs for any custom animation (`references/motion.md`).
5. Add one or two expressive moments per screen (a shape morph, an emphasized headline, a
   bouncy selection). Don't make everything expressive, or nothing stands out.
6. Check light, dark and dynamic color, and font scale 200%.

**Reviewing or migrating a screen**: follow `references/audit.md`.

## Design guidance, in short

Full version, with Google's seven tactics, hero moments and patterns from Google's own examples:
`references/expressive-design.md`. Read it before any "make this expressive" request.

- **One or two hero moments per product**, not per screen.
- **Selection changes shape as well as color:** unselected = tonal rounded square, selected =
  primary pill. **Morph, don't swap:** FAB ↔ close button, toolbar ↔ its primary action.
- **Expressive means guiding attention.** Make the one primary action bigger, rounder or more
  colorful than the rest. If every element is emphasized, none is.
- **Shape:** mix round and square to create contrast. Abstract `MaterialShapes` (cookie, clover,
  burst…) go on images, avatars, icons and decoration, **never** on containers that hold body
  text. Nested corners: inner radius = outer radius − padding.
- **Color:** `primary` for the most important element, `secondary`/`tertiary` containers for
  supporting accents, `surfaceContainer*` tones for hierarchy. Text on a container uses the matching
  `on*` role.
- **Type:** emphasized styles (`titleLargeEmphasized` etc.) are the same size as the baseline styles,
  just heavier. Use them for selected states, key numbers and headlines, alongside the baseline
  styles, not instead of them.
- **Motion:** expressive springs for hero moments and key interactions. Use `MotionScheme.standard()`
  only for utilitarian apps.
- **Accessibility:** keep 48dp touch targets, add `contentDescription` to icon-only buttons and a
  tooltip to icon-only FABs, set `stateDescription` on toggles, split buttons and FAB menus, and
  don't rely on a bounce or color change alone to show state.

## Where to look

| Need | File |
|---|---|
| Making a screen expressive: tactics, hero moments, Google's patterns, variable-font emphasis | `references/expressive-design.md` |
| Is this API stable, opt-in or missing in my version? What is deprecated? | `references/api-status.md` |
| Gradle setup, `MaterialExpressiveTheme`, dynamic color, emphasized type, shape scale | `references/theme.md` |
| `MotionScheme`, spring values, spatial vs effects, custom animations | `references/motion.md` |
| The 35 `MaterialShapes`, clipping, shape morphing | `references/shapes.md` |
| Code for every Expressive component | `references/components.md` |
| Reviewing or migrating an existing screen | `references/audit.md` |

Official sources: [Compose Material 3 release notes](https://developer.android.com/jetpack/androidx/releases/compose-material3),
[API reference](https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary),
[m3.material.io](https://m3.material.io).
