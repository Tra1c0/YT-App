package com.github.libretube.ui.yt.ssi

import kotlin.random.Random

/**
 * YT: SSI's settings keys (screen "SSI-Suche", `R/xml/yt_r33_ssi_settings.xml`) and its pure
 * budget rules. Deep asks YouTube like the archive does (one stream-info request and one caption
 * file per video, a random pause between videos, a bot check or 429 pauses everything), with
 * shorter pauses because someone is waiting.
 */
object YtSsiPrefs {
    const val KEY_BACKGROUND = "yt_ssi_background"
    const val DEFAULT_BACKGROUND = true
    const val KEY_FEED = "yt_ssi_feed"
    const val DEFAULT_FEED = true
    const val KEY_DEEP_BUDGET = "yt_ssi_deep_budget"
    const val DEFAULT_DEEP_BUDGET = 6
    val DEEP_BUDGETS = listOf(3, 6, 10, 15)
    const val KEY_ALLOW_AI = "yt_ssi_allow_ai"
    const val DEFAULT_ALLOW_AI = false

    /** SSI-2: Deep lets the AI grade its top passages (needs [KEY_ALLOW_AI]). */
    const val KEY_AI_RERANK = "yt_ssi_ai_rerank"
    const val DEFAULT_AI_RERANK = true

    /** SSI-2: feedback adjusts the fusion weights and the meaning cut-off. */
    const val KEY_LEARN = "yt_ssi_learn"
    const val DEFAULT_LEARN = true

    /** SSI-3: the relation analysis ("Belege"), needs [KEY_ALLOW_AI]. */
    const val KEY_STANCE = "yt_ssi_stance"
    const val DEFAULT_STANCE = true

    /** SSI-3: AI requests per analysis (each places [YtSsiStanceRules.BATCH] passages). */
    const val KEY_STANCE_PER_SEARCH = "yt_ssi_stance_per_search"
    const val DEFAULT_STANCE_PER_SEARCH = 3
    val STANCE_PER_SEARCH = listOf(1, 2, 3, 5)

    /** SSI-3: AI requests for relations per day (counted on the phone). */
    const val KEY_STANCE_PER_DAY = "yt_ssi_stance_per_day"
    const val DEFAULT_STANCE_PER_DAY = 30
    val STANCE_PER_DAY = listOf(10, 20, 30, 50)

    fun stancePerSearch(raw: String?): Int = raw?.toIntOrNull()?.takeIf { it in STANCE_PER_SEARCH } ?: DEFAULT_STANCE_PER_SEARCH

    fun stancePerDay(raw: String?): Int = raw?.toIntOrNull()?.takeIf { it in STANCE_PER_DAY } ?: DEFAULT_STANCE_PER_DAY

    /** Per Deep search: at most this many YouTube searches. */
    const val MAX_SEARCHES = 3

    /** Per background run: feed videos whose captions are fetched. */
    const val FEED_PER_RUN = 12

    /** Feed videos older than this are not fetched in the background. */
    const val FEED_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    /** A pause of 1–3 s between two videos of a Deep search. */
    const val DEEP_JITTER_MIN_MS = 1_000L
    const val DEEP_JITTER_MAX_MS = 3_000L

    /** One background run; WorkManager stops a worker after 10 minutes. */
    const val RUN_BUDGET_MS = 8L * 60 * 1000

    fun budget(raw: String?): Int = raw?.toIntOrNull()?.takeIf { it in DEEP_BUDGETS } ?: DEFAULT_DEEP_BUDGET

    fun deepJitterMs(random: Random = Random.Default): Long = random.nextLong(DEEP_JITTER_MIN_MS, DEEP_JITTER_MAX_MS + 1)
}
