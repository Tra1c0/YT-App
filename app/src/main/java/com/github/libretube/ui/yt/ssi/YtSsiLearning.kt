package com.github.libretube.ui.yt.ssi

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.pow

/** What the user said about a hit (the feedback buttons). */
enum class YtSsiVerdict {
    /** "Perfekte Stelle". */
    PERFECT,

    /** "Richtiges Video, falsche Stelle". */
    WRONG_SPOT,

    /** "Falscher Kontext". */
    WRONG_CONTEXT,

    /** "Passt nicht". */
    NOT_RELEVANT,

    /** "Zeit ungenau": about the timing only, the ranking learns nothing from it. */
    TIME_OFF,
}

/**
 * One feedback as stored (`feedback` table) and learnt from: the hit's place in each method's
 * list, whether the meaning search ran at all, and the AI's grade.
 */
data class YtSsiFeedback(
    val at: Long,
    val query: String,
    val kind: YtSsiIntentKind,
    val videoId: String,
    val segmentId: Long?,
    val verdict: YtSsiVerdict,
    val lexicalRank: Int?,
    val semanticRank: Int?,
    val titleRank: Int?,
    val aiGrade: Int?,
    val semanticUsed: Boolean,
    val timing: YtSsiTiming,
)

/** The multipliers of one intent kind's fusion weights. */
@Serializable
data class YtSsiLearnMult(val lexical: Double = 1.0, val semantic: Double = 1.0, val title: Double = 1.0)

/** What SSI has learnt from feedback; stored as JSON, reset by the settings' button. */
@Serializable
data class YtSsiLearnState(
    /** By [YtSsiIntentKind] name. */
    val kinds: Map<String, YtSsiLearnMult> = emptyMap(),
    /** Added to [YtSsiVectorIndex.MIN_COSINE] (the meaning search's cut-off). */
    val cutOffset: Double = 0.0,
    /** When the values were last moved (their decay counts from here). */
    val at: Long = 0,
    /** Feedback given per verdict (not decayed). */
    val verdicts: Map<String, Int> = emptyMap(),
) {
    val events: Int get() = verdicts.values.sum()
}

/**
 * YT (SSI-2): a small, bounded learning step from feedback, pure and transparent.
 * - Fusion weights per intent kind: a verdict credits each method by its place for the hit
 *   (1 at place 1, falling, 0 beyond place 20), signed (+1 "Perfekte Stelle", −1 "Passt nicht",
 *   −½ "Falscher Kontext"; "Richtiges Video, falsche Stelle" −½ for the passage methods and +½
 *   for the title), and moves each multiplier by [ETA] × (its credit − the mean credit), so only
 *   the balance between methods changes. Bounds [MIN_MULT]..[MAX_MULT].
 * - The meaning search's cut-off: a hit found only by meaning moves it by [CUT_STEP] (down for
 *   "Perfekte Stelle", up for "Passt nicht"/"Falscher Kontext"), at most ±[MAX_CUT].
 * - Everything decays back to neutral with a half-life of [HALF_LIFE_MS], so an old verdict
 *   fades. "Zeit ungenau" changes no weight. Reset = [YtSsiLearnState] ().
 */
object YtSsiLearning {
    const val ETA = 0.06
    const val MIN_MULT = 0.6
    const val MAX_MULT = 1.4
    const val CUT_STEP = 0.004
    const val MAX_CUT = 0.03
    const val HALF_LIFE_MS = 30L * 24 * 60 * 60 * 1000

    /** Beyond this place a method gets no credit for a hit. */
    const val CREDIT_PLACES = 20

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(state: YtSsiLearnState): String = json.encodeToString(YtSsiLearnState.serializer(), state)

    fun decode(text: String?): YtSsiLearnState = text?.let { runCatching { json.decodeFromString(YtSsiLearnState.serializer(), it) }.getOrNull() } ?: YtSsiLearnState()

    /** [state] with its values faded towards neutral for the time since it was last moved. */
    fun decay(state: YtSsiLearnState, now: Long): YtSsiLearnState {
        if (state.at <= 0 || now <= state.at) return state
        val f = 0.5.pow((now - state.at).toDouble() / HALF_LIFE_MS)
        fun fade(v: Double) = 1.0 + (v - 1.0) * f
        return state.copy(
            kinds = state.kinds.mapValues { (_, m) -> YtSsiLearnMult(fade(m.lexical), fade(m.semantic), fade(m.title)) },
            cutOffset = state.cutOffset * f,
            at = now,
        )
    }

    /** The credit of a method that put the hit at [rank] (null: not found by it). */
    fun credit(rank: Int?): Double = if (rank == null || rank < 1 || rank > CREDIT_PLACES) 0.0 else 1.0 / (1.0 + (rank - 1) / 3.0)

