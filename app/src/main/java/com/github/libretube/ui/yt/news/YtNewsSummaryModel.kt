package com.github.libretube.ui.yt.news

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * YT: the data and pure rules of the opt-in news summaries ("Kurzfassung"): the statements with
 * the second they come from, how an answer's text and citations become statements, and which
 * cached summaries are old enough to go. Free of Android and of the API SDKs, so it runs on the JVM.
 */

/** One key statement: what was said, in which video and at which second. */
@Serializable
data class YtNewsStatement(val text: String, val videoId: String, val seconds: Long)

/**
 * A finished summary of one video, or of a story told by several ([key] is the cache key).
 * [cut] is true when a transcript was too long and only its start was read.
 */
@Serializable
data class YtNewsDigest(
    val key: String,
    val statements: List<YtNewsStatement>,
    val createdMs: Long,
    val cut: Boolean = false,
)

/** Why there is no summary. */
enum class YtNewsSummaryFailure {
    /** No key entered: the feature is off. */
    NO_KEY,

    /** The video has no captions (or none could be read). */
    NO_TRANSCRIPT,

    /** The key was rejected (401). */
    INVALID_KEY,

    /** Too many requests for now (429). */
    RATE_LIMITED,

    /** The account has no credit left (402, or 429 with a quota or billing code). */
    NO_CREDIT,

    /** The model named in the settings is not known to the service. */
    MODEL_UNKNOWN,

    /** No connection. */
    OFFLINE,

    /** The request was declined, or the answer held no statement with a source. */
    REFUSED,

    /** Too few statements of the answer were found in the transcript to show a summary. */
    UNVERIFIED,

    /** Any other error of the service. */
    SERVICE,
}

/** What a card shows for its video. */
sealed interface YtNewsSummaryState {
    data object Idle : YtNewsSummaryState
    data object Loading : YtNewsSummaryState
    data class Ready(val digest: YtNewsDigest) : YtNewsSummaryState
    data class Failed(val reason: YtNewsSummaryFailure) : YtNewsSummaryState
}

/** One video as the summary request sends it: its id, a title for the document and the transcript. */
class YtNewsSource(val videoId: String, val title: String, val doc: YtNewsTranscriptDoc)

/** A citation of an answer block, as far as the mapping needs it. */
data class YtNewsCite(val documentIndex: Int, val startCharIndex: Int, val citedText: String?)

/** A text block of an answer with its citations (none for the parts between cited passages). */
data class YtNewsAnswerBlock(val text: String, val cites: List<YtNewsCite> = emptyList())

object YtNewsCitations {
    /** At most this many statements for one video, as agreed (3 to 5). */
    const val MAX_SINGLE = 5

    /** At most this many for a story of several videos. */
    const val MAX_STORY = 8

    private val BULLET = Regex("""^\s*(?:[-–•*]|\d{1,2}[.)])\s+""")
    private val BOLD = Regex("""\*\*|__""")
    private val SPACES = Regex("""\s+""")

    /**
     * The statements of an answer. The answer is split into lines (one statement per line, as the
     * prompt asks); a line counts only when at least one of its blocks cites the transcript, and
     * its second is that of the first citation. Lines without a source are left out: every
     * statement shown can be checked at its second.
     */
    fun statements(
        blocks: List<YtNewsAnswerBlock>,
        sources: List<YtNewsSource>,
        max: Int = MAX_SINGLE,
    ): List<YtNewsStatement> {
        // where each block sits in the joined text
        val text = StringBuilder()
        val ranges = ArrayList<IntRange>(blocks.size)
        for (block in blocks) {
            val start = text.length
            text.append(block.text)
            ranges += start until text.length
        }
        val all = text.toString()
        val out = ArrayList<YtNewsStatement>()
        var lineStart = 0
        while (lineStart <= all.length && out.size < max) {
            val end = all.indexOf('\n', lineStart).let { if (it < 0) all.length else it }
            val words = tidy(all.substring(lineStart, end))
            if (words.isNotEmpty()) {
                val cite = blocks.indices
                    .filter { i -> blocks[i].cites.isNotEmpty() && overlaps(ranges[i], lineStart, end) }
                    .firstNotNullOfOrNull { i -> blocks[i].cites.firstNotNullOfOrNull { place(it, sources) } }
                if (cite != null) out += YtNewsStatement(words, cite.first, cite.second)
            }
            lineStart = end + 1
        }
        return out
    }

    /** The video id and second of a citation, null when it points nowhere. */
    private fun place(cite: YtNewsCite, sources: List<YtNewsSource>): Pair<String, Long>? {
        val source = sources.getOrNull(cite.documentIndex) ?: return null
        val second = source.doc.resolve(cite.startCharIndex, cite.citedText) ?: return null
        return source.videoId to second
    }

    private fun overlaps(range: IntRange, start: Int, end: Int): Boolean =
        !range.isEmpty() && range.first < end && range.last >= start

    /** A statement line without its bullet, bold marks and extra spaces. */
    private fun tidy(line: String): String {
        val words = SPACES.replace(BOLD.replace(BULLET.replace(line, ""), ""), " ").trim()
        return if (words.any { it.isLetterOrDigit() }) words else ""
    }
}

/** The pure rules of the summary cache on disk (one JSON file per key). */
object YtNewsSummaryCacheRules {
    /** Summaries older than a week go. */
    const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true }

    /** The file name of a cache key: video ids are safe, a story key is hashed. */
    fun fileName(key: String): String {
        val safe = key.all { it.isLetterOrDigit() || it == '-' || it == '_' } && key.length <= 64
        return (if (safe) key else "s" + Integer.toHexString(key.hashCode()) + key.length) + ".json"
    }

    /** [key] within a provider's [scope] ([YtNewsProviders.cacheScope]); null keeps it as it is. */
    fun scoped(scope: String?, key: String): String = if (scope == null) key else "$scope|$key"

    /** The key of a story of several videos: its ids, sorted, so the order of the cards does not matter. */
    fun storyKey(videoIds: List<String>): String = "story:" + videoIds.distinct().sorted().joinToString(",")

    fun expired(createdMs: Long, nowMs: Long, maxAgeMs: Long = MAX_AGE_MS): Boolean =
        createdMs > nowMs + maxAgeMs || nowMs - createdMs > maxAgeMs

    /** The names of the files (name to last-modified time) that are old enough to delete. */
    fun toDelete(files: List<Pair<String, Long>>, nowMs: Long, maxAgeMs: Long = MAX_AGE_MS): List<String> =
        files.filter { (_, modified) -> expired(modified, nowMs, maxAgeMs) }.map { it.first }

    fun encode(digest: YtNewsDigest): String = json.encodeToString(YtNewsDigest.serializer(), digest)

    /** The digest of a cache file, null when it is unreadable, of another key or too old. */
    fun decode(text: String, key: String, nowMs: Long): YtNewsDigest? {
        val digest = runCatching { json.decodeFromString(YtNewsDigest.serializer(), text) }.getOrNull() ?: return null
        if (digest.key != key || digest.statements.isEmpty() || expired(digest.createdMs, nowMs)) return null
        return digest
    }
}
