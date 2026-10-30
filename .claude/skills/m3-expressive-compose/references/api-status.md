# API status: 1.4.0 vs 1.5.0-beta02

Built from the published sources of `androidx.compose.material3:material3-android` **1.4.0** and
**1.5.0-beta02** (Google Maven, checked 2026-10-26). "Stable" means public with no opt-in
annotation. Re-check when the pinned version changes.

## Quick answer

| Version | Expressive APIs you can use |
|---|---|
| **1.4.0** (stable) | Only `ShortNavigationBar` and `WideNavigationRail`. `MaterialExpressiveTheme`, `MotionScheme.expressive()`, `expressiveLightColorScheme()`, the emphasized `Typography` styles, the `largeIncreased` / `extraLargeIncreased` / `extraExtraLarge` shapes, flexible app bars and `VerticalSlider` exist but are **`internal`**. Button groups, toggle/split buttons, FAB menu, floating toolbars, wavy and loading indicators are **missing**. |
| **1.5.0-alpha01…29** | Most of it, behind `@ExperimentalMaterial3ExpressiveApi`. Names changed between alphas. Move to beta. |
| **1.5.0-beta01 / beta02** | Almost everything **stable**. Only the items under "Still opt-in" need `@OptIn` (same list in both betas). |

```toml
# gradle/libs.versions.toml
[versions]
material3 = "1.5.0-beta02"

[libraries]
androidx-compose-material3 = { group = "androidx.compose.material3", name = "material3", version.ref = "material3" }
```

A Compose BOM does not move material3 to a beta. Pin the version as above and keep the BOM for
the other Compose libraries.

## Still opt-in in 1.5.0-beta02

`@OptIn(ExperimentalMaterial3ExpressiveApi::class)`:

| API | File |
|---|---|
| `LoadingIndicator`, `ContainedLoadingIndicator`, `LoadingIndicatorDefaults` | LoadingIndicator.kt |
| `MaterialShapes` (all 35 shapes), `RoundedPolygon.toShape()`, `RoundedPolygon.toPath()`, `Morph.toPath()` | MaterialShapes.kt |
| `DropdownMenuItem(checked, onCheckedChange, text, shapes, …)` (the checkable overload with `shapes`) | Menu.kt |
| `PullToRefreshDefaults.LoadingIndicator`, `loadingIndicatorColor`, `loadingIndicatorContainerColor` | PullToRefresh.kt |
| One `Thumb` overload (the Expressive slider thumb) | Slider.kt |

`@OptIn(ExperimentalMaterial3Api::class)` (older APIs, not Expressive-specific, still experimental):
`ModalBottomSheet`, `BottomSheetScaffold`, `rememberModalBottomSheetState`, `SheetState`,
`TooltipBox`, `PlainTooltip`, `RichTooltip`, `rememberTooltipState`, `AppBarWithSearch`,
`TopSearchBar`, `DockedSearchBar`, the `SearchBar(inputField, expanded, …)` overload,
`Modifier.carouselParallaxScrollEffect`, `Label`.

Put file-level opt-ins only in files that use these: `@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)`.
An opt-in on code that doesn't need it compiles, but it hides which calls are still unstable.

## Stable in 1.5.0-beta02 (no opt-in)

| Area | APIs |
|---|---|
| Theme | `MaterialExpressiveTheme`, `MotionScheme`, `MotionScheme.expressive()`, `MotionScheme.standard()`, `MaterialTheme.motionScheme`, `expressiveLightColorScheme()`, `Typography.*Emphasized`, `Shapes.largeIncreased` / `extraLargeIncreased` / `extraExtraLarge` |
| Buttons | `Button(shapes = …)` and the other button overloads with `shapes`, `ButtonShapes`, `ButtonDefaults.shapes()` / `shapesFor()` / `contentPaddingFor()` / `textStyleFor()` / `iconSizeFor()` / `iconSpacingFor()`, size constants `ExtraSmall/Medium/Large/ExtraLargeContainerHeight` |
| Toggle buttons | `ToggleButton`, `ElevatedToggleButton`, `FilledTonalToggleButton`, `OutlinedToggleButton`, `ToggleButtonDefaults`, `ToggleButtonShapes` |
| Groups | `ButtonGroup`, `ButtonGroupScope` (`clickableItem`, `toggleableItem`, `customItem`), `ButtonGroupDefaults` (incl. `connectedLeading/Middle/TrailingButtonShapes()`), `ButtonGroupMenuState`, `Modifier.animateWidth` |
| Split button | `SplitButtonLayout`, `SplitButtonDefaults.LeadingButton` / `TrailingButton` / `TonalLeadingButton` …, `SplitButtonShapes` |
| Icon buttons | `IconButton(shapes = …)`, `IconButtonShapes`, `IconToggleButtonShapes`, `IconButtonDefaults.shapes()` / `toggleableShapes()` / `extraSmall…extraLargeContainerSize()` |
| FAB | `MediumFloatingActionButton`, `SmallExtendedFloatingActionButton`, `MediumExtendedFloatingActionButton`, `LargeExtendedFloatingActionButton`, `Modifier.animateFloatingActionButton` |
| FAB menu | `FloatingActionButtonMenu`, `FloatingActionButtonMenuItem`, `ToggleFloatingActionButton`, `ToggleFloatingActionButtonDefaults` |
| Toolbars | `HorizontalFloatingToolbar`, `VerticalFloatingToolbar`, `FloatingToolbarDefaults` (incl. `exitAlwaysScrollBehavior`, `VibrantFloatingActionButton`, `vibrantFloatingToolbarColors()`), `rememberFloatingToolbarState` |
| App bars | `MediumFlexibleTopAppBar`, `LargeFlexibleTopAppBar`, `TwoRowsTopAppBar`, `FlexibleBottomAppBar`, `AppBarOverflowIndicator`, `subtitle` and `titleHorizontalAlignment` params |
| Navigation | `ShortNavigationBar`, `ShortNavigationBarItem`, `WideNavigationRail`, `WideNavigationRailItem`, `ModalWideNavigationRail`, `NavigationItemIconPosition` |
| Lists | `SegmentedListItem`, `ListItemDefaults.segmentedShapes()` / `segmentedColors()` / `SegmentedGap`, `ListItemShapes`, `ListItem { content }` overloads |
| Menus | `DropdownMenuPopup`, `DropdownMenuGroup`, `SelectableDropdownMenuItem`, `CheckableDropdownMenuItem`, `MenuItemShapes`, `MenuGroupShapes` |
| Progress | `LinearWavyProgressIndicator`, `CircularWavyProgressIndicator`, `WavyProgressIndicatorDefaults` |
| Search | `SearchBar(state, inputField)`, `rememberSearchBarState`, `SearchBarDefaults.InputField(textFieldState, searchBarState, …)`, `ExpandedFullScreenSearchBar`, `ExpandedDockedSearchBar`, `ExpandedFullScreenContainedSearchBar`, `ExpandedDockedSearchBarWithGap` |
| Other new | `VerticalSlider`, `Scrim`, `ScrollField` / `TimeScroll`, `VibrantTimePickerDialog`, `Modifier.nonInteractiveScrollbar`, `TooltipAnchorPosition`, `FabPosition`, `TextFieldLabelPosition` |

