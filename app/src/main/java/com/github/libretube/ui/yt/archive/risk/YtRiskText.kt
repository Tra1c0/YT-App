package com.github.libretube.ui.yt.archive.risk

import java.text.Normalizer
import java.util.Locale

/*
 * YT: the text side of the risk estimator: the words of a video (title plus the start of the
 * description, German and English), the tokens the learning model counts, and the fixed
 * keywords that stand in for learned words before any video has vanished. Free of Android.
 */

/** A phrase that hints at removal risk; [label] is what the reason shows, [weight] in log-odds. */
class YtRiskKeyword(pattern: String, val label: String, val weight: Double) {
    /** The words of the phrase; a part ending in '*' matches any word starting with it. */
    val parts: List<String> = pattern.split(' ')

    fun matches(words: List<String>): Boolean {
        if (words.size < parts.size) return false
        for (i in 0..words.size - parts.size) {
            if (parts.indices.all { j -> partMatches(parts[j], words[i + j]) }) return true
        }
        return false
    }

    private fun partMatches(part: String, word: String): Boolean =
        if (part.endsWith('*')) word.startsWith(part.dropLast(1)) else word == part
}

object YtRiskText {
    /** How much of the description counts (and is sent to the AI). */
    const val DESCRIPTION_CHARS = 200

    /** The most distinct tokens one video adds to the model. */
    const val MAX_TOKENS = 40

    private val URL = Regex("""(https?://|www\.)\S*""")
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")

    /** The start of a description as it is used here: no links, one line. */
    fun descriptionStart(description: String?): String =
        description.orEmpty().take(DESCRIPTION_CHARS).replace(URL, " ").replace(Regex("""\s+"""), " ").trim()

    /**
     * The normalised words of a video in order, stop words included (keywords are phrases).
     * NFKC folds styled letters ("𝐋𝐄𝐀𝐊") to plain ones.
     */
    fun words(title: String, description: String?): List<String> {
        val text = title + " " + descriptionStart(description)
        val folded = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        return folded.replace(URL, " ").split(NON_WORD).filter { it.isNotEmpty() }
    }

    /** The distinct tokens the model learns from: no stop words, no numbers, at least 3 letters. */
    fun tokens(words: List<String>): Set<String> {
        val out = LinkedHashSet<String>()
        for (w in words) {
            if (w.length < 3 || w.all { it.isDigit() } || w in STOP) continue
            out += w
            if (out.size >= MAX_TOKENS) break
        }
        return out
    }

    /** The keywords found in [words], one per label. */
    fun keywords(words: List<String>): List<YtRiskKeyword> =
        KEYWORDS.filter { it.matches(words) }.distinctBy { it.label }

    /** A token as the reason shows it: first letter upper case. */
    fun display(token: String): String = token.replaceFirstChar { it.titlecase(Locale.ROOT) }

    private const val STRONG = 2.0
    private const val MEDIUM = 1.2
    private const val WEAK = 0.6

