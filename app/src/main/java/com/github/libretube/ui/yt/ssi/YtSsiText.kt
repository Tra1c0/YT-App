package com.github.libretube.ui.yt.ssi

import java.text.Normalizer

/**
 * YT: SSI's word handling, the same for the index and the query, so "Müller", "MÜLLER" and
 * "Muller" are one word: lower case, accents removed, ß as ss, anything that is not a letter or a
 * digit is a space. The full-text table stores this folded text (any SQLite tokenizer then splits
 * it the same way), the passage table the original for display. Pure.
 */
object YtSsiText {
    private val MARKS = Regex("\\p{Mn}+")

    /** [text] folded: lower case, no accents, words split by single spaces. */
    fun fold(text: String): String {
        val lower = text.lowercase().replace("ß", "ss")
        val bare = MARKS.replace(Normalizer.normalize(lower, Normalizer.Form.NFD), "")
        val out = StringBuilder(bare.length)
        var space = true
        for (ch in bare) {
            if (ch.isLetterOrDigit()) {
                out.append(ch)
                space = false
            } else if (!space) {
                out.append(' ')
                space = true
            }
        }
        return out.toString().trim()
    }

    /** The folded words of [text]. */
    fun words(text: String): List<String> = fold(text).split(' ').filter { it.isNotEmpty() }

    /**
     * Where the folded words of [text] start and end in the original [text] (character ranges),
     * in order. Folding never merges two words, so word i of [words] is range i here.
     */
    fun wordRanges(text: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = -1
        for (i in text.indices) {
            val word = text[i].isLetterOrDigit() || Character.getType(text[i]) == Character.NON_SPACING_MARK.toInt()
            if (word && start < 0) start = i
            if (!word && start >= 0) {
                out += start until i
                start = -1
            }
        }
        if (start >= 0) out += start until text.length
        return out
    }

    /** Words that carry no topic of their own (German and English). */
    val STOP = setOf(
        "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einem", "einer", "eines",
        "und", "oder", "aber", "ist", "sind", "war", "waren", "wird", "werden", "wurde", "hat", "haben",
        "hatte", "ich", "du", "er", "sie", "es", "wir", "ihr", "man", "mir", "mich", "dir", "dich", "sich",
        "in", "im", "an", "am", "auf", "aus", "bei", "mit", "nach", "von", "vom", "zu", "zum", "zur",
        "fur", "uber", "unter", "um", "als", "wie", "so", "dass", "da", "dann", "denn", "noch", "nur",
        "auch", "schon", "sehr", "mal", "ja", "nein", "doch", "wo", "was", "wer", "wann", "warum",
        "dieser", "diese", "dieses", "diesen", "jener", "welche", "welcher", "welches", "zeig", "zeige",
        "finde", "such", "suche", "stelle", "video", "videos", "mir", "bitte", "etwas", "kann", "konnen",
        "the", "a", "an", "and", "or", "but", "is", "are", "was", "were", "be", "been", "has", "have",
        "had", "i", "you", "he", "she", "it", "we", "they", "of", "in", "on", "at", "to", "for", "from",
        "with", "by", "about", "as", "that", "this", "these", "those", "what", "who", "when", "where",
        "why", "how", "which", "do", "does", "did", "find", "show", "me", "please", "part", "clip",
    )

    /** The words of [text] that are not stop words; all of them if every one is. */
    fun keywords(text: String): List<String> {
        val all = words(text)
        val kept = all.filter { it !in STOP && it.length > 1 }
        return kept.ifEmpty { all }
    }
}
