package com.github.libretube.ui.yt.feed

import kotlin.math.ln
import kotlin.math.max

/**
 * One video the feed can show, as the engine sees it. [rank] is its place in its own source
 * (0 first); [topic] the topic search it came from, [relatedTo] the title of the watched video
 * it is related to.
 */
data class YtFeedItem(
    val id: String,
    val source: YtFeedSource,
    val rank: Int = 0,
    val channelId: String? = null,
    val channelName: String? = null,
    val title: String = "",
    val description: String = "",
    val durationSec: Long = 0,
    val uploadedMs: Long = 0,
    val views: Long = 0,
    val isLive: Boolean = false,
    val isShort: Boolean = false,
    val isUpcoming: Boolean = false,
    val topic: String? = null,
    val relatedTo: String? = null,
) {
    /** How the per-channel rules find the channel: its id, else its name (as YtChannelPause). */
    val channelKey: String? get() = channelId ?: channelName?.takeIf { it.isNotBlank() }
}

/**
 * What the app knows locally: how far videos were watched (id to 0..1), how often each channel
 * was watched (channel key to count), the videos hearted, the subscriptions of the groups the
 * rules name (null: no group chosen), the chart the YouTube design's topic row picked
 * (TRENDING_CATEGORY) and the time.
 */
data class YtFeedSignals(
    val nowMs: Long,
    val watched: Map<String, Float> = emptyMap(),
    val channelViews: Map<String, Int> = emptyMap(),
    val hearts: Set<String> = emptySet(),
    val groupChannels: Set<String>? = null,
    val chosenChart: YtFeedSource? = null,
)

/** Why a video is where it is; YtFeedSheet and the quick sheet word it ("Warum sehe ich das?"). */
enum class YtReasonKind {
    SOURCE,
    TOPIC,
    RELATED,
    CHOSEN_CHART,
    BOOSTED,
    LESS,
    WORD,
    FRESH,
    POPULAR,
    OFTEN_WATCHED,
    HEARTED,
    LEARN,
    MUSIC,
    STARTED,
    WATCHED_LAST,
    SPACED,
}

/** One reason; [detail] is the source name, topic, title or word it is about. */
data class YtReason(val kind: YtReasonKind, val detail: String? = null)

data class YtRanked(val item: YtFeedItem, val score: Double, val reasons: List<YtReason>)

/**
 * YT: the home feed, ranked on the phone from what the user set in the Feed-Regler. Pure, so it
 * is tested on the JVM. It replaces ytInterleave (subscriptions and charts 1:1).
 *
 * 1. Filters: formats, length, age, words never to show, groups, the learn and music rules,
 *    watched videos (hide). A source with weight 0 is off.
 * 2. Each source is sorted on its own (as given, newest or most viewed) with the boosts on top:
 *    boosted and often watched channels, wanted words, hearts, fresh videos; "less" goes down.
 * 3. The sources are mixed by weight (smooth weighted round robin: a source of weight 6 gets
 *    three times the places of one of weight 2, evenly spread), the same video only once.
 * 4. Diversity: a channel waits until [YtFeedRules.channelGap] others came between and shows at
 *    most [YtFeedRules.maxPerChannel] times among 10; nothing is dropped for it, only moved.
 * 5. Watched videos go to the end (or stay, as the rules say).
 */
object YtFeedEngine {
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** A video at most this old counts as fresh. */
    const val FRESH_DAYS = 2

    /** Watched this far, a video counts as watched to the end. */
    const val WATCHED = 0.9f

    /** Started this far (and not watched), it is a video to go on with. */
    const val STARTED = 0.05f

    /** The window [YtFeedRules.maxPerChannel] counts in. */
    const val WINDOW = 10

    /** Words that mark a learning video, German and English. */
    val LEARN_WORDS = listOf(
        "erklärt", "erklärung", "warum", "wie ", "tutorial", "lernen", "vortrag", "doku", "dokumentation",
        "grundlagen", "kurs", "einführung", "vorlesung", "anleitung", "wissen",
        "explained", "how to", "how ", "why ", "lecture", "course", "learn", "guide", "introduction", "documentary", "lesson",
    )

