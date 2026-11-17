package com.github.libretube.ui.yt.builder

import android.graphics.BlurMaskFilter
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ParentDataModifierNode
import androidx.compose.ui.node.invalidateParentData
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.github.libretube.R
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.ui.yt.YtLargeThumbnail
import com.github.libretube.ui.yt.YtNavItem
import com.github.libretube.ui.yt.YtPalette
import com.github.libretube.ui.yt.YtShortsGlyph
import com.github.libretube.ui.yt.ytDuration
import com.github.libretube.ui.yt.ytMeta
import com.github.libretube.ui.yt.ytSeen
import com.github.libretube.ui.yt.ytTitle
import java.io.File
import kotlin.math.roundToInt

/*
 * YT: draws the components of a theme document (theme builder). The app's screens of "Eigenes"
 * and the editor's canvas use the same code, so the preview is the real thing.
 *
 * Efficiency: each node reads its own resolved value through a derivedStateOf with structural
 * equality, so a change to one node recomposes that node only, and a token colour that changes is
 * only drawn again (brushes are built in drawWithCache, text colour comes from a ColorProducer).
 * A child's rules travel to its parent's layout as parent data, so the parent does not recompose
 * when a child changes. Drag offsets are read in graphicsLayer, never in composition.
 *
 * Safety: resolving, measuring and drawing a node catch their own errors; a node that fails is
 * drawn as a hatched placeholder and the rest of the component draws on.
 */

/** Hooks of the editor into the renderer; the app passes none. */
interface YtRenderHooks {
    fun placed(kind: YtComponentKind, id: String, coordinates: LayoutCoordinates)

    /** Translation of a node being dragged, px (read in graphicsLayer only). */
    fun dragX(id: String): Float
    fun dragY(id: String): Float

    /** 0..1, how far a picked-up node is lifted (scale and shadow). */
    fun lift(id: String): Float

    /** Conflicts of a container's last layout. */
    fun conflicts(id: String, conflicts: List<YtLayoutEngine.Conflict>) {}

    /** The animator's playhead and test events (phase 2). */
    val anim: YtAnimLink? get() = null

    /**
     * Child [child] of container [parent] is placed at [x], [y] (px of the parent, its layout
     * place); the returned shift is added to the placement (the reorder after a drop settles).
     */
    fun placeOffset(parent: String, child: String, x: Float, y: Float): androidx.compose.ui.unit.IntOffset = androidx.compose.ui.unit.IntOffset.Zero

    /** The path of node [id] while the pen tool drags its points (read in the draw cache), or null (phase 3). */
    fun livePath(id: String): YtPath? = null

    /** Frames run over budget while points are dragged: the dragged path draws as an outline only. */
    fun reduced(): Boolean = false
}

