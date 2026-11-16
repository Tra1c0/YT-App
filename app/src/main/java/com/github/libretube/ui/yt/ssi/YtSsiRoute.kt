package com.github.libretube.ui.yt.ssi

/** SSI-3: the views of SSI's screen. */
enum class YtSsiView { HITS, EVIDENCE, COMPARE, TIMELINE, REPORT, SAVED }

/**
 * YT (SSI-3): where a query goes, pure. The asks the parser flags ([YtSsiLater]) open their view
 * by themselves: a chronology the timeline (following the named channel when there is one), a
 * comparison the side-by-side view, a refutation or contradiction the evidence view. Every one
 * of them also starts the relation analysis (when the AI is allowed); plain queries stay on the
 * hits, and the analysis starts only on a tap.
 */
object YtSsiRoute {
    /** The view for [intent]; HITS when it asks nothing of SSI-3. */
    fun view(intent: YtSsiIntent?): YtSsiView {
        val asks = intent?.later.orEmpty()
        return when {
            YtSsiLater.CHRONOLOGY in asks -> YtSsiView.TIMELINE
            YtSsiLater.COMPARE in asks -> YtSsiView.COMPARE
            YtSsiLater.REFUTE in asks || YtSsiLater.CONTRADICTION in asks -> YtSsiView.EVIDENCE
            else -> YtSsiView.HITS
        }
    }

    /** Whether [intent] starts the relation analysis by itself (once its search is done). */
    fun autoStance(intent: YtSsiIntent?): Boolean = intent?.later.orEmpty().isNotEmpty()

    /** Whether the timeline of [intent] follows one person (a channel named in the query). */
    fun personTimeline(intent: YtSsiIntent?): Boolean = YtSsiLater.CHRONOLOGY in intent?.later.orEmpty() && YtSsiChronology.person(intent) != null

    /**
     * The hits the side-by-side view compares: the chosen ones (2–4, in the order chosen) or,
     * when fewer than 2 were chosen, that one and then up to [MAX_COMPARE] passages: one per
     * relation with a verified quote first, then the best passages.
     */
    fun compared(hits: List<YtSsiHit>, chosen: List<String>, key: (YtSsiHit) -> String, stances: Map<Long, YtSsiStance>): List<YtSsiHit> {
        val byKey = hits.associateBy(key)
        val picked = chosen.mapNotNull { byKey[it] }.distinct().take(MAX_COMPARE)
        if (picked.size >= 2) return picked
        val passages = hits.filter { it.segmentId != null }
        val out = ArrayList<YtSsiHit>(picked)
        // one per relation that has a verified quote, in the order of the relations
        for (relation in YtSsiRelation.entries) {
            if (relation == YtSsiRelation.UNCERTAIN) continue
            passages.firstOrNull { h -> h !in out && stances[h.segmentId]?.let { it.verified && it.relation == relation } == true }?.let { out += it }
            if (out.size >= MAX_COMPARE) return out
        }
        for (hit in passages) {
            if (out.size >= MAX_COMPARE) break
            if (hit !in out) out += hit
        }
        return out.take(MAX_COMPARE)
    }

    const val MAX_COMPARE = 4
}
