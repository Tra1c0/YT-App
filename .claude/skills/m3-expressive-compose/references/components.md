# Components

Every block below compiles against `material3 1.5.0-beta02` with no opt-in unless it says so.
Icons are passed in as `ImageVector` parameters so the code doesn't depend on an icon library.

Imports that are easy to get wrong:

```kotlin
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon // Modifier.animateIcon in the FAB menu
import androidx.compose.material3.animateFloatingActionButton                 // Modifier extension
import androidx.compose.material3.toShape                                      // RoundedPolygon.toShape(), opt-in
import androidx.compose.material3.toPath                                       // Morph.toPath(), opt-in
import androidx.compose.material3.FloatingToolbarExitDirection                 // .Bottom / .Top / .Start / .End
import androidx.compose.foundation.text.input.rememberTextFieldState           // state-based SearchBar
import androidx.graphics.shapes.Morph                                          // graphics-shapes artifact
```

Contents: [Buttons](#buttons) · [Toggle buttons](#toggle-buttons) · [Button groups](#button-groups) ·
[Split button](#split-button) · [Icon buttons](#icon-buttons) · [FAB and FAB menu](#fab-and-fab-menu) ·
[Floating toolbar](#floating-toolbar) · [Flexible top app bar](#flexible-top-app-bar) ·
[Navigation](#navigation) · [Segmented lists](#segmented-lists) · [Progress and loading](#progress-and-loading) ·
[Search](#search)

## Buttons

Pass `shapes` to get the overload whose corners morph from round to square on press. Without it
you get the old fixed-shape button.

<!-- snippet: button-shapes -->
```kotlin
@Composable
fun MorphingButton(onClick: () -> Unit) {
    // Passing `shapes` switches to the overload whose corners morph (round -> square) on press.
    Button(onClick = onClick, shapes = ButtonDefaults.shapes()) { Text("Continue") }
}
```

Five sizes. For anything other than the default 40dp, size the container, shapes, padding, icon
and text together with the `…For(height)` helpers:

| Size | Height | Icon | Constant |
|---|---|---|---|
| Extra small | 32dp | 20dp | `ButtonDefaults.ExtraSmallContainerHeight` |
| Small (default) | 40dp | 20dp | `ButtonDefaults.MinHeight` |
| Medium | 56dp | 24dp | `ButtonDefaults.MediumContainerHeight` |
| Large | 96dp | 32dp | `ButtonDefaults.LargeContainerHeight` |
| Extra large | 136dp | 40dp | `ButtonDefaults.ExtraLargeContainerHeight` |

<!-- snippet: button-sizes -->
```kotlin
@Composable
fun MediumCta(icon: ImageVector, onClick: () -> Unit) {
    // Sizes: ExtraSmall 32dp, Small 40dp (default), Medium 56dp, Large 96dp, ExtraLarge 136dp.
    val height = ButtonDefaults.MediumContainerHeight
    Button(
        onClick = onClick,
        modifier = Modifier.heightIn(height),
        shapes = ButtonDefaults.shapesFor(height),
        contentPadding = ButtonDefaults.contentPaddingFor(height, hasStartIcon = true),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.iconSizeFor(height)))
        Spacer(Modifier.size(ButtonDefaults.iconSpacingFor(height)))
        Text("Start free trial", style = ButtonDefaults.textStyleFor(height))
    }
}
```

One medium or large button per screen for the main action. The rest stay small.

## Toggle buttons

`ToggleButton`, `ElevatedToggleButton`, `FilledTonalToggleButton`, `OutlinedToggleButton`. A
button with on/off state that changes color **and** shape when checked. Use one for things like
follow/unfollow or bold on/off, not for navigation.

<!-- snippet: toggle-button -->
```kotlin
@Composable
fun FollowButton() {
    var following by rememberSaveable { mutableStateOf(false) }
    // Checked state changes both color and shape (round when off, square when on).
    ToggleButton(checked = following, onCheckedChange = { following = it }) {
        Text(if (following) "Following" else "Follow")
    }
}
```

## Button groups

**Connected** (looks like a segmented control, single or multi select): build it from
`ToggleButton`s with the connected shapes. Each item has `Role.RadioButton` (single select) or
`Role.Checkbox` (multi select) for screen readers.

<!-- snippet: connected-button-group -->
```kotlin
@Composable
fun PeriodPicker(options: List<String>) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    // Connected button group = single-select segmented control with expressive shapes.
    // Replaces SingleChoiceSegmentedButtonRow when you want the M3 Expressive look.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { index, label ->
            ToggleButton(
                checked = selected == index,
                onCheckedChange = { selected = index },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
            ) { Text(label) }
        }
    }
}
```

**Standard** `ButtonGroup`: a row of actions where the pressed button grows and its neighbours
shrink. Items that don't fit move into the overflow menu. Add items with `clickableItem`,
`toggleableItem` or `customItem` inside the scope. Don't put plain composables in it.

<!-- snippet: button-group-overflow -->
```kotlin
@Composable
fun EditorActions(onAction: (String) -> Unit) {
    // Standard button group: neighbours squeeze when one is pressed; extra items go to overflow.
    ButtonGroup(
        overflowIndicator = { menuState -> ButtonGroupDefaults.OverflowIndicator(menuState = menuState) },
    ) {
        listOf("Bold", "Italic", "Underline", "Strike", "Code", "Link").forEach { label ->
            clickableItem(onClick = { onAction(label) }, label = label)
        }
    }
}
```

## Split button

A main action plus a menu of related actions. The trailing button is a toggle: rotate the arrow
and open your `DropdownMenu` while it is checked.

<!-- snippet: split-button -->
```kotlin
@Composable
fun SaveSplitButton(arrowDown: ImageVector, onSave: () -> Unit, onMenu: (Boolean) -> Unit) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    SplitButtonLayout(
        leadingButton = {
            SplitButtonDefaults.LeadingButton(onClick = onSave) { Text("Save") }
        },
        trailingButton = {
            SplitButtonDefaults.TrailingButton(
                checked = menuOpen,
                onCheckedChange = { menuOpen = it; onMenu(it) },
                modifier = Modifier.semantics {
                    stateDescription = if (menuOpen) "Expanded" else "Collapsed"
                },
            ) {
                val rotation by animateFloatAsState(if (menuOpen) 180f else 0f, label = "arrow")
                Icon(
                    arrowDown,
                    contentDescription = "More save options",
                    modifier = Modifier
                        .size(SplitButtonDefaults.TrailingIconSize)
                        .graphicsLayer { rotationZ = rotation },
                )
            }
        },
    )
}
```

Variants: `SplitButtonDefaults.LeadingButton` / `TrailingButton` (filled),
`TonalLeadingButton` / `TonalTrailingButton`, `ElevatedLeadingButton` / `ElevatedTrailingButton`,
`OutlinedLeadingButton` / `OutlinedTrailingButton`. Don't mix variants within one split button.

## Icon buttons

`IconButton`, `FilledIconButton`, `FilledTonalIconButton`, `OutlinedIconButton` take
`shapes = IconButtonDefaults.shapes()` for the press morph, and have container sizes from
`extraSmallContainerSize()` to `extraLargeContainerSize()`. Toggleable icon buttons use
`IconButtonDefaults.toggleableShapes()`.

<!-- snippet: icon-button-shapes -->
```kotlin
@Composable
fun AddButton(icon: ImageVector, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(),
        modifier = Modifier.size(IconButtonDefaults.mediumContainerSize()),
    ) {
        Icon(icon, contentDescription = "Add", modifier = Modifier.size(IconButtonDefaults.mediumIconSize))
    }
}
```

Icon-only buttons always need a `contentDescription`, and a tooltip if the meaning isn't obvious.

## FAB and FAB menu

<!-- snippet: medium-fab -->
```kotlin
@Composable
fun NewItemFab(icon: ImageVector, onClick: () -> Unit) {
    // FAB sizes: FloatingActionButton 56dp, MediumFloatingActionButton 80dp (new in 1.5.0),
    // LargeFloatingActionButton 96dp. M3 Expressive guidance steers away from the 40dp small FAB.
    MediumFloatingActionButton(onClick = onClick) { Icon(icon, contentDescription = "New item") }
}
```

The FAB menu replaces the speed dial: the FAB morphs into a close button and 2 to 6 labelled
actions open above it. Hide the FAB while scrolling down with `Modifier.animateFloatingActionButton`.

<!-- snippet: fab-menu -->
```kotlin
data class FabAction(val icon: ImageVector, val label: String, val onClick: () -> Unit)

@Composable
fun ComposeFabMenu(
    actions: List<FabAction>,
    addIcon: ImageVector,
    closeIcon: ImageVector,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    BackHandler(expanded) { expanded = false }
    val fabVisible by remember {
        derivedStateOf { listState.firstVisibleItemIndex == 0 || !listState.canScrollForward }
    }

    Box(modifier.fillMaxSize()) {
        FloatingActionButtonMenu(
            modifier = Modifier.align(Alignment.BottomEnd),
            expanded = expanded,
            button = {
                ToggleFloatingActionButton(
                    checked = expanded,
                    onCheckedChange = { expanded = it },
                    modifier = Modifier
                        .semantics {
                            traversalIndex = -1f
                            stateDescription = if (expanded) "Expanded" else "Collapsed"
                            contentDescription = "Toggle menu"
                        }
                        .animateFloatingActionButton(
                            visible = fabVisible || expanded,
                            alignment = Alignment.BottomEnd,
                        ),
                ) {
                    // checkedProgress goes 0 -> 1 as the FAB morphs into the close button.
                    val icon by remember { derivedStateOf { if (checkedProgress > 0.5f) closeIcon else addIcon } }
                    Icon(
                        painter = rememberVectorPainter(icon),
                        contentDescription = null,
                        modifier = Modifier.animateIcon({ checkedProgress }),
                    )
                }
            },
        ) {
            actions.forEach { action ->
                FloatingActionButtonMenuItem(
                    onClick = { expanded = false; action.onClick() },
                    icon = { Icon(action.icon, contentDescription = null) },
                    text = { Text(action.label) },
                )
            }
        }
    }
}
```

Accessibility: the toggle FAB comes first in traversal order (`traversalIndex = -1f`), announces
Expanded/Collapsed, and Back closes the menu. Google's sample also wraps the FAB in a
`TooltipBox` (experimental API) and adds a "Close menu" custom action on the last item.

## Floating toolbar

`HorizontalFloatingToolbar` / `VerticalFloatingToolbar` replace the bottom app bar for contextual
actions. Pair one with a FAB, give it `exitAlwaysScrollBehavior` so it slides away on scroll, and
use `vibrantFloatingToolbarColors()` (primary container) or `standardFloatingToolbarColors()`
(surface container).

<!-- snippet: floating-toolbar -->
```kotlin
@Composable
fun EditorScreen(
    lines: List<String>,
    toolIcons: List<Pair<ImageVector, String>>,
    addIcon: ImageVector,
    onAdd: () -> Unit,
) {
    // Floating toolbars replace the bottom app bar in M3 Expressive. They hide on scroll down.
    val exitAlways = FloatingToolbarDefaults.exitAlwaysScrollBehavior(
        exitDirection = FloatingToolbarExitDirection.Bottom,
    )
    Scaffold(modifier = Modifier.nestedScroll(exitAlways)) { padding ->
        Box(Modifier.padding(padding)) {
            LazyColumn {
                items(lines) { ListItem { Text(it) } }
            }
            HorizontalFloatingToolbar(
                expanded = true,
                floatingActionButton = {
                    FloatingToolbarDefaults.VibrantFloatingActionButton(onClick = onAdd) {
                        Icon(addIcon, contentDescription = "Add")
                    }
                },
                colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
                scrollBehavior = exitAlways,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = -FloatingToolbarDefaults.ScreenOffset)
                    .zIndex(1f),
            ) {
                toolIcons.forEach { (icon, label) ->
                    IconButton(onClick = {}) { Icon(icon, contentDescription = label) }
                }
            }
        }
    }
}
```

Without a FAB, use the overload with `expanded`, `leadingContent`, `trailingContent` and `content`.
Set `expanded = false` to collapse it to the `content` only. For a classic full-width bar,
`FlexibleBottomAppBar` exists too.

## Flexible top app bar

`MediumFlexibleTopAppBar` and `LargeFlexibleTopAppBar` add `subtitle` and
`titleHorizontalAlignment` (`Alignment.Start` or `Alignment.CenterHorizontally`). They collapse
with the usual `TopAppBarDefaults` scroll behaviors.

<!-- snippet: flexible-top-app-bar -->
```kotlin
@Composable
fun InboxScreen(items: List<String>, backIcon: ImageVector, onBack: () -> Unit) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            // Flexible app bars add a subtitle slot and centred or start-aligned titles.
            LargeFlexibleTopAppBar(
                title = { Text("Inbox") },
                subtitle = { Text("${items.size} unread") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(backIcon, contentDescription = "Back") }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            items(items) { ListItem { Text(it) } }
        }
    }
}
```

## Navigation

Pick by window width: `ShortNavigationBar` on phones, `WideNavigationRail` on medium and
expanded widths. With `material3-adaptive-navigation-suite`, `NavigationSuiteScaffold` switches
for you.

<!-- snippet: short-navigation-bar -->
```kotlin
@Composable
fun BottomBar(destinations: List<Destination>, selected: Int, onSelect: (Int) -> Unit) {
    // ShortNavigationBar is the Expressive bottom bar: 64dp tall, 3 to 5 items on phones.
    // On medium widths set iconPosition = NavigationItemIconPosition.Start on the items.
    ShortNavigationBar {
        destinations.forEachIndexed { index, destination ->
            ShortNavigationBarItem(
                selected = selected == index,
                onClick = { onSelect(index) },
                icon = { Icon(destination.icon, contentDescription = null) },
                label = { Text(destination.label) },
            )
        }
    }
}
```

<!-- snippet: wide-navigation-rail -->
```kotlin
@Composable
fun SideRail(destinations: List<Destination>, selected: Int, onSelect: (Int) -> Unit) {
    // For tablets and desktop. It expands in place, which is what M3 Expressive uses instead of a
    // navigation drawer. Show 3 to 7 items when collapsed.
    val state = rememberWideNavigationRailState(WideNavigationRailValue.Collapsed)
    val expanded = state.targetValue == WideNavigationRailValue.Expanded
    WideNavigationRail(state = state) {
        destinations.forEachIndexed { index, destination ->
            WideNavigationRailItem(
                selected = selected == index,
                onClick = { onSelect(index) },
                icon = { Icon(destination.icon, contentDescription = null) },
                label = { Text(destination.label) },
                railExpanded = expanded,
            )
        }
    }
}
```

`ModalWideNavigationRail` is the modal version for compact windows where a rail would take too
much space.

## Segmented lists

The Expressive settings look: list items in one rounded group with small gaps between them.
`SegmentedListItem` has overloads for plain, `onClick`, `selected` and `checked` items.
`ListItemDefaults.segmentedShapes(index, count)` gives the first and last items the larger corners.

<!-- snippet: segmented-list -->
```kotlin
@Composable
fun SettingsGroup(settings: List<Setting>, onToggle: (Int, Boolean) -> Unit) {
    // Segmented list items: one rounded group, small gaps, larger corners on the first and last.
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        settings.forEachIndexed { index, setting ->
            SegmentedListItem(
                checked = setting.enabled,
                onCheckedChange = { onToggle(index, it) },
                shapes = ListItemDefaults.segmentedShapes(index = index, count = settings.size),
                supportingContent = { Text(setting.summary) },
                trailingContent = { Switch(checked = setting.enabled, onCheckedChange = null) },
            ) {
                Text(setting.title)
            }
        }
    }
}
```

The trailing `Switch` has `onCheckedChange = null` because the whole row is the toggle. That gives
one touch target and one announcement.

## Progress and loading

| Wait | Use |
|---|---|
| Known progress, visible for a while (download, upload, setup) | `LinearWavyProgressIndicator(progress = { … })` or `CircularWavyProgressIndicator(progress = { … })` |
| Unknown length, short (loading a screen or a list) | `LoadingIndicator()` (opt-in) |
| Unknown length over content, or pull-to-refresh | `ContainedLoadingIndicator()` (opt-in), `PullToRefreshDefaults.LoadingIndicator` (opt-in) |
| Dense or utilitarian UI | Flat `LinearProgressIndicator` / `CircularProgressIndicator` are still fine |

<!-- snippet: wavy-progress -->
```kotlin
@Composable
fun DownloadProgress(progress: () -> Float) {
    // Wavy indicators are stable in 1.5.0. Use them for long, visible waits (downloads, uploads).
    LinearWavyProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
}

@Composable
fun SyncingSpinner() {
    CircularWavyProgressIndicator()
}
```

<!-- snippet: loading-indicator -->
```kotlin
// LoadingIndicator is still experimental in 1.5.0-beta02, so opt in where you use it.
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ScreenLoading() {
    // For short indeterminate waits (screen or content loading), instead of a circular spinner.
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        LoadingIndicator()
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ButtonLoading() {
    // Contained = on a filled circle, for use over content or inside pull-to-refresh.
    ContainedLoadingIndicator()
}
```

## Search

The state-based `SearchBar` is stable in 1.5.0. It uses `TextFieldState`, and the expanded results
are a separate composable (`ExpandedFullScreenSearchBar` or `ExpandedDockedSearchBar`). For a
search field inside a top app bar, use `AppBarWithSearch` (still `@ExperimentalMaterial3Api`).

<!-- snippet: search-bar -->
```kotlin
@Composable
fun AppSearch(suggestions: (String) -> List<String>, onSearch: (String) -> Unit) {
    // State-based search bar. The old SearchBar(query, onQueryChange, active, …) is deprecated.
    val searchBarState = rememberSearchBarState()
    val textFieldState = rememberTextFieldState()
    val scope = rememberCoroutineScope()
    val inputField = @Composable {
        SearchBarDefaults.InputField(
            textFieldState = textFieldState,
            searchBarState = searchBarState,
            onSearch = { query ->
                onSearch(query)
                scope.launch { searchBarState.animateToCollapsed() }
            },
            placeholder = { Text("Search") },
        )
    }

    SearchBar(state = searchBarState, inputField = inputField)
    ExpandedFullScreenSearchBar(state = searchBarState, inputField = inputField) {
        LazyColumn {
            items(suggestions(textFieldState.text.toString())) { suggestion ->
                ListItem(
                    onClick = {
                        textFieldState.setTextAndPlaceCursorAtEnd(suggestion)
                        onSearch(suggestion)
                        scope.launch { searchBarState.animateToCollapsed() }
                    },
                ) { Text(suggestion) }
            }
        }
    }
}
```
