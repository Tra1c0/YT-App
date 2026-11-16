package com.github.libretube.ui.yt.builder

import android.graphics.BlurMaskFilter
import android.os.Build
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/*
 * YT: vector shapes, masks and morphs in the renderer (phase 3). The app and the editor draw
 * through the same code (YtThemeRender), so a masked image or a drawn path looks the same in both.
 *
 * Efficiency: a node's outline is built once per size and shape (drawWithCache; the commands are
 * kept in a YtPathCache and replayed into a reused path). While the editor drags points of a
 * path, the live path is read inside the cache block: only that node's outline is rebuilt, nothing
 * composes. A morph writes its frame into a reused native path without allocating.
 */

/** Whether [node] draws a vector outline (a SHAPE that is not one of the cornered kinds). */
internal fun ytIsVector(node: YtNode): Boolean = node.type == YtNodeType.SHAPE && !node.shape.cornered

/** The outline of a vector node in a box of [w] × [h] px ([density] px per dp), into [cache]. */
internal fun ytVectorOutline(target: YtMorphTarget, live: YtPath?, w: Float, h: Float, density: Float, cache: YtPathCache) {
    val d = density.coerceAtLeast(0.01f)
    if (target.shape == YtShapeKind.PATH) {
        val p = live ?: target.path ?: YtShapeGeom.rectPath(1f, 1f, YtCorners())
        cache.rebuild(p, w, h)
        return
    }
    // parametric: built in dp (corner rounding is in dp), drawn stretched to px
    val p = YtShapeGeom.pathOf(target, w / d, h / d)
    cache.invalidate()
    cache.rebuild(p, w, h)
}

/** Replays [cache] into [out] (rewound first). */
internal fun YtPathCache.into(out: Path) {
    out.rewind()
    replay(out::moveTo, out::lineTo, out::cubicTo, out::close)
}

internal fun YtPathCache.into(out: android.graphics.Path) {
    out.rewind()
    replay(out::moveTo, out::lineTo, out::cubicTo, out::close)
}

/** A native path as the sink of a morph. */
internal class YtNativeSink(val path: android.graphics.Path) : YtPathSink {
    override fun move(x: Float, y: Float) = path.moveTo(x, y)
    override fun line(x: Float, y: Float) = path.lineTo(x, y)
    override fun cubic(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) = path.cubicTo(x1, y1, x2, y2, x, y)
    override fun close() = path.close()
}

/** The outline of a vector node as a Compose shape (for clips and the editor's checks). */
class YtVectorShape(private val target: YtMorphTarget, private val rule: YtFillRule) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val cache = YtPathCache()
        ytVectorOutline(target, null, size.width, size.height, density.density, cache)
        val p = Path()
        cache.into(p)
        p.fillType = if (rule == YtFillRule.EVEN_ODD) PathFillType.EvenOdd else PathFillType.NonZero
        return Outline.Generic(p)
    }

    override fun equals(other: Any?): Boolean = other is YtVectorShape && other.target == target && other.rule == rule
    override fun hashCode(): Int = target.hashCode() * 31 + rule.hashCode()
}

/** The shape a node clips and is outlined with: its vector outline, or its corners. */
internal fun ytNodeShape(node: YtNode): Shape =
    if (ytIsVector(node)) YtVectorShape(YtShapeGeom.targetOf(node), node.path?.fillRule ?: YtFillRule.NON_ZERO) else YtCornerShape(node.style.corners)

/**
 * Fills, strokes and drop shadows of a vector node. Strokes run along the outline (centred, round
 * joins and caps: what "Kontur in Form umwandeln" turns into a shape). In the editor, [hooks] may
 * hand a live path (points being dragged; read here, so only this node's outline is rebuilt) and
 * ask for the reduced preview (outline only) while frames run over budget.
 */
internal fun Modifier.ytVectorStyle(node: YtNode, src: YtThemeSource): Modifier = drawWithCache {
    try {
        buildVector(node, src)
    } catch (e: Exception) {
        onDrawWithContent { drawContent() }
    }
}

