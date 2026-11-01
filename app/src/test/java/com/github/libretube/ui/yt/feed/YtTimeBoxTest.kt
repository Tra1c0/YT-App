package com.github.libretube.ui.yt.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtTimeBoxTest {
    private fun video(id: String, minutes: Double, startSec: Long = 0) =
        YtBoxCandidate(id, (minutes * 60).toLong(), startSec)

    private fun ids(plan: YtBoxPlan) = plan.items.map { it.id }

    @Test
    fun budgetFollowsTheSpeed() {
        assertEquals(1800L, YtTimeBox.budgetSec(30, 1f))
        assertEquals(2700L, YtTimeBox.budgetSec(30, 1.5f))
        assertEquals(180L, YtTimeBox.allowanceSec(1800))
        assertEquals(90L, YtTimeBox.allowanceSec(900))
        assertEquals(33, YtTimeBox.capMinutes(1800, 1f))
    }

    @Test
    fun greedyKeepsTheRankOrderThenBestFitPacksTheGap() {
        // 12 + 10 fit, 15 does not; best fit takes the 7 over the 3, and the 3 would then run over more than it fills
        val plan = YtTimeBox.plan(
            listOf(video("a", 12.0), video("b", 10.0), video("c", 15.0), video("d", 3.0), video("e", 7.0)),
            YtTimeBox.budgetSec(30, 1f),
        )
        assertEquals(listOf("a", "b", "e"), ids(plan))
        assertEquals(29 * 60L, plan.totalSec)
        assertEquals(listOf("c", "d"), plan.spare.map { it.id })
    }

    @Test
    fun overshootIsSmallAndOnlyWhenCloser() {
        // 25 min planned, gap 5: a 7-minute video runs 2 over, which is closer than 5 under
        val over = YtTimeBox.plan(listOf(video("a", 25.0), video("b", 7.0)), 1800)
        assertEquals(listOf("a", "b"), ids(over))
        assertTrue(over.totalSec <= 1800 + 180)
        // gap 1 minute: a 3.5-minute video would run 2.5 over, worse than 1 under
        val under = YtTimeBox.plan(listOf(video("a", 29.0), video("b", 3.5)), 1800)
        assertEquals(listOf("a"), ids(under))
        // never more than min(10 %, 3 min)
        val short = YtTimeBox.plan(listOf(video("a", 10.0), video("b", 7.0)), 900)
        assertEquals(listOf("a"), ids(short))
    }

    @Test
    fun liveShortsUpcomingAndNearlyDoneVideosStayOut() {
        val plan = YtTimeBox.plan(
            listOf(
                YtBoxCandidate("live", 0, isLive = true),
                YtBoxCandidate("short", 50, isShort = true),
                YtBoxCandidate("soon", 600, isUpcoming = true),
                video("done", 10.0, startSec = 570),
                video("a", 5.0),
                video("a", 5.0),
            ),
            1800,
        )
        assertEquals(listOf("a"), ids(plan))
    }

    @Test
    fun continueWatchingCountsWhatIsLeft() {
        val plan = YtTimeBox.plan(listOf(video("half", 40.0, startSec = 25 * 60), video("b", 14.0)), 1800)
        assertEquals(listOf("half", "b"), ids(plan))
        assertEquals(15 * 60L, plan.items[0].seconds)
        assertEquals(25 * 60L, plan.items[0].startSec)
        assertNull(plan.items[0].endSec)
    }

    @Test
    fun aLongVideoIsCutWhereAChapterEnds() {
        val plan = YtTimeBox.plan(listOf(video("a", 18.0), video("long", 60.0), video("long2", 50.0), video("long3", 45.0)), 1800)
        assertEquals(listOf("a"), ids(plan))
        assertEquals(listOf("long", "long2"), plan.cutCandidates.map { it.id })
        // gap 12 min: chapter ends at 5, 11 and 20 min; 11 is closest
        val cut = YtTimeBox.cut(plan, plan.cutCandidates[0], listOf(0, 300, 660, 1200))!!
        assertEquals(660L, cut.endSec)
        assertEquals(660L, cut.seconds)
        val full = YtTimeBox.withCut(plan, cut)
        assertEquals(listOf("a", "long"), ids(full))
        assertTrue(full.totalSec <= 1800)
        assertFalse(full.spare.any { it.id == "long" })
    }

    @Test
    fun noCutWithoutAFittingChapterOrForASmallGap() {
        val plan = YtTimeBox.plan(listOf(video("a", 18.0), video("long", 60.0)), 1800)
        // only a chapter end after 2 min (less than half the gap) and one after 20 (too far)
        assertNull(YtTimeBox.cut(plan, plan.cutCandidates[0], listOf(0, 120, 1200)))
        assertNull(YtTimeBox.cut(plan, plan.cutCandidates[0], emptyList()))
        val small = YtTimeBox.plan(listOf(video("a", 27.0), video("long", 60.0)), 1800)
        assertTrue(small.cutCandidates.isEmpty())
    }

    @Test
    fun aCutOfAContinueItemStartsWhereItGoesOn() {
        val plan = YtTimeBox.plan(listOf(video("a", 20.0), video("long", 60.0, startSec = 600)), 1800)
        val cut = YtTimeBox.cut(plan, plan.cutCandidates[0], listOf(0, 900, 1140, 1500))!!
        assertEquals(1140L, cut.endSec)
        assertEquals(540L, cut.seconds)
    }

    @Test
    fun theBestOfTwoCutsWins() {
        val plan = YtTimeBox.plan(listOf(video("a", 18.0), video("x", 60.0), video("y", 60.0)), 1800)
        val x = YtTimeBox.cut(plan, plan.cutCandidates[0], listOf(0, 400))
        val y = YtTimeBox.cut(plan, plan.cutCandidates[1], listOf(0, 700))
        assertEquals("y", YtTimeBox.bestCut(plan, listOf(x, y))?.id)
        assertNull(YtTimeBox.bestCut(plan, listOf(null, null)))
    }

    @Test
    fun oneMorePrefersAShortRankedVideo() {
        val spare = listOf(video("long", 40.0), video("mid", 12.0), video("short", 4.0))
        assertEquals("mid", YtTimeBox.oneMore(spare, emptySet())?.id)
        assertEquals("short", YtTimeBox.oneMore(spare, setOf("mid"))?.id)
        assertEquals("long", YtTimeBox.oneMore(listOf(video("long", 40.0), video("longer", 50.0)), emptySet())?.id)
        assertNull(YtTimeBox.oneMore(emptyList(), emptySet()))
    }

    @Test
    fun customMinutesSnapToFiveMinuteSteps() {
        assertEquals(5, YtTimeBox.clampMinutes(1))
        assertEquals(25, YtTimeBox.clampMinutes(23))
        assertEquals(240, YtTimeBox.clampMinutes(500))
    }

    private fun state() = YtSessionState(
        key = 1, minutes = 30, speed = 1f, budgetMs = 30 * 60_000L,
        items = listOf(YtBoxItem("a", 0, null, 600), YtBoxItem("b", 0, 900, 900)),
    )

    @Test
    fun timeCountsOnlyWhilePlaying() {
        var s = state().accrue(now = 1_000, playing = true)
        assertEquals(30 * 60_000L - 9_000, s.remainingMs(10_000))
        s = s.accrue(10_000, playing = false)
        assertEquals(30 * 60_000L - 9_000, s.remainingMs(99_000))
        s = s.accrue(100_000, playing = true).accrue(100_000, playing = true)
        assertEquals(30 * 60_000L - 10_000, s.remainingMs(101_000))
        assertEquals(0L, s.remainingMs(10_000_000))
        assertEquals(0f, s.fraction(10_000_000))
    }

    @Test
    fun autoplayWaitsAfterTheLastVideoOrWhenTheTimeIsUp() {
        val s = state()
        assertFalse(s.holds("a", ended = true, now = 0))
        assertTrue(s.holds("b", ended = true, now = 0))
        // a next video asked for while one plays still goes
        assertFalse(s.holds("b", ended = false, now = 0))
        val spent = s.accrue(0, true)
        assertTrue(spent.holds("a", ended = true, now = 31 * 60_000L))
        assertTrue(s.copy(done = true).holds("a", ended = true, now = 0))
        assertEquals(900_000L, s.endAtMs("b"))
        assertNull(s.endAtMs("a"))
    }

    @Test
    fun oneMoreAndWatchToEndGrowTheBudget() {
        val done = state().copy(done = true, stoppedAt = "b")
        val more = done.plusOne(YtBoxItem("c", 0, null, 300))
        assertEquals("c", more.lastId)
        assertFalse(more.done)
        assertEquals(35 * 60_000L, more.budgetMs)
        val rest = done.watchToEnd("b", durationSec = 1500)
        assertNull(rest.endAtMs("b"))
        assertEquals(40 * 60_000L, rest.budgetMs)
        assertNull(rest.stoppedAt)
        // the time ran 2 minutes over: one more 5-minute video leaves 5 minutes, not 3
        val over = state().copy(spentMs = 32 * 60_000L, done = true).plusOne(YtBoxItem("c", 0, null, 300))
        assertEquals(5 * 60_000L, over.remainingMs(0))
        // at 2x a 5-minute video takes 2.5 real minutes
        assertEquals(150_000L, state().copy(speed = 2f).realMs(300))
    }
}
