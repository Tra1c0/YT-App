package com.github.libretube.ui.yt.ssi

import com.github.libretube.ui.yt.news.YtNewsTranscriptDoc
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** SSI-3: the groups of an evidence report, in the order it lists them. */
enum class YtSsiGroup { SUPPORTS, REFUTES_REASONED, CONTRADICTS, QUALIFIES, QUOTES, DISCUSSES, UNCERTAIN, UNCHECKED }

/**
 * One statement of a report: a hit, its relation and the quote it rests on. [quote] is the
 * verified evidence when [verified], else the passage's own text (shown as "nicht belegt").
 */
data class YtSsiFinding(
    val hit: YtSsiHit,
    val stance: YtSsiStance?,
    val group: YtSsiGroup,
    val quote: String,
    val verified: Boolean,
    val ms: Long,
    val timing: YtSsiTiming,
    val url: String,
    val origin: YtSsiOrigin?,
)

/** What a report cannot vouch for. */
data class YtSsiUncertainty(
    /** Relations the AI named whose quote was not found (forced to "unklar"). */
    val notVerified: Int,
    /** Passages the AI itself could not place. */
    val aiUnsure: Int,
    /** Hits without any relation (not analysed, budget, failure or no AI). */
    val unchecked: Int,
    val aiMissing: Boolean,
    /** Statements whose time is not word-exact. */
    val approximate: Int,
    val undated: Int,
    val videos: Int,
    val origins: Int,
    val shifts: List<YtSsiShift>,
)

/** SSI-3: an evidence report as shown and exported. */
data class YtSsiEvidenceReport(
    val question: String,
    val claim: String,
    val createdAt: Long,
    val groups: List<Pair<YtSsiGroup, List<YtSsiFinding>>>,
    val sameOrigin: List<YtSsiOrigin>,
    val uncertainty: YtSsiUncertainty,
    val notes: String,
)

/**
 * YT (SSI-3): builds the evidence report and writes it as Markdown and JSON, pure. Every
 * statement links its video at the second ("&t="). The headings and sentences of the Markdown
 * come from [Labels] (the screen fills them from the string resources), so the export speaks
 * the app's language; the JSON keys are fixed English.
 */
object YtSsiReport {
    /** A video link at a second: `https://www.youtube.com/watch?v=<id>&t=<s>s`. */
    fun url(videoId: String, ms: Long): String = "https://www.youtube.com/watch?v=$videoId&t=${(ms / 1000).coerceAtLeast(0)}s"

    fun group(stance: YtSsiStance?): YtSsiGroup = when {
        stance == null -> YtSsiGroup.UNCHECKED
        stance.relation == YtSsiRelation.SUPPORTS -> YtSsiGroup.SUPPORTS
        stance.relation == YtSsiRelation.REFUTES -> if (stance.opposition == YtSsiOpposition.REASONED) YtSsiGroup.REFUTES_REASONED else YtSsiGroup.CONTRADICTS
        stance.relation == YtSsiRelation.QUALIFIES -> YtSsiGroup.QUALIFIES
        stance.relation == YtSsiRelation.QUOTES -> YtSsiGroup.QUOTES
        stance.relation == YtSsiRelation.DISCUSSES -> YtSsiGroup.DISCUSSES
        else -> YtSsiGroup.UNCERTAIN
    }

