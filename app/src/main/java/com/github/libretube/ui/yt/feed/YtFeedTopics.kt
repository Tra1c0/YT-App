package com.github.libretube.ui.yt.feed

import java.text.Normalizer
import kotlin.math.roundToInt

/** A feed video counted as one of a topic: which topic, its name and the keyword it matched. */
data class YtTopicMark(val topicId: String, val name: String, val keyword: String)

/** A video a keyword search found for a topic, with that keyword. */
data class YtTopicCandidate(val item: YtFeedItem, val keyword: String)

/** What was found for one active topic, in the order to use it (empty with share 0: marks only). */
data class YtTopicPool(val topic: YtFeedTopic, val candidates: List<YtTopicCandidate> = emptyList())

/**
 * YT: the user's own topics ("Themen" in the Feed-Regler). Pure, so it is tested on the JVM:
 * keyword matching that ignores case and umlauts, editing the keywords, the expiry, and the
 * mixing of a topic's videos into the ranked home with its share ([mix]). The keyword
 * suggestion is YtTopicWords, the watched count YtTopicWatchLog.
 */
object YtFeedTopics {
    const val MAX_TOPICS = 6
    const val MAX_KEYWORDS = 12
    const val MAX_NAME = 40
    const val MAX_KEYWORD = 40

    /** The slider: 0 to [MAX_SHARE] percent in steps of [SHARE_STEP]. */
    const val MAX_SHARE = 50
    const val SHARE_STEP = 5
    const val DEFAULT_SHARE = 20

    /** All topics together never take more than this share of the home; above, each is scaled down. */
    const val MAX_TOTAL_SHARE = 60

    /** The page the sheet counts in: "≈ 4 von 20 Videos". */
    const val PAGE = 20

    /** Turned down words kept per topic (the oldest go first). */
    const val MAX_DISMISSED = 60

    /** A session ends after this long away from the app, as the intent chooser's visit. */
    const val SESSION_AWAY_MS = YtIntentAsk.AWAY_MS

    const val DAY_MS = 24L * 60 * 60 * 1000
    const val WEEK_MS = 7 * DAY_MS

    /** How far a topic may fall behind its share after the feed brought many of its videos by itself. */
    private const val MIN_DEBT = -2.0
    private const val MAX_DEBT = 2.0
    private const val EPS = 1e-9

    // ---------------------------------------------------------------------------------------------
    // Text

    /** Lower case, ß as ss, accents and umlauts dropped: "Über Käse" → "uber kase". */
    fun fold(text: String): String = strip(text.lowercase().replace("ß", "ss"))

    /** Lower case with umlauts written out: "Über Käse" → "ueber kaese". */
    fun foldWide(text: String): String =
        strip(text.lowercase().replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss"))

    private val MARKS = Regex("\\p{Mn}+")

    private fun strip(text: String): String = MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "")

    /**
     * [keyword] in [text] at the start of a word, case and umlauts ignored: "Kase" and "Kaese" find
     * "Käse", "kurs" finds "Kurse" but not "Exkurs".
     */
    fun matches(text: String, keyword: String): Boolean {
        val k = keyword.trim()
        if (k.isEmpty() || text.isEmpty()) return false
        return YtFeedEngine.matches(fold(text), fold(k)) || YtFeedEngine.matches(foldWide(text), foldWide(k))
    }

    /** The first of [keywords] that [text] names, or null. */
    fun keywordIn(text: String, keywords: List<String>): String? = keywords.firstOrNull { matches(text, it) }

    /** The same word, case and umlauts ignored ("Käse" = "kaese"). */
    fun same(a: String, b: String): Boolean {
        val x = a.trim()
        val y = b.trim()
        if (x.isEmpty() || y.isEmpty()) return false
        return fold(x) == fold(y) || foldWide(x) == foldWide(y)
    }

    // ---------------------------------------------------------------------------------------------
    // Editing

