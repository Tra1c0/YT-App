package com.github.libretube.ui.yt.ssi

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** What the query is after; it sets the weights of the retrieval methods. */
enum class YtSsiIntentKind { EXACT_QUOTE, FIND_VIDEO, EXPLANATION, QUESTION, STATEMENT }

/** Asks the later stages of SSI will answer (stance, comparison); now only flagged and said. */
enum class YtSsiLater { REFUTE, COMPARE, CONTRADICTION, CHRONOLOGY }

/** An upload-date range (epoch ms, both ends inclusive; null = open). */
data class YtSsiTimeRange(val fromMs: Long?, val toMs: Long?) {
    fun contains(ms: Long): Boolean = (fromMs == null || ms >= fromMs) && (toMs == null || ms <= toMs)
}

/** How much each method counts in the fusion (weights of the RRF sum). */
data class YtSsiWeights(val lexical: Double, val semantic: Double, val title: Double)

/**
 * A query as SSI understood it. Hard constraints ([time], [channels], [excludeTerms],
 * [excludeChannels], [phrases] when [kind] is EXACT_QUOTE, [language]) are kept by every hit;
 * [channelGuesses] (a known channel's name written without "von") only lift hits.
 */
data class YtSsiIntent(
    val raw: String,
    val kind: YtSsiIntentKind,
    /** Quoted phrases as written; required for EXACT_QUOTE. */
    val phrases: List<String>,
    /** The words for the embedding (the query without filters and command words). */
    val semanticText: String,
    /** Folded keywords for the full-text search. */
    val terms: List<String>,
    val time: YtSsiTimeRange?,
    /** Channel names or @handles that must match (as written). */
    val channels: List<String>,
    val channelGuesses: List<String>,
    /** Folded words or phrases a hit must not contain. */
    val excludeTerms: List<String>,
    val excludeChannels: List<String>,
    /** Base language code ("de", "en") the video must be in. */
    val language: String?,
    val later: Set<YtSsiLater>,
) {
    val exact: Boolean get() = kind == YtSsiIntentKind.EXACT_QUOTE
    val empty: Boolean get() = terms.isEmpty() && phrases.isEmpty() && semanticText.isBlank()

    val weights: YtSsiWeights
        get() = when (kind) {
            // the wording counts: semantic hits may only fill in below, never soften the quote
            YtSsiIntentKind.EXACT_QUOTE -> YtSsiWeights(lexical = 1.0, semantic = 0.2, title = 0.5)
            YtSsiIntentKind.FIND_VIDEO -> YtSsiWeights(lexical = 0.8, semantic = 0.8, title = 1.0)
            YtSsiIntentKind.EXPLANATION, YtSsiIntentKind.QUESTION -> YtSsiWeights(lexical = 0.6, semantic = 1.0, title = 0.3)
            YtSsiIntentKind.STATEMENT -> YtSsiWeights(lexical = 0.9, semantic = 0.9, title = 0.4)
        }

    /** A key for caches: the same query in another spelling of spaces and case is the same. */
    val cacheKey: String get() = YtSsiText.fold(raw)
}

/**
 * YT: the rule-based query reader of SSI. It finds quotes (exact), time ranges ("vor 3 Jahren",
 * "2023", "letzte Woche", "seit 2020"), channels ("@handle", "von <known channel>"), exclusions
 * ("ohne X", "-X", "aber nicht X", "nicht von <channel>"), language hints ("auf Englisch") and
 * asks for later stages ("widerlegt", "Gegenargument", "vergleiche"). A bare "nicht" stays part
 * of the meaning ("warum das nicht stimmt"). German and English. Pure: [now] and [zone] are passed in.
 */
object YtSsiIntentParser {
    private val QUOTES = Regex("""["„“”»«‚‘](.+?)["“”«»‘’]""")
    private val MINUS = Regex("""(?:^|\s)-(\p{L}[\p{L}\p{N}]+)""")
    private val HANDLE = Regex("""(?:^|\s)@([\p{L}\p{N}_.\-]{2,})""")
    private val EXCLUDE_WORD = Regex("""\b(?:ohne|au(?:ß|ss)er|without|except|excluding)\s+(\p{L}[\p{L}\p{N}\-]*)""", RegexOption.IGNORE_CASE)
    private val BUT_NOT = Regex("""\b(?:aber|jedoch|but)\s+(?:nicht|not)\s+(\p{L}[\p{L}\p{N}\-]*)""", RegexOption.IGNORE_CASE)
    private val NOT_FROM = Regex("""\b(?:nicht|not)\s+(?:von|vom|bei|auf|from|by|on)\s+(@?[\p{L}\p{N}_.\-]+(?:\s+[\p{L}\p{N}_.\-]+){0,3})""", RegexOption.IGNORE_CASE)
    private val FROM = Regex("""\b(?:von|vom|bei|auf|kanal|channel|from|by|on)\s+""", RegexOption.IGNORE_CASE)

