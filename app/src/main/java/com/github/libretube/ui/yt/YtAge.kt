package com.github.libretube.ui.yt

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.github.libretube.R
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.extensions.formatShort
import com.github.libretube.util.TextUtils
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToLong

/** The unit an upload age is told in. */
enum class YtAgeUnit { TODAY, MINUTES, HOURS, DAYS, WEEKS, MONTHS, YEARS }

/**
 * How long ago something was uploaded, in [halves] of [unit]. Only hours and years are counted
 * in halves ("~3,5 Std.", "~2,5 Jahre"); every other unit is whole, so [halves] is even there.
 */
data class YtAge(val unit: YtAgeUnit, val halves: Long) {
    val whole: Boolean get() = halves % 2 == 0L

    /** The count for the plural rules: a half ("1,5") always reads as plural. */
    val quantity: Int get() = if (whole) (halves / 2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 2
}

/**
 * YT: the age of an upload as people say it, instead of a raw "2026-03-14": minutes up to 59,
 * then hours rounded to half hours, days up to 6, weeks up to 4, months up to 11, then years
 * rounded to half years. Pure, so it can be tested on the JVM.
 */
object YtAgeFormat {
    private const val MINUTE = 60_000L
    private const val HALF_HOUR = 30 * MINUTE
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    /** The age of an upload at [uploadedMs] at [nowMs]; null when unknown (0 or negative) or still to come. */
    fun of(uploadedMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): YtAge? {
        if (uploadedMs <= 0) return null
        val diff = nowMs - uploadedMs
        if (diff < 0) return null
        if (diff < HOUR) return YtAge(YtAgeUnit.MINUTES, 2 * max(1L, diff / MINUTE))
        if (diff < DAY) return YtAge(YtAgeUnit.HOURS, max(2L, (diff.toDouble() / HALF_HOUR).roundToLong()))
        // months and years by the calendar, so "1 Monat" starts on the same day of the next month
        val from = LocalDateTime.ofInstant(Instant.ofEpochMilli(uploadedMs), zone)
        val to = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        return byCalendar(diff / DAY, from.until(to, ChronoUnit.MONTHS))
    }

    /**
     * The age of an upload known only by its day ([date], e.g. a watch history entry): from the
     * day on it is told in days and up, and the day itself is "today", since no hour is known.
     */
    fun ofDate(date: LocalDate, today: LocalDate): YtAge? {
        if (date.isAfter(today)) return null
        val days = ChronoUnit.DAYS.between(date, today)
        if (days == 0L) return YtAge(YtAgeUnit.TODAY, 0)
        return byCalendar(days, ChronoUnit.MONTHS.between(date, today))
    }

    private fun byCalendar(days: Long, months: Long): YtAge = when {
        months >= 12 -> YtAge(YtAgeUnit.YEARS, max(2L, (months / 6.0).roundToLong()))
        months >= 1 -> YtAge(YtAgeUnit.MONTHS, 2 * months)
        days >= 7 -> YtAge(YtAgeUnit.WEEKS, 2 * (days / 7))
        else -> YtAge(YtAgeUnit.DAYS, 2 * max(1L, days))
    }

    /** The number of [age] as written in [locale]: "3", "3,5" in German, "3.5" in English. */
    fun amount(age: YtAge, locale: Locale): String {
        val format = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 1
            isGroupingUsed = false
        }
        return format.format(age.halves / 2.0)
    }

    @PluralsRes
    fun plural(unit: YtAgeUnit, ago: Boolean): Int = when (unit) {
        YtAgeUnit.TODAY, YtAgeUnit.MINUTES -> if (ago) R.plurals.yt_age_ago_minutes else R.plurals.yt_age_minutes
        YtAgeUnit.HOURS -> if (ago) R.plurals.yt_age_ago_hours else R.plurals.yt_age_hours
        YtAgeUnit.DAYS -> if (ago) R.plurals.yt_age_ago_days else R.plurals.yt_age_days
        YtAgeUnit.WEEKS -> if (ago) R.plurals.yt_age_ago_weeks else R.plurals.yt_age_weeks
        YtAgeUnit.MONTHS -> if (ago) R.plurals.yt_age_ago_months else R.plurals.yt_age_months
        YtAgeUnit.YEARS -> if (ago) R.plurals.yt_age_ago_years else R.plurals.yt_age_years
    }

    /** [age] as text. [ago] gives the form for a sentence ("vor 3 Wochen"), else the bare one ("3 Wochen"). */
    fun text(context: Context, age: YtAge, ago: Boolean): String {
        if (age.unit == YtAgeUnit.TODAY) return context.getString(R.string.yt_age_today)
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        return context.resources.getQuantityString(plural(age.unit, ago), age.quantity, amount(age, locale))
    }
}

/** The age of an upload at [uploadedMs] as text, or null when it is not known. */
fun ytAgeText(context: Context, uploadedMs: Long, ago: Boolean = false, nowMs: Long = System.currentTimeMillis()): String? =
    YtAgeFormat.of(uploadedMs, nowMs)?.let { YtAgeFormat.text(context, it, ago) }

/**
 * The age of [video] as text: from its upload time, else from a "2026-03-14" date it came with.
 * Any other text it came with (some sources already say "vor 3 Tagen") is shown as it is.
 */
fun ytVideoAge(context: Context, video: StreamItem, ago: Boolean = false): String? {
    ytAgeText(context, video.uploaded, ago)?.let { return it }
    val raw = video.uploadedDate ?: return null
    val date = runCatching { LocalDate.parse(raw.trim()) }.getOrNull() ?: return raw
    return YtAgeFormat.ofDate(date, LocalDate.now())?.let { YtAgeFormat.text(context, it, ago) } ?: raw
}

@Composable
fun ytVideoAge(video: StreamItem, ago: Boolean = false): String? = ytVideoAge(LocalContext.current, video, ago)

/** "Uploader • 320.000 Aufrufe • vor 3 Wochen", like the classic meta line, with the age told as above. */
fun ytViewsAndAge(context: Context, views: Long, uploadedMs: Long, uploader: String? = null): String {
    val viewsText = views.takeIf { it != -1L }?.formatShort()?.let { context.getString(R.string.view_count, it) }
    return listOfNotNull(uploader, viewsText, ytAgeText(context, uploadedMs, ago = true)).joinToString(TextUtils.SEPARATOR)
}
