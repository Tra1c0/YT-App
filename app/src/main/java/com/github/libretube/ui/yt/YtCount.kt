package com.github.libretube.ui.yt

import android.content.Context
import android.content.res.Configuration
import com.github.libretube.LibreTubeApp
import com.github.libretube.R
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The templates for the three compact steps, each with one `%s` for the amount:
 * "~%sk", "~%s Mio.", "~%s Mrd." in German, "~%sk", "~%sM", "~%sB" in English.
 */
data class YtCountUnits(val thousands: String, val millions: String, val billions: String)

/**
 * YT: counts (views, subscribers, likes) as people read them. 0 to 999 exactly; from 1.000 on
 * compact and marked as approximate: "~1,5k" and "~27k", "~1,2 Mio." and "~27 Mio.", "~1,1 Mrd.".
 * Below 10 of a unit one decimal is kept (a trailing ",0" is dropped, so "~2k"), from 10 on
 * only whole units. Amounts are cut, not rounded, so 999.999 stays "~999k" instead of turning
 * into "~1000k", and "~" never claims more than there is. German CLDR has no short form for
 * thousands, which is why the system formatter printed "327.000 Aufrufe" and cut the age off.
 * Pure, so it can be tested on the JVM.
 */
object YtCountFormat {
    private const val THOUSAND = 1_000L
    private const val MILLION = 1_000_000L
    private const val BILLION = 1_000_000_000L

    /** [count] as text in [locale] with [units]; negative counts read as 0. */
    fun format(count: Long, locale: Locale, units: YtCountUnits): String {
        val n = count.coerceAtLeast(0)
        if (n < THOUSAND) return n.toString()
        val (step, template) = when {
            n < MILLION -> THOUSAND to units.thousands
            n < BILLION -> MILLION to units.millions
            else -> BILLION to units.billions
        }
        return template.format(amount(n, step, locale))
    }

    /** [n] in units of [step], one decimal below 10, cut and not rounded. */
    fun amount(n: Long, step: Long, locale: Locale): String {
        val whole = n / step
        val format = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = if (whole < 10) 1 else 0
            roundingMode = RoundingMode.DOWN
            isGroupingUsed = false
        }
        // tenths as an exact decimal, so no binary fraction (1.9 as 1.8999…) is cut a tenth short
        return format.format(if (whole < 10) BigDecimal.valueOf(n / (step / 10), 1) else BigDecimal.valueOf(whole))
    }

    private val unitCache = ConcurrentHashMap<Locale, YtCountUnits>()

    /** The templates of [locale], read from the app strings once per locale. */
    fun units(context: Context, locale: Locale): YtCountUnits = unitCache.getOrPut(locale) {
        val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
        val res = context.createConfigurationContext(config).resources
        YtCountUnits(
            res.getString(R.string.yt_count_thousands),
            res.getString(R.string.yt_count_millions),
            res.getString(R.string.yt_count_billions),
        )
    }

    /** [count] in the language the app currently shows (BaseActivity sets the default locale). */
    fun format(count: Long): String {
        val locale = Locale.getDefault()
        return format(count, locale, units(LibreTubeApp.instance, locale))
    }
}