    private const val N = """(\d{1,3}|einem|einer|einen|eins|ein|eine|zwei|drei|vier|fünf|funf|sechs|sieben|acht|neun|zehn|elf|zwölf|zwolf|a|an|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)"""
    private val AGO_DE = Regex("""\bvor\s+$N\s+(jahren|jahr|monaten|monat|wochen|woche|tagen|tag)\b""", RegexOption.IGNORE_CASE)
    private val AGO_EN = Regex("""\b$N\s+(years?|months?|weeks?|days?)\s+ago\b""", RegexOption.IGNORE_CASE)
    private val LAST = Regex("""\b(?:(?:in\s+der\s+|im\s+|in\s+den\s+)?(?:letzten|letzte|letzter|letztes|vergangenen|vergangene|vergangenes)|last|past)\s+(woche|monat|jahr|week|month|year)\b""", RegexOption.IGNORE_CASE)
    private val THIS = Regex("""\b(?:diese|dieser|diesen|dieses|this)\s+(woche|monat|jahr|week|month|year)\b""", RegexOption.IGNORE_CASE)
    private val TODAY = Regex("""\b(heute|today)\b""", RegexOption.IGNORE_CASE)
    private val YESTERDAY = Regex("""\b(gestern|yesterday)\b""", RegexOption.IGNORE_CASE)
    private val BETWEEN = Regex("""\b(?:zwischen|between)\s+(19\d\d|20\d\d)\s+(?:und|and)\s+(19\d\d|20\d\d)\b""", RegexOption.IGNORE_CASE)
    private val SINCE = Regex("""\b(?:seit|since|ab|after|nach)\s+(19\d\d|20\d\d)\b""", RegexOption.IGNORE_CASE)
    private val BEFORE = Regex("""\b(?:vor|before)\s+(19\d\d|20\d\d)\b""", RegexOption.IGNORE_CASE)
    private val UNTIL = Regex("""\b(?:bis|until)\s+(19\d\d|20\d\d)\b""", RegexOption.IGNORE_CASE)
    private val MONTH_YEAR = Regex("""\b(?:im\s+|in\s+)?(januar|jänner|februar|märz|maerz|april|mai|juni|juli|august|september|oktober|november|dezember|january|february|march|may|june|july|october|december)\s+(19\d\d|20\d\d)\b""", RegexOption.IGNORE_CASE)
    private val YEAR = Regex("""\b(?:im\s+jahr(?:e)?\s+|in\s+)?(19\d\d|20\d\d)\b""", RegexOption.IGNORE_CASE)