    /** A keyword as stored: trimmed, single spaces, no leading #, at most [MAX_KEYWORD] letters; null if empty. */
    fun cleanKeyword(raw: String): String? =
        raw.trim().trimStart('#').trim().replace(Regex("\\s+"), " ").take(MAX_KEYWORD).trim().takeIf { it.isNotEmpty() }

    /** "a, b; c" as keywords. */
    fun parseKeywords(text: String): List<String> = text.split(',', ';', '\n').mapNotNull(::cleanKeyword)

    /** [topic] with [raw] added (the same word only once, at most [MAX_KEYWORDS]); a word added is no longer "turned down". */
    fun withKeyword(topic: YtFeedTopic, raw: String): YtFeedTopic {
        val word = cleanKeyword(raw) ?: return topic
        if (topic.keywords.any { same(it, word) } || topic.keywords.size >= MAX_KEYWORDS) return topic
        return topic.copy(keywords = topic.keywords + word, dismissed = topic.dismissed.filterNot { same(it, word) })
    }

    /** [topic] with every keyword of [text] ("a, b") added. */
    fun withKeywords(topic: YtFeedTopic, text: String): YtFeedTopic = parseKeywords(text).fold(topic, ::withKeyword)

    fun withoutKeyword(topic: YtFeedTopic, keyword: String): YtFeedTopic =
        topic.copy(keywords = topic.keywords.filterNot { same(it, keyword) })

    /** [word] turned down for [topic]: it is never suggested again there. */
    fun dismiss(topic: YtFeedTopic, word: String): YtFeedTopic {
        val clean = cleanKeyword(word) ?: return topic
        if (topic.dismissed.any { same(it, clean) }) return topic
        return topic.copy(dismissed = (topic.dismissed + clean).takeLast(MAX_DISMISSED))
    }

    fun cleanName(raw: String): String = raw.trim().replace(Regex("\\s+"), " ").take(MAX_NAME).trim()

    /**
     * A new topic named [name] (its first keyword), switched on for [expiry] from [nowMs] in
     * [session]; null without a name.
     */
    fun create(id: String, name: String, nowMs: Long, session: Long, expiry: YtTopicExpiry = YtTopicExpiry.NEVER): YtFeedTopic? {
        val clean = cleanName(name).takeIf { it.isNotEmpty() } ?: return null
        val start = YtFeedTopic(id = id, name = clean, keywords = listOfNotNull(cleanKeyword(clean)), expiry = expiry)
        return switchedOn(start, nowMs, session)
    }

    /** [share] onto the slider's steps, 0 to [MAX_SHARE]. */
    fun snapShare(share: Int): Int =
        ((share.coerceIn(0, MAX_SHARE).toDouble() / SHARE_STEP).roundToInt() * SHARE_STEP).coerceIn(0, MAX_SHARE)

    // ---------------------------------------------------------------------------------------------
    // Expiry

    /**
     * Whether the time [topic] was switched on for is over at [nowMs]: for SESSION when
     * [session] is no longer the one it was switched on in, for DAY and WEEK at [YtFeedTopic.expiresAt].
     */
    fun expired(topic: YtFeedTopic, nowMs: Long, session: Long): Boolean = when (topic.expiry) {
        YtTopicExpiry.NEVER -> false
        YtTopicExpiry.SESSION -> topic.session != session
        YtTopicExpiry.DAY, YtTopicExpiry.WEEK -> topic.expiresAt?.let { nowMs >= it } ?: false
    }

    /** On and not expired: the topic is mixed in and counts. */
    fun active(topic: YtFeedTopic, nowMs: Long, session: Long): Boolean = topic.enabled && !expired(topic, nowMs, session)

    /** [topic] switched on at [nowMs]: its time starts again. */
    fun switchedOn(topic: YtFeedTopic, nowMs: Long, session: Long): YtFeedTopic = topic.copy(
        enabled = true,
        expiresAt = when (topic.expiry) {
            YtTopicExpiry.DAY -> nowMs + DAY_MS
            YtTopicExpiry.WEEK -> nowMs + WEEK_MS
            else -> null
        },
        session = if (topic.expiry == YtTopicExpiry.SESSION) session else 0L,
    )

