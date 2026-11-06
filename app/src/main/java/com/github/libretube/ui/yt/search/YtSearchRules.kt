package com.github.libretube.ui.yt.search

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/*
 * YT: the plain rules of the design search (no Android, tested on the JVM): what the list under
 * the field shows, when suggestions are fetched, the numbers of Editorial, the filters of
 * YouTube neu and the geometry of the fields that grow out of the button that opened them.
 */

/** One line under the field: a past search (can be deleted) or a suggestion from the service. */
data class YtSearchItem(val text: String, val history: Boolean)

object YtSearchRules {
    /** Suggestions wait this long after the last key, so a fast typist sends one request. */
    const val DEBOUNCE_MS = 250L

    /** Past searches while the field is empty. */
    const val HISTORY_EMPTY = 12

    /** Past searches that start with what was typed. */
    const val HISTORY_TYPING = 5

    /** Suggestions under them. */
    const val SUGGESTIONS = 10

    /** The text to search for, or null when there is nothing to search for. */
    fun query(text: String): String? = text.trim().takeIf { it.isNotEmpty() }

    /** Whether to ask the service for suggestions for [typed]. */
    fun fetches(typed: String, suggestionsOn: Boolean): Boolean = suggestionsOn && query(typed) != null

    /**
     * The past searches to show for [typed], newest first as [all] comes from the database: all
     * of them (up to [HISTORY_EMPTY]) for an empty field, else those that start with the text,
     * ignoring case (up to [HISTORY_TYPING]).
     */
    fun history(all: List<String>, typed: String): List<String> {
        val q = typed.trim()
        val clean = all.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
        if (q.isEmpty()) return clean.take(HISTORY_EMPTY)
        return clean.filter { it.startsWith(q, ignoreCase = true) }.take(HISTORY_TYPING)
    }

    /** History first, then the suggestions that are not already in it (ignoring case). */
    fun merge(history: List<String>, suggestions: List<String>): List<YtSearchItem> {
        val seen = HashSet<String>()
        val out = ArrayList<YtSearchItem>()
        for (h in history) if (seen.add(h.trim().lowercase())) out += YtSearchItem(h, true)
        var n = 0
        for (s in suggestions) {
            if (n >= SUGGESTIONS) break
            if (s.isBlank()) continue
            if (seen.add(s.trim().lowercase())) {
                out += YtSearchItem(s, false)
                n++
            }
        }
        return out
    }

    /** Editorial numbers its lines like a printed index: 01, 02, ... 10, 11. */
    fun number(index: Int): String = (index + 1).toString().padStart(2, '0')

    /** The filters of the results screen that YouTube neu offers under its field (key of the screen). */
    val FILTERS = listOf("all", "videos", "channels", "playlists", "music_songs")

    /** The filter to hand to the results screen: null for "all", which it shows anyway. */
    fun filterFor(key: String?): String? = key?.takeIf { it in FILTERS && it != "all" }

    /** Delay in ms of the [index]-th entry when the entries come in one after another. */
    fun stagger(index: Int, stepMs: Int, maxMs: Int): Int = (index * stepMs).coerceIn(0, maxMs)

    /** The part of [k] (0..1) that falls between [from] and [to], as 0..1. */
    fun phase(k: Float, from: Float, to: Float): Float =
        if (to <= from) (if (k >= to) 1f else 0f) else ((k - from) / (to - from)).coerceIn(0f, 1f)

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** A fall that bounces twice before it rests (Kiesel's pebbles): 0 at the top, 1 at rest. */
    fun bounce(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        val n = 7.5625f
        val d = 2.75f
        return when {
            x < 1f / d -> n * x * x
            x < 2f / d -> (x - 1.5f / d).let { n * it * it + 0.75f }
            x < 2.5f / d -> (x - 2.25f / d).let { n * it * it + 0.9375f }
            else -> (x - 2.625f / d).let { n * it * it + 0.984375f }
        }
    }

    /** The scale a field starts at to be as large as the button ([startPx]) it grows out of. */
    fun startScale(startPx: Float, fullPx: Float): Float =
        if (fullPx <= 0f) 1f else (startPx / fullPx).coerceIn(0.04f, 1f)

    /**
     * Where along a box (from [start], [size] long) a point lies, as a fraction for a transform
     * origin. Outside the box the fraction goes past 0 or 1 (the field then grows towards the
     * box from a button below or beside it), but not further than a few box lengths.
     */
    fun pivot(point: Float, start: Float, size: Float): Float =
        if (size <= 0f) 0.5f else ((point - start) / size).coerceIn(-3f, 4f)

    /**
     * The outline of Formen's cookie on its way to the field, in polar form around the centre of
     * a box of half sizes [halfW] and [halfH]: at [k] 0 a cookie with [lobes] scallops as wide as
     * the box is high, at 1 a rounded rectangle filling the box (a superellipse of [power]).
     */
    fun morphRadius(angle: Double, halfW: Float, halfH: Float, lobes: Int, depth: Float, k: Float, power: Double = 5.0): Float {
        val r0 = minOf(halfW, halfH) * (1f - depth + depth * cos(lobes * angle).toFloat())
        val c = abs(cos(angle))
        val s = abs(sin(angle))
        val sum = (c / halfW).pow(power) + (s / halfH).pow(power)
        val r1 = if (sum <= 0.0) halfW else sum.pow(-1.0 / power).toFloat()
        return lerp(r0, r1, k.coerceIn(0f, 1f))
    }

    /** Points of [morphRadius] around the box, as x, y pairs relative to its top left corner. */
    fun morphPoints(width: Float, height: Float, lobes: Int, depth: Float, k: Float, steps: Int = 96): FloatArray {
        val hw = width / 2f
        val hh = height / 2f
        val out = FloatArray(steps * 2)
        for (i in 0 until steps) {
            val a = 2 * PI * i / steps
            val r = morphRadius(a, hw, hh, lobes, depth, k)
            out[2 * i] = hw + r * cos(a).toFloat()
            out[2 * i + 1] = hh + r * sin(a).toFloat()
        }
        return out
    }

    /** The text of a history line for TalkBack and the old row: the query itself. */
    fun label(item: YtSearchItem): String = item.text.trim()

    /** A tap older than this does not count as the place the search was opened from. */
    const val ORIGIN_MAX_AGE_MS = 2500L

    /** Whether a touch at [downAt] still explains an opening at [now]. */
    fun originFresh(downAt: Long, now: Long): Boolean = downAt > 0 && now >= downAt && now - downAt <= ORIGIN_MAX_AGE_MS
}