    /**
     * The cold-start prior, tuned conservatively: one strong phrase alone stays well under the
     * alert line; it takes two strong signs (or keywords plus a learned channel or the AI).
     */
    val KEYWORDS: List<YtRiskKeyword> = listOf(
        // whole works of others: taken down on copyright claims
        YtRiskKeyword("full movie", "Full Movie", STRONG),
        YtRiskKeyword("ganzer film", "ganzer Film", STRONG),
        YtRiskKeyword("kompletter film", "ganzer Film", STRONG),
        YtRiskKeyword("film komplett", "ganzer Film", STRONG),
        YtRiskKeyword("reupload*", "Reupload", STRONG),
        YtRiskKeyword("re upload*", "Reupload", STRONG),
        YtRiskKeyword("dmca", "DMCA", 1.5),
        YtRiskKeyword("full concert", "Full Concert", MEDIUM),
        YtRiskKeyword("concert full", "Full Concert", MEDIUM),
        YtRiskKeyword("konzert full", "Full Concert", MEDIUM),
        YtRiskKeyword("ganzes konzert", "ganzes Konzert", MEDIUM),
        YtRiskKeyword("konzert komplett", "ganzes Konzert", MEDIUM),
        YtRiskKeyword("full album", "Full Album", MEDIUM),
        YtRiskKeyword("copyright*", "Copyright", MEDIUM),
        YtRiskKeyword("urheberrecht*", "Urheberrecht", MEDIUM),
        YtRiskKeyword("abmahnung*", "Abmahnung", MEDIUM),
        // the uploader expects the removal
        YtRiskKeyword("before it gets deleted", "before it gets deleted", STRONG),
        YtRiskKeyword("before it s deleted", "before it gets deleted", STRONG),
        YtRiskKeyword("before it gets removed", "before it gets deleted", STRONG),
        YtRiskKeyword("before they delete*", "before it gets deleted", STRONG),
        YtRiskKeyword("bevor es gelöscht", "wird gelöscht", STRONG),
        YtRiskKeyword("wird gelöscht", "wird gelöscht", STRONG),
        YtRiskKeyword("bald gelöscht", "wird gelöscht", STRONG),
        // leaks and censorship
        YtRiskKeyword("leak*", "Leak", MEDIUM),
        YtRiskKeyword("geleakt*", "Leak", MEDIUM),
        YtRiskKeyword("exposed", "exposed", 1.0),
        YtRiskKeyword("banned", "banned", MEDIUM),
        YtRiskKeyword("gebannt", "gebannt", MEDIUM),
        YtRiskKeyword("zensiert*", "zensiert", MEDIUM),
        YtRiskKeyword("zensur", "Zensur", MEDIUM),
        YtRiskKeyword("censored", "censored", MEDIUM),
        YtRiskKeyword("censorship", "censored", MEDIUM),
        // disputes the uploader may settle by deleting
        YtRiskKeyword("skandal*", "Skandal", WEAK),
        YtRiskKeyword("scandal*", "Scandal", WEAK),
        YtRiskKeyword("drama", "Drama", 0.5),
        YtRiskKeyword("anklage*", "Anklage", WEAK),
        YtRiskKeyword("angeklagt", "Anklage", WEAK),
        YtRiskKeyword("klage", "Klage", WEAK),
        YtRiskKeyword("verklagt", "Klage", WEAK),
        YtRiskKeyword("lawsuit*", "Lawsuit", WEAK),
        YtRiskKeyword("sued", "Lawsuit", WEAK),
        YtRiskKeyword("rechtsstreit*", "Rechtsstreit", WEAK),
        YtRiskKeyword("entschuldigung", "Entschuldigung", WEAK),
        YtRiskKeyword("apology", "Apology", WEAK),
        YtRiskKeyword("apologize*", "Apology", WEAK),
        YtRiskKeyword("apologise*", "Apology", WEAK),
        // recordings of streams are often taken down after a while
        YtRiskKeyword("stream recording", "Stream recording", WEAK),
        YtRiskKeyword("livestream recording", "Stream recording", WEAK),
        YtRiskKeyword("stream aufzeichnung", "Stream-Aufzeichnung", WEAK),
        YtRiskKeyword("livestream aufzeichnung", "Stream-Aufzeichnung", WEAK),
        YtRiskKeyword("vod", "VOD", 0.4),
        YtRiskKeyword("breaking", "Breaking", 0.3),
        YtRiskKeyword("eilmeldung", "Eilmeldung", 0.3),
    )

    /** Common German and English words and channel boilerplate that say nothing about a topic. */
    val STOP: Set<String> = setOf(
        // German
        "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einem", "einer", "eines",
        "und", "oder", "aber", "doch", "auch", "noch", "nur", "schon", "sehr", "mehr", "nicht", "kein",
        "keine", "ist", "sind", "war", "waren", "wird", "werden", "wurde", "hat", "haben", "hatte",
        "kann", "können", "muss", "müssen", "soll", "will", "mit", "von", "für", "auf", "aus", "bei",
        "nach", "über", "unter", "vor", "zum", "zur", "wie", "was", "wer", "wann", "warum", "weil",
        "wenn", "dass", "als", "ich", "du", "er", "sie", "wir", "ihr", "uns", "euch", "mein", "dein",
        "sein", "unser", "euer", "diese", "dieser", "dieses", "hier", "jetzt", "heute", "alle", "alles",
        "ganz", "neu", "neue", "neuer", "neues", "teil", "folge", "mal", "gibt", "geht", "macht",
        // English
        "the", "and", "for", "but", "not", "you", "your", "are", "was", "were", "has", "have", "had",
        "with", "from", "this", "that", "these", "those", "what", "who", "why", "how", "when", "where",
        "they", "them", "their", "our", "his", "her", "its", "all", "out", "into", "about", "just",
        "can", "will", "get", "got", "new", "now", "here", "there", "more", "most", "very", "than",
        "then", "one", "two", "part", "episode", "ever", "best", "first", "last",
        // channel boilerplate
        "video", "videos", "official", "offizielles", "offizielle", "channel", "kanal", "abonnieren",
        "subscribe", "shorts", "short", "watch", "follow", "instagram", "tiktok", "twitter", "facebook",
        "discord", "patreon", "merch", "link", "links", "http", "https", "www", "com",
    )
}
