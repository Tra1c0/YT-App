package com.github.libretube.ui.yt.builder

/**
 * YT: the editor's checks of a theme (pure): text that does not read on what lies below it
 * (aim 4.5:1, YtContrast's rule; Pitfall P1), controls smaller than 48 dp, layout conflicts.
 * Bounds come from the canvas (px); [density] turns them into dp.
 */
object YtEditorChecks {
    const val MIN_CONTRAST = 4.5
    const val MIN_TOUCH_DP = 48f

    enum class Kind { CONTRAST, TOUCH, LAYOUT }

    data class Warning(val kind: Kind, val component: YtComponentKind, val nodeId: String, val value: Double)

    /** Nodes whose text is read: TEXT nodes and text slots. */
    private fun YtNode.isText() = type == YtNodeType.TEXT || (type == YtNodeType.SLOT && slot?.text == true)

    /** Controls the finger must hit. */
    private fun interactive(kind: YtComponentKind, node: YtNode, root: Boolean) =
        node.slot == YtSlot.SEARCH || node.slot == YtSlot.PROFILE ||
            (root && (kind == YtComponentKind.CHIP || kind == YtComponentKind.NAV_TAB))

    /**
     * Contrast of every text against the nearest fill above it in the tree (or the background):
     * colours as the eye sees them (alpha laid over what is below), gradients by their first stop.
     */
    fun contrast(doc: YtThemeDoc, night: Boolean, kinds: Collection<YtComponentKind> = YtComponentKind.entries): List<Warning> {
        val t = YtTokensResolver(doc, night)
        val background = t.argb(YtRole.BACKGROUND) or 0xFF000000.toInt()
        val out = mutableListOf<Warning>()
        for (kind in kinds) {
            fun walk(n: YtNode, below: Int) {
                val fill = n.style.fills.lastOrNull { it.enabled }?.let { YtColor.over(t.argb(it), below) } ?: below
                if (n.isText()) {
                    val spec = n.text ?: YtTextSpec()
                    val ink = YtColor.over(t.resolve(spec.color).let { if (it.type == YtPaintType.SOLID) it.color else it.stops.first().color }, fill)
                    val ratio = YtColor.contrast(ink, fill)
                    if (ratio < MIN_CONTRAST) out += Warning(Kind.CONTRAST, kind, n.id, ratio)
                }
                // states change colours too: a selected chip's label against its selected fill
                for ((_, patch) in n.states) {
                    val sFill = patch.fills?.lastOrNull { it.enabled }?.let { YtColor.over(t.argb(it), below) } ?: fill
                    n.children.filter { it.isText() }.forEach { c ->
                        val cp = c.states.values.firstOrNull()?.textColor ?: c.text?.color ?: return@forEach
                        val ink = YtColor.over(t.resolve(cp).let { if (it.type == YtPaintType.SOLID) it.color else it.stops.first().color }, sFill)
                        val ratio = YtColor.contrast(ink, sFill)
                        if (ratio < MIN_CONTRAST) out += Warning(Kind.CONTRAST, kind, c.id, ratio)
                    }
                }
                n.children.forEach { walk(it, fill) }
            }
            // a tab lies on the bar's surface, everything else on the background
            val under = if (kind == YtComponentKind.NAV_TAB) {
                doc.components.navBar.style.fills.lastOrNull { it.enabled }?.let { YtColor.over(t.argb(it), background) } ?: background
            } else {
                background
            }
            walk(doc.components[kind], under)
        }
        return out.distinctBy { it.component to it.nodeId }
    }

    private fun YtTokensResolver.argb(p: YtPaint): Int = resolve(p).let { if (it.type == YtPaintType.SOLID) it.color else it.stops.first().color }

    /** Controls whose drawn box ([bounds], px) is under 48 dp on a side. */
    fun touch(doc: YtThemeDoc, bounds: (YtComponentKind, String) -> YtSnap.Rect?, density: Float): List<Warning> {
        val out = mutableListOf<Warning>()
        for (kind in YtComponentKind.entries) {
            val root = doc.components[kind]
            for (n in YtDocOps.all(root)) {
                if (!interactive(kind, n, n === root)) continue
                val b = bounds(kind, n.id) ?: continue
                val side = minOf(b.width, b.height) / density
                if (side < MIN_TOUCH_DP - 0.5f) out += Warning(Kind.TOUCH, kind, n.id, side.toDouble())
            }
        }
        return out
    }
}
