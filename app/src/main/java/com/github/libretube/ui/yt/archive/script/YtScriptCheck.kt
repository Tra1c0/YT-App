package com.github.libretube.ui.yt.archive.script

import com.github.libretube.ui.yt.news.YtNewsQuotes

/**
 * YT: the checks that keep an AI-written script true to what was said. Pure Kotlin.
 *
 * Words are compared after [YtNewsQuotes.normalize] (case, punctuation, umlaut spelling and
 * accents do not matter); a word of five letters or more may differ by one edit (a recognition
 * error the AI fixed). Names are taken only when every part of them occurs in the transcript,
 * the title or the description, and never for a role ("Moderator", "Gast").
 */
object YtScriptCheck {
    /** Share of the AI's words that must be in the source words (nothing invented). */
    const val PRECISION = 0.85

    /** Share of the source words that must be in the AI's text (nothing dropped). */
    const val RECALL = 0.75

    /** Fewer source words than this: only the precision counts (a few words prove little). */
    private const val MIN_WORDS_FOR_RECALL = 4

    private const val FUZZY_MIN_LENGTH = 5

    /** Words that name a role, not a person; never stored as a speaker's name. */
    private val ROLES = setOf(
        "sprecher", "sprecherin", "speaker", "moderator", "moderatorin", "host", "gast", "guest",
        "interviewer", "interviewerin", "reporter", "reporterin", "narrator", "erzaehler", "erzaehlerin",
        "unbekannt", "unknown", "person", "mann", "frau", "man", "woman", "stimme", "voice", "kommentator",
        "kommentatorin", "publikum", "audience", "zuschauer", "a", "b", "c",
    )

    fun words(text: String): List<String> = YtNewsQuotes.normalize(text).split(' ').filter { it.isNotEmpty() }

    /** Share of [words] found in [pool] (exact, or one edit for longer words); 1 for no words. */
    fun share(words: List<String>, pool: Collection<String>): Double {
        if (words.isEmpty()) return 1.0
        val set = pool as? Set<String> ?: pool.toHashSet()
        var found = 0
        for (w in words) if (w in set || fuzzyIn(w, set)) found++
        return found.toDouble() / words.size
    }

    private fun fuzzyIn(word: String, pool: Set<String>): Boolean {
        if (word.length < FUZZY_MIN_LENGTH) return false
        return pool.any { p -> kotlin.math.abs(p.length - word.length) <= 1 && p.length >= FUZZY_MIN_LENGTH - 1 && editsAtMostOne(word, p) }
    }

    /** Whether [a] and [b] differ by at most one insertion, deletion or substitution. */
    fun editsAtMostOne(a: String, b: String): Boolean {
        if (a == b) return true
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        val (s, l) = if (a.length <= b.length) a to b else b to a
        var i = 0
        var j = 0
        var edits = 0
        while (i < s.length && j < l.length) {
            if (s[i] == l[j]) {
                i++
                j++
                continue
            }
            if (++edits > 1) return false
            if (s.length == l.length) i++
            j++
        }
        return edits + (l.length - j) + (s.length - i) <= 1
    }

    /**
     * Whether [text] is the source passage [range] in readable form: almost all its words come
     * from [window] (the passage and its neighbours) and it keeps most words of [range].
     */
    fun faithful(text: String, range: String, window: String): Verdict {
        val said = words(text)
        val source = words(range)
        val precision = share(said, words(window).toHashSet())
        val recall = if (source.size < MIN_WORDS_FOR_RECALL) 1.0 else share(source, said.toHashSet())
        return Verdict(precision >= PRECISION, recall >= RECALL)
    }

    /** [noInvention]: nothing made up; [complete]: nothing important dropped. */
    data class Verdict(val noInvention: Boolean, val complete: Boolean) {
        val ok get() = noInvention && complete
    }

    /**
     * [name], cleaned, when it is a person's name that occurs in [known] (the normalised words
     * of transcript, title and description); else null.
     */
    fun name(name: String?, known: Set<String>): String? {
        val clean = name?.trim()?.trim('"', '\'', '.', ',')?.replace(Regex("""\s+"""), " ") ?: return null
        if (clean.isEmpty() || clean.length > 60) return null
        val parts = words(clean)
        if (parts.isEmpty() || parts.size > 4) return null
        if (parts.any { it in ROLES } || parts.any { it.length < 2 }) return null
        if (parts.any { it.all(Char::isDigit) }) return null
        return clean.takeIf { parts.all { it in known } }
    }
}