/** Where the renderer takes its theme from (state reads, so changes recompose or redraw). */
@Stable
class YtThemeSource(
    private val docOf: () -> YtThemeDoc?,
    private val envOf: () -> YtEnv,
    private val nightOf: () -> Boolean,
    private val assetsOf: () -> File?,
    val hooks: YtRenderHooks? = null,
    /** How animations play: the app's live ones, the editor's playhead, or none (still previews). */
    val animMode: YtAnimMode = if (hooks != null) YtAnimMode.EDITOR else YtAnimMode.APP,
) {
    private class Index(
        val doc: YtThemeDoc?,
        val night: Boolean,
        val byKind: Map<YtComponentKind, Map<String, YtNode>>,
        val resolver: YtTokensResolver?,
        val anims: Map<YtComponentKind, YtAnimIndex>,
        val patchMask: Map<YtComponentKind, Int>,
    )

    private val index: State<Index> = derivedStateOf {
        val doc = docOf()
        val night = nightOf()
        val byKind = doc?.let { d -> YtComponentKind.entries.associateWith { k -> YtDocOps.all(d.components[k]).associateBy { it.id } } }.orEmpty()
        val anims = if (doc == null || animMode == YtAnimMode.NONE || doc.animations.isEmpty) {
            emptyMap()
        } else {
            YtComponentKind.entries.associateWith { YtAnimIndex(doc.animations, it) }.filterValues { !it.empty }
        }
        // the states a node of the component has a look for: only these recompose it
        val custom = doc?.animations?.customStates.orEmpty()
        val mask = byKind.mapValues { (_, nodes) -> nodes.values.fold(0) { m, n -> m or YtRenderStates.bitsOf(n.states.keys, custom) } }
        Index(doc, night, byKind, doc?.let { YtTokensResolver(it, night) }, anims, mask)
    }

    /** The animations of component [kind], or null when it has none. */
    fun animIndex(kind: YtComponentKind): YtAnimIndex? = index.value.anims[kind]

    /** The animator owns the press of [kind]: the theme's plain press feel (ytThemePress) steps aside. */
    fun ownsPress(kind: YtComponentKind): Boolean = index.value.anims[kind]?.ownsPress == true

    internal fun patchMask(kind: YtComponentKind): Int = index.value.patchMask[kind] ?: 0

    val doc: YtThemeDoc? get() = index.value.doc
    val env: YtEnv get() = envOf()
    val assets: File? get() = assetsOf()
    val editing: Boolean get() = hooks != null

    /** The resolver of the current look; read in draw for token colours. */
    val resolver: YtTokensResolver? get() = index.value.resolver

    fun rootId(kind: YtComponentKind): String? = index.value.doc?.components?.get(kind)?.id

    /**
     * How far the plain press feel of [kind] gives: the pressed patch's scale on its root, else
     * the motion token; null where the animator owns the press (YtStatePatchRule).
     */
    fun pressScale(kind: YtComponentKind): Float? {
        val doc = index.value.doc ?: return null
        val root = doc.components[kind]
        return YtStatePatchRule.pressScale(root.states[YtStates.PRESSED]?.scale, doc.tokens.motion.pressScale, ownsPress(kind))
    }

    /** The node as it is drawn now (responsive overrides and [states] applied), or null if missing. */
    internal fun resolve(kind: YtComponentKind, id: String, states: Int): YtResolved? {
        val raw = index.value.byKind[kind]?.get(id) ?: return null
        return try {
            val custom = index.value.doc?.animations?.customStates.orEmpty()
            val s = YtStatePatchRule.order(YtRenderStates.names(states, custom), custom)
            // what the animator animates in a state, the state's patch leaves to it
            val anims = index.value.anims[kind]
            val n = if (anims == null) {
                YtResponsive.resolve(raw, envOf(), s)
            } else {
                YtResponsive.resolve(raw, envOf(), s) { state, patch -> YtStatePatchRule.effective(patch, anims.statePropsOf(id, state)) }
            }
            YtResolved(n.copy(children = emptyList()), n.children.map { it.id }, false)
        } catch (e: Exception) {
            YtResolved(raw.copy(children = emptyList()), emptyList(), true)
        }
    }
}

/**
 * Component states as bits, so the renderer's parameters stay stable: the built-in states first
 * (selected and pressed keep their bits of phase 1), then the theme's custom states in their order.
 */
object YtRenderStates {
    const val SELECTED = 1
    const val PRESSED = 2

    /** The built-in states by bit, from bit 0 up. */
    private val order = listOf(
        YtStateNames.SELECTED, YtStateNames.PRESSED, YtStateNames.ACTIVE, YtStateNames.LOADING, YtStateNames.DISABLED,
        YtStateNames.EXPANDED, YtStateNames.COLLAPSED, YtStateNames.FOCUSED,
    )

    fun bitOf(state: String, custom: List<String> = emptyList()): Int {
        val i = order.indexOf(state)
        if (i >= 0) return 1 shl i
        val c = custom.indexOf(state)
        return if (c in 0 until 16) 1 shl (order.size + c) else 0
    }

    fun bitsOf(states: Collection<String>, custom: List<String> = emptyList()): Int = states.fold(0) { b, s -> b or bitOf(s, custom) }

    fun names(bits: Int, custom: List<String> = emptyList()): Set<String> = when (bits) {
        0 -> emptySet()
        SELECTED -> setOf(YtStates.SELECTED)
        PRESSED -> setOf(YtStates.PRESSED)
        else -> buildSet {
            order.forEachIndexed { i, s -> if (bits and (1 shl i) != 0) add(s) }
            custom.forEachIndexed { i, s -> if (i < 16 && bits and (1 shl (order.size + i)) != 0) add(s) }
        }
    }
}

/** A node without its children (they resolve on their own) and the ids of the children. */
@Immutable
internal data class YtResolved(val node: YtNode, val childIds: List<String>, val broken: Boolean)

/** The real data the slots of a component show. */
@Immutable
class YtSlotData(
    val video: StreamItem? = null,
    /** 0..1 of the video watched; read in draw. */
    val progress: () -> Float = { 0f },
    val label: String = "",
    val navItem: YtNavItem? = null,
    val wordmark: String = "",
    val onSearch: (() -> Unit)? = null,
    val onProfile: (() -> Unit)? = null,
    val imageLoader: ImageLoader? = null,
    /** The palette for the watched marks; null in the editor. */
    val palette: YtPalette? = null,
    /** Title and channel the host shows (the mini player's video); null takes the video's. */
    val title: String? = null,
    val channel: String? = null,
    /**
     * The host draws the picture itself (the mini player's video surface): a THUMBNAIL slot keeps
     * its place in the layout but draws nothing (its outline and shadow follow the real picture).
     */
    val hostPicture: Boolean = false,
)

