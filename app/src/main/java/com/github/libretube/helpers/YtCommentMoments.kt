package com.github.libretube.helpers

import java.net.URLDecoder
import java.util.Locale

/** One line of a viewer's chapter list: its HTML and the first time it names (-1 if none). */
data class MomentLine(val html: String, val ms: Long)

/**
 * The times of the video a comment names, in ms, sorted and distinct. [lines] is set only for a
 * timeline (a viewer's chapter list: at least 3 times, most lines start or end with one), whose
 * lines are revealed one by one instead of hiding the whole comment.
 */
data class CommentMoments(val ms: List<Long>, val lines: List<MomentLine> = emptyList()) {
    val isTimeline: Boolean get() = lines.isNotEmpty()

    companion object {
        val NONE = CommentMoments(emptyList())
    }
}

/** What the comments do: show everything, hide what was not seen yet, or follow the scene. */
enum class SceneMode(val key: String) {
    /** Every comment, as always. */
    OFF("off"),

    /** Every comment; those naming a time not seen yet are hidden until it was. */
    SPOILER("spoiler"),

    /** Only the comments about the current scene, with the spoiler protection. */
    SCENE("scene");

    companion object {
        fun of(key: String?): SceneMode = entries.firstOrNull { it.key == key } ?: OFF
    }
}

/**
 * Where the playback is. [unlockedUpToMs] is how far the viewer has seen (YtSceneUnlock, it only
 * grows); [chapterStartsMs] are the chapters' starts in ms, sorted.
 */
data class ScenePlayback(
    val positionMs: Long = 0L,
    val unlockedUpToMs: Long = 0L,
    val durationMs: Long = 0L,
    val chapterStartsMs: List<Long> = emptyList(),
)

enum class SceneVisibility {
    SHOW,

    /** Names a time not seen yet: shown hidden, until it is seen or tapped. */
    SPOILER,

    /** Not about the current scene (only in [SceneMode.SCENE]): left out. */
    OFF_SCENE,
}

data class SceneVerdict(
    val visibility: SceneVisibility,
    /** The first time not seen yet that the comment names (what a hidden row tells), else -1. */
    val spoilerAtMs: Long = -1L,
    /** A timeline's lines that stay readable; the later ones fold away. -1: all of them. */
    val shownLines: Int = -1,
    /** How many times the folded lines of a timeline hold. */
    val foldedTimes: Int = 0,
) {
    val hidden: Boolean get() = visibility == SceneVisibility.SPOILER
    val folded: Boolean get() = shownLines >= 0

    companion object {
        val SHOW = SceneVerdict(SceneVisibility.SHOW)
    }
}

/**
 * YT (comments follow the scene): the times a comment names and whether the viewer may read it
 * yet. Pure Kotlin, tested on the JVM (YtCommentMomentsTest).
 *
 * The extractor turns every time YouTube linked in a comment into an anchor,
 * `<a href="https://www.youtube.com/watch?v=ID&amp;t=SECONDS">m:ss</a>`; those are read first.
 * A comment without such links of this video (replies, other services) falls back to the times
 * written in its text, like 2:16 or 1:02:16. Times past the video's end are dropped, which
 * filters most clock times ("um 23:30").
 */
object YtCommentMoments {
    /** A time this little past what was seen is no spoiler yet. */
    const val GRACE_MS = 5_000L

    /** The scene reaches this far back and ahead of the position, and further within its chapter. */
    const val SCENE_BEFORE_MS = 120_000L
    const val SCENE_AFTER_MS = 10_000L

    /** A chapter widens the scene, but not beyond this on either side (one long chapter is no scene). */
    const val CHAPTER_REACH_MS = 600_000L

    /** The scene list is filtered anew in these steps of the position, not on every tick. */
    const val SCENE_STEP_MS = 5_000L

    private const val MIN_TIMELINE_TIMES = 3

    // a numbering like "1. " or "#12 " may come before the time of a timeline line
    private const val TIME_PREFIX_CHARS = 5

