package com.github.libretube.ui.yt.liquid

import org.junit.Assert.*
import org.junit.Test

class YtSegmentLayoutTest {
    @Test fun transientZeroWidthDuringPipDoesNotProduceNegativeConstraints() {
        val layout = YtSegmentLayout.measure(0, 12f, listOf(1f, 1f, 1f))
        assertEquals(listOf(0f, 0f, 0f), layout.widths)
        assertTrue(layout.step.isFinite() && layout.step > 0f)
    }

    @Test fun gapsShrinkBeforeCellsWouldBecomeNegative() {
        val layout = YtSegmentLayout.measure(10, 12f, listOf(1f, 1f, 1f))
        assertTrue(layout.widths.all { it >= 0f })
        assertEquals(10f, layout.starts.last() + layout.widths.last(), 0.001f)
    }

    @Test fun normalWidthsKeepTheRequestedProportions() {
        val layout = YtSegmentLayout.measure(320, 10f, listOf(2f, 1f, 1f))
        assertEquals(listOf(150f, 75f, 75f), layout.widths)
        assertEquals(listOf(0f, 160f, 245f), layout.starts)
    }

    @Test fun emptyAndInvalidWeightsStayFinite() {
        assertTrue(YtSegmentLayout.measure(0, 8f, emptyList()).widths.isEmpty())
        assertTrue(YtSegmentLayout.measure(100, 8f, listOf(Float.NaN, Float.MAX_VALUE, -1f)).widths.all { it.isFinite() && it >= 0f })
    }
}