val LocalYtSlots = staticCompositionLocalOf { YtSlotData() }

/** Draws component [kind] of [src] with [slots]; [modifier] goes on its root. */
@Composable
fun YtThemeComponent(
    src: YtThemeSource,
    kind: YtComponentKind,
    slots: YtSlotData,
    modifier: Modifier = Modifier,
    states: Int = 0,
    /** What the host knows for the animations (on screen or not). */
    input: YtAnimInput = YtAnimInput.NONE,
    /** An instance the host made for this component (it drives the root itself), or null for the component's own. */
    hostAnim: YtAnimInstance? = null,
    /** The host draws the root's surface and moves it (the bar): the root draws no style and no animated layer. */
    bareRoot: Boolean = false,
) {
    val root by remember(src, kind) { derivedStateOf { src.rootId(kind) } }
    val id = root ?: return
    val own = if (hostAnim == null) rememberYtAnimInstance(src, kind, states, input) else null
    val anim = hostAnim ?: own
    val mask = src.patchMask(kind)
    val app = src.animMode == YtAnimMode.APP
    // a pressed look is shown at runtime: by the animator's state machine where there is one, else by a watcher.
    // A host that sends the touches itself (the mini player: the page's views take them) gets no
    // pointer input here, it would take the touches from the views
    val pressedLook = app && mask and YtRenderStates.PRESSED != 0
    val hostTouches = input.events
    val touch = anim != null && hostAnim == null && app && hostTouches == null && (src.animIndex(kind)?.touch == true || pressedLook)
    var pressed by remember { mutableStateOf(false) }
    val watchPress = pressedLook && anim == null && hostTouches == null
    if (pressedLook && anim == null && hostTouches != null) {
        LaunchedEffect(hostTouches) {
            hostTouches.collect { e ->
                if (e == YtTriggerKind.TOUCH_DOWN) pressed = true else if (e == YtTriggerKind.TOUCH_UP) pressed = false
            }
        }
    }
    val hostBits = if (app && anim == null) rememberYtHostStateBits(mask) else 0
    // states the animator's state machine set (pressed, loading, custom …) where a node has a look for them
    val all = (if (anim == null) states else states or (anim.stateBits and mask)) or hostBits or (if (anim == null && pressed) YtRenderStates.PRESSED else 0)
    val outer = when {
        touch && anim != null -> modifier.ytAnimTouch(anim)
        watchPress -> modifier.ytPressWatch { pressed = it }
        else -> modifier
    }.then(if (anim != null && app) Modifier.ytAnimShown(anim) else Modifier)
    CompositionLocalProvider(LocalYtSlots provides slots, LocalYtAnim provides anim) {
        YtNodeView(src, kind, id, all, outer, root = true, bare = bareRoot)
    }
}

@Composable
private fun YtNodeView(src: YtThemeSource, kind: YtComponentKind, id: String, states: Int, outer: Modifier = Modifier, root: Boolean = false, bare: Boolean = false) {
    val resolved by remember(src, kind, id, states) {
        derivedStateOf(structuralEqualityPolicy()) { src.resolve(kind, id, states) }
    }
    val r = resolved ?: return
    val node = r.node
    if (!node.visible) return
    val inst = LocalYtAnim.current
    // the instance's index changes when the animations are edited: read its version so this node follows
    val animVersion = inst?.indexVersion ?: 0
    // a bare root (the bar): the host draws its surface and moves it by the root's animation
    val anim = remember(inst, id, animVersion, bare) { if (bare) null else inst?.nodeAnim(id) }
    val density = LocalDensity.current.density
    var modifier = outer.then(YtNodeDataElement(node))
    if (root) modifier = modifier.ytRootSize(node, density)
    val hooks = src.hooks
    if (hooks != null) modifier = modifier.onPlaced { hooks.placed(kind, id, it) }
    modifier = modifier.ytLayer(node, hooks, anim)
    // a mask clips everything the node draws (phase 3)
    val mask = node.mask
    if (mask != null && mask.enabled && !mask.path.isEmpty) modifier = modifier.ytMask(mask)
    if (r.broken) {
        Box(modifier.ytPlaceholder())
        return
    }
    // the host's picture (the mini player's video): its place stays, nothing is drawn there
    if (node.slot == YtSlot.THUMBNAIL && LocalYtSlots.current.hostPicture) {
        Box(modifier)
        return
    }
    val shape = remember(node.style.corners, node.shape, node.shapeSpec, node.path, node.type) { ytNodeShape(node) }
    // the progress slot draws its fills as bar and track itself
    if (bare) {
        // no style: the host draws it
    } else if (node.slot != YtSlot.PROGRESS) {
        modifier = when {
            anim != null && anim.style -> modifier.ytAnimStyle(node.style, src, anim, node)
            ytIsVector(node) -> modifier.ytVectorStyle(node, src)
            else -> modifier.ytStyle(node.style, shape as? YtCornerShape ?: YtCornerShape(node.style.corners), src)
        }
    }
    if (node.layout.clip) modifier = modifier.clip(shape)
    // a size animation: the box (and its fills above) grows, the content keeps its layout
    if (anim != null && anim.size) modifier = modifier.ytAnimSize(anim)
    when (node.type) {
        YtNodeType.CONTAINER -> YtContainer(src, kind, r, states, modifier, density)
        YtNodeType.TEXT -> YtTextLeaf(src, node, node.text?.text.orEmpty(), modifier, anim)
        YtNodeType.SLOT -> YtSlotLeaf(src, node, shape, modifier, anim)
        YtNodeType.ICON -> YtIconLeaf(src, node, YtIcons.of(node.icon), modifier)
        YtNodeType.IMAGE -> YtImageLeaf(src, node, modifier)
        YtNodeType.SHAPE -> Box(modifier.padding(node.padding.start.dp, node.padding.top.dp, node.padding.end.dp, node.padding.bottom.dp))
    }
}