    /** A new expiry choice: counted from now, and the topic on. */
    fun withExpiry(topic: YtFeedTopic, expiry: YtTopicExpiry, nowMs: Long, session: Long): YtFeedTopic =
        switchedOn(topic.copy(expiry = expiry), nowMs, session)

    /** How long [topic] still runs (DAY, WEEK), or null when it has no end by time. */
    fun remainingMs(topic: YtFeedTopic, nowMs: Long): Long? {
        if (topic.expiry != YtTopicExpiry.DAY && topic.expiry != YtTopicExpiry.WEEK) return null
        return topic.expiresAt?.let { (it - nowMs).coerceAtLeast(0L) }
    }

    /**
     * The session after the app came to the front at [nowMs]: a new one (starting now) when there
     * was none ([current] 0), or the app was away for [SESSION_AWAY_MS] since [lastStopMs], or the
     * clock went back; else [current] goes on (also when it started after that stop already).
     */
    fun nextSession(nowMs: Long, lastStopMs: Long?, current: Long): Long {
        if (current <= 0L) return nowMs
        if (lastStopMs == null) return current
        // already started after that stop (the process start looked first)
        if (current > lastStopMs && current <= nowMs) return current
        val away = nowMs - lastStopMs
        return if (away < 0 || away >= SESSION_AWAY_MS) nowMs else current
    }

    // ---------------------------------------------------------------------------------------------
    // Share and mixing

    /**
     * The share of the home each of [topics] gets, 0 to 1, in their order: their own share
     * (snapped), all of them scaled down together where they add up to more than [MAX_TOTAL_SHARE].
     */
    fun fractions(topics: List<YtFeedTopic>): List<Double> {
        val shares = topics.map { snapShare(it.share).toDouble() }
        val total = shares.sum()
        val scale = if (total > MAX_TOTAL_SHARE) MAX_TOTAL_SHARE / total else 1.0
        return shares.map { it * scale / 100.0 }
    }

    /** About how many of [page] videos a [fraction] (0 to 1) is: the sheet's "≈ N von 20". */
    fun perPage(fraction: Double, page: Int = PAGE): Int = (fraction.coerceIn(0.0, 1.0) * page).roundToInt()

    /** The mark of a feed video for [topics]: the topic whose keyword its title names first. */
    fun markOf(title: String, topics: List<YtFeedTopic>): YtTopicMark? {
        for (topic in topics) {
            val keyword = keywordIn(title, topic.keywords) ?: continue
            return YtTopicMark(topic.id, topic.name, keyword)
        }
        return null
    }

