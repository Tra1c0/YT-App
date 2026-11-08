package com.github.libretube.services

import com.github.libretube.db.DatabaseHelper
import com.github.libretube.services.YtPositionRules.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtPositionRulesTest {
    private val tenMin = 600_000L
    private fun snap(pos: Long, dur: Long = tenMin, id: String = "a", live: Boolean = false) =
        Snapshot(id, pos, dur, live)

    @Test
    fun firstSaveOfAVideoGoesThrough() {
        assertEquals(60_000L, YtPositionRules.toPersist(snap(60_000), null, forced = false, explicitSeek = false))
    }

    @Test
    fun theTickWritesOnlyAfterFiveSeconds() {
        assertNull(YtPositionRules.toPersist(snap(64_999), 60_000, forced = false, explicitSeek = false))
        assertEquals(65_000L, YtPositionRules.toPersist(snap(65_000), 60_000, forced = false, explicitSeek = false))
    }

    @Test
    fun forcedSavesSkipTheStepButNotAnEqualValue() {
        assertEquals(61_000L, YtPositionRules.toPersist(snap(61_000), 60_000, forced = true, explicitSeek = false))
        assertNull(YtPositionRules.toPersist(snap(60_000), 60_000, forced = true, explicitSeek = false))
    }

    @Test
    fun nearTheStartIsSavedOnlyAfterASeekThere() {
        // a fresh start (or a restore still in flight) never writes ~0 over a real position
        assertNull(YtPositionRules.toPersist(snap(0), null, forced = true, explicitSeek = false))
        assertNull(YtPositionRules.toPersist(snap(4_999), 300_000, forced = true, explicitSeek = false))
        // the user sought back to the start: that counts
        assertEquals(0L, YtPositionRules.toPersist(snap(0), 300_000, forced = true, explicitSeek = true))
        assertEquals(3_000L, YtPositionRules.toPersist(snap(3_000), 300_000, forced = true, explicitSeek = true))
    }

    @Test
    fun liveAndUnknownDurationAreNeverSaved() {
        assertNull(YtPositionRules.toPersist(snap(60_000, live = true), null, forced = true, explicitSeek = true))
        assertNull(YtPositionRules.toPersist(snap(60_000, dur = 0), null, forced = true, explicitSeek = true))
    }

    @Test
    fun finishedIsStoredAtTheFullDuration() {
        // 95 % of a 2 h video (6 min left) is finished, stored as the duration
        val twoHours = 7_200_000L
        assertEquals(twoHours, YtPositionRules.toPersist(snap(6_840_000, twoHours), null, forced = true, explicitSeek = false))
        // the existing rule: 75 % and at most 60 s left
        assertEquals(tenMin, YtPositionRules.toPersist(snap(tenMin - 30_000), null, forced = true, explicitSeek = false))
        // end of the video (STATE_ENDED) and a position past the end
        assertEquals(tenMin, YtPositionRules.storedValue(snap(tenMin)))
        assertEquals(tenMin, YtPositionRules.storedValue(snap(tenMin + 500)))
    }

    @Test
    fun notFinishedInTheMiddleOrOnAShortVideoEarly() {
        assertFalse(YtPositionRules.isFinished(300_000, tenMin))
        // 10 s into a 35 s video: only 25 s left, but far from seen
        assertFalse(YtPositionRules.isFinished(10_000, 35_000))
        assertTrue(YtPositionRules.isFinished(34_000, 35_000))
    }

    @Test
    fun aFinishedValueIsSeenAsWatchedWhenThePlayerStarts() {
        // the start paths skip the restore when DatabaseHelper.isVideoWatched is true
        for ((pos, dur) in listOf(6_840_000L to 7_200_000L, 570_000L to tenMin, 34_000L to 35_000L)) {
            val stored = YtPositionRules.storedValue(snap(pos, dur))
            assertTrue(DatabaseHelper.isVideoWatched(stored, dur / 1000))
        }
        // an unfinished value is restored
        assertFalse(DatabaseHelper.isVideoWatched(YtPositionRules.storedValue(snap(300_000)), tenMin / 1000))
    }

    @Test
    fun theSwitchUsesThePlayerWhileItHoldsTheOldVideoElseTheLastSnapshot() {
        val live = snap(90_000)
        val last = snap(85_000)
        assertEquals(live, YtPositionRules.forSwitch(live, last))
        assertEquals(last, YtPositionRules.forSwitch(null, last))
        assertNull(YtPositionRules.forSwitch(null, null))
    }

    @Test
    fun aTransitionSavesOnlyTheVideoLeftBehind() {
        val last = snap(85_000, id = "old")
        assertEquals(last, YtPositionRules.leftBehind(last, "new"))
        assertEquals(last, YtPositionRules.leftBehind(last, null))
        assertNull(YtPositionRules.leftBehind(last, "old"))
        assertNull(YtPositionRules.leftBehind(null, "new"))
    }
}