@Composable
private fun YtContainer(src: YtThemeSource, kind: YtComponentKind, r: YtResolved, states: Int, modifier: Modifier, density: Float) {
    val policy = remember(r.node, density, src) { YtMeasure(r.node, density, src.hooks) }
    Layout(
        content = { r.childIds.forEach { cid -> key(cid) { YtNodeView(src, kind, cid, states) } } },
        modifier = modifier,
        measurePolicy = policy,
    )
}

/** Lays out a container's children with YtLayoutEngine; falls back to a stack if it fails. */
private class YtMeasure(val node: YtNode, val density: Float, val hooks: YtRenderHooks?) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val children = measurables.map { (it.parentData as? YtNodeData)?.theme ?: YtNode("?") }
        val placeables = arrayOfNulls<Placeable>(measurables.size)
        val result = try {
            YtLayoutEngine.layout(node, children, constraints.toBox(), density) { i, b ->
                val p = measurables[i].measure(b.toConstraints())
                placeables[i] = p
                YtLayoutEngine.Size(p.width.toFloat(), p.height.toFloat())
            }
        } catch (e: Exception) {
            null
        }
        if (result == null) {
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            measurables.forEachIndexed { i, m -> if (placeables[i] == null) placeables[i] = m.measure(loose) }
            val w = placeables.maxOfOrNull { it?.width ?: 0 } ?: 0
            val h = placeables.maxOfOrNull { it?.height ?: 0 } ?: 0
            return layout(w.coerceIn(constraints.minWidth, constraints.maxWidth), h.coerceIn(constraints.minHeight, constraints.maxHeight)) {
                placeables.forEach { it?.placeRelative(0, 0) }
            }
        }
        hooks?.conflicts(node.id, result.conflicts)
        val w = result.width.roundToInt().coerceIn(constraints.minWidth, constraints.maxWidth)
        val h = result.height.roundToInt().coerceIn(constraints.minHeight, constraints.maxHeight)
        return layout(w, h) {
            result.places.forEachIndexed { i, p ->
                val pl = placeables[i]
                if (p != null && pl != null) {
                    // the editor's reorder settles here: read in placement, a frame only places again
                    val shift = hooks?.placeOffset(node.id, children[i].id, p.x, p.y) ?: androidx.compose.ui.unit.IntOffset.Zero
                    pl.placeRelative(p.x.roundToInt() + shift.x, p.y.roundToInt() + shift.y, children[i].z.toFloat())
                }
            }
        }
    }
}

// Constraints can hold both sides up to 15 bits at once; theme sizes stay far below
private const val MAX_PX = (1 shl 15) - 1

private fun Constraints.toBox() = YtLayoutEngine.Box(
    minWidth.toFloat(),
    if (hasBoundedWidth) maxWidth.toFloat() else YtLayoutEngine.INF,
    minHeight.toFloat(),
    if (hasBoundedHeight) maxHeight.toFloat() else YtLayoutEngine.INF,
)

internal fun YtLayoutEngine.Box.toConstraints(): Constraints {
    fun px(v: Float) = if (v.isNaN()) 0 else v.roundToInt().coerceIn(0, MAX_PX)
    val minW = px(minW)
    val minH = px(minH)
    val maxW = if (maxW == YtLayoutEngine.INF) Constraints.Infinity else px(maxW).coerceAtLeast(minW)
    val maxH = if (maxH == YtLayoutEngine.INF) Constraints.Infinity else px(maxH).coerceAtLeast(minH)
    return Constraints(minW, maxW, minH, maxH)
}