## Deprecated in 1.5.0-beta02 (new since 1.4.0)

Warnings, not errors, but don't write new code with them.

| Deprecated | Use instead |
|---|---|
| `ListItem(headlineContent = { … }, …)` | `ListItem(…) { Text(…) }` (headline is the trailing `content` lambda) |
| `ListItemDefaults.Elevation`, `.shape` | `ListItemDefaults.elevation()`, `ListItemDefaults.shapes()` |
| `ListItemColors.headlineColor`, `leadingIconColor`, `trailingIconColor`, `overlineColor`, `supportingTextColor`, `disabled*` | `contentColor`, `leadingContentColor`, `trailingContentColor`, `overlineContentColor`, `supportingContentColor`, `disabled*ContentColor` |
| `SearchBar(query, onQueryChange, onSearch, active, onActiveChange, …)` | `SearchBar(state, inputField)` + `ExpandedFullScreenSearchBar` or `ExpandedDockedSearchBar` |
| `DockedSearchBar(…)` | `SearchBar(state, inputField)` + `ExpandedDockedSearchBar` |
| `SearchBarDefaults.InputField(query, …)` | `SearchBarDefaults.InputField(textFieldState, searchBarState, …)` |
| `TopSearchBar` | `AppBarWithSearch` (renamed) |
| `SearchBarDefaults.enterAlwaysSearchBarScrollBehavior()` without state | overload taking `SearchBarScrollState` |
| `Slider(value, onValueChange, …)` / `RangeSlider(value, …)` | overloads taking `SliderState` / `RangeSliderState` plus `onValueChange` and `onValueChangeFinished` |
| `TopAppBarDefaults.enterAlwaysScrollBehavior()` without state | overload taking a `ScrollableState` |
| `Modifier.floatingScrollBehavior` | `floatingScrollBehaviorModifier` |
| `MenuItemColors.selected*`, `MenuItemColors.copy(selected…)` | `SelectableMenuItemColors` |
| `NavigationItemColors.selectedTextColor` | `selectedTextColorTopIconPosition` |
| `RippleAlpha`, `rippleAlpha` | `RippleThemeConfiguration` / `RippleConfiguration` |
| `TextFieldLabelPosition.Attached` | `Inside` (filled) or `Cutout` (outlined) |
| `TextFieldDefaults.contentPadding()` | `contentPaddingWithLabel()` / `contentPaddingWithoutLabel()` |

The most common one after upgrading:

<!-- snippet: list-item-migration -->
```kotlin
@Composable
fun WifiRow(network: String, onClick: () -> Unit) {
    // 1.5.0: the headline is the trailing content lambda. ListItem(headlineContent = …) is deprecated.
    // The onClick overload makes the whole row clickable with the right semantics.
    ListItem(onClick = onClick, supportingContent = { Text(network) }) {
        Text("Wi-Fi")
    }
}
```

## How this table was made

1. Download `material3-android-<version>-sources.jar` from `https://dl.google.com/android/maven2/androidx/compose/material3/material3-android/<version>/`.
2. List public top-level declarations in both versions and diff them.
3. List declarations annotated `@ExperimentalMaterial3ExpressiveApi` / `@ExperimentalMaterial3Api`.
4. List `@Deprecated` declarations that are not in the older version.
5. Compile `verify/` against the new version.