    fun build(
        question: String,
        claim: String,
        hits: List<YtSsiHit>,
        stances: Map<Long, YtSsiStance>,
        origins: List<YtSsiOrigin>,
        aiAvailable: Boolean,
        now: Long,
        notes: String = "",
        person: String? = null,
    ): YtSsiEvidenceReport {
        val byVideo = YtSsiSources.byVideo(origins)
        val findings = hits.filter { it.segmentId != null }.map { hit ->
            val stance = stances[hit.segmentId]
            val evidence = stance?.evidence
            YtSsiFinding(
                hit = hit,
                stance = stance,
                group = group(stance),
                quote = evidence?.quote ?: hit.text,
                verified = evidence != null,
                ms = evidence?.ms ?: hit.hitMs,
                timing = evidence?.timing ?: hit.timing,
                url = url(hit.videoId, evidence?.ms ?: (hit.span?.playStartMs ?: hit.hitMs)),
                origin = byVideo[hit.videoId],
            )
        }
        val groups = YtSsiGroup.entries.mapNotNull { g -> findings.filter { it.group == g }.takeIf { it.isNotEmpty() }?.let { g to it } }
        val analysed = stances.values.filter { s -> findings.any { it.hit.segmentId == s.segmentId } }
        val chain = (person?.let { YtSsiChronology.of(hits, it) } ?: hits).let { YtSsiChronology.shifts(it, stances) }
        val videoIds = hits.map { it.videoId }.distinct()
        val relevant = origins.filter { o -> o.videos.any { it in videoIds } }
        return YtSsiEvidenceReport(
            question = question,
            claim = claim,
            createdAt = now,
            groups = groups,
            sameOrigin = relevant.filter { it.size > 1 },
            uncertainty = YtSsiUncertainty(
                notVerified = analysed.count { it.unsure == YtSsiUnsure.NO_QUOTE || it.unsure == YtSsiUnsure.QUOTE_NOT_FOUND },
                aiUnsure = analysed.count { it.unsure == YtSsiUnsure.AI_SAID },
                unchecked = findings.count { it.stance == null },
                aiMissing = !aiAvailable,
                approximate = findings.count { it.timing != YtSsiTiming.WORD },
                undated = findings.count { it.hit.uploaded == null },
                videos = videoIds.size,
                origins = if (relevant.isEmpty()) videoIds.size else relevant.size,
                shifts = chain,
            ),
            notes = notes,
        )
    }

    /** The words of the Markdown export (from the string resources; `%1$d` style placeholders). */
    data class Labels(
        val title: String,
        val question: String,
        val claim: String,
        val created: String,
        val neutral: String,
        val groups: Map<YtSsiGroup, String>,
        val flags: Map<YtSsiContextFlag, String>,
        val reason: String,
        val notVerified: String,
        val passageOnly: String,
        val sameOrigin: String,
        /** "%1$d Videos, %2$d Ursprünge" */
        val originsCount: String,
        val uncertainties: String,
        val uncertainNotVerified: String,
        val uncertainAi: String,
        val uncertainUnchecked: String,
        val uncertainNoAi: String,
        val uncertainApprox: String,
        val uncertainUndated: String,
        val shift: String,
        val shiftNote: String,
        val notes: String,
        val none: String,
        val approx: String,
    )