    /** [state] after [feedback]. */
    fun learn(state: YtSsiLearnState, feedback: YtSsiFeedback): YtSsiLearnState {
        val now = feedback.at
        val faded = decay(state, now).let { if (it.at == 0L) it.copy(at = now) else it }
        val verdicts = faded.verdicts + (feedback.verdict.name to (faded.verdicts[feedback.verdict.name] ?: 0) + 1)
        if (feedback.verdict == YtSsiVerdict.TIME_OFF) return faded.copy(verdicts = verdicts, at = now)
        val (passageSign, titleSign) = when (feedback.verdict) {
            YtSsiVerdict.PERFECT -> 1.0 to 1.0
            YtSsiVerdict.NOT_RELEVANT -> -1.0 to -1.0
            YtSsiVerdict.WRONG_CONTEXT -> -0.5 to -0.5
            YtSsiVerdict.WRONG_SPOT -> -0.5 to 0.5
            YtSsiVerdict.TIME_OFF -> 0.0 to 0.0
        }
        val lexical = passageSign * credit(feedback.lexicalRank)
        val semantic = if (feedback.semanticUsed) passageSign * credit(feedback.semanticRank) else null
        val title = titleSign * credit(feedback.titleRank)
        val raw = listOfNotNull(lexical, semantic, title)
        val mean = raw.average()
        val old = faded.kinds[feedback.kind.name] ?: YtSsiLearnMult()
        fun move(value: Double, credit: Double?) = if (credit == null) value else (value + ETA * (credit - mean)).coerceIn(MIN_MULT, MAX_MULT)
        val mult = YtSsiLearnMult(move(old.lexical, lexical), move(old.semantic, semantic), move(old.title, title))
        var cut = faded.cutOffset
        val meaningOnly = feedback.semanticUsed && feedback.semanticRank != null && feedback.lexicalRank == null
        if (meaningOnly) {
            cut += when (feedback.verdict) {
                YtSsiVerdict.PERFECT -> -CUT_STEP
                YtSsiVerdict.NOT_RELEVANT, YtSsiVerdict.WRONG_CONTEXT -> CUT_STEP
                else -> 0.0
            }
        }
        return faded.copy(
            kinds = faded.kinds + (feedback.kind.name to mult),
            cutOffset = cut.coerceIn(-MAX_CUT, MAX_CUT),
            at = now,
            verdicts = verdicts,
        )
    }

    /** [base] with the learnt multipliers of [kind] applied. */
    fun weights(base: YtSsiWeights, kind: YtSsiIntentKind, state: YtSsiLearnState, now: Long): YtSsiWeights {
        val m = decay(state, now).kinds[kind.name] ?: return base
        return YtSsiWeights(base.lexical * m.lexical, base.semantic * m.semantic, base.title * m.title)
    }

    /** The meaning search's cut-off with the learnt offset. */
    fun minCosine(state: YtSsiLearnState, now: Long): Float = (YtSsiVectorIndex.MIN_COSINE + decay(state, now).cutOffset).toFloat()

    /** The feedback of [verdict] on [hit] of a search for [intent]. */
    fun feedback(hit: YtSsiHit, intent: YtSsiIntent, verdict: YtSsiVerdict, now: Long, semanticUsed: Boolean): YtSsiFeedback {
        val textual = YtSsiMatch.TRANSCRIPT_LEXICAL in hit.matches || YtSsiMatch.CHAPTER in hit.matches
        val titled = YtSsiMatch.TITLE in hit.matches || YtSsiMatch.DESCRIPTION in hit.matches
        return YtSsiFeedback(
            at = now,
            query = intent.cacheKey,
            kind = intent.kind,
            videoId = hit.videoId,
            segmentId = hit.segmentId,
            verdict = verdict,
            lexicalRank = hit.ranks[YtSsiMethod.FTS_BM25]?.takeIf { textual },
            semanticRank = hit.ranks[YtSsiMethod.EMBEDDING],
            titleRank = if (titled) hit.ranks[YtSsiMethod.FTS_BM25] ?: 1 else null,
            aiGrade = hit.ai?.relevant,
            semanticUsed = semanticUsed,
            timing = hit.timing,
        )
    }

    /** For Diagnose: counts, the moved weights per kind and the cut-off. */
    fun summary(state: YtSsiLearnState, now: Long): String {
        val s = decay(state, now)
        if (s.events == 0) return "no feedback"
        return buildString {
            append("${s.events} feedback (")
            append(YtSsiVerdict.entries.joinToString(" ") { "${it.name.lowercase()} ${s.verdicts[it.name] ?: 0}" })
            append(")")
            for ((kind, m) in s.kinds.toSortedMap()) {
                append(", ${kind.lowercase()} lex ×%.2f sem ×%.2f title ×%.2f".format(java.util.Locale.ROOT, m.lexical, m.semantic, m.title))
            }
            append(", cut-off %.3f (%+.3f)".format(java.util.Locale.ROOT, YtSsiVectorIndex.MIN_COSINE + s.cutOffset, s.cutOffset))
        }
    }
}