    /**
     * Ranks [items] (all sources in one list) by [rules] and [personal] with [signals]. The
     * result holds every video at most once, with its reasons.
     */
    fun rank(items: List<YtFeedItem>, rules: YtFeedRules, personal: YtFeedPersonal, signals: YtFeedSignals): List<YtRanked> {
        val never = YtFeedRules.cleanWords(personal.neverWords).map { it.lowercase() }
        val wanted = YtFeedRules.cleanWords(rules.includeWords).map { it.lowercase() }

        // 1. filters, and the same video once (from the source with the higher weight)
        val kept = LinkedHashMap<String, YtFeedItem>()
        for (item in items) {
            if (rules.weight(item.source) == 0) continue
            if (!passes(item, rules, personal, signals, never, wanted)) continue
            val before = kept[item.id]
            if (before == null || rules.weight(item.source) > rules.weight(before.source)) kept[item.id] = item
        }

        // 2. each source on its own
        val bySource = kept.values.groupBy { it.source }.mapValues { (_, list) ->
            list.map { score(it, rules, personal, signals, wanted) }.let { sortSource(it, rules) }
        }

        // 3. mixed by weight
        val mixed = mix(bySource, rules)

        // 5. watched to the end goes last (split before the diversity pass, so it never waits for them)
        val (fresh, watched) = if (rules.watched == YtWatchedMode.LAST) {
            mixed.partition { (signals.watched[it.item.id] ?: 0f) < WATCHED }
        } else {
            mixed to emptyList()
        }
        val lastly = watched.map { it.copy(reasons = it.reasons + YtReason(YtReasonKind.WATCHED_LAST)) }

        // 4. diversity
        return spread(fresh, rules) + spread(lastly, rules)
    }

    // ---------------------------------------------------------------------------------------------

    private fun passes(
        item: YtFeedItem,
        rules: YtFeedRules,
        personal: YtFeedPersonal,
        signals: YtFeedSignals,
        never: List<String>,
        wanted: List<String>,
    ): Boolean {
        val regular = !item.isShort && !item.isLive && !item.isUpcoming
        if (item.isUpcoming && !rules.upcoming) return false
        if (item.isShort && !rules.shorts) return false
        if (item.isLive && !item.isUpcoming && !rules.live) return false
        if (regular && !rules.videos) return false
        // the length only where there is one
        if (item.durationSec > 0 && !item.isLive) {
            if (rules.minMinutes > 0 && item.durationSec < rules.minMinutes * 60L) return false
            if (rules.maxMinutes > 0 && item.durationSec > rules.maxMinutes * 60L) return false
        }
        if (rules.maxAgeDays > 0 && item.uploadedMs > 0 && signals.nowMs - item.uploadedMs > rules.maxAgeDays * DAY_MS) return false
        val text = searchText(item)
        if (never.any { matches(text, it) }) return false
        if (rules.onlyIncluded && wanted.isNotEmpty() && wanted.none { matches(text, it) }) return false
        if (item.source == YtFeedSource.SUBSCRIPTIONS && signals.groupChannels != null) {
            val key = item.channelId ?: return false
            if (key !in signals.groupChannels) return false
        }
        if (rules.onlyMusic && !isMusic(item, personal)) return false
        if (rules.onlyLearning && !isLearning(item, rules, personal)) return false
        if (rules.watched == YtWatchedMode.HIDE && (signals.watched[item.id] ?: 0f) >= WATCHED) return false
        return true
    }

    private fun searchText(item: YtFeedItem): String = (item.title + "\n" + item.description).lowercase()

    /** [word] (lower case) in [text] (lower case), at the start of a word, so "kurs" finds "Kurse", not "Exkurs". */
    fun matches(text: String, word: String): Boolean {
        if (word.isEmpty()) return false
        var from = 0
        while (true) {
            val at = text.indexOf(word, from)
            if (at < 0) return false
            if (at == 0 || !text[at - 1].isLetterOrDigit()) return true
            from = at + 1
        }
    }