/** The root's own size rules against what its host offers. */
private fun Modifier.ytRootSize(node: YtNode, density: Float): Modifier = layout { m, c ->
    val box = runCatching {
        YtLayoutEngine.boxFor(
            node,
            if (c.hasBoundedWidth) c.maxWidth.toFloat() else YtLayoutEngine.INF,
            if (c.hasBoundedHeight) c.maxHeight.toFloat() else YtLayoutEngine.INF,
            density,
        ).toConstraints()
    }.getOrDefault(c)
    // never larger than the host allows
    val inner = Constraints(
        box.minWidth.coerceIn(c.minWidth, c.maxWidth),
        if (box.hasBoundedWidth) box.maxWidth.coerceIn(c.minWidth, c.maxWidth) else c.maxWidth,
        box.minHeight.coerceIn(c.minHeight, c.maxHeight),
        if (box.hasBoundedHeight) box.maxHeight.coerceIn(c.minHeight, c.maxHeight) else c.maxHeight,
    )
    val p = m.measure(inner)
    layout(p.width, p.height) { p.placeRelative(0, 0) }
}

// ---- parent data ----

internal class YtNodeData(var theme: YtNode) : ParentDataModifierNode, Modifier.Node() {
    override fun Density.modifyParentData(parentData: Any?): Any = this@YtNodeData
}

private data class YtNodeDataElement(val node: YtNode) : ModifierNodeElement<YtNodeData>() {
    override fun create() = YtNodeData(node)
    override fun update(node: YtNodeData) {
        if (node.theme == this.node) return
        node.theme = this.node
        // the parent's measure reads the node's rules from its parent data: it must measure again, else
        // the parent keeps the sizes of the old node (items too small, siblings that overlap)
        node.invalidateParentData()
    }
}

// ---- layer, style ----

/**
 * Rotation, opacity, the animator's transform, opacity and blur and, in the editor, the drag
 * offset and the lift, all read in the layer (a frame of an animation runs this block only).
 */
private fun Modifier.ytLayer(node: YtNode, hooks: YtRenderHooks?, anim: YtNodeAnim? = null): Modifier {
    val opacity = node.style.opacity
    val rotation = node.rotation
    val animated = anim != null && anim.layer
    if (hooks == null && opacity >= 1f && rotation == 0f && !animated) return this
    val id = node.id
    return graphicsLayer {
        alpha = opacity
        rotationZ = rotation
        if (animated && anim != null) {
            anim.inst.frame.intValue
            val n = anim.node
            alpha = (opacity * n.float(YtAnimProp.OPACITY)).coerceIn(0f, 1f)
            rotationZ = rotation + n.float(YtAnimProp.ROTATION)
            translationX = n.float(YtAnimProp.X) * density
            translationY = n.float(YtAnimProp.Y) * density
            scaleX = n.float(YtAnimProp.SCALE_X)
            scaleY = n.float(YtAnimProp.SCALE_Y)
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(n.float(YtAnimProp.PIVOT_X), n.float(YtAnimProp.PIVOT_Y))
            if (anim.blur) renderEffect = anim.blurOf(n.float(YtAnimProp.BLUR) * density)
            // along a motion path (phase 3): moved by the path, turned with it or held at its angle
            if (anim.motion && n.animated(YtAnimProp.PATH)) {
                val out = anim.motionOut
                anim.vector.motion(n.channel(YtAnimProp.PATH)?.owner)?.let { m ->
                    m.at(n.float(YtAnimProp.PATH), out)
                    translationX += out[0] * density
                    translationY += out[1] * density
                    rotationZ += out[2]
                }
            }
        }
        if (hooks != null) {
            translationX += hooks.dragX(id)
            translationY += hooks.dragY(id)
            val lift = hooks.lift(id)
            if (lift > 0f) {
                scaleX *= 1f + 0.03f * lift
                scaleY *= 1f + 0.03f * lift
                shadowElevation = 12f * density * lift
            }
        }
    }
}

/** Shadows, fills and strokes of [style] in [shape]; everything is built once per size and style. */
private fun Modifier.ytStyle(style: YtStyle, shape: YtCornerShape, src: YtThemeSource): Modifier {
    val fills = style.fills.filter { it.enabled }
    val strokes = style.strokes.filter { it.enabled && it.width > 0f }
    val shadows = style.shadows.filter { it.enabled }
    if (fills.isEmpty() && strokes.isEmpty() && shadows.isEmpty()) return this
    return drawWithCache { buildStyle(fills, strokes, shadows, style.corners, shape, src) }
}