    private fun date(ms: Long?, zone: ZoneId): String = ms?.let { DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: "–"

    private fun stamp(ms: Long, timing: YtSsiTiming, labels: Labels): String {
        val s = YtNewsTranscriptDoc.stamp(ms / 1000)
        return if (YtSsiPlayRules.label(timing).approximate) labels.approx + s else s
    }

    private fun md(text: String): String = text.replace("\n", " ").replace("|", "\\|").replace("*", "\\*").replace("_", "\\_").trim()

    fun markdown(report: YtSsiEvidenceReport, labels: Labels, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        append("# ").append(labels.title).append("\n\n")
        append("**").append(labels.question).append(":** ").append(md(report.question)).append("  \n")
        if (report.claim.isNotBlank()) append("**").append(labels.claim).append(":** ").append(md(report.claim)).append("  \n")
        append("**").append(labels.created).append(":** ").append(date(report.createdAt, zone)).append("\n\n")
        append("> ").append(labels.neutral).append("\n\n")
        val u = report.uncertainty
        append(labels.originsCount.format(u.videos, u.origins)).append("\n\n")
        for ((group, findings) in report.groups) {
            append("## ").append(labels.groups[group] ?: group.name).append(" (").append(findings.size).append(")\n\n")
            for (f in findings) {
                val h = f.hit
                append("- [").append(md(h.title)).append(" @ ").append(stamp(f.ms, f.timing, labels)).append("](").append(f.url).append(")")
                append(" — ").append(md(h.channelName ?: "–")).append(", ").append(date(h.uploaded, zone)).append("\n")
                if (f.verified) {
                    append("  > „").append(md(f.quote)).append("“\n")
                } else {
                    append("  > ").append(if (f.stance != null) labels.notVerified else labels.passageOnly).append(": „").append(md(f.quote.take(280))).append("“\n")
                }
                f.stance?.reason?.let { append("  - ").append(labels.reason).append(": „").append(md(it.quote)).append("“ (").append(stamp(it.ms, it.timing, labels)).append(")\n") }
                f.stance?.flags?.forEach { (flag, ev) -> append("  - ").append(labels.flags[flag] ?: flag.name).append(": „").append(md(ev.quote)).append("“\n") }
                f.origin?.takeIf { it.size > 1 && it.key != h.videoId }?.let { append("  - ").append(labels.sameOrigin).append(": ").append(it.key).append("\n") }
            }
            append("\n")
        }
        append("## ").append(labels.uncertainties).append("\n\n")
        val lines = buildList {
            if (u.aiMissing) add(labels.uncertainNoAi)
            if (u.notVerified > 0) add(labels.uncertainNotVerified.format(u.notVerified))
            if (u.aiUnsure > 0) add(labels.uncertainAi.format(u.aiUnsure))
            if (u.unchecked > 0) add(labels.uncertainUnchecked.format(u.unchecked))
            if (u.approximate > 0) add(labels.uncertainApprox.format(u.approximate))
            if (u.undated > 0) add(labels.uncertainUndated.format(u.undated))
            for (origin in report.sameOrigin) add(labels.sameOrigin + ": " + origin.videos.joinToString(", "))
            for (s in u.shifts) {
                add(labels.shift.format(md(s.earlier.channelName ?: "–"), date(s.earlier.uploaded, zone), date(s.later.uploaded, zone)) + " " + labels.shiftNote)
            }
        }
        if (lines.isEmpty()) append("- ").append(labels.none).append("\n") else lines.forEach { append("- ").append(it).append("\n") }
        if (report.notes.isNotBlank()) {
            append("\n## ").append(labels.notes).append("\n\n").append(report.notes.trim()).append("\n")
        }
    }

    private val pretty = Json { prettyPrint = true }

    private fun evidenceJson(e: YtSsiEvidence): JsonObject = buildJsonObject {
        put("quote", e.quote)
        put("segment", e.segmentId)
        put("ms", e.ms)
        put("timing", e.timing.name.lowercase())
    }

    fun json(report: YtSsiEvidenceReport): String {
        val u = report.uncertainty
        val obj = buildJsonObject {
            put("format", "yt-ssi-report")
            put("version", 1)
            put("question", report.question)
            put("claim", report.claim)
            put("created", report.createdAt)
            putJsonArray("findings") {
                for ((group, findings) in report.groups) {
                    for (f in findings) {
                        addJsonObject {
                            put("group", group.name.lowercase())
                            put("relation", f.stance?.relation?.name?.lowercase())
                            f.stance?.opposition?.let { put("opposition", it.name.lowercase()) }
                            put("claimed", f.stance?.claimed?.name?.lowercase())
                            put("verified", f.verified)
                            f.stance?.unsure?.let { put("unsure", it.name.lowercase()) }
                            put("video", f.hit.videoId)
                            put("title", f.hit.title)
                            put("channel", f.hit.channelName)
                            put("uploaded", f.hit.uploaded)
                            put("ms", f.ms)
                            put("timing", f.timing.name.lowercase())
                            put("url", f.url)
                            if (f.verified) put("quote", f.quote) else put("passage", f.quote)
                            f.stance?.reason?.let { put("reason", evidenceJson(it)) }
                            f.stance?.flags?.takeIf { it.isNotEmpty() }?.let { flags ->
                                putJsonObject("flags") { for ((flag, ev) in flags) put(flag.name.lowercase(), evidenceJson(ev)) }
                            }
                            f.origin?.takeIf { it.size > 1 }?.let { put("origin", it.key) }
                        }
                    }
                }
            }
            putJsonArray("same_origin") {
                for (o in report.sameOrigin) {
                    addJsonObject {
                        putJsonArray("videos") { o.videos.forEach { add(it) } }
                        putJsonArray("reasons") { o.reasons.forEach { add(it.name.lowercase()) } }
                    }
                }
            }
            putJsonObject("uncertainty") {
                put("not_verified", u.notVerified)
                put("ai_unsure", u.aiUnsure)
                put("unchecked", u.unchecked)
                put("ai_missing", u.aiMissing)
                put("approximate_times", u.approximate)
                put("undated", u.undated)
                put("videos", u.videos)
                put("independent_origins", u.origins)
                putJsonArray("position_hints") {
                    for (s in u.shifts) {
                        addJsonObject {
                            put("kind", s.kind.name.lowercase())
                            put("channel", s.earlier.channelName)
                            put("earlier", url(s.earlier.videoId, s.earlierStance.evidence?.ms ?: s.earlier.hitMs))
                            put("earlier_quote", s.earlierStance.evidence?.quote)
                            put("later", url(s.later.videoId, s.laterStance.evidence?.ms ?: s.later.hitMs))
                            put("later_quote", s.laterStance.evidence?.quote)
                        }
                    }
                }
            }
            put("notes", report.notes)
        }
        return pretty.encodeToString(JsonObject.serializer(), obj)
    }
}
