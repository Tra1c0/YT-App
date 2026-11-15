package com.github.libretube.ui.yt.archive

import com.github.libretube.ui.yt.archive.YtArchiveRules.MB
import com.github.libretube.ui.yt.archive.YtArchiveRules.PlanItem
import com.github.libretube.ui.yt.archive.YtArchiveTier.AUDIO
import com.github.libretube.ui.yt.archive.YtArchiveTier.TEXT
import com.github.libretube.ui.yt.archive.YtArchiveTier.VIDEO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtArchiveRulesTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    private fun item(
        id: String,
        current: YtArchiveTier? = null,
        risk: Double = 0.0,
        high: Boolean = false,
        uploadedDaysAgo: Long = 1,
        savedDaysAgo: Long? = null,
        protected: Boolean = false,
        durationSec: Long = 600,
    ) = PlanItem(
        videoId = id,
        current = current,
        durationSec = durationSec,
        risk = risk,
        high = high,
        uploaded = now - uploadedDaysAgo * day,
        savedAt = savedDaysAgo?.let { now - it * day },
        protected = protected,
    )

    @Test
    fun capacitySteps() {
        assertEquals(5, YtArchiveRules.CAPACITY_STEPS_MB.first())
        assertEquals(15360, YtArchiveRules.CAPACITY_STEPS_MB.last())
        assertTrue(YtArchiveRules.CAPACITY_STEPS_MB.toList().zipWithNext().all { (a, b) -> b > a })
        assertEquals(5, YtArchiveRules.stepIndexOf(250))
        assertEquals(5, YtArchiveRules.stepIndexOf(300))
        assertEquals(0, YtArchiveRules.stepIndexOf(1))
        assertEquals(15360, YtArchiveRules.capacityMb(99))
    }

    @Test
    fun hardCapIsBudgetPlusAQuarterWithinLimits() {
        assertEquals(5 * MB + 50 * MB, YtArchiveRules.hardCap(5 * MB))
        assertEquals(1000 * MB + 250 * MB, YtArchiveRules.hardCap(1000 * MB))
        assertEquals(15360 * MB + 1024 * MB, YtArchiveRules.hardCap(15360 * MB))
    }

    @Test
    fun fitsShowsTextOnlyForSmallBudgets() {
        val small = YtArchiveRules.fits(5 * MB)
        assertTrue(small.textOnly)
        assertEquals(102, small.text)
        assertEquals(0, small.audio)
        val big = YtArchiveRules.fits(1024 * MB)
        assertFalse(big.textOnly)
        assertTrue(big.audio in 200..300)
        assertTrue(big.video in 80..120)
    }

    @Test
    fun smallBudgetKeepsOnlyTextButHighRiskGetsAudio() {
        val items = listOf(item("aaaaaaaaaaa", risk = 0.9, high = true), item("bbbbbbbbbbb", risk = 0.1))
        val plan = YtArchiveRules.plan(items, 5 * MB, VIDEO, now)
        assertEquals(AUDIO, plan["aaaaaaaaaaa"])
        assertEquals(TEXT, plan["bbbbbbbbbbb"])
    }

    @Test
    fun highRiskMayPassTheSoftBudgetButNotTheHardCap() {
        // 100 MB budget is full of protected entries; high-risk audio still fits in the 50 MB extra
        val full = item("ppppppppppp", current = TEXT, protected = true).copy(textBytes = 100 * MB)
        val high = (1..20).map { item("h%010d".format(it), risk = 0.9, high = true) }
        val plan = YtArchiveRules.plan(listOf(full) + high, 100 * MB, VIDEO, now)
        val audio = high.count { plan[it.videoId] == AUDIO }
        // each audio is ~3.9 MB + 50 KB text: about 12 fit into 50 MB
        assertTrue("audio=$audio", audio in 10..13)
        assertTrue(high.all { plan[it.videoId] != VIDEO })
    }

    @Test
    fun riskOrderDecidesWhoGetsMoreThenNewest() {
        // 30-minute videos: room for text and audio for all, video for the best one
        val items = listOf(
            item("lowlowlow01", risk = 0.1, uploadedDaysAgo = 1, durationSec = 1800),
            item("midmidmid01", risk = 0.5, uploadedDaysAgo = 3, durationSec = 1800),
            item("toptoptop01", risk = 0.7, uploadedDaysAgo = 5, durationSec = 1800),
        )
        val plan = YtArchiveRules.plan(items, 50 * MB, VIDEO, now)
        assertEquals(VIDEO, plan["toptoptop01"])
                assertEquals(AUDIO, plan["midmidmid01"])
        assertEquals(AUDIO, plan["lowlowlow01"])
        val plan2 = YtArchiveRules.plan(items, 50 * MB, AUDIO, now)
        assertTrue(plan2.values.none { it == VIDEO })
    }

    @Test
    fun equalRiskPrefersNewer() {
        val many = (1..30).map { item("v%010d".format(it), uploadedDaysAgo = it.toLong()) }
        val plan = YtArchiveRules.plan(many, 50 * MB, AUDIO, now)
        val withAudio = many.filter { plan[it.videoId] == AUDIO }.map { it.uploaded!! }
        val textOnly = many.filter { plan[it.videoId] == TEXT }.map { it.uploaded!! }
        assertTrue(withAudio.isNotEmpty() && textOnly.isNotEmpty())
        assertTrue(withAudio.min() > textOnly.max())
    }

    @Test
    fun protectedKeepTheirTier() {
        val vanished = item("vvvvvvvvvvv", current = VIDEO, protected = true, uploadedDaysAgo = 300).copy(videoBytes = 30 * MB)
        val plan = YtArchiveRules.plan(listOf(vanished), 5 * MB, AUDIO, now)
        assertEquals(VIDEO, plan["vvvvvvvvvvv"])
        assertTrue(YtArchiveRules.steps(listOf(vanished), plan).isEmpty())
    }

    @Test
    fun oldTextIsDroppedOnlyAfterItsMinimumAge() {
        val young = (1..200).map { item("y%010d".format(it), current = TEXT, savedDaysAgo = 10) }
        val plan = YtArchiveRules.plan(young, 5 * MB, AUDIO, now)
        // 200 × 50 KB = 10 MB > 5 MB, but none is 30 days old
        assertTrue(young.all { plan[it.videoId] == TEXT })
        val old = (1..200).map { item("o%010d".format(it), current = TEXT, savedDaysAgo = 40, uploadedDaysAgo = 40) }
        val plan2 = YtArchiveRules.plan(old, 5 * MB, AUDIO, now)
        val kept = old.count { plan2[it.videoId] == TEXT }
        assertEquals(102, kept)
        val steps = YtArchiveRules.steps(old, plan2)
        assertEquals(98, steps.size)
        assertTrue(steps.all { it.from == TEXT && it.to == null })
    }

    @Test
    fun stepsGoVideoThenAudioThenTextLowestRankedFirst() {
        val a = item("aaaaaaaaaaa", current = VIDEO, risk = 0.1, uploadedDaysAgo = 20, savedDaysAgo = 40)
        val b = item("bbbbbbbbbbb", current = VIDEO, risk = 0.2, uploadedDaysAgo = 5)
        val targets = mapOf("aaaaaaaaaaa" to null, "bbbbbbbbbbb" to TEXT)
        val steps = YtArchiveRules.steps(listOf(b, a), targets)
        assertEquals(
            listOf("aaaaaaaaaaa" to VIDEO, "bbbbbbbbbbb" to VIDEO, "aaaaaaaaaaa" to AUDIO, "bbbbbbbbbbb" to AUDIO, "aaaaaaaaaaa" to TEXT),
            steps.map { it.videoId to it.from },
        )
        assertEquals(AUDIO, steps.first().to)
        assertNull(steps.last().to)
    }

    @Test
    fun makeRoomTakesStepsUntilEnoughOrNothing() {
        val steps = listOf(
            YtArchiveRules.Step("a", VIDEO, AUDIO, 5 * MB),
            YtArchiveRules.Step("b", VIDEO, AUDIO, 6 * MB),
            YtArchiveRules.Step("a", AUDIO, TEXT, 4 * MB),
        )
        assertEquals(listOf("a", "b"), YtArchiveRules.makeRoom(steps, 10 * MB)!!.map { it.videoId })
        assertEquals(emptyList<YtArchiveRules.Step>(), YtArchiveRules.makeRoom(steps, 0))
        assertNull(YtArchiveRules.makeRoom(steps, 100 * MB))
        // the entry being raised never pays for itself
        assertEquals(listOf("b"), YtArchiveRules.makeRoom(steps, 6 * MB, excluding = "a")!!.map { it.videoId })
    }

    @Test
    fun upgradesBestFirst() {
        val a = item("aaaaaaaaaaa", current = TEXT, risk = 0.1)
        val b = item("bbbbbbbbbbb", current = null, risk = 0.9, high = true)
        val c = item("ccccccccccc", current = AUDIO)
        val ups = YtArchiveRules.upgrades(listOf(a, b, c), mapOf("aaaaaaaaaaa" to AUDIO, "bbbbbbbbbbb" to AUDIO, "ccccccccccc" to AUDIO))
        assertEquals(listOf("bbbbbbbbbbb", "aaaaaaaaaaa"), ups.map { it.first.videoId })
    }

    @Test
    fun longVideosStayText() {
        val long = item("lllllllllll", durationSec = 4 * 3600L, high = true, risk = 1.0)
        val plan = YtArchiveRules.plan(listOf(long), 10_000 * MB, VIDEO, now)
        assertEquals(TEXT, plan["lllllllllll"])
    }

    @Test
    fun candidatesSkipShortsWatchedOldAndLive() {
        fun c(id: String, daysAgo: Long, duration: Long = 300) =
            YtArchiveCandidate(id, "ch", "Channel", "Title $id", null, now - daysAgo * day, duration)
        val feed = listOf(
            YtArchiveRules.FeedVideo(c("keepkeep001", 1), isShort = false, watched = false),
            YtArchiveRules.FeedVideo(c("shortshort1", 1), isShort = true, watched = false),
            YtArchiveRules.FeedVideo(c("watchedwat1", 1), isShort = false, watched = true),
            YtArchiveRules.FeedVideo(c("oldoldold01", 20), isShort = false, watched = false),
            YtArchiveRules.FeedVideo(c("livelive001", 1, duration = 0), isShort = false, watched = false),
            YtArchiveRules.FeedVideo(c("keepkeep002", 3), isShort = false, watched = false),
        )
        assertEquals(listOf("keepkeep001", "keepkeep002"), YtArchiveRules.candidates(feed, now).map { it.videoId })
    }

    @Test
    fun validIds() {
        assertTrue(YtArchiveRules.validId("dQw4w9WgXcQ"))
        assertFalse(YtArchiveRules.validId("../../etc"))
        assertFalse(YtArchiveRules.validId(""))
        assertFalse(YtArchiveRules.validId(null))
    }

    @Test
    fun checksConfirmVanishedFirstThenDueInWindow() {
        fun ci(id: String, uploadedDaysAgo: Long, checkedDaysAgo: Long?, vanished: Boolean = false, labelled: Boolean = false) =
            YtArchiveRules.CheckItem(id, now - uploadedDaysAgo * day, now - uploadedDaysAgo * day, checkedDaysAgo?.let { now - it * day }, vanished, labelled)
        val items = listOf(
            ci("fresh", 1, 0),
            ci("due", 5, 3),
            ci("never", 2, null),
            ci("outside", 40, 10),
            ci("gone", 3, null, vanished = true),
            ci("goneknown", 3, null, vanished = true, labelled = true),
        )
        assertEquals(listOf("gone", "never", "due"), YtArchiveRules.checks(items, now, 5))
        assertEquals(listOf("gone", "never"), YtArchiveRules.checks(items, now, 2))
    }

    @Test
    fun windowLabelOnceAfterTheWindow() {
        val meta = YtArchiveMeta(videoId = "aaaaaaaaaaa", uploaded = now - 31 * day, savedAt = now - 30 * day)
        assertTrue(YtArchiveRules.windowLabelDue(meta, now))
        assertFalse(YtArchiveRules.windowLabelDue(meta.copy(windowLabelled = true), now))
        assertFalse(YtArchiveRules.windowLabelDue(meta.copy(vanishedAt = now), now))
        assertFalse(YtArchiveRules.windowLabelDue(meta.copy(uploaded = now - 2 * day), now))
    }

    @Test
    fun pickAudioTakesTheSmallestOriginal() {
        fun s(bitrate: Int, mime: String, track: String? = null, drc: Boolean = false, len: Long = 1000) =
            YtArchiveRules.StreamChoice("https://x/$bitrate$mime", mime, if (mime.contains("webm")) "opus" else "mp4a.40.2", bitrate, null, len, drc, track)
        val streams = listOf(
            s(130_000, "audio/webm"),
            s(50_000, "audio/webm", track = "DUBBED"),
            s(48_000, "audio/mp4"),
            s(48_000, "audio/webm"),
            s(30_000, "audio/webm", drc = true),
            s(20_000, "audio/webm", len = 0),
        )
        assertEquals(3, YtArchiveRules.pickAudio(streams, sabr = false))
        assertNull(YtArchiveRules.pickAudio(emptyList(), sabr = false))
        val sabrOnly = listOf(YtArchiveRules.StreamChoice(null, "audio/webm", "opus", 1, null, 0), YtArchiveRules.StreamChoice("sabr:1", "audio/webm", "opus", 2, null, 0))
        assertEquals(1, YtArchiveRules.pickAudio(sabrOnly, sabr = true))
        assertNull(YtArchiveRules.pickAudio(sabrOnly, sabr = false))
    }

    @Test
    fun pickVideoTakes144pSmallCodec() {
        fun v(height: Int, codec: String, bitrate: Int = 100) =
            YtArchiveRules.StreamChoice("https://x/$height$codec", "video/webm", codec, bitrate, height, 1000)
        val streams = listOf(v(360, "av01.0.01M.08"), v(144, "avc1.4d400c"), v(144, "vp9"), v(144, "av01.0.00M.08"), v(240, "vp9"))
        assertEquals(3, YtArchiveRules.pickVideo(streams, sabr = false))
        assertEquals(0, YtArchiveRules.pickVideo(listOf(v(360, "vp9")), sabr = false))
    }

    @Test
    fun boardAndFrames() {
        val boards = listOf(
            YtArchiveRules.Board(48, 27, 1),
            YtArchiveRules.Board(80, 45, 2),
            YtArchiveRules.Board(160, 90, 8),
            YtArchiveRules.Board(320, 180, 30),
        )
        assertEquals(2, YtArchiveRules.pickBoard(boards))
        assertEquals(1, YtArchiveRules.pickBoard(listOf(YtArchiveRules.Board(160, 90, 20), YtArchiveRules.Board(48, 27, 1))))
        assertNull(YtArchiveRules.pickBoard(emptyList()))

        // 5×5 per sheet, 2 s per frame
        assertEquals(YtArchiveRules.Frame(0, 0, 0), YtArchiveRules.frameAt(0, 2000, 5, 5, 60, 3))
        assertEquals(YtArchiveRules.Frame(0, 1, 0), YtArchiveRules.frameAt(2500, 2000, 5, 5, 60, 3))
        assertEquals(YtArchiveRules.Frame(1, 1, 1), YtArchiveRules.frameAt(2000L * 31, 2000, 5, 5, 60, 3))
        // past the end: the last frame
        assertEquals(YtArchiveRules.Frame(2, 4, 1), YtArchiveRules.frameAt(10_000_000, 2000, 5, 5, 60, 3))
        assertNull(YtArchiveRules.frameAt(0, 0, 5, 5, 60, 3))
        assertNotNull(YtArchiveRules.frameAt(0, 2000, 5, 5, 0, 1))
    }
}