private fun CacheDrawScope.buildStyle(
    fills: List<YtPaint>,
    strokes: List<YtStroke>,
    shadows: List<YtShadow>,
    corners: YtCorners,
    shape: YtCornerShape,
    src: YtThemeSource,
): DrawResult {
    val t = src.resolver
    val outline = shape.createOutline(size, layoutDirection, this)
    val brushes = try {
        fills.map { p ->
            if (p.type == YtPaintType.IMAGE) imageBrush(p, src, size) else YtBuilderPaint.brush(t?.resolve(p) ?: p, size)
        }
    } catch (e: Exception) {
        emptyList()
    }
    val strokeDraws = try {
        strokes.map { s ->
            val w = s.width * density
            // inside the edge: the outline inset by half the width
            val inset = YtCornerShape(corners.inset(s.width / 2f)).createOutline(Size((size.width - w).coerceAtLeast(0f), (size.height - w).coerceAtLeast(0f)), layoutDirection, this)
            Triple(inset, YtBuilderPaint.brush(t?.resolve(s.paint) ?: s.paint, size), w)
        }
    } catch (e: Exception) {
        emptyList()
    }
    val shadowPaths = try {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) emptyList() else shadows.map { s -> shadowDraw(s, corners, layoutDirection, this, outline) }
    } catch (e: Exception) {
        emptyList()
    }
    return onDrawWithContent {
        try {
            for (s in shadowPaths) if (!s.inner) drawShadow(s)
            for (b in brushes) drawOutline(outline, b)
            for (s in shadowPaths) if (s.inner) drawShadow(s)
        } catch (e: Exception) {
            // a paint the canvas cannot take: the node stays without it
        }
        drawContent()
        try {
            for ((o, b, w) in strokeDraws) translate(w / 2f, w / 2f) { drawOutline(o, b, style = Stroke(w)) }
        } catch (e: Exception) {
            // see above
        }
    }
}

/** An image fill covering [size]; its placeholder colour until the image is loaded (the cache read redraws it then). */
internal fun imageBrush(p: YtPaint, src: YtThemeSource, size: Size): androidx.compose.ui.graphics.Brush {
    YtBuilderImages.version
    val name = p.image
    val dir = src.assets
    val bitmap = if (name != null && dir != null && YtThemeZip.safeAssetName(name)) YtBuilderImages.get(File(dir, name)) else null
    return if (bitmap != null) YtBuilderImages.cover(bitmap, size) else androidx.compose.ui.graphics.SolidColor(Color(p.color))
}

private fun YtCorners.inset(dp: Float) = copy(
    topStart = (topStart - dp).coerceAtLeast(0f), topEnd = (topEnd - dp).coerceAtLeast(0f),
    bottomEnd = (bottomEnd - dp).coerceAtLeast(0f), bottomStart = (bottomStart - dp).coerceAtLeast(0f),
)

private class ShadowDraw(val path: Path, val clip: Path?, val paint: android.graphics.Paint, val inner: Boolean)

/**
 * A shadow as a blurred copy of the outline, grown by its spread and moved by its offset. A drop
 * shadow is cut out where the element is (so it never shows through a translucent fill); an inner
 * shadow is the frame around the moved outline, clipped to the element.
 */
private fun shadowDraw(s: YtShadow, corners: YtCorners, dir: LayoutDirection, density: Density, outline: Outline): ShadowDraw {
    val d = density.density
    val spread = if (s.inner) -s.spread else s.spread
    val grown = corners.copy(
        topStart = (corners.topStart + spread).coerceAtLeast(0f), topEnd = (corners.topEnd + spread).coerceAtLeast(0f),
        bottomEnd = (corners.bottomEnd + spread).coerceAtLeast(0f), bottomStart = (corners.bottomStart + spread).coerceAtLeast(0f),
    )
    val bounds = outline.bounds
    val sp = spread * d
    val moved = YtCornerShape(grown).createOutline(Size((bounds.width + 2 * sp).coerceAtLeast(1f), (bounds.height + 2 * sp).coerceAtLeast(1f)), dir, density)
    val shapePath = Path().apply { addOutline(outline) }
    val path = Path().apply {
        addOutline(moved)
        translate(Offset(s.x * d - sp, s.y * d - sp))
    }
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = s.color
        if (s.blur > 0f) maskFilter = BlurMaskFilter(s.blur * d / 2f, BlurMaskFilter.Blur.NORMAL)
    }
    return if (s.inner) {
        val frame = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(androidx.compose.ui.geometry.Rect(-bounds.width - 400f * d, -bounds.height - 400f * d, bounds.width * 2 + 400f * d, bounds.height * 2 + 400f * d))
            addPath(path)
        }
        ShadowDraw(frame, shapePath, paint, true)
    } else {
        ShadowDraw(path, shapePath, paint, false)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawShadow(s: ShadowDraw) {
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        val count = native.save()
        val clip = s.clip?.asAndroid()
        if (clip != null) {
            if (s.inner) native.clipPath(clip) else native.clipOutPath(clip)
        }
        native.drawPath(s.path.asAndroid(), s.paint)
        native.restoreToCount(count)
    }
}

