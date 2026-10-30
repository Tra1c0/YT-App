# Audit: review or migrate a screen

Use this when asked to review a Compose screen against M3 Expressive, or to make an existing
screen "expressive". Report findings first, then change code only if asked.

## 1. Version and theme

```bash
# material3 version actually resolved (not just declared)
./gradlew :app:dependencies --configuration releaseRuntimeClasspath | grep "androidx.compose.material3:material3:"

# Theme entry point
grep -rn "MaterialExpressiveTheme\|MaterialTheme(" --include=*.kt app/src/main
```

- [ ] material3 is `1.5.0-beta02` or newer (see `api-status.md` for older versions)
- [ ] Root theme is `MaterialExpressiveTheme`, with a dark scheme and dynamic color
- [ ] No `@OptIn(ExperimentalMaterial3ExpressiveApi::class)` left on code that is now stable

## 2. Hardcoded values

```bash
grep -rnE "Color\(0x|Color\.(Red|Blue|Green|Black|White|Gray)" --include=*.kt app/src/main | grep -v "/theme/"
grep -rnE "fontSize\s*=\s*[0-9]+\.sp|fontWeight\s*=" --include=*.kt app/src/main | grep -v "/theme/"
grep -rnE "RoundedCornerShape\([0-9]+\.dp\)" --include=*.kt app/src/main | grep -v "/theme/"
grep -rnE "tween\(|keyframes\s*\{|LinearEasing|FastOutSlowInEasing" --include=*.kt app/src/main
```

- [ ] Colors come from `MaterialTheme.colorScheme`
- [ ] Text uses `MaterialTheme.typography` styles (emphasized where it guides attention)
- [ ] Corners use `MaterialTheme.shapes` or `MaterialShapes`
- [ ] In-screen animations use `MaterialTheme.motionScheme` springs (spatial vs effects correct)

## 3. Deprecated and old components

```bash
grep -rnE "ListItem\(\s*headlineContent|DockedSearchBar\(|TopSearchBar\(|SearchBar\(\s*query" --include=*.kt app/src/main
grep -rnE "\bNavigationBar\(|\bNavigationRail\(|ModalNavigationDrawer\(|BottomAppBar\(|SmallFloatingActionButton\(" --include=*.kt app/src/main
grep -rnE "SingleChoiceSegmentedButtonRow|MultiChoiceSegmentedButtonRow|CircularProgressIndicator\(\)" --include=*.kt app/src/main
```

For each hit, check the mapping table in `SKILL.md`. Suggest, don't force: a segmented button row
or a flat progress indicator can be the right call in a dense screen.

## 4. Expressive quality

- [ ] One clear primary action per screen: bigger (medium/large button or FAB), `primary` color
- [ ] One or two expressive moments, no more: a morph, an emphasized headline, a bouncy selection
- [ ] Abstract `MaterialShapes` only on images and decoration, never on text containers
- [ ] Nested corners follow inner = outer − padding
- [ ] Grouped settings use `SegmentedListItem`, not cards of `ListItem`s
- [ ] Contextual actions use a floating toolbar or FAB menu, not a crowded top bar

## 5. Accessibility

- [ ] Touch targets ≥ 48dp (`Modifier.minimumInteractiveComponentSize()` is on by default for M3
      components; check custom clickables)
- [ ] Icon-only buttons have `contentDescription`; toggles and menus have `stateDescription`
- [ ] Connected button groups set `Role.RadioButton` / `Role.Checkbox`
- [ ] Text contrast ≥ 4.5:1 (≥ 3:1 for 18sp+ or 14sp+ bold). Stick to `on…` role pairs
- [ ] Layout survives font scale 200% and a 320dp-wide window
- [ ] State is never shown by motion or color alone

## Report format

```markdown
## M3 Expressive review: <screen>
material3: <version> · theme: <MaterialExpressiveTheme | MaterialTheme>

### Must fix
- <file:line> <problem> → <fix>

### Should fix
- …

### Expressive opportunities
- <where> <what to add and why it helps attention>

### Already good
- …
```
