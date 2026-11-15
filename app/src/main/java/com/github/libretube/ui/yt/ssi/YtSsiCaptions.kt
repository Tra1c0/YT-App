package com.github.libretube.ui.yt.ssi

import com.github.libretube.ui.yt.news.YtNewsCue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * A timed piece of what is said: a word (when [word] is true, its own start from the source) or
 * a caption line. [endMs] is where the next unit starts or the line ends.
 */
data class YtSsiUnit(val startMs: Long, val endMs: Long, val text: String, val word: Boolean)

/** A caption track read for SSI: its units and how exact their times are. */
data class YtSsiTrack(val units: List<YtSsiUnit>, val timing: YtSsiTiming)

/**
 * YT: SSI's caption reading. NewPipe lists YouTube's caption tracks as timedtext URLs with
 * "&fmt=ttml" (YoutubeStreamExtractor.getSubtitles drops the fmt parameter and appends the
 * format's suffix). The same URL with "fmt=json3" gives YouTube's JSON format, in which the
 * speech-recognition track carries a start offset per word ("segs" with "tOffsetMs"): those become
 * WORD timings. Manual tracks have whole lines only: LINE. Pure.
 */
object YtSsiCaptions {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val FMT = Regex("""([?&])fmt=[^&]*""")

    /** Sound and speaker marks that are not speech: "[Musik]", "(Applaus)", "♪". */
    private val NOISE = Regex("""\[[^\]]*]|\([^)]*\)|[♪♫]""")
    private val SPACES = Regex("""\s+""")

    /** The longest a caption line is held when the next one is far away. */
    private const val MAX_LINE_MS = 8_000L

    /** [url] asking for the json3 format instead of whatever it named. */
    fun json3Url(url: String): String =
        if (FMT.containsMatchIn(url)) FMT.replace(url) { "${it.groupValues[1]}fmt=json3" }
        else url + (if (url.contains('?')) "&" else "?") + "fmt=json3"

    /**
     * The units of a json3 body, or null when it is not json3. Words keep their own starts only
     * when the source gives them (tOffsetMs); a track without any is read line by line.
     */
    fun parseJson3(body: String): YtSsiTrack? {
        val root = runCatching { json.parseToJsonElement(body.trim()) as? JsonObject }.getOrNull() ?: return null
        val events = root["events"] as? JsonArray ?: return null
        data class Piece(val start: Long, val text: String, val own: Boolean)
        data class Event(val start: Long, val end: Long, val pieces: List<Piece>)
        val read = ArrayList<Event>()
        var offsets = 0
        for (element in events) {
            val event = element as? JsonObject ?: continue
            val segs = event["segs"] as? JsonArray ?: continue
            val start = event.long("tStartMs") ?: continue
            val duration = event.long("dDurationMs") ?: 0L
            val pieces = ArrayList<Piece>()
            for (seg in segs) {
                val obj = seg as? JsonObject ?: continue
                val text = obj["utf8"]?.jsonPrimitive?.contentOrNull ?: continue
                val offset = obj.long("tOffsetMs")
                if (offset != null) offsets++
                // the first word of an event carries no offset: it starts with the event
                pieces += Piece(start + (offset ?: 0L), text, offset != null)
            }
            if (pieces.all { it.text.isBlank() }) continue
            read += Event(start, start + duration, pieces)
        }
        if (read.isEmpty()) return YtSsiTrack(emptyList(), YtSsiTiming.LINE)
        if (offsets == 0) {
            // whole lines (a manual track): one unit per event
            val lines = read.mapNotNull { event ->
                val text = clean(event.pieces.joinToString("") { it.text })
                if (text.isEmpty()) null else YtNewsCue(event.start / 1000.0, text) to event
            }
            val units = lines.mapIndexed { i, (cue, event) ->
                val next = lines.getOrNull(i + 1)?.second?.start
                val end = when {
                    event.end > event.start -> event.end
                    next != null -> next
                    else -> event.start + MAX_LINE_MS
                }.coerceIn(event.start, event.start + MAX_LINE_MS)
                YtSsiUnit(event.start, end, cue.text, word = false)
            }
            return YtSsiTrack(units, YtSsiTiming.LINE)
        }
        // word by word; a piece may hold more than one word ("  of the"): they share its start
        val words = ArrayList<Pair<Long, String>>()
        val ends = ArrayList<Long>()
        for (event in read) {
            for (piece in event.pieces) {
                val text = clean(piece.text)
                if (text.isEmpty()) continue
                for (word in text.split(' ')) {
                    words += piece.start to word
                    ends += event.end
                }
            }
        }
        val order = words.indices.sortedBy { words[it].first }
        val units = order.mapIndexed { i, index ->
            val (start, text) = words[index]
            val next = order.getOrNull(i + 1)?.let { words[it].first }
            val eventEnd = ends[index]
            val end = when {
                next != null && next >= start -> minOf(next, maxOf(eventEnd, start))
                eventEnd > start -> eventEnd
                else -> start + 500
            }
            YtSsiUnit(start, maxOf(end, start), text, word = true)
        }
        return YtSsiTrack(units, YtSsiTiming.WORD)
    }

    /** Caption cues (start only, from TTML/VTT/SRT) as line units. */
    fun lines(cues: List<YtNewsCue>): YtSsiTrack {
        val sorted = cues.filter { it.text.isNotBlank() }.sortedBy { it.startSec }
        val units = sorted.mapIndexed { i, cue ->
            val start = (cue.startSec * 1000).toLong()
            val next = sorted.getOrNull(i + 1)?.let { (it.startSec * 1000).toLong() }
            val end = (next ?: (start + MAX_LINE_MS)).coerceIn(start, start + MAX_LINE_MS)
            YtSsiUnit(start, end, clean(cue.text), word = false)
        }.filter { it.text.isNotEmpty() }
        return YtSsiTrack(units, YtSsiTiming.LINE)
    }

    /** The archive's transcript lines (ms start/end) as line units. */
    fun archiveLines(lines: List<Triple<Long, Long, String>>): YtSsiTrack = YtSsiTrack(
        lines.sortedBy { it.first }
            .map { (start, end, text) -> YtSsiUnit(start, maxOf(start, end), clean(text), word = false) }
            .filter { it.text.isNotEmpty() },
        YtSsiTiming.LINE,
    )

    /** One caption piece without sound marks, line breaks and extra spaces. */
    fun clean(raw: String): String = SPACES.replace(NOISE.replace(raw, " ").replace(' ', ' '), " ").trim()

    private fun JsonObject.long(name: String): Long? = this[name]?.jsonPrimitive?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() }
}
