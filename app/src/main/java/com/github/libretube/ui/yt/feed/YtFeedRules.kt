package com.github.libretube.ui.yt.feed

import kotlinx.serialization.Serializable

/**
 * What the user came for, picked when the app opens (YtIntentChooser) or with the chip on the
 * home. Each intent has its own feed rules (YtFeedRules.preset).
 */
@Serializable
enum class YtIntent { LEARN, MUSIC, SUBSCRIPTIONS, BROWSE }

/**
 * Where a home video comes from. There is no recommendation source: the subscription feed, the
 * four charts YouTube still has (music, podcasts, trailers, gaming), the live chart, searches for
 * the user's topics, the related videos of what was watched last and the watch history.
 */
@Serializable
enum class YtFeedSource { SUBSCRIPTIONS, MUSIC, PODCASTS, TRAILERS, GAMING, LIVE, TOPICS, RELATED, CONTINUE }

/** How a source list is sorted before the sources are mixed. */
@Serializable
enum class YtFeedOrder {
    /** As the source gives it, with the boosts on top. */
    MIXED,
    NEWEST,
    POPULAR,
}

/** What happens to videos the user has watched to the end. */
@Serializable
enum class YtWatchedMode { HIDE, LAST, SHOW }

/** A channel's place in the feed; a pause for a week is YtChannelPause. */
@Serializable
enum class YtChannelRule { BOOST, LESS }

/** A search the feed adds, with its weight among the topics (1 to 5). */
@Serializable
data class YtTopic(val query: String, val weight: Int = 3)

/**
 * YT: the rules of one intent's home feed, stored as JSON (YtFeed, key yt_feed_rules). Every
 * field has a default, so an older stored text still reads; 0 means "any" for the limits.
 */
@Serializable
data class YtFeedRules(
    /** Weight per source, 0 (off) to 10. Missing sources count as 0. */
    // the home as it was: subscriptions and the four charts in turn (1:1); topics count once added,
    // "like what I watched" costs a lookup per video, so it starts off
    val weights: Map<YtFeedSource, Int> = mapOf(
        YtFeedSource.SUBSCRIPTIONS to 4,
        YtFeedSource.MUSIC to 1,
        YtFeedSource.PODCASTS to 1,
        YtFeedSource.TRAILERS to 1,
        YtFeedSource.GAMING to 1,
        YtFeedSource.LIVE to 0,
        YtFeedSource.TOPICS to 3,
        YtFeedSource.RELATED to 0,
        YtFeedSource.CONTINUE to 0,
    ),
    val videos: Boolean = true,
    val shorts: Boolean = false,
    val live: Boolean = true,
    val upcoming: Boolean = false,
    val minMinutes: Int = 0,
    val maxMinutes: Int = 0,
    val maxAgeDays: Int = 0,
    val order: YtFeedOrder = YtFeedOrder.MIXED,
    /** At most this many videos of one channel among any 10 in a row (0: no limit). */
    val maxPerChannel: Int = 3,
    /** At least this many other videos between two of the same channel (0: none). */
    val channelGap: Int = 1,
    val watched: YtWatchedMode = YtWatchedMode.LAST,
    /** Only subscriptions of these groups (their names); empty: all. */
    val groups: List<String> = emptyList(),
    /** Videos with one of these words go up; with [onlyIncluded] only they show. */
    val includeWords: List<String> = emptyList(),
    val onlyIncluded: Boolean = false,
    val topics: List<YtTopic> = emptyList(),
    /** Learn: only videos of at least [learnMinMinutes], or with a learning word, or from a learning channel. */
    val onlyLearning: Boolean = false,
    val learnMinMinutes: Int = 8,
    /** Music: only music (the music chart, music channels, " - Topic", VEVO). */
    val onlyMusic: Boolean = false,
    /** Music: videos open as audio. */
    val musicAsAudio: Boolean = false,
) {
    fun weight(source: YtFeedSource): Int = (weights[source] ?: 0).coerceIn(0, MAX_WEIGHT)

    fun withWeight(source: YtFeedSource, weight: Int): YtFeedRules =
        copy(weights = weights + (source to weight.coerceIn(0, MAX_WEIGHT)))

    companion object {
        const val MAX_WEIGHT = 10
        const val MAX_WORDS = 30
        const val MAX_TOPICS = 8

        /** The rules an intent starts with, and the ones "Zurücksetzen" brings back. */
        fun preset(intent: YtIntent): YtFeedRules = when (intent) {
            // today's home: subscriptions and the charts in turn
            YtIntent.BROWSE -> YtFeedRules()
            YtIntent.SUBSCRIPTIONS -> YtFeedRules(
                weights = mapOf(YtFeedSource.SUBSCRIPTIONS to 10),
                order = YtFeedOrder.NEWEST,
                maxPerChannel = 0,
                channelGap = 0,
            )
            YtIntent.MUSIC -> YtFeedRules(
                weights = mapOf(
                    YtFeedSource.MUSIC to 8,
                    YtFeedSource.SUBSCRIPTIONS to 4,
                    YtFeedSource.TOPICS to 3,
                    YtFeedSource.RELATED to 2,
                ),
                live = false,
                onlyMusic = true,
                maxPerChannel = 2,
            )
            YtIntent.LEARN -> YtFeedRules(
                weights = mapOf(
                    YtFeedSource.SUBSCRIPTIONS to 6,
                    YtFeedSource.TOPICS to 6,
                    YtFeedSource.PODCASTS to 3,
                    YtFeedSource.RELATED to 3,
                    YtFeedSource.CONTINUE to 2,
                ),
                live = false,
                onlyLearning = true,
                minMinutes = 4,
            )
        }

        /** Words in a list as the user typed them: trimmed, without empties and doubles, at most [MAX_WORDS]. */
        fun cleanWords(words: List<String>): List<String> =
            words.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.take(MAX_WORDS)

        /** "a, b; c" as a list of words. */
        fun parseWords(text: String): List<String> = cleanWords(text.split(',', ';', '\n'))
    }
}

