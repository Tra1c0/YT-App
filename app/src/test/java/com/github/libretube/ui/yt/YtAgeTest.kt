package com.github.libretube.ui.yt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

class YtAgeTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val now = LocalDateTime.of(2026, 11, 7, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    private fun age(agoMs: Long) = YtAgeFormat.of(now - agoMs, now, zone)
    private fun at(year: Int, month: Int, dayOfMonth: Int, h: Int = 12) =
        YtAgeFormat.of(LocalDateTime.of(year, month, dayOfMonth, h, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), now, zone)

    private fun de(a: YtAge?) = YtAgeFormat.amount(a!!, Locale.GERMANY)
    private fun en(a: YtAge?) = YtAgeFormat.amount(a!!, Locale.US)

    @Test
    fun `minutes up to 59`() {
        assertEquals(YtAge(YtAgeUnit.MINUTES, 2), age(10_000))
        assertEquals(YtAge(YtAgeUnit.MINUTES, 74), age(37 * minute + 20_000))
        assertEquals("37", de(age(37 * minute)))
        assertEquals(YtAge(YtAgeUnit.MINUTES, 118), age(59 * minute + 59_000))
    }

    @Test
    fun `hours rounded to half hours`() {
        assertEquals(YtAge(YtAgeUnit.HOURS, 2), age(60 * minute))
        assertEquals(YtAge(YtAgeUnit.HOURS, 2), age(74 * minute))
        assertEquals(YtAge(YtAgeUnit.HOURS, 3), age(80 * minute))
        val threeAndHalf = age(3 * hour + 25 * minute)
        assertEquals(YtAge(YtAgeUnit.HOURS, 7), threeAndHalf)
        assertEquals("3,5", de(threeAndHalf))
        assertEquals("3.5", en(threeAndHalf))
        assertEquals("1", de(age(hour)))
        assertEquals(YtAgeUnit.HOURS, age(23 * hour + 59 * minute)!!.unit)
    }

    @Test
    fun `days then weeks`() {
        assertEquals(YtAge(YtAgeUnit.DAYS, 2), age(day))
        assertEquals(YtAge(YtAgeUnit.DAYS, 8), age(4 * day + 3 * hour))
        assertEquals(YtAge(YtAgeUnit.DAYS, 12), age(6 * day + 23 * hour))
        assertEquals(YtAge(YtAgeUnit.WEEKS, 2), age(7 * day))
        assertEquals(YtAge(YtAgeUnit.WEEKS, 6), age(3 * 7 * day + 2 * day))
        // 30 days after the 8th of October is not yet a calendar month
        assertEquals(YtAge(YtAgeUnit.WEEKS, 8), at(2026, 10, 8, 13))
    }

    @Test
    fun `months by the calendar`() {
        assertEquals(YtAge(YtAgeUnit.MONTHS, 2), at(2026, 10, 7))
        assertEquals(YtAge(YtAgeUnit.MONTHS, 10), at(2026, 6, 1))
        assertEquals(YtAge(YtAgeUnit.MONTHS, 22), at(2025, 12, 1))
    }

    @Test
    fun `years in halves`() {
        assertEquals(YtAge(YtAgeUnit.YEARS, 2), at(2025, 11, 7))
        assertEquals(YtAge(YtAgeUnit.YEARS, 2), at(2025, 9, 1)) // 14 months
        assertEquals(YtAge(YtAgeUnit.YEARS, 3), at(2025, 6, 1)) // 17 months
        val twoAndHalf = at(2024, 5, 7) // 30 months
        assertEquals(YtAge(YtAgeUnit.YEARS, 5), twoAndHalf)
        assertEquals("2,5", de(twoAndHalf))
        assertEquals("2.5", en(twoAndHalf))
        assertEquals("10", de(at(2016, 11, 1)))
    }

    @Test
    fun `plural count reads a half as plural`() {
        assertEquals(1, YtAge(YtAgeUnit.YEARS, 2).quantity)
        assertEquals(2, YtAge(YtAgeUnit.YEARS, 3).quantity)
        assertEquals(4, YtAge(YtAgeUnit.DAYS, 8).quantity)
    }

    @Test
    fun `unknown or upcoming has no age`() {
        assertNull(YtAgeFormat.of(0, now, zone))
        assertNull(YtAgeFormat.of(-1, now, zone))
        assertNull(YtAgeFormat.of(now + hour, now, zone))
    }

    @Test
    fun `a day without time`() {
        val today = LocalDate.of(2026, 11, 7)
        assertEquals(YtAge(YtAgeUnit.TODAY, 0), YtAgeFormat.ofDate(today, today))
        assertEquals(YtAge(YtAgeUnit.DAYS, 2), YtAgeFormat.ofDate(today.minusDays(1), today))
        assertEquals(YtAge(YtAgeUnit.WEEKS, 4), YtAgeFormat.ofDate(today.minusDays(14), today))
        assertEquals(YtAge(YtAgeUnit.MONTHS, 6), YtAgeFormat.ofDate(LocalDate.of(2026, 8, 7), today))
        assertEquals(YtAge(YtAgeUnit.YEARS, 3), YtAgeFormat.ofDate(LocalDate.of(2025, 5, 7), today))
        assertNull(YtAgeFormat.ofDate(today.plusDays(1), today))
    }
}
