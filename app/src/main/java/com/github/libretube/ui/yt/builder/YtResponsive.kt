package com.github.libretube.ui.yt.builder

/**
 * YT: what the theme looks like in one environment (pure): which overrides of a node apply to a
 * window size, orientation and font scale, and the patch of a component state laid over it.
 *
 * Order: the node's own values, then each matching override from the least specific (fewest
 * conditions) to the most specific, in file order where they tie; later wins. A state patch
 * (selected, pressed) comes last. So "landscape" and then "expanded + landscape" both apply,
 * and the latter wins where they disagree.
 */
data class YtEnv(
    val widthDp: Float,
    val heightDp: Float,
    val fontScale: Float = 1f,
    val dark: Boolean = true,
) {
    val widthClass: YtWidthClass
        get() = when {
            widthDp < 600f -> YtWidthClass.COMPACT
            widthDp < 840f -> YtWidthClass.MEDIUM
            else -> YtWidthClass.EXPANDED
        }

    val orientation: YtOrientation
        get() = if (widthDp > heightDp) YtOrientation.LANDSCAPE else YtOrientation.PORTRAIT

    companion object {
        val PHONE = YtEnv(393f, 852f)
    }
}

object YtResponsive {
    fun matches(o: YtOverride, env: YtEnv): Boolean =
        (o.width == null || o.width == env.widthClass) &&
            (o.orientation == null || o.orientation == env.orientation) &&
            (o.minFontScale == null || env.fontScale >= o.minFontScale)

    fun specificity(o: YtOverride): Int =
        (if (o.width != null) 1 else 0) + (if (o.orientation != null) 1 else 0) + (if (o.minFontScale != null) 1 else 0)

    /** The overrides of [node] that apply in [env], in the order they are laid on. */
    fun applying(node: YtNode, env: YtEnv): List<YtOverride> =
        node.overrides.withIndex().filter { matches(it.value, env) }
            .sortedWith(compareBy({ specificity(it.value) }, { it.index }))
            .map { it.value }

    /**
     * [node] as it is in [env] and [states] (children untouched: each resolves its own). The
     * state patches are laid on in the order of [states]; [patchOf] may trim one first (what the
     * animator animates in that state, YtStatePatchRule).
     */
    fun resolve(node: YtNode, env: YtEnv, states: Collection<String> = emptySet(), patchOf: (String, YtPatch) -> YtPatch = { _, p -> p }): YtNode {
        if (node.overrides.isEmpty() && (states.isEmpty() || node.states.isEmpty())) return node
        var n = node
        for (o in applying(node, env)) n = apply(n, o.patch)
        for (s in states) node.states[s]?.let { n = apply(n, patchOf(s, it)) }
        return n
    }

    fun apply(n: YtNode, p: YtPatch): YtNode = n.copy(
        width = p.width ?: n.width,
        height = p.height ?: n.height,
        x = p.x ?: n.x,
        y = p.y ?: n.y,
        rotation = p.rotation ?: n.rotation,
        visible = p.visible ?: n.visible,
        padding = p.padding ?: n.padding,
        layout = n.layout.copy(
            gap = p.gap ?: n.layout.gap,
            mode = p.mode ?: n.layout.mode,
            columns = p.columns ?: n.layout.columns,
        ),
        style = n.style.copy(
            opacity = p.opacity ?: n.style.opacity,
            corners = p.corners ?: n.style.corners,
            fills = p.fills ?: n.style.fills,
            strokes = p.strokes ?: n.style.strokes,
        ),
        text = n.text?.let { t ->
            t.copy(color = p.textColor ?: t.color, size = p.textSize ?: t.size, maxLines = p.maxLines ?: t.maxLines)
        },
    )

    /**
     * The patch that makes the override for [cond] hold the given values: an edit made while the
     * editor previews a breakpoint goes there instead of into the node's base values.
     */
    fun withOverride(node: YtNode, cond: YtOverride, change: (YtPatch) -> YtPatch): YtNode {
        val at = node.overrides.indexOfFirst {
            it.width == cond.width && it.orientation == cond.orientation && it.minFontScale == cond.minFontScale
        }
        val list = node.overrides.toMutableList()
        if (at >= 0) list[at] = list[at].copy(patch = change(list[at].patch)) else list += cond.copy(patch = change(YtPatch()))
        return node.copy(overrides = list)
    }
}