/** How long a [YtFeedTopic] stays on after it was switched on. */
@Serializable
enum class YtTopicExpiry {
    /** Until the app was away for half an hour (YtTopicSession), like the intent chooser's visit. */
    SESSION,
    DAY,
    WEEK,
    NEVER,
}

/**
 * A topic of the user's own ("Thema", Feed-Regler → Themen): a name and the keywords that make
 * it. [share] percent of the home (0 to YtFeedTopics.MAX_SHARE) are videos found by searching
 * the keywords, spread through the feed; videos of the feed that name a keyword count as well.
 * Switched on it lasts as [expiry] says: until [expiresAt] (DAY, WEEK) or for the session
 * [session] (SESSION, the start of that session). Words suggested from watched videos and turned
 * down stay in [dismissed], so they are not offered again. Every field has a default, so an
 * older or partial text still reads.
 */
@Serializable
data class YtFeedTopic(
    val id: String,
    val name: String = "",
    val keywords: List<String> = emptyList(),
    val share: Int = YtFeedTopics.DEFAULT_SHARE,
    val enabled: Boolean = true,
    val expiry: YtTopicExpiry = YtTopicExpiry.NEVER,
    val expiresAt: Long? = null,
    val session: Long = 0L,
    val dismissed: List<String> = emptyList(),
)

/**
 * What holds for every intent: the channels the user boosted or wants less of (by channel id,
 * else by name, as YtChannelPause), their names for the sheet, and the words never to show.
 */
@Serializable
data class YtFeedPersonal(
    val channels: Map<String, YtChannelRule> = emptyMap(),
    val channelNames: Map<String, String> = emptyMap(),
    val neverWords: List<String> = emptyList(),
    /** Channels the user marked as music or as learning, by the same keys. */
    val musicChannels: Set<String> = emptySet(),
    val learnChannels: Set<String> = emptySet(),
    /** "Gesehenes ausblenden": videos seen to the end (YtSeenHide) leave every home list and the Abos tab. */
    val hideSeen: Boolean = false,
    /**
     * The user's own topics (YtFeedTopic), for every intent: a focus that survives a preset or
     * "Zurücksetzen" of the rules and keeps the keywords it learned.
     */
    val feedTopics: List<YtFeedTopic> = emptyList(),
)

/** Everything stored: the rules per intent (missing ones are the preset) and [personal]. */
@Serializable
data class YtFeedProfile(
    val rules: Map<YtIntent, YtFeedRules> = emptyMap(),
    val personal: YtFeedPersonal = YtFeedPersonal(),
) {
    fun rulesFor(intent: YtIntent): YtFeedRules = rules[intent] ?: YtFeedRules.preset(intent)

    fun withRules(intent: YtIntent, rules: YtFeedRules): YtFeedProfile = copy(rules = this.rules + (intent to rules))

    fun reset(intent: YtIntent): YtFeedProfile = copy(rules = rules - intent)
}

/**
 * YT: when the intent chooser asks on opening. Pure, so it is tested on the JVM.
 */
object YtIntentAsk {
    /** "Nach 30 Minuten weg". */
    const val AWAY_MS = 30L * 60 * 1000

    /** "Immer" still skips coming back from a share sheet or a dialog of another app. */
    const val ALWAYS_MIN_AWAY_MS = 60L * 1000

    enum class Mode(val key: String) {
        ALWAYS("always"),
        AFTER_30("after30"),
        NEVER("never"),
        ;

        companion object {
            fun of(key: String?): Mode = entries.firstOrNull { it.key == key } ?: AFTER_30
        }
    }

    /**
     * Whether to ask. [lastStopMs] is when the app last went to the background (null: never, the
     * first start after the installation, where the welcome sheet speaks); [blocked] is true with
     * yt_quiet, a link or search the app was opened with, or a player open over the screen.
     * [coldStart] is the first start of the app's process: it asks after a minute away, as
     * "Immer" does.
     */
    fun shouldAsk(mode: Mode, nowMs: Long, lastStopMs: Long?, blocked: Boolean, coldStart: Boolean = false): Boolean {
        if (mode == Mode.NEVER || blocked || lastStopMs == null) return false
        val away = nowMs - lastStopMs
        // a clock set back counts as away
        if (away < 0) return true
        return away >= if (mode == Mode.ALWAYS || coldStart) ALWAYS_MIN_AWAY_MS else AWAY_MS
    }
}
