# Setup and theme

## Gradle

```toml
# gradle/libs.versions.toml
[versions]
composeBom = "2026.09.00"
material3 = "1.5.0-beta02"
graphicsShapes = "1.1.0"   # only if you morph shapes yourself (androidx.graphics.shapes.Morph)

[libraries]
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-compose-material3 = { group = "androidx.compose.material3", name = "material3", version.ref = "material3" }
androidx-graphics-shapes = { group = "androidx.graphics", name = "graphics-shapes", version.ref = "graphicsShapes" }
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)   // explicit version wins over the BOM
}
```

Icons: `material-icons-extended` is no longer updated and adds megabytes. Use vector drawables
from [Material Symbols](https://fonts.google.com/icons) (`painterResource(R.drawable.ic_…)`) or
copy the few `ImageVector`s you need.

## `MaterialExpressiveTheme`

`MaterialExpressiveTheme(colorScheme, motionScheme, shapes, typography, content)`. Every parameter
is optional. At the top level the defaults are `expressiveLightColorScheme()`,
`MotionScheme.expressive()`, `Shapes()` and `Typography()`. Nested inside another
`MaterialExpressiveTheme`, missing parameters inherit from the parent.

There is **no** `expressiveDarkColorScheme()`. Use `darkColorScheme()` (or your own dark scheme)
and dynamic color on Android 12+:

<!-- snippet: theme -->
```kotlin
@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> darkColorScheme()
        else -> expressiveLightColorScheme()
    }
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
```

Rules:
- Wrap the app once, in `setContent { AppTheme { … } }`. Don't nest themes per screen.
- Keep dynamic color on by default and offer a toggle. Test with it off: that's what
  Android 11 and older show.
- Custom brand colors: generate a full light and dark scheme with
  [Material Theme Builder](https://material-foundation.github.io/material-theme-builder/).
  Don't hand-pick a few roles and leave the rest at defaults.

## Color roles

| Role | Use for |
|---|---|
| `primary` / `onPrimary` | The single most important action (main CTA, selected state) |
| `primaryContainer` / `onPrimaryContainer` | Large emphasized surfaces: FAB, hero card, selected chip |
| `secondaryContainer` / `onSecondaryContainer` | Tonal buttons, selected nav item indicator, less urgent accents |
| `tertiary` / `tertiaryContainer` | Small accents that need to contrast with primary (badges, highlights) |
| `surface` | Screen background |
| `surfaceContainerLowest` … `surfaceContainerHighest` | Cards, sheets, toolbars, grouped lists. Higher = more emphasis |
| `onSurface` / `onSurfaceVariant` | Body text / secondary text and icons |
| `outline` / `outlineVariant` | Borders of inputs / dividers and decorative lines |
| `error` / `errorContainer` | Errors only |

Containers are fills. Text and icons on a container use its `on…` pair.

## Emphasized typography

`Typography` has 15 emphasized styles: `displayLarge/Medium/SmallEmphasized`,
`headline…Emphasized`, `title…Emphasized`, `body…Emphasized`, `label…Emphasized`. Same size and line
height as the baseline style, heavier weight. They are public in 1.5.0 (internal in 1.4.0).

<!-- snippet: emphasized-type -->
```kotlin
@Composable
fun PlanHeader(name: String, price: String) {
    Column {
        // Emphasized = same size as the baseline style, heavier weight. Use it for the one thing
        // that should win attention, not for every heading.
        Text(name, style = MaterialTheme.typography.titleLargeEmphasized)
        Text(price, style = MaterialTheme.typography.displaySmallEmphasized)
        Text("Billed yearly", style = MaterialTheme.typography.bodyMedium)
    }
}
```

Where to use emphasized: the selected item in a list or menu, the price or key number on a
screen, a hero headline, the primary button label in a big CTA. Body copy stays baseline.

Custom font: pass a `Typography` built from your `FontFamily`, then emphasized styles follow it.
A variable font (for example Google Sans Flex or Roboto Flex) gives the smoothest weight changes.

## Shape scale

| `MaterialTheme.shapes.` | Corner | Typical use |
|---|---|---|
| `extraSmall` | 4dp | Chips, snackbars, tooltips |
| `small` | 8dp | Text fields, menus |
| `medium` | 12dp | Cards |
| `large` | 16dp | FAB (56dp), nav drawer |
| `largeIncreased` | 20dp | **New.** Medium FAB (80dp), larger cards |
| `extraLarge` | 28dp | Dialogs, bottom sheets, large FAB (96dp) |
| `extraLargeIncreased` | 32dp | **New.** Hero cards, feature panels |
| `extraExtraLarge` | 48dp | **New.** Big image tiles, onboarding art |
| `CircleShape` | full | Buttons (resting), badges, avatars |

<!-- snippet: increased-shapes -->
```kotlin
@Composable
fun HeroCard(content: @Composable () -> Unit) {
    // largeIncreased (20dp), extraLargeIncreased (32dp) and extraExtraLarge (48dp) are new steps.
    Card(shape = MaterialTheme.shapes.extraLargeIncreased) { content() }
}
```

Nested corners: inner radius = outer radius − padding. A 28dp card with 12dp padding holds an
image with 16dp corners. Large corners on dense content (tables, long text) clip it: use a smaller
step there.
