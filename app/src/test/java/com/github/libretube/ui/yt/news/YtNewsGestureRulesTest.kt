package com.github.libretube.ui.yt.news

import com.github.libretube.ui.yt.news.YtNewsGestureRules.Claim
import com.github.libretube.ui.yt.news.YtNewsGestureRules.Release
import com.github.libretube.ui.yt.news.YtNewsGestureRules.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtNewsGestureRulesTest {
    private val spec = YtNewsGestureRules.Spec(claimPx = 28f, commitPx = 96f)

    @Test
    fun aSmallSpreadWaitsAndTheContentKeepsTheEvents() {
        assertEquals(Step.WAIT, YtNewsGestureRules.step(200f, 210f, 4f, claimed = false, spec = spec))
        assertEquals(Step.WAIT, YtNewsGestureRules.step(200f, 200f, 0f, claimed = false, spec = spec))
    }

    @Test
    fun aClearSpreadIsClaimedThenCommits() {
        assertEquals(Step.CLAIM, YtNewsGestureRules.step(200f, 240f, 6f, claimed = false, spec = spec))
        assertEquals(Step.WAIT, YtNewsGestureRules.step(200f, 260f, 6f, claimed = true, spec = spec))
        assertEquals(Step.COMMIT, YtNewsGestureRules.step(200f, 300f, 6f, claimed = true, spec = spec))
        // fast fingers: past the commit at the first look
        assertEquals(Step.COMMIT, YtNewsGestureRules.step(200f, 320f, 10f, claimed = false, spec = spec))
    }

    @Test
    fun aTwoFingerScrollIsNoPinch() {
        // the centre travelled far, the gap stayed
        assertEquals(Step.REJECT, YtNewsGestureRules.step(200f, 205f, 90f, claimed = false, spec = spec))
        // a spread that is mostly a pan
        assertEquals(Step.REJECT, YtNewsGestureRules.step(200f, 230f, 120f, claimed = false, spec = spec))
    }

    @Test
    fun theOtherDirectionIsRejected() {
        assertEquals(Step.REJECT, YtNewsGestureRules.step(300f, 260f, 0f, claimed = false, spec = spec))
        // pinching together is the closing gesture
        assertEquals(Step.CLAIM, YtNewsGestureRules.step(300f, 260f, 0f, claimed = false, spec = spec, inward = true))
        assertEquals(Step.REJECT, YtNewsGestureRules.step(300f, 340f, 0f, claimed = false, spec = spec, inward = true))
    }

    @Test
    fun pinchingTogetherCommitsAtMostAtHalfTheStartGap() {
        assertEquals(96f, YtNewsGestureRules.commitAt(400f, spec, inward = true), 0f)
        assertEquals(60f, YtNewsGestureRules.commitAt(120f, spec, inward = true), 0f)
        // never below one and a half claims
        assertEquals(42f, YtNewsGestureRules.commitAt(40f, spec, inward = true), 0f)
        assertEquals(Step.COMMIT, YtNewsGestureRules.step(120f, 55f, 0f, claimed = true, spec = spec, inward = true))
        // spreading has no such cap
        assertEquals(96f, YtNewsGestureRules.commitAt(40f, spec, inward = false), 0f)
    }

    @Test
    fun progressRunsFromHalfAClaimToTheCommit() {
        assertEquals(0f, YtNewsGestureRules.progress(200f, 210f, spec), 0f)
        assertEquals(1f, YtNewsGestureRules.progress(200f, 296f, spec), 0f)
        val mid = YtNewsGestureRules.progress(200f, 255f, spec)
        assertTrue(mid > 0.4f && mid < 0.6f)
        assertEquals(1f, YtNewsGestureRules.progress(200f, 400f, spec), 0f)
    }

    @Test
    fun aSecondFingerAfterTheFirstScrolledStartsNothing() {
        assertTrue(YtNewsGestureRules.acceptsSecond(5f, 16f))
        assertFalse(YtNewsGestureRules.acceptsSecond(40f, 16f))
    }

    @Test
    fun theCardTakesOnlyClearlyVerticalMoves() {
        assertEquals(Claim.WAIT, YtNewsGestureRules.claim(3f, 5f, 8f))
        assertEquals(Claim.VERTICAL, YtNewsGestureRules.claim(4f, -20f, 8f))
        assertEquals(Claim.HORIZONTAL, YtNewsGestureRules.claim(20f, 4f, 8f))
        // slanted: the pager's
        assertEquals(Claim.HORIZONTAL, YtNewsGestureRules.claim(14f, 16f, 8f))
    }

    @Test
    fun releaseQueuesUpAndClosesDown() {
        assertEquals(Release.QUEUE, YtNewsGestureRules.release(-130f, 0f, 120f, 180f))
        assertEquals(Release.STAY, YtNewsGestureRules.release(-60f, 0f, 120f, 180f))
        assertEquals(Release.QUEUE, YtNewsGestureRules.release(-60f, -2000f, 120f, 180f))
        assertEquals(Release.STAY, YtNewsGestureRules.release(-20f, -2000f, 120f, 180f))
        assertEquals(Release.CLOSE, YtNewsGestureRules.release(200f, 0f, 120f, 180f))
        assertEquals(Release.CLOSE, YtNewsGestureRules.release(70f, 2000f, 120f, 180f))
        // thrown back the other way: nothing
        assertEquals(Release.STAY, YtNewsGestureRules.release(-100f, 1500f, 120f, 180f))
        assertEquals(Release.STAY, YtNewsGestureRules.release(0f, 3000f, 120f, 180f))
    }
}