private fun Path.asAndroid(): android.graphics.Path = (this as androidx.compose.ui.graphics.AndroidPath).internalPath

/** A hatched box where a node could not be drawn. */
private fun Modifier.ytPlaceholder(): Modifier = drawBehind {
    drawRect(Color(0x33FF3B30))
    val step = 10.dp.toPx()
    var x = -size.height
    while (x < size.width) {
        drawLine(Color(0x99FF3B30), Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = 1.dp.toPx())
        x += step
    }
}

// ---- leaves ----

private fun Modifier.ytPadding(node: YtNode) =
    if (node.padding == YtInsets()) this else padding(node.padding.start.dp, node.padding.top.dp, node.padding.end.dp, node.padding.bottom.dp)

@Composable
private fun YtTextLeaf(src: YtThemeSource, node: YtNode, text: String, modifier: Modifier, anim: YtNodeAnim? = null) {
    val spec = node.text ?: YtTextSpec()
    // only the type token's values compose: a colour or another node changing does not
    val style by remember(src, spec) {
        derivedStateOf(structuralEqualityPolicy()) {
            val t = src.resolver
            runCatching { t?.let { YtBuilderPaint.textStyle(spec, it, src.assets, colorOverride = Color.Unspecified) } }.getOrNull() ?: androidx.compose.ui.text.TextStyle.Default
        }
    }
    val caps by remember(src, spec.styleRef) { derivedStateOf { src.resolver?.type(spec.styleRef)?.caps == true } }
    BasicText(
        text = if (caps) text.uppercase() else text,
        modifier = modifier.ytPadding(node),
        style = style,
        maxLines = spec.maxLines.coerceAtLeast(1),
        overflow = TextOverflow.Ellipsis,
        // the colour is read in draw: a token colour that changes (or an animated one) draws again without recomposing
        color = {
            if (anim != null && anim.text && anim.inst.frame.intValue >= 0 && anim.node.animated(YtAnimProp.TEXT_COLOR)) {
                Color(anim.node.argb(YtAnimProp.TEXT_COLOR, 0))
            } else {
                src.resolver?.let { YtBuilderPaint.color(it.resolve(spec.color)) } ?: Color.Unspecified
            }
        },
    )
}

@Composable
private fun YtIconLeaf(src: YtThemeSource, node: YtNode, icon: Int, modifier: Modifier) {
    val tint by rememberTint(src, node.text?.color ?: YtPaint.token(YtRole.TEXT))
    Image(
        painter = painterResource(icon),
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier.ytPadding(node),
    )
}

/** An icon's colour, composing again only when that colour changes. */
@Composable
private fun rememberTint(src: YtThemeSource, paint: YtPaint): State<Color> = remember(src, paint) {
    derivedStateOf(structuralEqualityPolicy()) { src.resolver?.let { YtBuilderPaint.color(it.resolve(paint)) } ?: Color.White }
}