    /** Music by its source or channel: the music chart, a marked channel, "… - Topic", VEVO. */
    fun isMusic(item: YtFeedItem, personal: YtFeedPersonal): Boolean {
        if (item.source == YtFeedSource.MUSIC) return true
        val key = item.channelKey
        if (key != null && key in personal.musicChannels) return true
        val name = item.channelName.orEmpty()
        return name.endsWith(" - Topic") || name.contains("VEVO", ignoreCase = false) || name.endsWith("Music", ignoreCase = true)
    }

    /** A learning video: from a marked channel, long enough, or with a learning word (a guess, said in the sheet). */
    fun isLearning(item: YtFeedItem, rules: YtFeedRules, personal: YtFeedPersonal): Boolean {
        val key = item.channelKey
        if (key != null && key in personal.learnChannels) return true
        if (item.source == YtFeedSource.TOPICS) return true
        val text = searchText(item)
        if (LEARN_WORDS.any { text.contains(it) }) return true
        return rules.learnMinMinutes > 0 && item.durationSec >= rules.learnMinMinutes * 60L
    }

    private fun score(item: YtFeedItem, rules: YtFeedRules, personal: YtFeedPersonal, signals: YtFeedSignals, wanted: List<String>): YtRanked {
        val reasons = ArrayList<YtReason>(4)
        when (item.source) {
            YtFeedSource.TOPICS -> reasons += YtReason(YtReasonKind.TOPIC, item.topic)
            YtFeedSource.RELATED -> reasons += YtReason(YtReasonKind.RELATED, item.relatedTo)
            YtFeedSource.CONTINUE -> reasons += YtReason(YtReasonKind.STARTED)
            else -> reasons += YtReason(YtReasonKind.SOURCE, item.source.name)
        }
        // the place in its source: 1, then slowly less
        var score = 1.0 / (1.0 + item.rank * 0.08)
        val key = item.channelKey
        when (key?.let { personal.channels[it] }) {
            YtChannelRule.BOOST -> {
                score += 1.0
                reasons += YtReason(YtReasonKind.BOOSTED, item.channelName)
            }
            YtChannelRule.LESS -> {
                score -= 0.8
                reasons += YtReason(YtReasonKind.LESS, item.channelName)
            }
            null -> Unit
        }
        if (item.source == signals.chosenChart) {
            score += 0.3
            reasons += YtReason(YtReasonKind.CHOSEN_CHART, item.source.name)
        }
        val text = searchText(item)
        wanted.firstOrNull { matches(text, it) }?.let {
            score += 0.6
            reasons += YtReason(YtReasonKind.WORD, it)
        }
        if (item.id in signals.hearts) {
            score += 0.4
            reasons += YtReason(YtReasonKind.HEARTED)
        }
        val views = key?.let { signals.channelViews[it] } ?: 0
        if (views >= 3) {
            // a channel watched often goes up, but less with each more video
            score += (0.15 * ln(views.toDouble())).coerceAtMost(0.5)
            reasons += YtReason(YtReasonKind.OFTEN_WATCHED, item.channelName)
        }
        if (item.uploadedMs > 0 && signals.nowMs - item.uploadedMs in 0..FRESH_DAYS * DAY_MS) {
            score += 0.2
            reasons += YtReason(YtReasonKind.FRESH)
        }
        val progress = signals.watched[item.id] ?: 0f
        if (progress >= STARTED && progress < WATCHED && item.source != YtFeedSource.CONTINUE) {
            score += 0.2
            reasons += YtReason(YtReasonKind.STARTED)
        }
        if (rules.onlyLearning) reasons += YtReason(YtReasonKind.LEARN)
        if (rules.onlyMusic) reasons += YtReason(YtReasonKind.MUSIC)
        return YtRanked(item, score, reasons)
    }

