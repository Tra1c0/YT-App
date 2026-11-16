package com.github.libretube.ui.yt.ssi

import java.time.Instant
import java.time.ZoneId

/** SSI-3: what a pair of a person's statements may show (a hint, never a finding). */
enum class YtSsiShiftKind {
    /** One supports the claim, the other is against it. */
    CHANGED,

    /** A clear position became a qualified one (or the other way round). */
    NARROWED,
}

/**
 * SSI-3: a possible change of position between [earlier] and [later] (two hits of one channel,
 * each with a verified stance). Only a hint: the facts or the question may have changed between.
 */
data class YtSsiShift(val earlier: YtSsiHit, val earlierStance: YtSsiStance, val later: YtSsiHit, val laterStance: YtSsiStance, val kind: YtSsiShiftKind)

/** One year of the timeline. [year] is null for hits without an upload date (shown last). */
data class YtSsiYear(val year: Int?, val hits: List<YtSsiHit>)

/**
 * YT (SSI-3): the timeline and a person's statements over time, pure. Hits are ordered by upload
 * date (oldest first; inside a video by time; without a date last). A "person" is a channel. A
 * change of position is flagged only between two of its statements that both carry a verified
 * stance (an AI relation backed by a quote found in each passage) of opposite direction, and
 * only as a question ("änderte Position?").
 */
object YtSsiChronology {
    /** [hits] by upload date, oldest first; undated last in their found order. */
    fun order(hits: List<YtSsiHit>): List<YtSsiHit> = hits.withIndex().sortedWith(
        compareBy<IndexedValue<YtSsiHit>> { it.value.uploaded == null }
            .thenBy { it.value.uploaded ?: 0L }
            .thenBy { it.value.videoId }
            .thenBy { it.value.hitMs }
            .thenBy { it.index },
    ).map { it.value }

    /** [hits] in [order], grouped by the year of their upload in [zone]. */
    fun years(hits: List<YtSsiHit>, zone: ZoneId): List<YtSsiYear> {
        val out = LinkedHashMap<Int?, MutableList<YtSsiHit>>()
        for (hit in order(hits)) {
            val year = hit.uploaded?.let { Instant.ofEpochMilli(it).atZone(zone).year }
            out.getOrPut(year) { ArrayList() } += hit
        }
        return out.map { (year, list) -> YtSsiYear(year, list) }
    }

    /** Channels with at least [min] hits, most hits first (the people a timeline can follow). */
    fun people(hits: List<YtSsiHit>, min: Int = 2): List<String> = hits.mapNotNull { it.channelName?.takeIf(String::isNotBlank) }
        .groupingBy { YtSsiText.fold(it) }.eachCount().filter { it.value >= min }
        .entries.sortedByDescending { it.value }
        .mapNotNull { (folded, _) -> hits.firstOrNull { YtSsiText.fold(it.channelName.orEmpty()) == folded }?.channelName }

    /** The person a query names: its first channel filter, else its first channel guess. */
    fun person(intent: YtSsiIntent?): String? = intent?.channels?.firstOrNull() ?: intent?.channelGuesses?.firstOrNull()

    /** [hits] of [person] (folded name, or a handle without "@"). */
    fun of(hits: List<YtSsiHit>, person: String): List<YtSsiHit> {
        val name = YtSsiText.fold(person.removePrefix("@"))
        return hits.filter { val c = YtSsiText.fold(it.channelName.orEmpty()); c == name || c.replace(" ", "") == name.replace(" ", "") }
    }

    /** +1 for the claim, −1 against it, 0 qualified; null when it says nothing about a position. */
    fun direction(stance: YtSsiStance?): Int? {
        if (stance == null || !stance.verified) return null
        return when (stance.relation) {
            YtSsiRelation.SUPPORTS -> 1
            YtSsiRelation.REFUTES -> -1
            YtSsiRelation.QUALIFIES -> 0
            else -> null
        }
    }

    /**
     * Possible changes of position in [hits] of one person: each statement with a direction is
     * compared with the one before it (by upload date, both dated, the later really later);
     * +1 ↔ −1 is CHANGED, ±1 ↔ 0 NARROWED.
     */
    fun shifts(hits: List<YtSsiHit>, stances: Map<Long, YtSsiStance>): List<YtSsiShift> {
        val out = ArrayList<YtSsiShift>()
        var previous: Pair<YtSsiHit, YtSsiStance>? = null
        for (hit in order(hits)) {
            if (hit.uploaded == null) continue
            val stance = hit.segmentId?.let { stances[it] } ?: continue
            val d = direction(stance) ?: continue
            val before = previous
            previous = hit to stance
            if (before == null) continue
            val (earlier, earlierStance) = before
            if (earlier.uploaded == null || earlier.uploaded >= hit.uploaded) continue
            val e = direction(earlierStance) ?: continue
            val kind = when {
                e * d == -1 -> YtSsiShiftKind.CHANGED
                e != d && (e == 0 || d == 0) -> YtSsiShiftKind.NARROWED
                else -> null
            } ?: continue
            out += YtSsiShift(earlier, earlierStance, hit, stance, kind)
        }
        return out
    }
}
