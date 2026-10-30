# Shapes: `MaterialShapes` and morphing

`MaterialShapes` holds 35 `RoundedPolygon`s. **Still `@ExperimentalMaterial3ExpressiveApi` in
1.5.0-beta02**, so use a file-level opt-in where you use them:

```kotlin
@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)
```

## The 35 shapes

`Circle` `Square` `Slanted` `Arch` `Fan` `Arrow` `SemiCircle` `Oval` `Pill` `Triangle` `Diamond`
`ClamShell` `Pentagon` `Gem` `Sunny` `VerySunny` `Cookie4Sided` `Cookie6Sided` `Cookie7Sided`
`Cookie9Sided` `Cookie12Sided` `Ghostish` `Clover4Leaf` `Clover8Leaf` `Burst` `SoftBurst` `Boom`
`SoftBoom` `Flower` `Puffy` `PuffyDiamond` `PixelCircle` `PixelTriangle` `Bun` `Heart`

Accessed as `MaterialShapes.Cookie9Sided` and so on. Any name not in this list does not exist.

## Using a shape

`RoundedPolygon.toShape()` turns a polygon into a Compose `Shape` for `clip`, `background`,
`border` or a component's `shape` parameter.

<!-- snippet: shape-avatar -->
```kotlin
@Composable
fun CookieAvatar(photo: Painter, contentDescription: String?) {
    // Abstract shapes are for images and decoration, never for text-heavy containers.
    Image(
        painter = photo,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(72.dp).clip(MaterialShapes.Cookie9Sided.toShape()),
    )
}
```

Where abstract shapes fit: avatars, app icons, image thumbnails, empty-state art, onboarding
illustrations, selected-state indicators, loading states. Where they don't: cards, dialogs, text
fields, anything holding paragraphs. Use one or two per screen.

## Morphing between two shapes

Morph needs `androidx.graphics:graphics-shapes` (`Morph`). Build the `Morph` once with
`remember`, animate a 0..1 progress with a **spatial** spring, and draw it with a small `Shape`
that scales the unit-size path to the layout:

<!-- snippet: morph-shape -->
```kotlin
/** Draws [morph] at [progress] (0 = start polygon, 1 = end polygon), scaled to the layout size. */
class MorphShape(private val morph: Morph, private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = morph.toPath(progress = progress, path = Path())
        path.transform(Matrix().apply { scale(size.width, size.height) })
        return Outline.Generic(path)
    }
}
```

<!-- snippet: morph-toggle -->
```kotlin
@Composable
fun MorphOnTap() {
    var selected by remember { mutableStateOf(false) }
    val morph = remember { Morph(MaterialShapes.Circle, MaterialShapes.Cookie9Sided) }
    val progress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "morph",
    )
    Box(
        Modifier
            .size(96.dp)
            .background(MaterialTheme.colorScheme.primary, MorphShape(morph, progress))
            .clickable { selected = !selected },
    )
}
```

Tips:
- Morph between shapes with similar complexity (Circle ↔ Cookie9Sided, Square ↔ Cookie4Sided)
  for the smoothest result.
- For a continuous spin + morph (like `LoadingIndicator`), animate progress with
  `rememberInfiniteTransition` and add `graphicsLayer { rotationZ = … }`.
- Need a shape that isn't in the list? Build a `RoundedPolygon(numVertices, rounding = CornerRounding(…))`
  from graphics-shapes. Don't claim it is a Material shape.
