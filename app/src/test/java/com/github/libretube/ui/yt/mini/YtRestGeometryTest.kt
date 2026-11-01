package com.github.libretube.ui.yt.mini

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtRestGeometryTest {
    private val open = YtBox(0f, 128f, 1080f, 735f)
    private val dock = YtBox(33f, 2222f, 1047f, 2400f)

    @Test fun theFirstDragDoesNotLearnAnEndpointFromThePreviousLayoutFrame() {
        val model = YtBox(52f, 2241f, 297f, 2378f)
        val previous = open.lerp(model, 0.237f)
        assertFalse(YtRestGeometry.currentSample(previous, open, model, 0.316f, 0.237f))
        assertTrue(YtRestGeometry.currentSample(open.lerp(model, 0.316f), open, model, 0.316f, 0.237f))
    }

    @Test fun progressChangingBeforeLayoutDoesNotReplaceTheDockWithTheOpenBox() {
        assertFalse(YtRestGeometry.valid(open, open, 2400, true, open, open))
        assertFalse(YtRestGeometry.valid(dock, open, 2400, true, open, open))
        assertFalse(YtRestGeometry.valid(open, dock, 2400, true, open, open))
        assertTrue(YtRestGeometry.valid(dock, dock, 2400, true, open, open))
    }

    @Test fun openingWaitsForBothViewsAndAcceptsTheRealOpenLayout() {
        assertFalse(YtRestGeometry.valid(dock, dock, 2400, false, dock, dock))
        assertFalse(YtRestGeometry.valid(open, dock, 2400, false, dock, dock))
        assertTrue(YtRestGeometry.valid(open, open, 2400, false, dock, dock))
    }

    @Test fun theFirstSampleMustBeLaidOutAtTheRightEnd() {
        assertFalse(YtRestGeometry.valid(open, open, 2400, true, null, null))
        assertTrue(YtRestGeometry.valid(dock, dock, 2400, true, null, null))
        assertFalse(YtRestGeometry.valid(YtBox(0f, 0f, 0f, 0f), open, 2400, false, null, null))
    }
}