@Composable
private fun YtImageLeaf(src: YtThemeSource, node: YtNode, modifier: Modifier) {
    val name = node.icon
    val dir = src.assets
    val file = remember(name, dir) { if (name != null && dir != null && YtThemeZip.safeAssetName(name)) File(dir, name) else null }
    if (file == null) {
        Box(modifier.ytPlaceholder())
        return
    }
    AsyncImage(
        model = file,
        imageLoader = LocalYtSlots.current.imageLoader ?: coil3.SingletonImageLoader.get(androidx.compose.ui.platform.LocalContext.current),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}

@Composable
private fun YtSlotLeaf(src: YtThemeSource, node: YtNode, shape: androidx.compose.ui.graphics.Shape, modifier: Modifier, anim: YtNodeAnim? = null) {
    val data = LocalYtSlots.current
    val video = data.video
    val live = !src.editing
    when (node.slot) {
        YtSlot.THUMBNAIL -> {
            val loader = data.imageLoader
            if (video == null || loader == null) {
                Box(modifier)
            } else {
                Box(modifier) {
                    YtLargeThumbnail(
                        video, loader,
                        Modifier.fillMaxSize().clip(shape).then(if (live && data.palette != null) Modifier.ytSeen(video, data.palette) else Modifier),
                    )
                }
            }
        }
        YtSlot.AVATAR -> {
            val loader = data.imageLoader
            Box(modifier.clip(shape)) {
                if (video?.uploaderAvatar != null && loader != null) {
                    AsyncImage(
                        model = video.uploaderAvatar,
                        imageLoader = loader,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        YtSlot.TITLE -> YtTextLeaf(src, node, data.title ?: video?.ytTitle ?: stringResource(R.string.yt_builder_sample_title), modifier, anim)
        YtSlot.CHANNEL -> YtTextLeaf(src, node, data.channel ?: video?.uploaderName ?: stringResource(R.string.yt_builder_sample_channel), modifier, anim)
        YtSlot.DURATION -> {
            val text = when {
                video == null -> "12:34"
                video.isLive -> stringResource(R.string.yt_live_now)
                else -> ytDuration(video).orEmpty()
            }
            YtTextLeaf(src, node, text, modifier, anim)
        }
        YtSlot.META -> {
            val text = if (video == null) stringResource(R.string.yt_builder_sample_meta) else listOfNotNull(video.uploaderName, ytMeta(video, " · ").ifEmpty { null }).joinToString(" · ")
            YtTextLeaf(src, node, text, modifier, anim)
        }
        YtSlot.WORDMARK -> YtTextLeaf(src, node, data.wordmark.ifEmpty { src.doc?.meta?.wordmark.orEmpty() }, modifier, anim)
        YtSlot.NAV_LABEL -> YtTextLeaf(src, node, data.navItem?.let { stringResource(it.label) } ?: data.label, modifier, anim)
        YtSlot.CHIP_LABEL -> YtTextLeaf(src, node, data.label, modifier, anim)
        YtSlot.PROGRESS -> {
            val fills = node.style.fills.filter { it.enabled }
            Box(
                modifier.clearAndSetSemantics {}.drawBehind {
                    val f = if (live) data.progress().coerceIn(0f, 1f) else 0.4f
                    if (f <= 0f) return@drawBehind
                    val t = src.resolver ?: return@drawBehind
                    try {
                        fills.getOrNull(1)?.let { drawRect(YtBuilderPaint.brush(t.resolve(it), size)) }
                        val bar = fills.firstOrNull() ?: YtPaint.token(YtRole.ACCENT)
                        drawRect(YtBuilderPaint.brush(t.resolve(bar), size), size = Size(size.width * f, size.height))
                    } catch (e: Exception) {
                        // nothing to draw
                    }
                },
            )
        }
        YtSlot.SEARCH -> YtActionIcon(src, node, R.drawable.ic_yt_search, if (live) data.onSearch else null, R.string.yt_builder_slot_search, modifier)
        YtSlot.PROFILE -> YtActionIcon(src, node, R.drawable.ic_yt_person, if (live) data.onProfile else null, R.string.yt_builder_slot_profile, modifier)
        YtSlot.NAV_ICON -> {
            val item = data.navItem
            val tint by rememberTint(src, node.text?.color ?: YtPaint.token(YtRole.TEXT))
            if (item == null || item.icon == null) {
                Box(modifier) { YtShortsGlyph(tint, filled = false, modifier = Modifier.fillMaxSize()) }
            } else {
                Image(
                    painter = painterResource(item.icon),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(tint),
                    modifier = modifier,
                )
            }
        }
        null -> Box(modifier)
    }
}

@Composable
private fun YtActionIcon(src: YtThemeSource, node: YtNode, icon: Int, onClick: (() -> Unit)?, label: Int, modifier: Modifier) {
    val tint by rememberTint(src, node.text?.color ?: YtPaint.token(YtRole.TEXT))
    val description = stringResource(label)
    Box(
        modifier
            .then(
                if (onClick != null) {
                    Modifier
                        .semantics {
                            contentDescription = description
                            role = Role.Button
                        }
                        .clickable(onClick = onClick)
                } else {
                    Modifier
                },
            ),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Image(painterResource(icon), contentDescription = null, colorFilter = ColorFilter.tint(tint), modifier = Modifier.padding(9.dp).fillMaxSize())
    }
}

/** The glyphs an ICON node can show, by name. */
object YtIcons {
    val names = listOf("search", "person", "home", "subs", "heart", "bookmark", "grid", "live", "play", "star", "dots", "check", "news", "send", "timer", "tune", "users", "eye")

    fun of(name: String?): Int = when (name) {
        "search" -> R.drawable.ic_yt_search
        "person" -> R.drawable.ic_yt_person
        "home" -> R.drawable.ic_yt_nav_home
        "subs" -> R.drawable.ic_yt_nav_subs
        "heart" -> R.drawable.ic_yt_heart
        "bookmark" -> R.drawable.ic_yt_nav_bookmark
        "grid" -> R.drawable.ic_yt_grid
        "live" -> R.drawable.ic_yt_nav_live
        "play" -> R.drawable.ic_yt_play_round
        "dots" -> R.drawable.ic_yt_dots
        "check" -> R.drawable.ic_yt_check
        "news" -> R.drawable.ic_yt_news
        "send" -> R.drawable.ic_yt_send
        "timer" -> R.drawable.ic_yt_timer
        "tune" -> R.drawable.ic_yt_tune
        "users" -> R.drawable.ic_yt_users
        "eye" -> R.drawable.ic_yt_eye
        else -> R.drawable.ic_yt_star
    }
}