    private val LANGUAGE = Regex(
        """\b(?:auf|in)\s+(deutsch|englisch|spanisch|französisch|franzosisch|italienisch|english|german|spanish|french|italian)\b|\b(deutsche|deutschen|englische|englischen|spanische|spanischen|französische|französischen|german|english|spanish|french)\s+(?:videos?|clips?)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val LATER = listOf(
        YtSsiLater.REFUTE to Regex("""\b(widerleg\w*|gegenargument\w*|entkr(ä|a)ft\w*|debunk\w*|refut\w*|counter\s*argument\w*|rebut\w*)""", RegexOption.IGNORE_CASE),
        YtSsiLater.COMPARE to Regex("""\b(vergleich\w*|compar\w*|versus|vs\.?)\b""", RegexOption.IGNORE_CASE),
        YtSsiLater.CONTRADICTION to Regex("""\b(widersp(r|ru)ch\w*|widerspricht|widersprochen|etwas anderes behauptet|contradict\w*)""", RegexOption.IGNORE_CASE),
        YtSsiLater.CHRONOLOGY to Regex("""\b(im laufe der (zeit|jahre)|entwicklung|früher|frueher|over time|timeline)\b""", RegexOption.IGNORE_CASE),
    )

    private val QUESTION_START = Regex("""^\s*(warum|wieso|weshalb|wie|was|wer|wann|wo|welche\w*|woher|wozu|why|how|what|who|when|where|which)\b""", RegexOption.IGNORE_CASE)
    private val EXPLAIN = Regex("""\b(erkl(ä|a)r\w*|warum|wieso|weshalb|explain\w*|why|how does|how do)\b""", RegexOption.IGNORE_CASE)
    private val FIND_VIDEO = Regex("""\b(das video|den clip|ein video|the video|a video)\s*,?\s*(in dem|wo|in which|where|that)\b""", RegexOption.IGNORE_CASE)

    /** Command words that are not what is searched. */
    private val FILLER = listOf(
        Regex("""^\s*(bitte\s+)?(finde|such(e)?|zeig(e)?(\s+mir)?|gib\s+mir|find|search(\s+for)?|show(\s+me)?|look\s+for)\b\s*""", RegexOption.IGNORE_CASE),
        Regex("""\b(alle|sämtliche|samtliche|all)\s+(relevanten\s+)?""", RegexOption.IGNORE_CASE),
        Regex("""\b(die|eine|the|a)\s+(stelle|stellen|part|moment|scene|szene)\s*,?\s*(an der|in der|wo|where|in which)?\s*""", RegexOption.IGNORE_CASE),
        Regex("""\b(das|ein|the|a)\s+(video|clip)\s*,?\s*(in dem|wo|in which|where)?\s*""", RegexOption.IGNORE_CASE),
        Regex("""\b(videos?|clips?)\s*,?\s*(die|in denen|that|which)\s+""", RegexOption.IGNORE_CASE),
        Regex("""\b(und ordne die dazugehörigen videostellen)\b""", RegexOption.IGNORE_CASE),
    )

    private val LANGUAGE_CODES = mapOf(
        "deutsch" to "de", "deutsche" to "de", "deutschen" to "de", "german" to "de",
        "englisch" to "en", "englische" to "en", "englischen" to "en", "english" to "en",
        "spanisch" to "es", "spanische" to "es", "spanischen" to "es", "spanish" to "es",
        "französisch" to "fr", "franzosisch" to "fr", "französische" to "fr", "französischen" to "fr", "french" to "fr",
        "italienisch" to "it", "italian" to "it",
    )

    fun parse(raw: String, now: Long, zone: ZoneId, known: List<String> = emptyList()): YtSsiIntent {
        var text = " " + raw.trim() + " "
        fun cut(range: IntRange) {
            text = text.substring(0, range.first) + " " + text.substring(range.last + 1)
        }

        // quotes first: nothing inside a quote is a filter
        val phrases = ArrayList<String>()
        QUOTES.findAll(text).toList().asReversed().forEach { match ->
            match.groupValues[1].trim().takeIf { YtSsiText.words(it).isNotEmpty() }?.let { phrases.add(0, it) }
            cut(match.range)
        }

        val later = LATER.filter { (_, regex) -> regex.containsMatchIn(raw) }.map { it.first }.toSet()

        val excludeChannels = ArrayList<String>()
        NOT_FROM.findAll(text).toList().asReversed().forEach { match ->
            val written = match.groupValues[1].trim()
            // only a known channel or a handle: "nicht von Menschen gemacht" is meaning, not a filter
            val name = channelAt(written, known)
                ?: written.split(Regex("\\s+")).first().takeIf { it.startsWith("@") }
                ?: return@forEach
            excludeChannels.add(0, name.removePrefix("@"))
            val end = if (name.startsWith("@")) match.groups[1]!!.range.first + name.length else match.groups[1]!!.range.first + lengthOf(written, name)
            cut(match.range.first until end)
        }
        val excludeTerms = ArrayList<String>()
        for (regex in listOf(BUT_NOT, EXCLUDE_WORD)) {
            regex.findAll(text).toList().asReversed().forEach { match ->
                YtSsiText.fold(match.groupValues[1]).takeIf { it.isNotEmpty() }?.let { excludeTerms.add(0, it) }
                cut(match.range)
            }
        }
        MINUS.findAll(text).toList().asReversed().forEach { match ->
            YtSsiText.fold(match.groupValues[1]).takeIf { it.isNotEmpty() }?.let { excludeTerms.add(0, it) }
            cut(match.groups[0]!!.range)
        }

        val channels = ArrayList<String>()
        HANDLE.findAll(text).toList().asReversed().forEach { match ->
            channels.add(0, match.groupValues[1].trimEnd('.', ','))
            cut(match.range)
        }
        // "von <known channel>": the longest known name right after the word
        run {
            var at = 0
            while (true) {
                val match = FROM.find(text, at) ?: break
                val name = channelAt(text.substring(match.range.last + 1), known)
                if (name == null) {
                    at = match.range.last + 1
                    continue
                }
                channels += name
                val end = match.range.last + 1 + lengthOf(text.substring(match.range.last + 1), name)
                cut(match.range.first until end)
                at = match.range.first
            }
        }

        var language: String? = null
        LANGUAGE.find(text)?.let { match ->
            val word = (match.groupValues[1].ifEmpty { match.groupValues[2] }).lowercase()
            language = LANGUAGE_CODES[word]
            if (language != null) cut(match.range)
        }

        val (time, rest) = time(text, now, zone)
        text = rest

        // a known channel's name written plainly lifts its hits, without filtering
        val folded = " " + YtSsiText.fold(text) + " "
        val guesses = known.filter { name ->
            val f = YtSsiText.fold(name)
            f.length >= 6 && channels.none { YtSsiText.fold(it) == f } && folded.contains(" $f ")
        }.distinct()

        var semantic = text
        for (filler in FILLER) semantic = filler.replace(semantic, " ")
        semantic = semantic.replace(Regex("""\s+"""), " ").trim().trim(',', '.', ';', ':').trim()

        val kind = when {
            phrases.isNotEmpty() -> YtSsiIntentKind.EXACT_QUOTE
            FIND_VIDEO.containsMatchIn(raw) -> YtSsiIntentKind.FIND_VIDEO
            EXPLAIN.containsMatchIn(raw) -> YtSsiIntentKind.EXPLANATION
            QUESTION_START.containsMatchIn(raw) || raw.trim().endsWith("?") -> YtSsiIntentKind.QUESTION
            else -> YtSsiIntentKind.STATEMENT
        }
        val semanticText = listOf(semantic, phrases.joinToString(" ")).filter { it.isNotBlank() }.joinToString(" ")
        val terms = YtSsiText.keywords(semantic).filter { it !in excludeTerms }.distinct()
        return YtSsiIntent(
            raw = raw.trim(),
            kind = kind,
            phrases = phrases,
            semanticText = semanticText,
            terms = terms,
            time = time,
            channels = channels.distinct(),
            channelGuesses = guesses,
            excludeTerms = excludeTerms.distinct(),
            excludeChannels = excludeChannels.distinct(),
            language = language,
            later = later,
        )
    }

    /** The longest of [known] that [text] starts with (folded, whole words), or null. */
    private fun channelAt(text: String, known: List<String>): String? {
        val start = " " + YtSsiText.fold(text) + " "
        return known.filter { it.isNotBlank() }
            .sortedByDescending { YtSsiText.fold(it).length }
            .firstOrNull { name -> start.startsWith(" " + YtSsiText.fold(name) + " ") }
    }

    /** How many characters of [text] the words of [name] take (to cut them out). */
    private fun lengthOf(text: String, name: String): Int {
        val count = YtSsiText.words(name).size
        val ranges = YtSsiText.wordRanges(text)
        return ranges.getOrNull(count - 1)?.let { it.last + 1 } ?: text.length
    }

    private fun number(word: String): Int? = word.toIntOrNull() ?: when (word.lowercase()) {
        "ein", "eine", "einem", "einer", "einen", "eins", "a", "an", "one" -> 1
        "zwei", "two" -> 2
        "drei", "three" -> 3
        "vier", "four" -> 4
        "fünf", "funf", "five" -> 5
        "sechs", "six" -> 6
        "sieben", "seven" -> 7
        "acht", "eight" -> 8
        "neun", "nine" -> 9
        "zehn", "ten" -> 10
        "elf", "eleven" -> 11
        "zwölf", "zwolf", "twelve" -> 12
        else -> null
    }

    private const val DAY = 24L * 60 * 60 * 1000

    private fun startOf(date: LocalDate, zone: ZoneId): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun year(y: Int, zone: ZoneId) = YtSsiTimeRange(startOf(LocalDate.of(y, 1, 1), zone), startOf(LocalDate.of(y + 1, 1, 1), zone) - 1)

    /**
     * The first time expression in [text] as a range, and the text without it. "vor N Jahren"
     * means around then: ± half a year (± half a unit for months, weeks, days); "letzte Woche" and
     * "letzten Monat" the last 14 or 62 days (what people mean is seldom the calendar week);
     * "letztes Jahr" and a year number the calendar year.
     */
    fun time(text: String, now: Long, zone: ZoneId): Pair<YtSsiTimeRange?, String> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun around(n: Int, unit: String): YtSsiTimeRange {
            val u = unit.lowercase()
            val (center, half) = when {
                u.startsWith("j") || u.startsWith("y") -> today.minusYears(n.toLong()) to 182L
                u.startsWith("m") -> today.minusMonths(n.toLong()) to 15L
                u.startsWith("w") -> today.minusWeeks(n.toLong()) to 4L
                else -> today.minusDays(n.toLong()) to 1L
            }
            val from = startOf(center.minusDays(half), zone)
            val to = minOf(now, startOf(center.plusDays(half + 1), zone) - 1)
            return YtSsiTimeRange(from, to)
        }
        val rules: List<Pair<Regex, (MatchResult) -> YtSsiTimeRange?>> = listOf(
            BETWEEN to { m ->
                val a = m.groupValues[1].toInt()
                val b = m.groupValues[2].toInt()
                YtSsiTimeRange(year(minOf(a, b), zone).fromMs, year(maxOf(a, b), zone).toMs)
            },
            AGO_DE to { m -> number(m.groupValues[1])?.let { around(it, m.groupValues[2]) } },
            AGO_EN to { m -> number(m.groupValues[1])?.let { around(it, m.groupValues[2]) } },
            LAST to { m ->
                when (m.groupValues[1].lowercase()) {
                    "woche", "week" -> YtSsiTimeRange(now - 14 * DAY, now)
                    "monat", "month" -> YtSsiTimeRange(now - 62 * DAY, now)
                    else -> year(today.year - 1, zone)
                }
            },
            THIS to { m ->
                val start = when (m.groupValues[1].lowercase()) {
                    "woche", "week" -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    "monat", "month" -> today.withDayOfMonth(1)
                    else -> today.withDayOfYear(1)
                }
                YtSsiTimeRange(startOf(start, zone), now)
            },
            TODAY to { _ -> YtSsiTimeRange(startOf(today, zone), now) },
            YESTERDAY to { _ -> YtSsiTimeRange(startOf(today.minusDays(1), zone), startOf(today, zone) - 1) },
            SINCE to { m -> YtSsiTimeRange(year(m.groupValues[1].toInt(), zone).fromMs, null) },
            UNTIL to { m -> YtSsiTimeRange(null, year(m.groupValues[1].toInt(), zone).toMs) },
            BEFORE to { m -> YtSsiTimeRange(null, year(m.groupValues[1].toInt(), zone).fromMs!! - 1) },
            MONTH_YEAR to { m ->
                val month = MONTHS[m.groupValues[1].lowercase()] ?: return@to null
                val first = LocalDate.of(m.groupValues[2].toInt(), month, 1)
                YtSsiTimeRange(startOf(first, zone), startOf(first.plusMonths(1), zone) - 1)
            },
            YEAR to { m -> year(m.groupValues[1].toInt(), zone) },
        )
        for ((regex, read) in rules) {
            val match = regex.find(text) ?: continue
            val range = read(match) ?: continue
            return range to (text.substring(0, match.range.first) + " " + text.substring(match.range.last + 1))
        }
        return null to text
    }

    private val MONTHS = mapOf(
        "januar" to 1, "jänner" to 1, "january" to 1, "februar" to 2, "february" to 2, "märz" to 3, "maerz" to 3,
        "march" to 3, "april" to 4, "mai" to 5, "may" to 5, "juni" to 6, "june" to 6, "juli" to 7, "july" to 7,
        "august" to 8, "september" to 9, "oktober" to 10, "october" to 10, "november" to 11, "dezember" to 12,
        "december" to 12,
    )
}