    /**
     * The ranked [main] feed with the videos of [pools] mixed in by their share, spread evenly:
     * each place adds its fraction to a topic's due, and a topic with a full place due gets the
     * next place (the first of them at place 1/fraction, never on top). A video of the feed whose
     * title names a keyword, or that a topic's search found, stays where it is, is marked as the
     * topic's and pays one place of its due (at most [MIN_DEBT] ahead), so a feed already full of
     * the topic gets fewer extra videos. Two added videos (of two topics) follow each other only
     * when the shares add up to more than half. Found videos the feed has already are not added again,
     * one found by two topics belongs to the first; nothing is added after the feed ends.
     */
    fun mix(main: List<YtRanked>, pools: List<YtTopicPool>): List<YtRanked> {
        if (pools.isEmpty() || main.isEmpty()) return main
        val topics = pools.map { it.topic }
        val fraction = fractions(topics)
        val at = HashMap<String, Int>()
        topics.forEachIndexed { i, topic -> at.putIfAbsent(topic.id, i) }
        // what each found video belongs to: the first topic that found it
        val found = HashMap<String, Pair<Int, String>>()
        pools.forEachIndexed { t, pool -> pool.candidates.forEach { found.putIfAbsent(it.item.id, t to it.keyword) } }
        val inMain = main.mapTo(HashSet()) { it.item.id }
        val queues = pools.mapIndexed { t, pool ->
            pool.candidates.filter { it.item.id !in inMain && found[it.item.id]?.first == t }.distinctBy { it.item.id }
        }
        val next = IntArray(pools.size)
        val due = DoubleArray(pools.size)
        val out = ArrayList<YtRanked>(main.size + queues.sumOf { it.size })
        // two added videos in a row only where the shares together need it (over half)
        val together = fraction.sum() > 0.5
        var lastAdded = false
        var i = 0
        while (i < main.size) {
            for (t in fraction.indices) due[t] = (due[t] + fraction[t]).coerceAtMost(MAX_DEBT)
            var pick = -1
            if (!lastAdded || together) for (t in fraction.indices) {
                if (fraction[t] <= 0.0 || next[t] >= queues[t].size || due[t] < 1.0 - EPS) continue
                if (pick < 0 || due[t] > due[pick] + EPS) pick = t
            }
            if (pick >= 0) {
                val candidate = queues[pick][next[pick]++]
                due[pick] -= 1.0
                val topic = topics[pick]
                out += topicVideo(candidate, YtTopicMark(topic.id, topic.name, candidate.keyword))
                lastAdded = true
                continue
            }
            lastAdded = false
            val ranked = main[i++]
            val mark = found[ranked.item.id]?.let { (t, keyword) ->
                // the title's own keyword where it names one, else the one it was found by
                val topic = topics[t]
                YtTopicMark(topic.id, topic.name, keywordIn(ranked.item.title, topic.keywords) ?: keyword)
            } ?: markOf(ranked.item.title, topics)
            if (mark == null) {
                out += ranked
                continue
            }
            at[mark.topicId]?.let { t -> due[t] = (due[t] - 1.0).coerceAtLeast(MIN_DEBT) }
            out += ranked.copy(focus = mark, reasons = listOf(reasonOf(mark)) + ranked.reasons)
        }
        return out
    }

    fun reasonOf(mark: YtTopicMark): YtReason = YtReason(YtReasonKind.FOCUS, mark.name, mark.keyword)

    private fun topicVideo(candidate: YtTopicCandidate, mark: YtTopicMark): YtRanked =
        YtRanked(candidate.item, 0.0, listOf(reasonOf(mark)), focus = mark)

    // ---------------------------------------------------------------------------------------------
    // The watch side

    /**
     * The topics of [active] a video belongs to: the one the feed marked it with ([markedTopic]),
     * then every topic whose keyword its [title] or one of its [tags] names.
     */
    fun topicsOf(active: List<YtFeedTopic>, title: String, tags: List<String>, markedTopic: String? = null): List<YtFeedTopic> =
        active.filter { topic ->
            topic.id == markedTopic ||
                keywordIn(title, topic.keywords) != null ||
                tags.any { tag -> keywordIn(tag, topic.keywords) != null }
        }
}

/**
 * YT: which videos of each topic were watched in this session (in memory), and the videos a
 * keyword was already suggested for. A new [session] forgets everything. Pure.
 */
data class YtTopicWatchLog(
    val session: Long = 0L,
    val watched: Map<String, Set<String>> = emptyMap(),
    val suggested: Set<String> = emptySet(),
) {
    /** This log for [session]: itself, or an empty one for a new session. */
    fun inSession(session: Long): YtTopicWatchLog = if (session == this.session) this else YtTopicWatchLog(session)

    fun watched(topicId: String, videoId: String): YtTopicWatchLog =
        copy(watched = watched + (topicId to (watched[topicId].orEmpty() + videoId)))

    fun count(topicId: String): Int = watched[topicId]?.size ?: 0

    /** From the [FROM]th watched video of a topic on, once per video. */
    fun mayAsk(topicId: String, videoId: String): Boolean =
        videoId !in suggested && videoId in watched[topicId].orEmpty() && count(topicId) >= FROM

    fun asked(videoId: String): YtTopicWatchLog = copy(suggested = suggested + videoId)

    companion object {
        /** The suggestion starts with the second video of a topic. */
        const val FROM = 2
    }
}
