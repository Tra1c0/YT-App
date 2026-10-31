package com.github.libretube.ui.yt.feed

import kotlinx.serialization.Serializable
import kotlin.math.ceil

/** One day in milliseconds. */
const val YT_DAY_MILLIS = 24L * 60 * 60 * 1000

/**
 * YT: the rules behind YtHidden (videos swiped away on the home screens), kept apart from the
 * preferences so they can be tested on the JVM. A hidden video is stored with the day it went
 * (days since 1970), which keeps the stored text short: a backup only restores texts of up to
 * 10 000 characters, and [CAP] entries stay below that.
 */
object YtHiddenRules {
    /** How long a video stays hidden. */
    const val TTL_DAYS = 90

    /** At most this many hidden videos are kept; beyond that the oldest come back. */
    const val CAP = 450

    fun day(nowMillis: Long): Int = (nowMillis / YT_DAY_MILLIS).toInt()

    /**
     * [hidden] without what is due back on [today], and at most [cap] entries (the newest stay;
     * of one day the later ones, as the map keeps the order they were hidden in).
     * @return [hidden] itself when nothing went
     */
    fun prune(hidden: Map<String, Int>, today: Int, ttlDays: Int = TTL_DAYS, cap: Int = CAP): Map<String, Int> {
        val fresh = hidden.filterValues { today - it < ttlDays }
        if (fresh.size <= cap) return if (fresh.size == hidden.size) hidden else fresh
        return fresh.entries.sortedBy { it.value }.takeLast(cap).associate { it.key to it.value }
    }

    /** [hidden] with [id] hidden on [today] (as the newest entry). */
    fun hide(hidden: Map<String, Int>, id: String, today: Int, cap: Int = CAP): Map<String, Int> {
        val next = LinkedHashMap<String, Int>(hidden.size + 1)
        for ((key, day) in hidden) if (key != id) next[key] = day
        next[id] = today
        return prune(next, today, cap = cap)
    }

    /** [hidden] without [id]. */
    fun unhide(hidden: Map<String, Int>, id: String): Map<String, Int> =
        if (id in hidden) hidden - id else hidden
}

/**
 * A channel paused on the home screens until [untilMillis]. [id] is the channel id (from its
 * URL); a channel known only by [name] has none and is matched by its name.
 */
@Serializable
data class YtPausedChannel(
    val id: String? = null,
    val name: String = "",
    val avatar: String? = null,
    val untilMillis: Long = 0,
)

/** YT: the rules behind YtChannelPause, kept apart from the preferences for the JVM tests. */
object YtPauseRules {
    /** "Kanal 1 Woche pausieren". */
    const val DAYS = 7

    /** The pauses still running at [now]. @return [paused] itself when none ran out */
    fun prune(paused: List<YtPausedChannel>, now: Long): List<YtPausedChannel> =
        if (paused.all { it.untilMillis > now }) paused else paused.filter { it.untilMillis > now }

    /** [paused] with the channel paused for [days] from [now] (a running pause starts again). */
    fun pause(
        paused: List<YtPausedChannel>,
        id: String?,
        name: String?,
        avatar: String?,
        now: Long,
        days: Int = DAYS,
    ): List<YtPausedChannel> {
        if (id == null && normalize(name).isEmpty()) return prune(paused, now)
        val entry = YtPausedChannel(id, name.orEmpty().trim(), avatar, now + days * YT_DAY_MILLIS)
        return prune(paused, now).filterNot { same(it, id, name) } + entry
    }

    /** [paused] with [entry] back as it was (undo of a resume). */
    fun restore(paused: List<YtPausedChannel>, entry: YtPausedChannel, now: Long): List<YtPausedChannel> =
        prune(paused, now).filterNot { same(it, entry.id, entry.name) } + listOfNotNull(entry.takeIf { it.untilMillis > now })

    /** [paused] without the channel. */
    fun resume(paused: List<YtPausedChannel>, id: String?, name: String?): List<YtPausedChannel> =
        paused.filterNot { same(it, id, name) }

    /** The running pause of the channel, or null. */
    fun find(paused: List<YtPausedChannel>, id: String?, name: String?, now: Long): YtPausedChannel? =
        paused.firstOrNull { it.untilMillis > now && same(it, id, name) }

    /**
     * Whether [entry] is the channel with [id] and [name]: by id where both have one (two
     * channels may share a name), else by the name.
     */
    fun same(entry: YtPausedChannel, id: String?, name: String?): Boolean {
        if (entry.id != null && id != null) return entry.id == id
        val n = normalize(name)
        return n.isNotEmpty() && normalize(entry.name) == n
    }

    /** Whole days left of a pause ("noch 5 T."), at least 1 while it runs, 0 once it ran out. */
    fun daysLeft(untilMillis: Long, now: Long): Int =
        if (untilMillis <= now) 0 else ceil((untilMillis - now).toDouble() / YT_DAY_MILLIS).toInt().coerceAtLeast(1)

    fun normalize(name: String?): String = name.orEmpty().trim().lowercase()
}

/**
 * YT: what the home screens show of a list: without the hidden videos ([hidden], by video id)
 * and the channels paused at [now]. Search, channel pages, history and the library are not
 * filtered, so a hidden video or paused channel can still be found on purpose.
 */
object YtVisibility {
    /** @return [list] itself when nothing has to go (so screens can compare it cheaply) */
    fun <T> visible(
        list: List<T>?,
        hidden: Map<String, Int>,
        paused: List<YtPausedChannel>,
        now: Long,
        id: (T) -> String?,
        channelId: (T) -> String?,
        channelName: (T) -> String?,
    ): List<T>? {
        if (list.isNullOrEmpty()) return list
        val running = paused.filter { it.untilMillis > now }
        if (hidden.isEmpty() && running.isEmpty()) return list
        fun shown(item: T): Boolean {
            val video = id(item)
            if (video != null && video in hidden) return false
            if (running.isEmpty()) return true
            val channel = channelId(item)
            val name = channelName(item)
            return running.none { YtPauseRules.same(it, channel, name) }
        }
        return if (list.all(::shown)) list else list.filter(::shown)
    }
}
