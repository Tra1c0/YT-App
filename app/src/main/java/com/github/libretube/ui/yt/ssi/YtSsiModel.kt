package com.github.libretube.ui.yt.ssi

/*
 * YT: the data of SSI ("Super Search Intelligence"), free of Android so the rules run on the JVM.
 * A document is a video, a segment one passage of what is said in it (1–3 sentences) with its
 * time span. A hit is a passage (or a whole video, for a match in its title) with everything the
 * screen needs to say honestly where it comes from and how sure its time is.
 */

/** How exact a segment's times are. Never more than the source gives. */
enum class YtSsiTiming {
    /** Every word has its own start (YouTube's speech recognition, json3). */
    WORD,

    /** Times per caption line (manual captions, TTML/VTT, the archive's transcript). */
    LINE,

    /** A span, not a spoken moment: a chapter, or a match in the title or description. */
    APPROX,
}

/** What a segment holds. */
enum class YtSsiSegmentKind { SPEECH, CHAPTER }

/** Where a video's text came from. */
enum class YtSsiSource { ARCHIVE, CAPTIONS }

/** Whether the original can still be played. */
enum class YtSsiAvailability {
    PUBLIC,

    /** Gone from YouTube; only the archive's copy (text, maybe sound or 144p) is left. */
    VANISHED_ARCHIVED,
}

/** What part of a video matched. */
enum class YtSsiMatch { TITLE, DESCRIPTION, CHAPTER, TRANSCRIPT_LEXICAL, TRANSCRIPT_SEMANTIC }

/** Which retrieval found it. */
enum class YtSsiMethod { FTS_BM25, EMBEDDING, AI_REWRITE, YOUTUBE_SEARCH }

/** A video as SSI keeps it. [uploaded] epoch ms or null; [language] a base code ("de") or null. */
data class YtSsiDoc(
    val videoId: String,
    val title: String,
    val channelId: String?,
    val channelName: String?,
    val description: String?,
    val uploaded: Long?,
    val durationSec: Long?,
    val language: String?,
    /** The captions were made by speech recognition. */
    val autoCaptions: Boolean?,
    val timing: YtSsiTiming,
    val source: YtSsiSource,
    val vanished: Boolean,
    val chapters: List<YtSsiChapter> = emptyList(),
    val indexedAt: Long = 0,
    val version: Int = YtSsiVersions.ANALYSIS,
) {
    val availability: YtSsiAvailability get() = if (vanished) YtSsiAvailability.VANISHED_ARCHIVED else YtSsiAvailability.PUBLIC
}

data class YtSsiChapter(val startMs: Long, val title: String)

/**
 * One passage. [ord] is its place in the video (the context passages are ord ± 1). [marks] are
 * the times inside the text: for each timed unit (a word for WORD, a caption line for LINE) the
 * character offset in [text] where it starts and its start in ms.
 */
data class YtSsiSegment(
    val videoId: String,
    val ord: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val kind: YtSsiSegmentKind,
    val timing: YtSsiTiming,
    val marks: List<YtSsiMark> = emptyList(),
    val id: Long = 0,
)

/** A timed unit inside a segment's text: it starts at character [char] and at [ms]. */
data class YtSsiMark(val char: Int, val ms: Long)

/** Versions stored with the index and every hit: a change re-indexes or re-embeds. */
object YtSsiVersions {
    /** Segmentation and folding. */
    const val ANALYSIS = 1
}

/**
 * One result. Relevance and timing precision are separate on purpose: there is no single
 * percentage. [relevance] is only the place in the fused list (1 = best) and the fused RRF score,
 * which compares hits of the same search and nothing else.
 */
data class YtSsiHit(
    val videoId: String,
    val title: String,
    val channelName: String?,
    val uploaded: Long?,
    val language: String?,
    /** The passage that matched; null for a match in the title or description only. */
    val segmentId: Long?,
    val startMs: Long,
    val endMs: Long,
    /** The hit inside the passage (a matched word for WORD timing), else [startMs]. */
    val hitMs: Long,
    /** The context around it: the passages before and after. */
    val contextStartMs: Long,
    val contextEndMs: Long,
    val text: String,
    /** Character ranges in [text] that matched the query's words (for the highlight). */
    val highlights: List<IntRange>,
    val matches: Set<YtSsiMatch>,
    val methods: Set<YtSsiMethod>,
    val timing: YtSsiTiming,
    val availability: YtSsiAvailability,
    val rank: Int,
    val fused: Double,
    /** The place in each method's own list (1-based), for "Warum dieser Treffer?". */
    val ranks: Map<YtSsiMethod, Int>,
    /** Soft signals that applied (a channel name named without "von"). */
    val notes: List<YtSsiNote> = emptyList(),
    val analysisVersion: Int = YtSsiVersions.ANALYSIS,
    /** The embedding model of the semantic part, null when it was not used. */
    val modelVersion: String? = null,
)

/** Things to say about a hit beyond its matches. */
enum class YtSsiNote { CHANNEL_GUESSED, LANGUAGE_UNKNOWN, LATER_PHASE_STANCE }
