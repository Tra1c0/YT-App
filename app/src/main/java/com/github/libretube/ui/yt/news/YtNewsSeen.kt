package com.github.libretube.ui.yt.news

import kotlinx.serialization.Serializable

/** What the overlay has shown: video id → when it was last on a card the user looked at. */
@Serializable
data class YtNewsSeenList(val seen: Map<String, Long> = emptyMap())

/**
 * YT: "Neu seit deinem letzten Blick", pure for the JVM tests. When the overlay opens, the
 * videos shown before are the baseline; a story's video outside it is new for this opening.
 * Before the first look (an empty baseline) nothing is new, or every video would be.
 */
object YtNewsSeenRules {
    /** Seen videos are kept this long (the overlay shows a week of videos). */
    const val KEEP_MS = 8L * 24 * 60 * 60 * 1000

    /** At most this many seen videos are kept (the oldest go first). */
    const val CAP = 1000

    /** The videos of [ids] that are new against [baseline]. */
    fun fresh(baseline: Set<String>, ids: List<String>): Set<String> =
        if (baseline.isEmpty()) emptySet() else ids.filterTo(LinkedHashSet()) { it !in baseline }

    /** [list] with [ids] seen at [nowMs], pruned; the same instance when nothing changed. */
    fun record(list: YtNewsSeenList, ids: Collection<String>, nowMs: Long): YtNewsSeenList {
        val pruned = prune(list, nowMs)
        // within a minute a video counts as seen already (no write for every page swiped by)
        if (ids.all { id -> (pruned.seen[id] ?: Long.MIN_VALUE) >= nowMs - 60_000L }) return pruned
        val next = LinkedHashMap(pruned.seen)
        for (id in ids) if (id.isNotBlank()) next[id] = nowMs
        return prune(YtNewsSeenList(next), nowMs)
    }

    fun prune(list: YtNewsSeenList, nowMs: Long): YtNewsSeenList {
        val kept = list.seen.filterValues { it <= nowMs && nowMs - it <= KEEP_MS }
        val capped = if (kept.size > CAP) {
            kept.entries.sortedBy { it.value }.takeLast(CAP).associate { it.key to it.value }
        } else {
            kept
        }
        return if (capped.size == list.seen.size) list else YtNewsSeenList(capped)
    }
}
