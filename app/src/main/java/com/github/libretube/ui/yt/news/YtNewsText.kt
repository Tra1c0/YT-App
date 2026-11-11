package com.github.libretube.ui.yt.news

import com.github.libretube.ui.yt.feed.YtFeedTopics

/**
 * YT: the few lines of a video description a News card shows. Pure, so it is tested on the JVM.
 * Descriptions of news channels start with the story and end in links, hashtags, chapter lists
 * and "follow us"; the card keeps the story: no links, no hashtags or handles, no emoji, no
 * chapter or link-only lines, nothing after a separator line, at most [MAX_CHARS] letters cut at
 * a word.
 */
object YtNewsText {
    const val MAX_CHARS = 280

    private val URL = Regex("""(?i)\b(?:https?://|www\.)\S+""")

    /** A bare link such as "tagesschau.de/inland" or "bit.ly/abc". */
    private val DOMAIN = Regex(
        """(?i)(?<![\p{L}\p{N}@.])[\p{L}\p{N}-]+(?:\.[\p{L}\p{N}-]+)*\.(?:com|de|org|net|io|tv|be|ly|gl|me|co|at|ch|info|eu|news|app)(?:/\S*)?(?![\p{L}\p{N}])""",
    )
    private val HASHTAG = Regex("""(?<![\p{L}\p{N}&/])#[\p{L}\p{N}_]+""")
    private val HANDLE = Regex("""(?<![\p{L}\p{N}.])@[\p{L}\p{N}_.]+""")

    /** Emoji and pictographs, their joiners and variation selectors. */
    private val SYMBOLS = Regex("""[\p{So}\p{Cs}‍︎️⃣]""")

    /** "00:00 Intro", "(1:02:03) Teil 2", "1:23 - Wetter". */
    private val CHAPTER = Regex("""^\s*[(\[]?\d{1,2}:\d{2}(?::\d{2})?[)\]]?(?:\s|$|[-–:])""")

    /** "-----", "_____", "=====", "*****", "•••". */
    private val SEPARATOR = Regex("""^\s*[-_=*•~#.·]{3,}\s*$""")
    private val SPACES = Regex("""\s+""")

    /** Punctuation left at the ends of a line once its link is gone ("Mehr: ", "| ", "► "). */
    private val EDGE = Regex("""^[\s\-–—|:•·►▶➤→>»*+]+|[\s\-–—|:•·►▶➤→>»*+(]+$""")
    private val EMPTY_BRACKETS = Regex("""\(\s*\)|\[\s*]""")

    /** Calls to action and social lines: dropped where they are short (folded, see YtFeedTopics.fold). */
    private val CALLS = listOf(
        "abonnier", "abo ", "kanal abonnieren", "folgt uns", "folge uns", "follow us", "subscribe", "social media",
        "instagram", "facebook", "tiktok", "twitter", "threads", "whatsapp", "telegram", "impressum",
        "datenschutz", "newsletter", "mehr videos", "more videos", "unterstutz", "support us", "patreon", "merch",
    )

    /** The words of a line under which a line that lost a link is only a label ("Instagram:"). */
    private const val LINK_LABEL_WORDS = 4

    /** A call to action of up to this many words goes. */
    private const val CALL_WORDS = 12

    /** The card text of [raw], "" when nothing is left. */
    fun clean(raw: String?, maxChars: Int = MAX_CHARS): String {
        if (raw.isNullOrBlank()) return ""
        val kept = ArrayList<String>()
        for (line in raw.replace("\r", "").split('\n')) {
            if (SEPARATOR.matches(line)) {
                // what follows a separator is the channel's footer
                if (kept.isNotEmpty()) break else continue
            }
            if (CHAPTER.containsMatchIn(line)) continue
            val text = cleanLine(line) ?: continue
            kept += text
            if (kept.sumOf { it.length + 1 } > maxChars) break
        }
        return cut(kept.joinToString(" "), maxChars)
    }

    /** One line without links, tags, handles and emoji; null when it should go. */
    private fun cleanLine(line: String): String? {
        var text = line
        val hadLink = URL.containsMatchIn(text) || DOMAIN.containsMatchIn(text)
        text = URL.replace(text, " ")
        text = DOMAIN.replace(text, " ")
        text = HASHTAG.replace(text, " ")
        text = HANDLE.replace(text, " ")
        text = SYMBOLS.replace(text, " ")
        text = EMPTY_BRACKETS.replace(text, " ")
        text = SPACES.replace(text, " ").trim()
        text = EDGE.replace(text, "").trim()
        if (text.none { it.isLetterOrDigit() }) return null
        val words = text.split(' ').count { word -> word.any { it.isLetterOrDigit() } }
        if (hadLink && words < LINK_LABEL_WORDS) return null
        if (words <= CALL_WORDS) {
            val folded = YtFeedTopics.fold(text) + " "
            if (CALLS.any { folded.contains(it) }) return null
        }
        return text
    }

    /** [text] at most [maxChars] long, cut after a whole word with "…". */
    fun cut(text: String, maxChars: Int = MAX_CHARS): String {
        val trimmed = text.trim()
        if (trimmed.length <= maxChars) return trimmed
        val head = trimmed.substring(0, maxChars)
        val space = head.lastIndexOf(' ')
        val word = if (space > maxChars / 2) head.substring(0, space) else head
        return word.trimEnd(' ', ',', ';', ':', '-', '–', '.') + "…"
    }
}
