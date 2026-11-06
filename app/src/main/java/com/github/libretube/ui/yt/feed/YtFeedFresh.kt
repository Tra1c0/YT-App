package com.github.libretube.ui.yt.feed

import kotlin.math.max

/** A video that was on top of the home once: its id, channel and title, as read on a refresh. */
data class YtSeen(val id: String, val channel: String?, val title: String)

/** The top of one refresh: when it was read, and the videos. */
data class YtSeenRefresh(val atMs: Long, val items: List<YtSeen>)

/**
 * YT: what the home showed on top at the last [KEEP] refreshes, newest first, at most [TOP]
 * videos each, for [MAX_AGE_MS]. A pull to refresh remembers the current top here, and the
 * engine then fills the new top with videos that are not in it ([YtFreshTop]). It is local,
 * small and temporary: ids, channels and titles only, stored as plain lines (YtFeed, key
 * yt_feed_seen), forgotten after half a day.
 */
class YtSeenMemory(val refreshes: List<YtSeenRefresh> = emptyList()) {
    private val ids: Set<String> = refreshes.flatMap { it.items }.mapTo(HashSet()) { it.id }

    // the same video is sometimes there twice (a re-upload, a clip): the channel and title tell
    private val keys: Set<String> = refreshes.flatMap { it.items }.mapNotNullTo(HashSet()) { key(it.channel, it.title) }

    val isEmpty: Boolean get() = ids.isEmpty()

    fun contains(item: YtFeedItem): Boolean =
        item.id in ids || key(item.channelName ?: item.channelId, item.title)?.let { it in keys } == true

    /** [top] (only its first [TOP]) as the newest refresh at [nowMs]; the oldest beyond [KEEP] is forgotten. */
    fun remember(top: List<YtSeen>, nowMs: Long): YtSeenMemory {
        val cut = top.distinctBy { it.id }.take(TOP)
        val kept = within(nowMs).refreshes
        if (cut.isEmpty()) return YtSeenMemory(kept)
        return YtSeenMemory((listOf(YtSeenRefresh(nowMs, cut)) + kept).take(KEEP))
    }

    /** Without the refreshes older than [MAX_AGE_MS] (or from a clock that went back). */
    fun within(nowMs: Long): YtSeenMemory {
        val kept = refreshes.filter { nowMs - it.atMs in 0..MAX_AGE_MS }
        return if (kept.size == refreshes.size) this else YtSeenMemory(kept)
    }

    /** Per refresh a "#time" line, then one line per video (id, channel, title, tab separated); a blank line between. */
    fun encode(): String = refreshes.joinToString("\n\n") { refresh ->
        "#" + refresh.atMs + "\n" + refresh.items.joinToString("\n") {
            listOf(it.id, it.channel.orEmpty(), it.title).joinToString("\t") { part -> clean(part) }
        }
    }

    companion object {
        /** How many refreshes are remembered. */
        const val KEEP = 3

        /** How many videos of each refresh: the first 50 shown. */
        const val TOP = 50

        /** How long a refresh is remembered: half a day, after that the top may repeat. */
        const val MAX_AGE_MS = 12L * 60 * 60 * 1000

        fun decode(text: String?): YtSeenMemory {
            if (text.isNullOrBlank()) return YtSeenMemory()
            val refreshes = text.split("\n\n").mapNotNull { block ->
                val lines = block.lines()
                val at = lines.firstOrNull()?.takeIf { it.startsWith("#") }?.drop(1)?.toLongOrNull() ?: return@mapNotNull null
                val items = lines.drop(1).mapNotNull { line ->
                    val parts = line.split('\t')
                    val id = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    YtSeen(id, parts.getOrNull(1)?.takeIf { it.isNotBlank() }, parts.getOrNull(2).orEmpty())
                }
                if (items.isEmpty()) null else YtSeenRefresh(at, items)
            }
            return YtSeenMemory(refreshes.take(KEEP))
        }

        /** Tabs and line breaks would break the lines; titles are cut, they only have to match. */
        private fun clean(text: String): String = text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').take(120)

        private fun key(channel: String?, title: String): String? {
            val t = title.trim().lowercase().replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').take(120)
            if (t.length < 4) return null
            return channel.orEmpty().trim().lowercase() + "\u0000" + t
        }
    }
}

/**
 * YT: the top of the feed after a refresh. The engine's order stays the base, but the first
 * [TOP] places go to videos that were not on top at the last refreshes ([YtSeenMemory]), as
 * many as the data has, with
 * - at most one video per channel among any 10 (two only when no other channel is left), and
 * - no third video of the same source in a row while another source still has an unseen one.
 * What was seen is not dropped: it follows after the new top in its old order.
 */
object YtFreshTop {
    /** The places that get videos not shown on top before. */
    const val TOP = 20

    /** The window the channel limit counts in. */
    const val WINDOW = 10

    /** How far down the unseen videos are looked through for one place, so the order keeps its sense. */
    private const val LOOK = 40

    fun apply(list: List<YtRanked>, seen: YtSeenMemory, size: Int = TOP, perChannel: Int = 2): List<YtRanked> {
        if (seen.isEmpty || list.isEmpty() || size <= 0) return list
        val pool = list.filterTo(ArrayList()) { !seen.contains(it.item) }
        if (pool.isEmpty()) return list
        val top = ArrayList<YtRanked>(size)

        fun channelCount(candidate: YtRanked): Int {
            val key = candidate.item.channelKey ?: return 0
            var count = 0
            for (i in top.size - 1 downTo max(0, top.size - (WINDOW - 1))) if (top[i].item.channelKey == key) count++
            return count
        }

        fun sourceRun(candidate: YtRanked): Boolean =
            top.size >= 2 && top[top.size - 1].item.source == candidate.item.source && top[top.size - 2].item.source == candidate.item.source

        while (top.size < size && pool.isNotEmpty()) {
            val window = pool.take(LOOK)
            val pick = window.firstOrNull { channelCount(it) == 0 && !sourceRun(it) }
                ?: window.firstOrNull { channelCount(it) == 0 }
                ?: window.firstOrNull { channelCount(it) < perChannel }
                // only one channel left: the rest keeps its order below
                ?: break
            pool.remove(pick)
            top += pick.copy(reasons = pick.reasons + YtReason(YtReasonKind.NEW_ON_TOP))
        }
        if (top.isEmpty()) return list
        val taken = top.mapTo(HashSet()) { it.item.id }
        return top + list.filter { it.item.id !in taken }
    }
}

/** YT: which part of a source a refresh uses, so pulling again brings other videos of it. */
object YtRotation {
    /** [count] items of [list] from place [turn] × [count] on, wrapping round; all of it when shorter. */
    fun <T> window(list: List<T>, turn: Int, count: Int): List<T> {
        if (list.size <= count || count <= 0) return list
        val start = Math.floorMod(turn * count, list.size)
        return List(count) { list[(start + it) % list.size] }
    }

    /**
     * A small, repeatable nudge in -[spread]..[spread] for [id] on refresh [seed] (0: none), so
     * videos of nearly the same score change places from one refresh to the next. Local only.
     */
    fun jitter(id: String, seed: Long, spread: Double): Double {
        if (seed == 0L || spread <= 0.0) return 0.0
        var h = seed xor (id.hashCode().toLong() * -0x61c8864680b583ebL)
        h = (h xor (h ushr 33)) * -0xae502812aa7333L
        h = h xor (h ushr 29)
        val unit = (h ushr 11).toDouble() / (1L shl 53).toDouble()
        return (unit * 2 - 1) * spread
    }
}
