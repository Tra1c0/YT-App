package com.github.libretube.ui.yt.archive.risk

import com.github.libretube.ui.yt.archive.YtArchiveCandidate
import com.github.libretube.ui.yt.archive.YtRiskScore
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * YT: the small learning model of the risk estimator. It learns on the device from videos that
 * really vanished or stayed ([YtRiskModel.observe]) and combines, in log-odds:
 *  - the global rate of vanished videos (Beta prior 0.5 / 30, so about 1.6 % before any label),
 *  - the channel's own rate (Beta-Binomial around the global rate with strength 20, about
 *    0.33 / 19.7 at the start: one deleted video does not make a channel risky),
 *  - the topic words (per-word rate of vanished videos with the same kind of shrinkage, strongest
 *    words only, damped while few videos have vanished),
 *  - fixed keywords as the cold-start prior (weight shrinks as vanished videos are learned),
 *  - optionally the AI's estimate (weight shrinks as vanished videos are learned).
 * All counts decay with a half-life of 180 days; vocabulary, channels and labels are capped, so the
 * stored JSON stays small. Free of Android; thread safety is the caller's (see [YtRiskEngine]).
 */

/** Decayed counts of vanished ([gone]) and stayed ([kept]) videos. */
@Serializable
class YtRiskCount(
    @SerialName("g") var gone: Double = 0.0,
    @SerialName("k") var kept: Double = 0.0,
) {
    val total get() = gone + kept
}

/** The last label of one video: vanished or not, and when it was learned. */
@Serializable
data class YtRiskLabel(
    @SerialName("g") val gone: Boolean,
    @SerialName("t") val at: Long,
)

/** Everything the model has learned; stored as JSON. */
@Serializable
class YtRiskState(
    @SerialName("v") val version: Int = 1,
    @SerialName("gone") var goneDocs: Double = 0.0,
    @SerialName("kept") var keptDocs: Double = 0.0,
    @SerialName("decayed") var decayedAt: Long = 0,
    @SerialName("channels") val channels: MutableMap<String, YtRiskCount> = HashMap(),
    @SerialName("words") val words: MutableMap<String, YtRiskCount> = HashMap(),
    @SerialName("labels") val labels: MutableMap<String, YtRiskLabel> = HashMap(),
)

/** The parts of one estimate, in log-odds, with what the reason needs to name them. */
data class YtRiskFactors(
    val base: Double,
    val channel: Double = 0.0,
    val channelGone: Int = 0,
    val channelTotal: Int = 0,
    val words: Double = 0.0,
    val topics: List<String> = emptyList(),
    val keywords: Double = 0.0,
    val keywordLabels: List<String> = emptyList(),
    val ai: Double = 0.0,
    val aiRisk: Double? = null,
    val aiReason: String? = null,
) {
    val logit: Double get() = (base + channel + words + keywords + ai).coerceIn(-LOGIT_CAP, LOGIT_CAP)
    val risk: Double get() = YtRiskModel.sigmoid(logit)

    private companion object {
        const val LOGIT_CAP = 8.0
    }
}

/** How the reason names its factors, in the user's language (string resources in the app). */
interface YtRiskWords {
    fun channel(gone: Int, total: Int): String
    fun topic(words: List<String>): String
    fun keywords(words: List<String>): String
    fun ai(reason: String?, percent: Int): String
}

/** An answer of the AI for one video; [risk] null when it was asked but gave none. */
@Serializable
data class YtRiskAiAnswer(
    @SerialName("r") val risk: Double? = null,
    @SerialName("w") val reason: String? = null,
    @SerialName("t") val at: Long = 0,
)

class YtRiskModel(val state: YtRiskState = YtRiskState()) {

    /**
     * Learns that [candidate] vanished or stayed. The same label again changes nothing (the core
     * checks a video many times); a changed label first takes the old one back. True when the
     * model changed.
     */
    fun observe(candidate: YtArchiveCandidate, vanished: Boolean, now: Long): Boolean {
        decay(now)
        val previous = state.labels[candidate.videoId]
        if (previous != null && previous.gone == vanished) return false
        val channel = channelKey(candidate)
        val tokens = YtRiskText.tokens(YtRiskText.words(candidate.title, candidate.description))
        if (previous != null) {
            val left = 0.5.pow((now - previous.at).coerceAtLeast(0).toDouble() / HALF_LIFE_MS)
            add(channel, tokens, previous.gone, -left)
        }
        add(channel, tokens, vanished, 1.0)
        state.labels[candidate.videoId] = YtRiskLabel(vanished, now)
        prune(now)
        return true
    }

