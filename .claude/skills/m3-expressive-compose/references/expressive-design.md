# Expressive design: what to change, and how much

Read this before making a screen "expressive". Sources:
- Google's announcement post, [Start building with Material 3 Expressive](https://m3.material.io/blog/building-with-m3-expressive) (May 13, 2025).
- The launch video and the post's component, motion and app clips. Patterns below marked
  *(seen in Google's examples)* come from those clips. They are observations, not published
  rules, so don't present them as spec.

## What it is

- M3 Expressive is an **evolution of Material 3**, not a new version. M3 isn't deprecated, and
  there is no "M4".
- It is the most researched Material update since 2014: 46 studies with more than 18,000 people.
  The headline result is a **usability** one. People spotted key UI elements **up to 4× faster**
  on expressive screens.
- So every expressive choice should answer one question: **does this help people find what
  matters on this screen?** If it doesn't, leave it out.

## The 14 new or updated components → Compose

| Component | Compose (1.5.0) |
|---|---|
| App bars | `MediumFlexibleTopAppBar`, `LargeFlexibleTopAppBar`, `FlexibleBottomAppBar` |
| Button groups *(new)* | `ButtonGroup`, connected `ToggleButton`s |
| Common buttons | `Button(shapes = …)` + 5 sizes, `ToggleButton` |
| Extended FAB | `Small/Medium/LargeExtendedFloatingActionButton` |
| FAB menu *(new)* | `FloatingActionButtonMenu`, `ToggleFloatingActionButton` |
| FABs | `FloatingActionButton`, `MediumFloatingActionButton`, `LargeFloatingActionButton` |
| Icon buttons | `IconButton(shapes = …)`, `IconButtonDefaults.*ContainerSize()` |
| Loading indicator *(new)* | `LoadingIndicator`, `ContainedLoadingIndicator` (opt-in) |
| Navigation bar | `ShortNavigationBar` |
| Navigation rail | `WideNavigationRail`, `ModalWideNavigationRail` |
| Progress indicators | `LinearWavyProgressIndicator`, `CircularWavyProgressIndicator` |
| Sliders | `Slider(state)` with the Expressive thumb, `VerticalSlider` |
| Split button *(new)* | `SplitButtonLayout` |
| Toolbars *(new)* | `HorizontalFloatingToolbar`, `VerticalFloatingToolbar`, `FlexibleBottomAppBar` |

## The seven tactics

Each tactic is a separate dial you can turn up.

1. **Use a variety of shapes.** Mix round and square to create tension. Combine classic shapes
   with abstract ones from `MaterialShapes`.
   - *Caution:* if the essential action is small or plain, it looks unimportant.
   - *Do:* break the shape style of its surroundings to draw attention to one element.
2. **Apply rich, nuanced color.** Mix primary, secondary and tertiary on key elements, and build
   hierarchy with surface tones.
   - *Caution:* without contrast, elements blend together.
   - *Do:* use contrast to point at the main takeaway.
3. **Guide attention with typography.** Use emphasized styles, heavier weights, larger sizes,
   color and spacing for headlines, key actions and editorial moments.
4. **Contain content for emphasis.** Group related things in containers. Give the most important
   group the most space and the brightest surface.
   - *Caution:* ungrouped information blends together.
5. **Add fluid, natural motion.** Use shape morphs, surface effects, spring motion and small
   micro-animations.
6. **Lean on component flexibility.** Move controls to fit the context. On foldables and tablets,
   use canonical layouts.
7. **Combine tactics for hero moments.** Hero moments are brief, surprising and delightful. Use
   **one or two per product**, not per screen; more than that overwhelms. To pick one, ask:
   - Is this interaction emotionally important?
   - Is it a key task?

## Patterns *(seen in Google's examples)*

1. **Selection changes shape as well as color.** In a connected group, unselected segments are
   tonal rounded squares. The selected one turns dark (primary) and becomes a full pill, and it can
   get wider while its neighbours shrink.
   → Compose: `ToggleButton` with the `ButtonGroupDefaults.connected*ButtonShapes()` shapes does the
   shape change for you.
2. **Morph, don't swap.** The FAB menu's close button morphs back into the FAB. An expanded
   toolbar collapses into its main action. A docked bottom bar becomes a floating toolbar plus a
   separate FAB. Keep the element in place and animate its shape, size and color.
3. **Shape contrast marks the primary action.** Examples:
   - a square-ish FAB next to a pill toolbar
   - a big pill Play/Pause button between round icon buttons
   - a dark selected pill among tonal segments

   One element per row breaks the pattern.
4. **Tertiary is for the one key accent.** Extended FABs and FABs often use the tertiary
   container while the rest of the UI stays primary or secondary. The toolbar and its FAB get
   different container colors.
5. **Wavy means "working".** Wavy progress shows something is in progress. The wave flattens into
   a straight bar when it's done. The track keeps its gap and the stop dot at the end.
6. **One huge emphasized headline per screen.** Use it for the album title, the card name or the
   reaction sticker. Labels and body text stay on the baseline scale.
7. **Bold, full-bleed color on hero screens only.** A music player or event card can be a solid
   saturated color. Utility screens (journal, settings, lists) stay light and calm.
8. **Abstract shapes go on images and markers.**
   - Avatars masked in cookie, clover or flower shapes.
   - A selected filter thumbnail turned into a scalloped circle.
   - A **Sunny badge marking the days a goal was hit** in a bar chart.

   Never on text containers.
9. **Large screens:**
   - Tablet: an expanded `WideNavigationRail` with an extended FAB at the top, and big type over
     large-radius image cards.
   - Phone: floating toolbar and FAB menu at the bottom.
   - Landscape: toolbar bottom-centre, FAB bottom-right.
10. **Same layout, many schemes.** Google shows one chat screen in a dozen dynamic schemes, light
    and dark. If a design only works in one color scheme, it relies on color too much. Test with
    dynamic color on several wallpapers.

## Typography with a variable font

`md.sys.typescale.emphasized.*` is the emphasized version of each role at the same size and line
height. In Compose it is `MaterialTheme.typography.*Emphasized`. With a variable font you can make
emphasis wider and rounder, not only heavier. Google's specimen pairs weight 850 / width 130 with
500 / 100.

<!-- snippet: variable-font-emphasis -->
```kotlin
/**
 * A variable font gives emphasis more range than weight alone: wider and heavier for the
 * emphasized display styles, like Google's Gg specimen (weight 850, width 130 vs 500 / 100).
 * Google Sans Flex has wght 1..1000, wdth 25..151 and ROND 0..100 axes.
 */
fun expressiveTypography(assets: AssetManager): Typography {
    fun flex(weight: Int, width: Float = 100f, round: Float = 0f) = FontFamily(
        Font(
            path = "fonts/GoogleSansFlex.ttf",
            assetManager = assets,
            weight = FontWeight(weight),
            variationSettings = FontVariation.Settings(
                FontVariation.weight(weight),
                FontVariation.width(width),
                FontVariation.Setting("ROND", round),
            ),
        ),
    )
    val regular = flex(400)
    val medium = flex(500)
    val heroWide = flex(weight = 850, width = 130f, round = 100f)
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.copy(fontFamily = regular),
        headlineMedium = base.headlineMedium.copy(fontFamily = regular),
        titleLarge = base.titleLarge.copy(fontFamily = regular),
        bodyLarge = base.bodyLarge.copy(fontFamily = regular),
        labelLarge = base.labelLarge.copy(fontFamily = medium),
        // Same size and line height as the baseline role; only the font settings change.
        displayLargeEmphasized = base.displayLargeEmphasized.copy(fontFamily = heroWide),
        headlineMediumEmphasized = base.headlineMediumEmphasized.copy(fontFamily = heroWide),
        titleLargeEmphasized = base.titleLargeEmphasized.copy(fontFamily = flex(700)),
        labelLargeEmphasized = base.labelLargeEmphasized.copy(fontFamily = flex(700)),
    )
}
```

Ship one variable font file, not a file per weight. Keep `wdth` near 100 for body text, because
very wide or narrow text hurts readability at small sizes.

## Shapes as data highlights

<!-- snippet: shape-highlight-chart -->
```kotlin
/**
 * Shapes as highlights in data, like Google's steps chart: plain pill bars, and a Sunny-shaped
 * check badge only on the days that hit the goal. The badge is decoration, so the bar still says
 * what it means (contentDescription on the column, not on the shape).
 */
@Composable
fun WeekChart(steps: List<Int>, goal: Int, check: ImageVector, max: Int = 15_000) {
    Row(
        modifier = Modifier.height(200.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        steps.forEach { value ->
            val hit = value >= goal
            Column(
                modifier = Modifier.width(40.dp).fillMaxHeight(),
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height((200 * value / max).coerceAtLeast(40).dp)
                        .background(
                            if (hit) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primary,
                            CircleShape,
                        ),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    if (hit) {
                        Box(
                            Modifier
                                .size(36.dp)
                                .background(MaterialTheme.colorScheme.tertiary, MaterialShapes.Sunny.toShape()),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(check, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiary)
                        }
                    }
                }
            }
        }
    }
}
```

## Docked bar → floating toolbar

<!-- snippet: docked-to-floating -->
```kotlin
/**
 * Google's before/after: a docked bottom bar with a FAB becomes a floating toolbar plus a
 * separate FAB in a contrasting shape and color. The FAB is the primary action, so it gets the
 * vibrant container and the square-ish shape; the toolbar stays a pill.
 */
@Composable
fun ComposeActions(actions: List<Pair<ImageVector, String>>, primary: ImageVector, onPrimary: () -> Unit) {
    HorizontalFloatingToolbar(
        expanded = true,
        floatingActionButton = {
            FloatingToolbarDefaults.VibrantFloatingActionButton(onClick = onPrimary) {
                Icon(primary, contentDescription = "Compose")
            }
        },
    ) {
        actions.forEach { (icon, label) ->
            IconButton(onClick = {}) { Icon(icon, contentDescription = label) }
        }
    }
}
```

## Checklist for "make it expressive"

1. Find the one primary action and the one key piece of information on the screen.
2. Make the action bigger and give it a contrasting shape (pill vs rounded square) and the
   strongest color role (primary, or tertiary as an accent).
3. Give the key information an emphasized style. Use the large emphasized display style only if
   this screen is a hero moment.
4. Group the rest into surface containers. The most important group gets the brightest one.
5. Swap static selection for shape + color change (`ToggleButton`, connected groups,
   `SegmentedListItem` selected state).
6. Replace swaps with morphs, and use `motionScheme` springs.
7. Stop. Check light, dark, two dynamic color wallpapers, and font scale 200%.
