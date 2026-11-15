package com.github.libretube.ui.yt.ssi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class YtSsiPlayRulesTest {
    private val text = "Erster Satz hier. Zweiter Satz mit dem Treffer drin."
    private val segment = YtSsiSegment(
        "v", 0, 10_000, 16_000, text, YtSsiSegmentKind.SPEECH, YtSsiTiming.WORD,
        YtSsiText.wordRanges(text).mapIndexed { i, r -> YtSsiMark(r.first, 10_000L + i * 600L) },
    )

    private fun hit(video: String, start: Long, end: Long, hitMs: Long = start, timing: YtSsiTiming = YtSsiTiming.WORD) = YtSsiHit(
        videoId = video, title = video, channelName = null, uploaded = null, language = null, segmentId = 1, startMs = start, endMs = end,
        hitMs = hitMs, contextStartMs = start, contextEndMs = end, text = "", highlights = emptyList(), matches = setOf(YtSsiMatch.TRANSCRIPT_LEXICAL),
        methods = setOf(YtSsiMethod.FTS_BM25), timing = timing, availability = YtSsiAvailability.PUBLIC, rank = 1, fused = 0.1,
        ranks = mapOf(YtSsiMethod.FTS_BM25 to 1),
    )

    @Test
    fun precisionIsNeverPretended() {
        assertEquals("1:02", YtSsiPlayRules.stamp(62_400, YtSsiTiming.WORD))
        assertEquals("≈ 1:02", YtSsiPlayRules.stamp(62_400, YtSsiTiming.LINE))
        assertEquals("≈ 1:01:02", YtSsiPlayRules.stamp(3_662_000, YtSsiTiming.APPROX))
        assertFalse(YtSsiPlayRules.label(YtSsiTiming.WORD).approximate)
        assertTrue(YtSsiPlayRules.label(YtSsiTiming.LINE).approximate)
        assertTrue(YtSsiPlayRules.label(YtSsiTiming.APPROX).approximate)
    }

    @Test
    fun startsAtASentenceBeforeTheHit() {
        // "Zweiter" is word 3 (at 11.8 s) and starts a sentence; "Treffer" is word 7 (at 14.2 s)
        assertEquals(listOf(10_000L, 11_800L), YtSsiPlayRules.sentenceStarts(segment))
        // 1.5 s before the hit is 12.7 s: the nearest sentence start is 11.8 s, less the pre-roll
        assertEquals(11_500L, YtSsiPlayRules.start(segment, 14_200))
        // at 12 s the passage start (10 s) is nearer to 10.5 s than 11.8 s is
        assertEquals(9_700L, YtSsiPlayRules.start(segment, 12_000))
        // a sentence start after the hit never counts; none in reach: 1.5 s before the hit
        assertEquals(9_700L, YtSsiPlayRules.start(segment, 10_800))
        assertEquals(18_500L, YtSsiPlayRules.start(segment, 20_000))
        // a span starts at its start
        assertEquals(10_000L, YtSsiPlayRules.start(segment.copy(timing = YtSsiTiming.APPROX), 14_200))
        assertEquals(0L, YtSsiPlayRules.start(null, -5))
    }

    @Test
    fun clipsGoPerVideoAndJoin() {
        val hits = listOf(hit("a", 60_000, 70_000), hit("b", 5_000, 9_000), hit("a", 10_000, 15_000), hit("a", 70_500, 80_000))
        val clips = YtSsiPlayRules.clips(hits, segments = { null }, before = mapOf(1 to 30_000L), after = mapOf(2 to 30_000L))
        assertEquals(listOf("a", "a", "b"), clips.map { it.videoId })
        // a's clips in time order; 60–70 s and 70.5–80 s joined
        assertEquals(10_000L to 46_000L, clips[0].startMs to clips[0].endMs)
        assertEquals(60_000L to 81_000L, clips[1].startMs to clips[1].endMs)
        // b got 30 s more before (not below 0)
        assertEquals(0L, clips[2].startMs)
    }

    @Test
    fun reasonsNameEverySignal() {
        val intent = YtSsiIntentParser.parse("Treffer 2023", 1_800_000_000_000L, ZoneId.of("UTC"))
        val reasons = YtSsiPlayRules.reasons(
            hit("a", 0, 1000).copy(
                methods = setOf(YtSsiMethod.FTS_BM25, YtSsiMethod.EMBEDDING, YtSsiMethod.YOUTUBE_SEARCH),
                ranks = mapOf(YtSsiMethod.FTS_BM25 to 2, YtSsiMethod.EMBEDDING to 5),
                availability = YtSsiAvailability.VANISHED_ARCHIVED,
                modelVersion = "m/1",
                timing = YtSsiTiming.LINE,
            ),
            intent,
            listOf("treffer"),
        )
        assertEquals(
            listOf(
                // SSI-2: the relevance band comes first
                YtSsiReason.Band(YtSsiBand.LOW, ai = false),
                YtSsiReason.Lexical(2, listOf("treffer")),
                YtSsiReason.Semantic(5, "m/1"),
                YtSsiReason.FromYouTubeSearch,
                YtSsiReason.TimeFilter,
                YtSsiReason.Vanished,
                YtSsiReason.Timing(YtSsiPrecisionLabel.LINE),
            ),
            reasons,
        )
    }

    @Test
    fun ssi2ReasonsNameTheAiGradeAndTheBorders() {
        val intent = YtSsiIntentParser.parse("Treffer", 1_800_000_000_000L, ZoneId.of("UTC"))
        val reasons = YtSsiPlayRules.reasons(
            hit("a", 0, 1000).copy(
                rank = 1,
                localRank = 6,
                band = YtSsiBand.HIGH,
                ai = YtSsiAiGrade(3, 2..5),
                span = YtSsiSpan(0, 900, 0, YtSsiTiming.WORD, verified = true, qualifier = true),
            ),
            intent,
            listOf("treffer"),
        )
        assertEquals(YtSsiReason.Band(YtSsiBand.HIGH, ai = true), reasons[0])
        assertEquals(YtSsiReason.AiGrade(3, before = 6, after = 1, span = true, cached = false), reasons[1])
        assertEquals(YtSsiReason.Bounds(verified = true, qualifier = true, label = YtSsiPrecisionLabel.WORD), reasons.last())
    }

    @Test
    fun clipsFollowTheCheckedSpan() {
        val withSpan = hit("a", 10_000, 12_000, 10_500).copy(span = YtSsiSpan(9_800, 15_000, 9_500, YtSsiTiming.WORD, verified = true, qualifier = true))
        assertEquals(9_500L, YtSsiPlayRules.playStart(withSpan, segment))
        assertEquals(15_000L + YtSsiPlayRules.TAIL_MS, YtSsiPlayRules.clipEnd(withSpan))
        val clip = YtSsiPlayRules.clips(listOf(withSpan), segments = { segment }).single()
        assertEquals(9_500L, clip.startMs)
        assertEquals(16_000L, clip.endMs)
        // without a span the SSI-1 rules stay
        val plain = hit("a", 10_000, 16_000, 14_200)
        assertEquals(YtSsiPlayRules.start(segment, 14_200), YtSsiPlayRules.playStart(plain, segment))
    }
}
