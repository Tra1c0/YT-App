package com.github.libretube.ui.yt.ssi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class YtSsiChronologyTest {
    private val utc = ZoneId.of("UTC")
    private fun day(y: Int, m: Int = 1, d: Int = 1) = LocalDate.of(y, m, d).atStartOfDay(utc).toInstant().toEpochMilli()

    private fun hit(id: String, seg: Long, up: Long?, channel: String? = "Anna Beispiel", ms: Long = 0) = YtSsiHit(
        videoId = id, title = "T$id", channelName = channel, uploaded = up, language = null, segmentId = seg, startMs = ms, endMs = ms + 1000,
        hitMs = ms, contextStartMs = 0, contextEndMs = 0, text = "x", highlights = emptyList(), matches = emptySet(), methods = emptySet(),
        timing = YtSsiTiming.WORD, availability = YtSsiAvailability.PUBLIC, rank = 1, fused = 0.0, ranks = emptyMap(),
    )

    private fun stance(seg: Long, relation: YtSsiRelation, verified: Boolean = true) = YtSsiStance(
        seg, if (verified) relation else YtSsiRelation.UNCERTAIN,
        if (verified) YtSsiEvidence("q $seg", seg, 0, YtSsiTiming.WORD) else null,
        opposition = if (verified && relation == YtSsiRelation.REFUTES) YtSsiOpposition.CONTRADICTS else null,
        claimed = relation, unsure = if (verified) null else YtSsiUnsure.QUOTE_NOT_FOUND,
    )

    @Test
    fun ordersByUploadDateWithUndatedLast() {
        val hits = listOf(hit("c", 3, day(2023)), hit("x", 9, null), hit("a", 1, day(2019)), hit("b", 2, day(2021)), hit("a", 4, day(2019), ms = 5_000))
        assertEquals(listOf(1L, 4L, 2L, 3L, 9L), YtSsiChronology.order(hits).map { it.segmentId })
        val years = YtSsiChronology.years(hits, utc)
        assertEquals(listOf(2019, 2021, 2023, null), years.map { it.year })
        assertEquals(2, years[0].hits.size)
    }

    @Test
    fun aChangeOfPositionNeedsTwoVerifiedOppositeStances() {
        val hits = listOf(hit("a", 1, day(2018)), hit("b", 2, day(2020)), hit("c", 3, day(2022)), hit("d", 4, day(2024)))
        val stances = mapOf(
            1L to stance(1, YtSsiRelation.SUPPORTS),
            2L to stance(2, YtSsiRelation.REFUTES, verified = false), // not evidenced: ignored
            3L to stance(3, YtSsiRelation.REFUTES),
            4L to stance(4, YtSsiRelation.QUALIFIES),
        )
        val shifts = YtSsiChronology.shifts(hits, stances)
        assertEquals(2, shifts.size)
        assertEquals(YtSsiShiftKind.CHANGED, shifts[0].kind)
        assertEquals(1L to 3L, shifts[0].earlier.segmentId to shifts[0].later.segmentId)
        assertEquals(YtSsiShiftKind.NARROWED, shifts[1].kind)
        assertEquals(3L to 4L, shifts[1].earlier.segmentId to shifts[1].later.segmentId)
    }

    @Test
    fun noShiftWithoutDatesOrForTheSamePosition() {
        val hits = listOf(hit("a", 1, day(2018)), hit("b", 2, null), hit("c", 3, day(2022)))
        val stances = mapOf(1L to stance(1, YtSsiRelation.SUPPORTS), 2L to stance(2, YtSsiRelation.REFUTES), 3L to stance(3, YtSsiRelation.SUPPORTS))
        assertTrue(YtSsiChronology.shifts(hits, stances).isEmpty())
        // discusses and quotes carry no position
        val other = mapOf(1L to stance(1, YtSsiRelation.SUPPORTS), 3L to stance(3, YtSsiRelation.DISCUSSES))
        assertTrue(YtSsiChronology.shifts(hits, other).isEmpty())
    }

    @Test
    fun followsOnePerson() {
        val hits = listOf(hit("a", 1, day(2018)), hit("b", 2, day(2020), channel = "Bert"), hit("c", 3, day(2022), channel = "anna beispiel"), hit("d", 4, day(2021), channel = "Bert"))
        assertEquals(listOf(1L, 3L), YtSsiChronology.of(hits, "Anna Beispiel").map { it.segmentId })
        assertEquals(listOf(1L, 3L), YtSsiChronology.of(hits, "@annabeispiel").map { it.segmentId })
        assertEquals(2, YtSsiChronology.people(hits).size)
        val intent = YtSsiIntentParser.parse("wie hat sich @annabeispiel im Laufe der Jahre zu Atomkraft geäußert", day(2025), utc)
        assertEquals("annabeispiel", YtSsiChronology.person(intent))
        assertTrue(YtSsiLater.CHRONOLOGY in intent.later)
    }
}