    private fun add(channel: String?, tokens: Set<String>, gone: Boolean, amount: Double) {
        fun YtRiskCount.bump() {
            if (gone) this.gone = (this.gone + amount).coerceAtLeast(0.0) else kept = (kept + amount).coerceAtLeast(0.0)
        }
        if (gone) state.goneDocs = (state.goneDocs + amount).coerceAtLeast(0.0) else state.keptDocs = (state.keptDocs + amount).coerceAtLeast(0.0)
        if (channel != null) state.channels.getOrPut(channel) { YtRiskCount() }.bump()
        for (t in tokens) state.words.getOrPut(t) { YtRiskCount() }.bump()
    }

    /** Lets old counts fade: at most once a day, by the time passed since the last time. */
    fun decay(now: Long) {
        if (state.decayedAt == 0L) {
            state.decayedAt = now
            return
        }
        val passed = now - state.decayedAt
        if (passed < DAY_MS) return
        val f = 0.5.pow(passed.toDouble() / HALF_LIFE_MS)
        state.goneDocs *= f
        state.keptDocs *= f
        for (c in state.channels.values) { c.gone *= f; c.kept *= f }
        for (c in state.words.values) { c.gone *= f; c.kept *= f }
        state.channels.values.removeAll { it.total < FADED }
        state.words.values.removeAll { it.total < FADED }
        state.decayedAt = now
    }

    /** Keeps the stored model small: old labels out, then the least telling channels and words. */
    private fun prune(now: Long) {
        state.labels.values.removeAll { now - it.at > LABEL_DAYS * DAY_MS }
        if (state.labels.size > MAX_LABELS) {
            state.labels.entries.sortedBy { it.value.at }.take(state.labels.size - MAX_LABELS * 9 / 10)
                .forEach { state.labels.remove(it.key) }
        }
        trim(state.channels, MAX_CHANNELS)
        trim(state.words, MAX_WORDS)
    }

    private fun trim(map: MutableMap<String, YtRiskCount>, max: Int) {
        if (map.size <= max) return
        // a vanished video is rare and worth ten that stayed
        map.entries.sortedBy { it.value.gone * 10 + it.value.kept }.take(map.size - max * 9 / 10)
            .forEach { map.remove(it.key) }
    }

    /** The share of videos that vanished, with the prior 0.5 / 30. */
    fun globalRate(): Double = (PRIOR_GONE + state.goneDocs) / (PRIOR_GONE + PRIOR_KEPT + state.goneDocs + state.keptDocs)

    /** The parts of the estimate for [candidate]; [ai] is the AI's answer when there is one. */
    fun factors(candidate: YtArchiveCandidate, ai: YtRiskAiAnswer? = null): YtRiskFactors {
        val g = globalRate()
        val base = logit(g)
        val gone = state.goneDocs

        var channel = 0.0
        var channelGone = 0
        var channelTotal = 0
        channelKey(candidate)?.let { state.channels[it] }?.let { c ->
            val p = (c.gone + CHANNEL_STRENGTH * g) / (c.total + CHANNEL_STRENGTH)
            channel = logit(p) - base
            channelGone = c.gone.roundToInt()
            channelTotal = c.total.roundToInt().coerceAtLeast(channelGone)
        }

        val words = YtRiskText.words(candidate.title, candidate.description)
        val ratios = YtRiskText.tokens(words).mapNotNull { t ->
            val c = state.words[t] ?: return@mapNotNull null
            val r = (c.gone + WORD_STRENGTH * g) / (c.total + WORD_STRENGTH)
            Triple(t, logit(r) - base, c.gone)
        }
        val up = ratios.map { it.second }.filter { it > 0 }.sortedDescending()
        val down = ratios.map { it.second }.filter { it < 0 }.sorted()
        val raw = (up.getOrElse(0) { 0.0 } + SECOND * up.getOrElse(1) { 0.0 }) +
            (down.getOrElse(0) { 0.0 } + SECOND * down.getOrElse(1) { 0.0 })
        val wordTerm = (WORD_WEIGHT * gone / (gone + WORD_DAMP) * raw).coerceIn(WORD_MIN, WORD_MAX)
        val topics = ratios.filter { it.second >= TOPIC_MIN && it.third >= TOPIC_GONE_MIN }
            .sortedByDescending { it.second }.take(2).map { YtRiskText.display(it.first) }

        val found = YtRiskText.keywords(words)
        val keywordWeight = KEYWORD_FLOOR + (1 - KEYWORD_FLOOR) * KEYWORD_HALF / (KEYWORD_HALF + gone)
        val keywordTerm = found.sumOf { it.weight }.coerceAtMost(KEYWORD_CAP) * keywordWeight

        val aiRisk = ai?.risk
        val aiTerm = if (aiRisk == null) {
            0.0
        } else {
            val weight = AI_FLOOR + (AI_START - AI_FLOOR) * AI_HALF / (AI_HALF + gone)
            weight * (logit(aiRisk.coerceIn(AI_CLAMP, 1 - AI_CLAMP)) - logit(AI_NEUTRAL))
        }

        return YtRiskFactors(
            base = base,
            channel = channel,
            channelGone = channelGone,
            channelTotal = channelTotal,
            words = wordTerm,
            topics = if (wordTerm >= REASON_MIN) topics else emptyList(),
            keywords = keywordTerm,
            keywordLabels = found.map { it.label },
            ai = aiTerm,
            aiRisk = aiRisk,
            aiReason = ai?.reason,
        )
    }