    private val ANCHOR = Regex(
        """<a\b[^>]*?\bhref\s*=\s*(["'])(.*?)\1[^>]*>(.*?)</a\s*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /** A video time like 2:16 or 1:02:16 in plain text, not part of a longer number or time. */
    val TIMESTAMP = Regex("""(?<![\d:])(\d{1,2}:)?\d{1,2}:\d{2}(?![\d:])""")

    private val LINE_BREAK = Regex("""<br\s*/?>|\n""", RegexOption.IGNORE_CASE)
    private val TAG = Regex("""<[^>]+>""")
    private val ENTITY = Regex("""&(#[xX][0-9a-fA-F]+|#\d+|[a-zA-Z]+);""")
    private val T_UNITS = Regex("""(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?""")

    private val YOUTUBE_HOSTS = setOf("youtube.com", "youtube-nocookie.com", "piped.video")

    /**
     * The times [html] names for the video [videoId] that is [durationMs] long (0: unknown, no
     * filter): its links to this video with a time, else the times in its text.
     */
    fun parse(html: String?, videoId: String?, durationMs: Long): CommentMoments {
        if (html.isNullOrBlank()) return CommentMoments.NONE
        val linked = videoId != null && ANCHOR.findAll(html).any { anchorMs(it, videoId) != null }
        val lines = html.split(LINE_BREAK)
        val lineTimes = lines.map { timesOf(it, videoId, linked, durationMs) }
        val all = lineTimes.flatten().distinct().sorted()
        if (all.isEmpty()) return CommentMoments.NONE

        val texts = lines.map { plainText(it).trim() }
        val written = texts.count { it.isNotEmpty() }
        val timeLines = texts.indices.count { lineTimes[it].isNotEmpty() && startsOrEndsWithTime(texts[it]) }
        val timeline = all.size >= MIN_TIMELINE_TIMES && timeLines >= MIN_TIMELINE_TIMES && timeLines * 2 >= written
        if (!timeline) return CommentMoments(all)
        return CommentMoments(all, lines.mapIndexed { i, line -> MomentLine(line, lineTimes[i].firstOrNull() ?: -1L) })
    }

    // the times of one line, in the order written
    private fun timesOf(html: String, videoId: String?, linked: Boolean, durationMs: Long): List<Long> {
        val found = if (linked && videoId != null) {
            ANCHOR.findAll(html).mapNotNull { anchorMs(it, videoId) }.toList()
        } else {
            // links elsewhere are no times of this video, even when their text looks like one
            TIMESTAMP.findAll(plainText(html.replace(ANCHOR, " "))).mapNotNull { parseClock(it.value) }.toList()
        }
        return found.filter { it >= 0 && (durationMs <= 0 || it <= durationMs) }
    }

    private fun anchorMs(match: MatchResult, videoId: String): Long? {
        // the attribute is escaped (&amp;), sometimes twice
        var href = match.groupValues[2]
        repeat(2) { if (href.contains('&')) href = decodeEntities(href) }
        val (id, ms) = linkTarget(href.trim()) ?: return null
        return ms.takeIf { id == videoId }
    }

    /**
     * The video and the time in ms a link points at, if it is a link to a YouTube video with a
     * time (`watch?v=ID&t=90`, `youtu.be/ID?t=1m30s`, also `#t=` and `start=`); else null.
     */
    fun linkTarget(href: String): Pair<String, Long>? {
        val fragment = href.substringAfter('#', "")
        val beforeFragment = href.substringBefore('#')
        val hasScheme = beforeFragment.contains("://")
        val rest = if (hasScheme) beforeFragment.substringAfter("://") else beforeFragment
        val authority = if (hasScheme) rest.substringBefore('/').substringBefore('?') else ""
        val pathAndQuery = rest.substring(authority.length)
        val path = pathAndQuery.substringBefore('?')
        val params = queryParams(pathAndQuery.substringAfter('?', "")) + queryParams(fragment)
        val host = authority.substringAfterLast('@').substringBefore(':').lowercase(Locale.ROOT)
            .removePrefix("www.").removePrefix("m.").removePrefix("music.")

        val id = when {
            host == "youtu.be" -> path.trim('/').substringBefore('/')
            host in YOUTUBE_HOSTS -> params["v"]
            // a link without a host only counts when it is a watch link of the same site
            host.isEmpty() && !hasScheme && path.endsWith("/watch") -> params["v"]
            else -> null
        }?.takeIf { it.isNotBlank() } ?: return null
        val time = params["t"] ?: params["start"] ?: return null
        val seconds = parseLinkTime(time) ?: return null
        return id to seconds * 1000
    }

    private fun queryParams(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        return query.split('&').mapNotNull { pair ->
            val key = pair.substringBefore('=')
            if (key.isEmpty() || !pair.contains('=')) return@mapNotNull null
            val value = runCatching { URLDecoder.decode(pair.substringAfter('='), "UTF-8") }.getOrNull()
                ?: return@mapNotNull null
            key to value
        }.toMap()
    }

    /** Seconds from a link's time: 90, 90s, 1m30s, 1h2m3s or 1:30. */
    fun parseLinkTime(value: String): Long? {
        val text = value.trim().lowercase(Locale.ROOT)
        if (text.isEmpty()) return null
        if (text.all { it.isDigit() }) return text.toLongOrNull()
        text.toDoubleOrNull()?.let { return it.toLong().takeIf { s -> s >= 0 } }
        if (text.contains(':')) return parseClock(text)?.div(1000)
        val units = T_UNITS.matchEntire(text) ?: return null
        val (h, m, s) = units.destructured
        if (h.isEmpty() && m.isEmpty() && s.isEmpty()) return null
        return (h.toLongOrNull() ?: 0) * 3600 + (m.toLongOrNull() ?: 0) * 60 + (s.toLongOrNull() ?: 0)
    }

    /** A written time (2:16, 1:02:16) in ms, or null if it is none (2:75, 1:75:00). */
    fun parseClock(text: String): Long? {
        val parts = text.trim().split(':')
        if (parts.size !in 2..3 || parts.any { it.isEmpty() || !it.all(Char::isDigit) }) return null
        val numbers = parts.map { it.toLongOrNull() ?: return null }
        val seconds = numbers.last()
        if (seconds >= 60) return null
        if (numbers.size == 3 && numbers[1] >= 60) return null
        return numbers.fold(0L) { acc, n -> acc * 60 + n } * 1000
    }

    /** [ms] as the player shows it: 2:16 or 1:02:16. */
    fun formatTime(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    /** The text of [html]: line breaks kept, tags dropped, entities decoded. */
    fun plainText(html: String): String = decodeEntities(html.replace(LINE_BREAK, "\n").replace(TAG, ""))

    private fun decodeEntities(text: String): String = text.replace(ENTITY) { match ->
        val name = match.groupValues[1]
        val code = when {
            name.startsWith("#x") || name.startsWith("#X") -> name.substring(2).toIntOrNull(16)
            name.startsWith("#") -> name.substring(1).toIntOrNull()
            else -> null
        }
        when {
            code != null && Character.isValidCodePoint(code) -> String(Character.toChars(code))
            name == "amp" -> "&"
            name == "lt" -> "<"
            name == "gt" -> ">"
            name == "quot" -> "\""
            name == "apos" -> "'"
            name == "nbsp" -> " "
            else -> match.value
        }
    }

    // a line of a timeline: "0:00 Intro", "1. 2:15 – Teil 2", "Teil 2 (2:15)"
    private fun startsOrEndsWithTime(text: String): Boolean {
        val core = text.trim { !it.isLetterOrDigit() }
        val times = TIMESTAMP.findAll(core).toList()
        if (times.isEmpty()) return false
        return times.first().range.first <= TIME_PREFIX_CHARS || times.last().range.last == core.lastIndex
    }

    /**
     * What [moments] may show at [playback] in [mode]. [revealed]: the viewer tapped the comment
     * open. A comment without times is never a spoiler, but in [SceneMode.SCENE] it is off the
     * scene. A timeline is never hidden whole: its lines stay readable up to what was seen.
     */
    fun classify(
        moments: CommentMoments,
        playback: ScenePlayback,
        mode: SceneMode,
        revealed: Boolean = false,
    ): SceneVerdict {
        if (mode == SceneMode.OFF) return SceneVerdict.SHOW
        if (mode == SceneMode.SCENE && !inScene(moments, playback)) return SceneVerdict(SceneVisibility.OFF_SCENE)
        if (revealed) return SceneVerdict.SHOW
        val seen = playback.unlockedUpToMs + GRACE_MS
        if (moments.isTimeline) {
            val cut = moments.lines.indexOfFirst { it.ms > seen }
            if (cut < 0) return SceneVerdict.SHOW
            val folded = moments.lines.subList(cut, moments.lines.size).count { it.ms >= 0 }
            return SceneVerdict(SceneVisibility.SHOW, moments.lines[cut].ms, cut, folded)
        }
        val first = moments.ms.firstOrNull { it > seen } ?: return SceneVerdict.SHOW
        return SceneVerdict(SceneVisibility.SPOILER, first)
    }

    /** The lines of a timeline that stay readable, as HTML. */
    fun shownHtml(moments: CommentMoments, verdict: SceneVerdict): String? {
        if (!moments.isTimeline || !verdict.folded) return null
        return moments.lines.take(verdict.shownLines).joinToString("<br>") { it.html }
    }

    /**
     * The current scene: [SCENE_BEFORE_MS] before the position to [SCENE_AFTER_MS] after it,
     * widened to the current chapter (within [CHAPTER_REACH_MS]).
     */
    fun sceneWindow(playback: ScenePlayback): LongRange {
        val position = playback.positionMs
        var from = position - SCENE_BEFORE_MS
        var to = position + SCENE_AFTER_MS
        val chapter = chapterIndex(playback)
        if (chapter >= 0) {
            val starts = playback.chapterStartsMs
            val end = starts.getOrNull(chapter + 1)
                ?: playback.durationMs.takeIf { it > 0 }
                ?: Long.MAX_VALUE
            from = minOf(from, maxOf(starts[chapter], position - CHAPTER_REACH_MS))
            to = maxOf(to, minOf(end - 1, position + CHAPTER_REACH_MS))
        }
        return from.coerceAtLeast(0L)..to
    }

    /** The chapter the position is in, or -1. */
    fun chapterIndex(playback: ScenePlayback): Int =
        playback.chapterStartsMs.indexOfLast { it <= playback.positionMs }

    /** Whether [moments] names a time of the current scene. */
    fun inScene(moments: CommentMoments, playback: ScenePlayback): Boolean {
        if (moments.ms.isEmpty()) return false
        val window = sceneWindow(playback)
        return moments.ms.any { it in window }
    }

    /** The step of the position the scene list was filtered for. */
    fun sceneStep(positionMs: Long): Long = positionMs.coerceAtLeast(0L) / SCENE_STEP_MS

    /**
     * The marks of "seen up to" at which a verdict changes: each time minus [GRACE_MS], sorted
     * and distinct. A list rebinds only when the mark passes the next of them.
     */
    fun boundaries(times: Iterable<Long>): LongArray =
        times.map { it - GRACE_MS }.distinct().sorted().toLongArray()

    /** The first of the sorted [boundaries] past [unlockedMs], or Long.MAX_VALUE. */
    fun nextBoundary(boundaries: LongArray, unlockedMs: Long): Long {
        var low = 0
        var high = boundaries.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (boundaries[mid] <= unlockedMs) low = mid + 1 else high = mid
        }
        return if (low < boundaries.size) boundaries[low] else Long.MAX_VALUE
    }
}