    private fun sortSource(list: List<YtRanked>, rules: YtFeedRules): List<YtRanked> = when (rules.order) {
        YtFeedOrder.MIXED -> list.sortedWith(compareByDescending<YtRanked> { it.score }.thenBy { it.item.rank })
        YtFeedOrder.NEWEST -> list.sortedWith(
            // boosted channels first within the same day, "less" at the end of it
            compareByDescending<YtRanked> { it.item.uploadedMs / DAY_MS }.thenByDescending { it.score }.thenByDescending { it.item.uploadedMs },
        )
        YtFeedOrder.POPULAR -> list.sortedWith(compareByDescending<YtRanked> { it.item.views }.thenByDescending { it.score })
            .map { ranked -> if (ranked.item.views > 0) ranked.copy(reasons = ranked.reasons + YtReason(YtReasonKind.POPULAR)) else ranked }
    }

    /**
     * Smooth weighted round robin: each turn every source with videos left gains its weight, the
     * one with the most gives the next video and pays the sum of all weights. So the places follow
     * the weights and spread evenly (6:2 gives three of one, then one of the other, interleaved),
     * ties go to the higher weight, then the source's order, and an empty source just stops.
     */
    private fun mix(bySource: Map<YtFeedSource, List<YtRanked>>, rules: YtFeedRules): List<YtRanked> {
        val sources = YtFeedSource.entries.filter { rules.weight(it) > 0 && !bySource[it].isNullOrEmpty() }
        val next = IntArray(YtFeedSource.entries.size)
        val credit = IntArray(YtFeedSource.entries.size)
        val total = sources.sumOf { bySource.getValue(it).size }
        val out = ArrayList<YtRanked>(total)
        while (out.size < total) {
            val active = sources.filter { next[it.ordinal] < bySource.getValue(it).size }
            if (active.isEmpty()) break
            var sum = 0
            for (source in active) {
                credit[source.ordinal] += rules.weight(source)
                sum += rules.weight(source)
            }
            val pick = active.maxWithOrNull(
                compareBy<YtFeedSource> { credit[it.ordinal] }.thenBy { rules.weight(it) }.thenByDescending { it.ordinal },
            ) ?: break
            credit[pick.ordinal] -= sum
            out += bySource.getValue(pick)[next[pick.ordinal]++]
        }
        return out
    }

    /**
     * Moves videos down so a channel keeps its distance; each one waits only until it fits, and
     * when nothing fits the one waiting longest comes anyway.
     */
    private fun spread(list: List<YtRanked>, rules: YtFeedRules): List<YtRanked> {
        val gap = rules.channelGap.coerceAtLeast(0)
        val cap = rules.maxPerChannel.coerceAtLeast(0)
        if (gap == 0 && cap == 0) return list
        val out = ArrayList<YtRanked>(list.size)
        val waiting = ArrayList<YtRanked>()
        var cursor = 0

        fun fits(candidate: YtRanked): Boolean {
            val key = candidate.item.channelKey ?: return true
            if (gap > 0) {
                for (i in out.size - 1 downTo max(0, out.size - gap)) if (out[i].item.channelKey == key) return false
            }
            if (cap > 0) {
                var count = 0
                for (i in out.size - 1 downTo max(0, out.size - (WINDOW - 1))) if (out[i].item.channelKey == key) count++
                if (count >= cap) return false
            }
            return true
        }

        while (out.size < list.size) {
            val waited = waiting.indexOfFirst(::fits)
            if (waited >= 0) {
                out += waiting.removeAt(waited)
                continue
            }
            if (cursor < list.size) {
                val candidate = list[cursor++]
                if (fits(candidate)) {
                    out += candidate
                } else {
                    waiting += candidate.copy(reasons = candidate.reasons + YtReason(YtReasonKind.SPACED, candidate.item.channelName))
                }
                continue
            }
            // nothing fits: the longest waiting comes anyway
            out += waiting.removeAt(0)
        }
        return out
    }
}
