# Motion: `MotionScheme`

M3 Expressive moves component motion from duration + easing curves to **springs**. The theme holds
a `MotionScheme`. Material components already use it, and your own animations should too.

```kotlin
MaterialExpressiveTheme(motionScheme = MotionScheme.expressive()) { … }  // default at the root
MaterialTheme.motionScheme                                                // read it anywhere
```

## The six specs

| Spec | Animates | Expressive (damping / stiffness) | Standard (damping / stiffness) |
|---|---|---|---|
| `fastSpatialSpec()` | small things: switch thumb, icon, chip | 0.6 / 800 | 0.9 / 1400 |
| `defaultSpatialSpec()` | most size, position, corner, morph changes | 0.8 / 380 | 0.9 / 700 |
| `slowSpatialSpec()` | full-screen or large containers | 0.8 / 200 | 0.9 / 300 |
| `fastEffectsSpec()` | small color / alpha changes | 1.0 / 3800 | 1.0 / 3800 |
| `defaultEffectsSpec()` | most color / alpha changes | 1.0 / 1600 | 1.0 / 1600 |
| `slowEffectsSpec()` | large fades, scrims | 1.0 / 800 | 1.0 / 800 |

Values from `ExpressiveMotionTokens` / `StandardMotionTokens` in 1.5.0-beta02. A damping ratio
below 1.0 overshoots (bounces). Effects specs are 1.0 in both schemes, so colors never overshoot.

**Spatial** = anything that moves, resizes, rotates, changes corners or morphs.
**Effects** = color and alpha. An overshooting color looks broken, so never use a spatial spec for it.

## Using it in your own code

<!-- snippet: motion-custom -->
```kotlin
@Composable
fun SelectableTile(selected: Boolean, modifier: Modifier = Modifier) {
    val motion = MaterialTheme.motionScheme
    // Size and corner changes are spatial: they may overshoot (bounce) with MotionScheme.expressive().
    val size by animateDpAsState(
        targetValue = if (selected) 120.dp else 96.dp,
        animationSpec = motion.defaultSpatialSpec(),
        label = "size",
    )
    val corner by animateDpAsState(
        targetValue = if (selected) 48.dp else 16.dp,
        animationSpec = motion.fastSpatialSpec(),
        label = "corner",
    )
    // Color and alpha are effects: they never overshoot, in either scheme.
    val color by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        animationSpec = motion.defaultEffectsSpec(),
        label = "color",
    )
    Box(modifier.size(size).background(color, RoundedCornerShape(corner)))
}
```

- The specs are generic: `defaultSpatialSpec<Dp>()`, `<Float>`, `<IntOffset>` … Type inference
  usually handles it.
- They work with `animate*AsState`, `Animatable.animateTo`, `updateTransition`,
  `AnimatedVisibility(enter = fadeIn(motion.defaultEffectsSpec()) + expandVertically(motion.defaultSpatialSpec()))`
  and `Modifier.animateItem(placementSpec = motion.defaultSpatialSpec())`.
- Springs have no fixed duration and handle interruptions (a tap mid-animation retargets
  smoothly). Don't convert them to `tween`.
- `tween` + easing still fits screen-to-screen navigation transitions. Inside a screen, use
  the springs.

## Choosing a scheme

- `MotionScheme.expressive()`: default. Consumer apps, onboarding, paywalls, anything with hero
  moments.
- `MotionScheme.standard()`: utility apps where bounce would feel out of place (banking, admin).
- Mixing: wrap one subtree in `MaterialExpressiveTheme(motionScheme = MotionScheme.standard())`
  to calm a dense area.

Compose animations follow the system animation scale (Developer options and the accessibility
"Remove animations" setting), so springs respect it without extra code. Don't build custom
animation loops that bypass it.
