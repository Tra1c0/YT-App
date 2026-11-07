package com.github.libretube.ui.yt.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YtSwipeTuningTest {
    @Test
    fun theMiddleOfTheSliderIsTodaysBehaviour() {
        val t = YtSwipeTuning.forSensitivity(YtSwipeTuning.DEFAULT_SENSITIVITY)
        assertEquals(30f, t.angleDeg, 0f)
        assertEquals(24f, t.driftDp, 0f)
        assertEquals(96f, t.distanceDp, 0f)
        assertEquals(YtSwipePhysics.FLICK_VELOCITY, t.flickVelocity, 0f)
        assertEquals(YtSwipePhysics.CLAIM_RATIO, t.claimRatio, 1e-4f)
        assertEquals(YtSwipePhysics.STEEP, t.steep, 1e-3f)
    }

    @Test
    fun theEndsAreStrictAndLoose() {
        assertEquals(YtSwipeTuning.STRICT, YtSwipeTuning.forSensitivity(0))
        assertEquals(YtSwipeTuning.LOOSE, YtSwipeTuning.forSensitivity(100))
        // out of range is clamped
        assertEquals(YtSwipeTuning.STRICT, YtSwipeTuning.forSensitivity(-20))
        assertEquals(YtSwipeTuning.LOOSE, YtSwipeTuning.forSensitivity(140))
    }

    @Test
    fun looserMeansWiderAngleMoreDriftShorterPathEasierClaim() {
        var last = YtSwipeTuning.forSensitivity(0)
        for (s in 5..100 step 5) {
            val t = YtSwipeTuning.forSensitivity(s)
            assertTrue(t.angleDeg > last.angleDeg)
            assertTrue(t.driftDp > last.driftDp)
            assertTrue(t.distanceDp < last.distanceDp)
            assertTrue(t.flickVelocity < last.flickVelocity)
            assertTrue(t.claimRatio < last.claimRatio)
            last = t
        }
    }

    @Test
    fun theClaimIsAlwaysStricterThanTheDropAngle() {
        for (s in 0..100 step 10) {
            val t = YtSwipeTuning.forSensitivity(s)
            assertTrue(t.claimAngleDeg < t.angleDeg)
        }
    }

    @Test
    fun fineValuesReplaceTheSlidersOnesAndAreClamped() {
        val t = YtSwipeTuning.resolve(50, angleDeg = 40f, driftDp = null, distanceDp = 500f)
        assertEquals(40f, t.angleDeg, 0f)
        assertEquals(24f, t.driftDp, 0f)
        assertEquals(YtSwipeTuning.DISTANCE_RANGE.endInclusive, t.distanceDp, 0f)
        // the flick follows the slider
        assertEquals(YtSwipeTuning.forSensitivity(0).flickVelocity, YtSwipeTuning.resolve(0, 30f, 24f, 96f).flickVelocity, 0f)
    }

    @Test
    fun aWiderAngleLetsASlantedSwipeThrough() {
        // 3 dp down per 4 dp across (~37°): dropped by default, kept when loose
        val strict = YtSwipeTuning.DEFAULT
        val loose = YtSwipeTuning.LOOSE
        assertTrue(YtSwipePhysics.drifted(40f, 30f, 100f, 8f, strict.steep))
        assertTrue(!YtSwipePhysics.drifted(40f, 30f, 100f, 8f, loose.steep))
        assertEquals(YtSwipePhysics.Claim.SWIPE, YtSwipePhysics.claim(30f, 20f, 8f, loose.claimRatio))
        assertEquals(YtSwipePhysics.Claim.SCROLL, YtSwipePhysics.claim(30f, 20f, 8f, strict.claimRatio))
    }
}