    /** The score of [candidate] with a short reason naming the strongest factors. */
    fun score(candidate: YtArchiveCandidate, ai: YtRiskAiAnswer?, words: YtRiskWords): YtRiskScore {
        val f = factors(candidate, ai)
        val risk = f.risk
        return YtRiskScore(candidate.videoId, risk, risk >= HIGH, reason(f, words))
    }

    /** Learned channels and labels, for the diagnosis line. */
    fun counts(): Triple<Int, Int, Int> =
        Triple(state.channels.size, state.labels.values.count { it.gone }, state.labels.values.count { !it.gone })

    fun encode(): String = JSON.encodeToString(YtRiskState.serializer(), state)

    companion object {
        /** The alert line: a video at or above it is "high". */
        const val HIGH = 0.35

        const val PRIOR_GONE = 0.5
        const val PRIOR_KEPT = 30.0

        /**
         * How many labels the channel prior is worth: one deletion moves a channel to about 6 %,
         * 15 of 20 or 10 of 10 cross the alert line (with 30.5, as the global prior, 15 of 20
         * stayed at 31 %).
         */
        const val CHANNEL_STRENGTH = 20.0

        /** How many labels the prior of one word is worth. */
        const val WORD_STRENGTH = 20.0

        /** The second strongest word counts half; the rest not at all (words come in clusters). */
        private const val SECOND = 0.5
        private const val WORD_WEIGHT = 0.8

        /** The word term reaches half of [WORD_WEIGHT] at this many vanished videos. */
        private const val WORD_DAMP = 3.0
        private const val WORD_MIN = -2.0
        private const val WORD_MAX = 3.0
        private const val TOPIC_MIN = 1.0
        private const val TOPIC_GONE_MIN = 0.75

        private const val KEYWORD_CAP = 3.6
        private const val KEYWORD_FLOOR = 0.3

        /** Keywords drop halfway to their floor at this many vanished videos. */
        private const val KEYWORD_HALF = 10.0

        private const val AI_START = 0.8
        private const val AI_FLOOR = 0.25
        private const val AI_HALF = 20.0

        /** An AI estimate at this value leaves the risk as it is. */
        const val AI_NEUTRAL = 0.15
        private const val AI_CLAMP = 0.02

        /** A factor is named in the reason from this many log-odds on. */
        const val REASON_MIN = 0.5
        private const val REASON_PARTS = 2
        const val SEPARATOR = " · "

        const val DAY_MS = 24L * 60 * 60 * 1000
        const val HALF_LIFE_MS = 180 * DAY_MS
        private const val FADED = 0.05
        const val LABEL_DAYS = 400L
        const val MAX_LABELS = 3000
        const val MAX_CHANNELS = 1500
        const val MAX_WORDS = 3000

        val JSON = Json { ignoreUnknownKeys = true }

        fun decode(text: String): YtRiskModel = YtRiskModel(JSON.decodeFromString(YtRiskState.serializer(), text))

        fun channelKey(c: YtArchiveCandidate): String? =
            c.channelId?.trim()?.takeIf { it.isNotEmpty() }
                ?: c.channelName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { "name:$it" }

        fun logit(p: Double): Double = ln(p / (1 - p))
        fun sigmoid(x: Double): Double = 1 / (1 + exp(-x))

        /** The strongest factors as one short sentence, or null when nothing stands out. */
        fun reason(f: YtRiskFactors, words: YtRiskWords): String? {
            val parts = buildList {
                if (f.channelGone >= 1) add(f.channel to { words.channel(f.channelGone, f.channelTotal) })
                if (f.topics.isNotEmpty()) add(f.words to { words.topic(f.topics) })
                if (f.keywordLabels.isNotEmpty()) add(f.keywords to { words.keywords(f.keywordLabels.take(3)) })
                f.aiRisk?.let { r -> add(f.ai to { words.ai(f.aiReason, (r * 100).roundToInt()) }) }
            }
            val named = parts.filter { it.first >= REASON_MIN }.sortedByDescending { it.first }.take(REASON_PARTS)
            return named.takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR) { it.second() }
        }
    }
}
