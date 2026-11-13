package com.github.libretube.ui.yt.feed

import kotlinx.serialization.Serializable

/** What a check of one channel found out. */
@Serializable
enum class YtSubState {
    /** The channel has uploads; [YtSubCheck.last] is the newest one. */
    UPLOADS,

    /** The channel answered, but shows no video at all. */
    NONE,

    /** The channel is gone (closed or terminated). */
    GONE,
}

/** The result of checking one channel at [at], kept for [YtDeadSubsRules.CHECK_KEEP_MILLIS]. */
@Serializable
data class YtSubCheck(
    val id: String,
    val state: YtSubState,
    val last: Long = 0,
    val at: Long = 0,
)

/** A subscribed channel without an upload for over a year. [last] is 0 when no upload is known. */
data class YtDeadSub(val id: String, val state: YtSubState, val last: Long)

/** What the scan found: the dead channels (most dead first) and how many could not be checked. */
data class YtDeadSubsResult(val dead: List<YtDeadSub>, val unknown: Int)

/**
 * YT: "Tote Abos finden": which subscribed channels have not uploaded for over a year. Pure, so
 * it can be tested on the JVM. Channels with a video in the feed already at hand are alive
 * without a request; the rest are checked one by one (YtDeadSubs) and the results are kept for a
 * week, so opening the list again does not walk every channel again.
 */
object YtDeadSubsRules {
    /** No upload for this long makes a channel dead. */
    const val DEAD_AFTER_MILLIS = 365L * YT_DAY_MILLIS

    /** How long the result of a check is reused. */
    const val CHECK_KEEP_MILLIS = 7L * YT_DAY_MILLIS

    fun isDead(last: Long, now: Long): Boolean = last <= 0 || now - last > DEAD_AFTER_MILLIS

    /** The newest upload per channel id in a feed of (channel id, upload time) pairs. */
    fun latest(feed: List<Pair<String?, Long>>): Map<String, Long> {
        val out = HashMap<String, Long>()
        for ((id, uploaded) in feed) {
            if (id.isNullOrBlank() || uploaded <= 0) continue
            if (uploaded > (out[id] ?: 0L)) out[id] = uploaded
        }
        return out
    }

    /** A check still young enough to trust at [now]. */
    fun fresh(check: YtSubCheck?, now: Long): Boolean =
        check != null && check.at <= now && now - check.at < CHECK_KEEP_MILLIS

    /** The channels of [ids] that need a request: no recent video in the feed, no fresh check. */
    fun toCheck(ids: List<String>, feedLatest: Map<String, Long>, checks: Map<String, YtSubCheck>, now: Long): List<String> =
        ids.distinct().filter { id ->
            val inFeed = feedLatest[id] ?: 0L
            isDead(inFeed, now) && !fresh(checks[id], now)
        }

    /**
     * The dead channels of [ids]. A video in the feed beats an older check (the channel uploaded
     * since); a channel neither in the feed nor freshly checked counts as unknown, not as dead.
     */
    fun verdict(ids: List<String>, feedLatest: Map<String, Long>, checks: Map<String, YtSubCheck>, now: Long): YtDeadSubsResult {
        val dead = ArrayList<YtDeadSub>()
        var unknown = 0
        for (id in ids.distinct()) {
            val inFeed = feedLatest[id] ?: 0L
            if (!isDead(inFeed, now)) continue
            val check = checks[id]?.takeIf { fresh(it, now) }
            if (check == null) {
                unknown++
                continue
            }
            val last = maxOf(inFeed, if (check.state == YtSubState.UPLOADS) check.last else 0L)
            if (!isDead(last, now)) continue
            val state = if (last > 0) YtSubState.UPLOADS else check.state
            dead += YtDeadSub(id, state, last)
        }
        return YtDeadSubsResult(sort(dead), unknown)
    }

    /** Gone channels first, then channels without any video, then the longest silent first. */
    fun sort(dead: List<YtDeadSub>): List<YtDeadSub> = dead.sortedWith(
        compareBy<YtDeadSub> { rank(it.state) }.thenBy { it.last }.thenBy { it.id },
    )

    private fun rank(state: YtSubState) = when (state) {
        YtSubState.GONE -> 0
        YtSubState.NONE -> 1
        YtSubState.UPLOADS -> 2
    }

    /** The checks worth keeping: fresh ones of channels still subscribed ([ids]). */
    fun prune(checks: List<YtSubCheck>, ids: Collection<String>, now: Long): List<YtSubCheck> {
        val keep = ids.toHashSet()
        return checks.filter { it.id in keep && fresh(it, now) }
    }

    /** [checks] with [added] put in (a newer check of a channel replaces the older one). */
    fun merge(checks: List<YtSubCheck>, added: List<YtSubCheck>): List<YtSubCheck> {
        val byId = LinkedHashMap<String, YtSubCheck>()
        (checks + added).forEach { check ->
            val old = byId[check.id]
            if (old == null || check.at >= old.at) byId[check.id] = check
        }
        return byId.values.toList()
    }

    /**
     * The newest upload among the dates a channel's page or feed listed ([dates], 0 = no date).
     * @return the check, null when videos were listed but none had a date (nothing can be said)
     */
    fun fromDates(id: String, dates: List<Long>, now: Long): YtSubCheck? {
        if (dates.isEmpty()) return YtSubCheck(id, YtSubState.NONE, 0, now)
        val newest = dates.max()
        if (newest <= 0) return null
        return YtSubCheck(id, YtSubState.UPLOADS, newest, now)
    }
}
