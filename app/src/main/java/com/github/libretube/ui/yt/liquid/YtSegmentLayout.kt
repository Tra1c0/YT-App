package com.github.libretube.ui.yt.liquid

import kotlin.math.min

internal data class YtSegmentLayout(val widths: List<Float>, val starts: List<Float>, val step: Float) {
    companion object {
        fun measure(widthPx: Int, requestedGap: Float, weights: List<Float>): YtSegmentLayout {
            if (weights.isEmpty()) return YtSegmentLayout(emptyList(), emptyList(), 1f)
            val width = widthPx.coerceAtLeast(0).toFloat()
            val gap = if (weights.size == 1) 0f else min(requestedGap.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f, width / (weights.size - 1))
            val clean = weights.map { it.takeIf { w -> w.isFinite() && w > 0f } ?: 1f }
            val max = clean.max()
            val normalized = clean.map { it / max }
            val free = (width - gap * (weights.size - 1)).coerceAtLeast(0f)
            val total = normalized.sum()
            val widths = normalized.map { free * it / total }
            val starts = widths.runningFold(0f) { x, w -> x + w + gap }.dropLast(1)
            return YtSegmentLayout(widths, starts, (free / weights.size + gap).coerceAtLeast(1f))
        }
    }
}