private fun CacheDrawScope.buildVector(node: YtNode, src: YtThemeSource): DrawResult {
    val hooks = src.hooks
    // a state read: the cache is built again when the dragged points move
    val live = hooks?.livePath(node.id)
    val cache = YtPathCache()
    ytVectorOutline(YtShapeGeom.targetOf(node), live, size.width, size.height, density, cache)
    val path = Path()
    cache.into(path)
    val rule = (live ?: node.path)?.fillRule ?: YtFillRule.NON_ZERO
    path.fillType = if (rule == YtFillRule.EVEN_ODD) PathFillType.EvenOdd else PathFillType.NonZero
    val style = node.style
    val t = src.resolver
    val fills = style.fills.filter { it.enabled }.mapNotNull { p ->
        runCatching { if (p.type == YtPaintType.IMAGE) imageBrush(p, src, size) else YtBuilderPaint.brush(t?.resolve(p) ?: p, size) }.getOrNull()
    }
    val strokes = style.strokes.filter { it.enabled && it.width > 0f }.mapNotNull { s ->
        runCatching { YtBuilderPaint.brush(t?.resolve(s.paint) ?: s.paint, size) to Stroke(s.width * density, cap = StrokeCap.Round, join = StrokeJoin.Round) }.getOrNull()
    }
    val native = android.graphics.Path()
    cache.into(native)
    native.fillType = if (rule == YtFillRule.EVEN_ODD) android.graphics.Path.FillType.EVEN_ODD else android.graphics.Path.FillType.WINDING
    val shadows = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) emptyList() else style.shadows.filter { it.enabled && !it.inner }.map { s ->
        s to android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = s.color
            if (s.blur > 0f) maskFilter = BlurMaskFilter(s.blur * density / 2f, BlurMaskFilter.Blur.NORMAL)
        }
    }
    val hair = Stroke(1.5f * density)
    return onDrawWithContent {
        // only the dragged node asks (the others never read the budget state)
        if (live != null && hooks?.reduced() == true) {
            // the reduced preview while dragging over budget: the outline only
            drawPath(path, Color(0xFF4C8DFF), style = hair)
            drawContent()
            return@onDrawWithContent
        }
        try {
            if (shadows.isNotEmpty()) drawIntoCanvas { canvas ->
                val nc = canvas.nativeCanvas
                val save = nc.save()
                nc.clipOutPath(native)
                for ((s, paint) in shadows) {
                    nc.translate(s.x * density, s.y * density)
                    nc.drawPath(native, paint)
                    nc.translate(-s.x * density, -s.y * density)
                }
                nc.restoreToCount(save)
            }
            for (b in fills) drawPath(path, b)
        } catch (e: Exception) {
            // a paint the canvas cannot take: the node stays without it
        }
        drawContent()
        try {
            for ((b, st) in strokes) drawPath(path, b, style = st)
        } catch (e: Exception) {
            // see above
        }
    }
}

/**
 * A mask: only what lies inside [mask]'s path (stretched to the node's box) is drawn, or with
 * invert only what lies outside. Applies to everything the node draws: fills, images, children.
 */
internal fun Modifier.ytMask(mask: YtMask): Modifier = drawWithCache {
    val cache = YtPathCache()
    cache.rebuild(mask.path, size.width, size.height)
    val p = Path()
    cache.into(p)
    p.fillType = if (mask.path.fillRule == YtFillRule.EVEN_ODD) PathFillType.EvenOdd else PathFillType.NonZero
    onDrawWithContent {
        clipPath(p, if (mask.invert) ClipOp.Difference else ClipOp.Intersect) { this@onDrawWithContent.drawContent() }
    }
}

/**
 * The morphs and motion paths of one node's animations (phase 3), by animation id. Morph plans
 * are made per box size (the first frame of a size) and kept.
 */
internal class YtVectorAnims(node: String, items: List<YtAnim>) {
    private val motions: Map<String, YtMotionPathEval> = items.filter { it.node == node && it.motion != null && it.tracks.any { t -> t.prop == YtAnimProp.PATH } }
        .associate { it.id to YtMotionPathEval(it.motion!!) }
    private val morphs: Map<String, YtMorphTarget> = items.filter { it.node == node && it.morph != null && it.tracks.any { t -> t.prop == YtAnimProp.MORPH } }
        .associate { it.id to it.morph!! }
    private val plans = HashMap<String, YtMorph.Plan>()
    private var planW = -1f
    private var planH = -1f

    val hasMotion: Boolean get() = motions.isNotEmpty()
    val hasMorph: Boolean get() = morphs.isNotEmpty()

    /** The motion path of the animation that last drove PATH (or the first one). */
    fun motion(owner: String?): YtMotionPathEval? = owner?.let { motions[it] } ?: motions.values.firstOrNull()

    fun morphTarget(owner: String?): YtMorphTarget? = owner?.let { morphs[it] } ?: morphs.values.firstOrNull()

    /** The morph from [from] to the target of [owner] in a [w] × [h] dp box. */
    fun plan(owner: String?, from: YtMorphTarget, w: Float, h: Float): YtMorph.Plan? {
        val id = owner?.takeIf { it in morphs } ?: morphs.keys.firstOrNull() ?: return null
        if (w != planW || h != planH) {
            plans.clear()
            planW = w
            planH = h
        }
        return plans.getOrPut(id) { YtMorph.plan(from, morphs.getValue(id), w, h) }
    }
}
