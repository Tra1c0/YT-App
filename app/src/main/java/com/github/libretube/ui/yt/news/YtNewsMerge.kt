package com.github.libretube.ui.yt.news

import com.github.libretube.ui.yt.feed.YtFeedEngine
import com.github.libretube.ui.yt.feed.YtFeedRules
import com.github.libretube.ui.yt.feed.YtFeedTopics

/**
 * YT: which videos the News overlay shows, in which order. Pure, so it is tested on the JVM.
 * The sources are lists the app already has (no network call of its own): the subscription feed
 * (the videos of news channels, and the ones whose title names a keyword of an active topic) and
 * what the topic searches found for the home. Hidden videos and paused channels are taken out by
 * the caller before (rememberYtVisible), the words the user never wants to see here.
 */
object YtNewsMerge {
    /** At most this many cards. */
    const val MAX_ITEMS = 30

    /** At most this many videos of one channel, so a channel that uploads all day leaves room. */
    const val MAX_PER_CHANNEL = 5

    /** Older videos are no news. */
    const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    data class Limits(
        val maxItems: Int = MAX_ITEMS,
        val perChannel: Int = MAX_PER_CHANNEL,
        val maxAgeMs: Long = MAX_AGE_MS,
    )

    /**
     * The news of [subscriptions] and [topicFinds]: a subscription video counts if its channel is
     * a news channel ([isNewsChannel]) or its title names a keyword of one of [topics]; the topic
     * finds count as they are (their [YtNewsItem.topics] set by the caller). Then [merge].
     */
    fun collect(
        subscriptions: List<YtNewsItem>,
        isNewsChannel: (YtNewsItem) -> Boolean,
        topics: List<YtNewsTopic>,
        topicFinds: List<YtNewsItem>,
        never: List<String>,
        nowMs: Long,
        limits: Limits = Limits(),
    ): List<YtNewsItem> {
        val candidates = ArrayList<YtNewsItem>(subscriptions.size + topicFinds.size)
        // the keywords folded once (YtFeedTopics.matches folds both sides on every call)
        val folded = topics.map { topic ->
            topic.name to topic.keywords.map { it.trim() }.filter { it.isNotEmpty() }
                .map { YtFeedTopics.fold(it) to YtFeedTopics.foldWide(it) }
        }.filter { it.second.isNotEmpty() }
        for (item in subscriptions) {
            val named = if (folded.isEmpty() || item.title.isEmpty()) {
                emptyList()
            } else {
                val plain = YtFeedTopics.fold(item.title)
                val wide = YtFeedTopics.foldWide(item.title)
                folded.filter { (_, words) ->
                    words.any { (k, kw) -> YtFeedEngine.matches(plain, k) || YtFeedEngine.matches(wide, kw) }
                }.map { it.first }
            }
            when {
                isNewsChannel(item) -> candidates += item.copy(origin = YtNewsOrigin.CHANNEL, topics = named)
                named.isNotEmpty() -> candidates += item.copy(origin = YtNewsOrigin.TOPIC, topics = named)
            }
        }
        topicFinds.mapTo(candidates) { it.copy(origin = YtNewsOrigin.TOPIC) }
        return merge(candidates, never, nowMs, limits)
    }

    /**
     * [candidates] as the overlay shows them: only regular videos (no Shorts, live or upcoming
     * streams) of the last [Limits.maxAgeMs], none with a word of [never]; each video once (a
     * news channel's copy wins over a topic's, the topics joined); newest first (an unknown age
     * last); at most [Limits.perChannel] per channel and [Limits.maxItems] in all.
     */
    fun merge(
        candidates: List<YtNewsItem>,
        never: List<String>,
        nowMs: Long,
        limits: Limits = Limits(),
    ): List<YtNewsItem> {
        val words = YtFeedRules.cleanWords(never).map { it.lowercase() }
        val byId = LinkedHashMap<String, YtNewsItem>()
        for (item in candidates) {
            if (!keeps(item, words, nowMs, limits.maxAgeMs)) continue
            val before = byId[item.id]
            byId[item.id] = if (before == null) item else join(before, item)
        }
        // newest first; the same age keeps the order the sources gave (sortedWith is stable)
        val ordered = byId.values.sortedWith(
            compareBy<YtNewsItem> { it.uploadedMs <= 0L }.thenByDescending { it.uploadedMs },
        )
        val perChannel = HashMap<String, Int>()
        val result = ArrayList<YtNewsItem>(minOf(ordered.size, limits.maxItems))
        for (item in ordered) {
            if (result.size >= limits.maxItems) break
            val key = item.channelKey?.lowercase()
            if (key != null) {
                val count = perChannel[key] ?: 0
                if (count >= limits.perChannel) continue
                perChannel[key] = count + 1
            }
            result += item
        }
        return result
    }

    /** One card per item (stage 1). */
    fun cards(items: List<YtNewsItem>): List<YtNewsCard> = items.map { YtNewsCard.Single(it) }

    private fun keeps(item: YtNewsItem, never: List<String>, nowMs: Long, maxAgeMs: Long): Boolean {
        if (item.id.isBlank() || item.title.isBlank()) return false
        if (item.isShort || item.isLive || item.isUpcoming || item.uploadedMs > nowMs) return false
        if (item.uploadedMs > 0L && nowMs - item.uploadedMs > maxAgeMs) return false
        if (never.isEmpty()) return true
        val text = (item.title + "\n" + item.description).lowercase()
        return never.none { YtFeedEngine.matches(text, it) }
    }

    /** The same video from two sources: the channel's copy, with the topics of both. */
    private fun join(a: YtNewsItem, b: YtNewsItem): YtNewsItem {
        val first = if (b.origin == YtNewsOrigin.CHANNEL && a.origin != YtNewsOrigin.CHANNEL) b else a
        val other = if (first === a) b else a
        val topics = (first.topics + other.topics).distinct()
        return first.copy(
            topics = topics,
            description = first.description.ifBlank { other.description },
            uploadedMs = if (first.uploadedMs > 0L) first.uploadedMs else other.uploadedMs,
        )
    }
}
