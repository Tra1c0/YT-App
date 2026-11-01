package com.github.libretube.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtCommentMomentsTest {
    private val id = "dQw4w9WgXcQ"
    private val tenMinutes = 600_000L

    // an anchor as the extractor writes it
    private fun anchor(seconds: Long, video: String = id, text: String = YtCommentMoments.formatTime(seconds * 1000)) =
        """<a href="https://www.youtube.com/watch?v=$video&amp;t=$seconds">$text</a>"""

    private fun playback(position: Long, unlocked: Long = position, chapters: List<Long> = emptyList()) =
        ScenePlayback(position, unlocked, tenMinutes, chapters)

    @Test
    fun anchorsOfTheVideoAreRead() {
        val html = "Best part ${anchor(125)} and ${anchor(30)}"
        assertEquals(listOf(30_000L, 125_000L), YtCommentMoments.parse(html, id, tenMinutes).ms)
    }

    @Test
    fun ampersandEntityAndOtherLinkShapes() {
        assertEquals("abc" to 90_000L, YtCommentMoments.linkTarget("https://www.youtube.com/watch?v=abc&t=90"))
        assertEquals("abc" to 90_000L, YtCommentMoments.linkTarget("https://m.youtube.com/watch?t=1m30s&v=abc"))
        assertEquals("abc" to 90_000L, YtCommentMoments.linkTarget("https://youtu.be/abc?t=90s"))
        assertEquals("abc" to 3_723_000L, YtCommentMoments.linkTarget("https://youtu.be/abc#t=1h2m3s"))
        assertEquals("abc" to 90_000L, YtCommentMoments.linkTarget("/watch?v=abc&t=90"))
        assertNull(YtCommentMoments.linkTarget("https://www.youtube.com/watch?v=abc"))
        assertNull(YtCommentMoments.linkTarget("https://example.com/watch?v=abc&t=90"))
        assertNull(YtCommentMoments.linkTarget("https://www.youtube.com/@channel"))
        // an attribute escaped twice still reads
        val html = """<a href="https://www.youtube.com/watch?v=$id&amp;amp;t=5">0:05</a>"""
        assertEquals(listOf(5_000L), YtCommentMoments.parse(html, id, tenMinutes).ms)
    }

    @Test
    fun linksToOtherVideosAreNoTimesOfThisOne() {
        val html = "See ${anchor(42, video = "otherVideo1")} for that"
        assertTrue(YtCommentMoments.parse(html, id, tenMinutes).ms.isEmpty())
        // its link text is no written time of this video either
        val mixed = "See ${anchor(42, video = "otherVideo1")}, here at 3:10"
        assertEquals(listOf(190_000L), YtCommentMoments.parse(mixed, id, tenMinutes).ms)
    }

    @Test
    fun writtenTimesAreTheFallback() {
        val m = YtCommentMoments.parse("At 2:16 and again at 1:02:16, not 12:345 or 3:75", id, 2 * 3_600_000L)
        assertEquals(listOf(136_000L, 3_736_000L), m.ms)
        // only when the comment has no links of this video
        val linked = YtCommentMoments.parse("${anchor(10)} but also 4:00", id, tenMinutes)
        assertEquals(listOf(10_000L), linked.ms)
    }

    @Test
    fun timesPastTheEndAreDropped() {
        val m = YtCommentMoments.parse("Watching at 23:30 lol, 4:20 is great", id, tenMinutes)
        assertEquals(listOf(260_000L), m.ms)
        // without a known length everything stays
        assertEquals(2, YtCommentMoments.parse("23:30 and 4:20", id, 0).ms.size)
    }

    @Test
    fun parseClockChecksTheParts() {
        assertEquals(136_000L, YtCommentMoments.parseClock("2:16"))
        assertEquals(3_736_000L, YtCommentMoments.parseClock("1:02:16"))
        assertNull(YtCommentMoments.parseClock("2:60"))
        assertNull(YtCommentMoments.parseClock("1:60:00"))
        assertNull(YtCommentMoments.parseClock("12"))
        assertNull(YtCommentMoments.parseClock("a:10"))
    }

    @Test
    fun formatTime() {
        assertEquals("0:05", YtCommentMoments.formatTime(5_400))
        assertEquals("14:20", YtCommentMoments.formatTime(860_000))
        assertEquals("1:02:16", YtCommentMoments.formatTime(3_736_000))
    }

    @Test
    fun plainTextDecodesEntities() {
        assertEquals("a & b\n<c> \"d\" 'e'", YtCommentMoments.plainText("a &amp; b<br><b>&lt;c&gt;</b> &quot;d&quot; &#39;e&#x27;"))
    }

    @Test
    fun timelinesAreRecognised() {
        val timeline = listOf(
            "Timestamps:",
            "${anchor(0)} Intro",
            "1. ${anchor(65)} The setup",
            "The twist ${anchor(250)}",
            "${anchor(400)} Ending",
        ).joinToString("<br>")
        val m = YtCommentMoments.parse(timeline, id, tenMinutes)
        assertTrue(m.isTimeline)
        assertEquals(5, m.lines.size)
        assertEquals(listOf(-1L, 0L, 65_000L, 250_000L, 400_000L), m.lines.map { it.ms })

        // three times in running text are no timeline
        val prose = "Loved ${anchor(10)}, ${anchor(20)} and ${anchor(30)} the most, what a video"
        assertFalse(YtCommentMoments.parse(prose, id, tenMinutes).isTimeline)
    }

    @Test
    fun spoilersAreHiddenUntilSeen() {
        val m = YtCommentMoments.parse("The end at ${anchor(500)} wow", id, tenMinutes)
        val early = YtCommentMoments.classify(m, playback(60_000), SceneMode.SPOILER)
        assertTrue(early.hidden)
        assertEquals(500_000L, early.spoilerAtMs)
        // within the grace it shows
        assertFalse(YtCommentMoments.classify(m, playback(496_000), SceneMode.SPOILER).hidden)
        assertTrue(YtCommentMoments.classify(m, playback(494_000), SceneMode.SPOILER).hidden)
        // the position alone does not reveal it, only the mark of what was seen
        assertTrue(YtCommentMoments.classify(m, playback(550_000, unlocked = 60_000), SceneMode.SPOILER).hidden)
        // off and tapped open
        assertFalse(YtCommentMoments.classify(m, playback(60_000), SceneMode.OFF).hidden)
        assertFalse(YtCommentMoments.classify(m, playback(60_000), SceneMode.SPOILER, revealed = true).hidden)
        // no times, no spoiler
        assertFalse(YtCommentMoments.classify(CommentMoments.NONE, playback(0), SceneMode.SPOILER).hidden)
    }

    @Test
    fun theFirstUnseenTimeIsNamed() {
        val m = YtCommentMoments.parse("${anchor(30)} ok but ${anchor(300)} and ${anchor(450)}", id, tenMinutes)
        assertEquals(300_000L, YtCommentMoments.classify(m, playback(100_000), SceneMode.SPOILER).spoilerAtMs)
        assertEquals(450_000L, YtCommentMoments.classify(m, playback(320_000), SceneMode.SPOILER).spoilerAtMs)
        assertFalse(YtCommentMoments.classify(m, playback(450_000), SceneMode.SPOILER).hidden)
    }

    @Test
    fun timelinesFoldTheirLaterLines() {
        val timeline = listOf("${anchor(0)} Intro", "${anchor(65)} Setup", "${anchor(250)} Twist", "${anchor(400)} End")
            .joinToString("<br>")
        val m = YtCommentMoments.parse(timeline, id, tenMinutes)
        val verdict = YtCommentMoments.classify(m, playback(100_000), SceneMode.SPOILER)
        assertFalse(verdict.hidden)
        assertEquals(2, verdict.shownLines)
        assertEquals(2, verdict.foldedTimes)
        assertEquals(250_000L, verdict.spoilerAtMs)
        assertEquals("${anchor(0)} Intro<br>${anchor(65)} Setup", YtCommentMoments.shownHtml(m, verdict))
        val all = YtCommentMoments.classify(m, playback(420_000), SceneMode.SPOILER)
        assertFalse(all.folded)
        assertNull(YtCommentMoments.shownHtml(m, all))
    }

    @Test
    fun sceneWithoutChapters() {
        val near = YtCommentMoments.parse("${anchor(150)}", id, tenMinutes)
        val far = YtCommentMoments.parse("${anchor(20)}", id, tenMinutes)
        val p = playback(200_000)
        assertEquals(80_000L..210_000L, YtCommentMoments.sceneWindow(p))
        assertEquals(SceneVisibility.SHOW, YtCommentMoments.classify(near, p, SceneMode.SCENE).visibility)
        assertEquals(SceneVisibility.OFF_SCENE, YtCommentMoments.classify(far, p, SceneMode.SCENE).visibility)
        // a comment without times is not about the scene
        assertEquals(SceneVisibility.OFF_SCENE, YtCommentMoments.classify(CommentMoments.NONE, p, SceneMode.SCENE).visibility)
        // in the scene but ahead of what was seen: there, and hidden
        val ahead = YtCommentMoments.parse("${anchor(209)}", id, tenMinutes)
        assertTrue(YtCommentMoments.classify(ahead, playback(200_000, unlocked = 190_000), SceneMode.SCENE).hidden)
    }

    @Test
    fun sceneFollowsTheChapter() {
        val chapters = listOf(0L, 30_000L, 400_000L)
        val p = playback(200_000, chapters = chapters)
        assertEquals(1, YtCommentMoments.chapterIndex(p))
        // the chapter 0:30 to 6:40 widens the window on both sides
        assertEquals(30_000L..399_999L, YtCommentMoments.sceneWindow(p))
        val laterInChapter = YtCommentMoments.parse("${anchor(390)}", id, tenMinutes)
        val nextChapter = YtCommentMoments.parse("${anchor(410)}", id, tenMinutes)
        assertEquals(SceneVisibility.SPOILER, YtCommentMoments.classify(laterInChapter, p, SceneMode.SCENE).visibility)
        assertEquals(SceneVisibility.OFF_SCENE, YtCommentMoments.classify(nextChapter, p, SceneMode.SCENE).visibility)
        // a very long chapter reaches only so far
        val long = ScenePlayback(2_000_000, 2_000_000, 7_200_000, listOf(0L))
        assertEquals(1_400_000L..2_600_000L, YtCommentMoments.sceneWindow(long))
    }

    @Test
    fun boundaries() {
        val b = YtCommentMoments.boundaries(listOf(300_000L, 30_000L, 300_000L, 450_000L))
        assertEquals(listOf(25_000L, 295_000L, 445_000L), b.toList())
        assertEquals(25_000L, YtCommentMoments.nextBoundary(b, 0))
        assertEquals(295_000L, YtCommentMoments.nextBoundary(b, 25_000))
        assertEquals(445_000L, YtCommentMoments.nextBoundary(b, 300_000))
        assertEquals(Long.MAX_VALUE, YtCommentMoments.nextBoundary(b, 445_000))
        assertEquals(Long.MAX_VALUE, YtCommentMoments.nextBoundary(LongArray(0), 0))
    }

    @Test
    fun crossingABoundaryChangesTheVerdict() {
        val m = YtCommentMoments.parse("${anchor(300)}", id, tenMinutes)
        val boundary = YtCommentMoments.nextBoundary(YtCommentMoments.boundaries(m.ms), 0)
        assertTrue(YtCommentMoments.classify(m, playback(boundary - 1), SceneMode.SPOILER).hidden)
        assertFalse(YtCommentMoments.classify(m, playback(boundary), SceneMode.SPOILER).hidden)
    }

    @Test
    fun modeKeys() {
        assertEquals(SceneMode.SCENE, SceneMode.of("scene"))
        assertEquals(SceneMode.SPOILER, SceneMode.of("spoiler"))
        assertEquals(SceneMode.OFF, SceneMode.of(null))
        assertEquals(SceneMode.OFF, SceneMode.of("whatever"))
    }

    @Test
    fun linkTimeFormats() {
        assertEquals(90L, YtCommentMoments.parseLinkTime("90"))
        assertEquals(90L, YtCommentMoments.parseLinkTime("90s"))
        assertEquals(90L, YtCommentMoments.parseLinkTime("1m30s"))
        assertEquals(3_723L, YtCommentMoments.parseLinkTime("1h2m3s"))
        assertEquals(90L, YtCommentMoments.parseLinkTime("1:30"))
        assertEquals(12L, YtCommentMoments.parseLinkTime("12.7"))
        assertNull(YtCommentMoments.parseLinkTime(""))
        assertNull(YtCommentMoments.parseLinkTime("soon"))
    }
}
