package com.github.libretube.util

import java.util.Locale

/**
 * YT: calmer video titles, for display only (never for what is stored, shared or searched).
 *
 * - [fixCase]: a title written mostly in capitals reads in sentence case, sentence by sentence,
 *   with acronyms kept ("NASA", "GTA", "4K", "USA"); in a normal title a single shouted word
 *   ("This is INSANE") is lowered. Short titles are left alone.
 * - [fixMarks]: "!!!" becomes "!", "???" "?", "?!?!" "?!", a run of emoji one emoji, a run of
 *   dots three dots.
 *
 * Pure and Unicode aware (works on code points, lowers with [Locale.ROOT] so a Turkish phone
 * does not turn "I" into a dotless i, capitalises with [Character.toTitleCase] so "ß" stays one
 * letter). Never returns an empty or blank text for a non-blank title.
 */
object YtTitleCalm {
    /** Fewer cased letters than this: the case is left as it is ("LOL", "WOW!"). */
    const val MIN_LETTERS = 8

    /** At least this share of the cased letters upper case: the whole title is shouting. */
    const val CAPS_SHARE = 0.75

    /** A word of capitals with at most this many letters is taken for an acronym. */
    const val ACRONYM_MAX = 4

    fun calm(title: String, fixCase: Boolean = true, fixMarks: Boolean = true): String {
        if (title.isBlank() || (!fixCase && !fixMarks)) return title
        var text = title
        if (fixMarks) text = calmMarks(text)
        if (fixCase) text = calmCase(text)
        return if (text.isBlank() || text == title) title else text
    }

    // ---------------------------------------------------------------------------------------------
    // Case

    /** The case part of [calm] on its own. */
    fun calmCase(title: String): String {
        val tokens = tokenize(title)
        var cased = 0
        var upper = 0
        for (t in tokens) if (t.word) {
            cased += t.cased
            upper += t.upper
        }
        if (cased < MIN_LETTERS) return title
        val shouting = upper >= CAPS_SHARE * cased
        val german = !shouting && looksGerman(tokens)

        val out = StringBuilder(title.length)
        // the first letter of the title and of each sentence or part ("Title: subtitle") is capital
        var sentenceStart = true
        var changed = false
        for (t in tokens) {
            if (!t.word) {
                out.append(t.text)
                if (startsSentence(t.text)) sentenceStart = true
                continue
            }
            val atStart = sentenceStart
            if (t.cased > 0) sentenceStart = false
            val allCaps = t.cased > 0 && t.upper == t.cased
            val word = when {
                !allCaps || t.digits -> t.text
                shouting -> lowerInShout(t.text, t.cased, atStart)
                t.cased > ACRONYM_MAX && !isKnownAcronym(t.text) ->
                    if (atStart || german) capitalize(t.text.lowercase(Locale.ROOT)) else t.text.lowercase(Locale.ROOT)
                else -> t.text
            }
            if (word != t.text) changed = true
            out.append(word)
        }
        return if (changed) out.toString() else title
    }

    /** A capital word of a shouting title in sentence case; acronyms stay. */
    private fun lowerInShout(word: String, cased: Int, atStart: Boolean): String {
        val lower = word.lowercase(Locale.ROOT)
        // the English "I" and "I'm", "I'll", ...
        if (lower == "i" || lower.startsWith("i'") || lower.startsWith("i’")) return capitalize(lower)
        val apostrophe = word.any { it == '\'' || it == '’' }
        // in a shouting title a short word is only taken for an acronym when it is known, has no
        // vowel ("BMW", "HTML") or has at most three letters and is no common word ("GTA", "USA");
        // "MARS" or "GOLD" are words
        val acronym = !apostrophe && (
            isKnownAcronym(word) ||
                (cased <= ACRONYM_MAX && lower.none { it in VOWELS }) ||
                (cased <= 3 && lower !in COMMON_SHORT)
            )
        if (acronym) return word
        return if (atStart) capitalize(lower) else lower
    }

    /** Whether a separator ends a sentence or a part of the title, so the next word starts with a capital. */
    private fun startsSentence(separator: String): Boolean {
        for (c in separator) if (c in SENTENCE_MARKS) return true
        // " - " and " – " between parts, not the hyphen in "E-Mail"
        return separator.contains('-') && separator.any { it.isWhitespace() }
    }

    private fun capitalize(word: String): String {
        if (word.isEmpty()) return word
        val cp = word.codePointAt(0)
        val title = Character.toTitleCase(cp)
        if (title == cp) return word
        return String(Character.toChars(title)) + word.substring(Character.charCount(cp))
    }

    private fun isKnownAcronym(word: String): Boolean = word.uppercase(Locale.ROOT) in KNOWN_ACRONYMS

    private fun looksGerman(tokens: List<Token>): Boolean = tokens.any { t ->
        t.word && (t.text.any { it in "äöüßÄÖÜẞ" } || t.text.lowercase(Locale.ROOT) in GERMAN_WORDS)
    }

    /** A word (letters, marks, digits, apostrophes inside) or the text between two words. */
    private class Token(val text: String, val word: Boolean, val cased: Int, val upper: Int, val digits: Boolean)

    private fun tokenize(text: String): List<Token> {
        val tokens = ArrayList<Token>()
        var i = 0
        while (i < text.length) {
            val start = i
            val word = isWordChar(text, i)
            var cased = 0
            var upper = 0
            var digits = false
            while (i < text.length && isWordChar(text, i) == word) {
                val cp = text.codePointAt(i)
                if (word) {
                    if (Character.isUpperCase(cp) || Character.isTitleCase(cp)) {
                        cased++
                        upper++
                    } else if (Character.isLowerCase(cp)) {
                        cased++
                    } else if (Character.isDigit(cp)) {
                        digits = true
                    }
                }
                i += Character.charCount(cp)
            }
            tokens += Token(text.substring(start, i), word, cased, upper, digits)
        }
        return tokens
    }

    private fun isWordChar(text: String, i: Int): Boolean {
        val cp = text.codePointAt(i)
        if (Character.isLetterOrDigit(cp)) return true
        val type = Character.getType(cp)
        if (type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt()) return true
        // an apostrophe between letters ("DON'T") belongs to the word
        if ((cp == '\''.code || cp == '’'.code) && i > 0 && i + 1 < text.length) {
            return Character.isLetter(text.codePointBefore(i)) && Character.isLetter(text.codePointAt(i + 1))
        }
        return false
    }

    private const val VOWELS = "aeiouyäöüéèáàóòíìúùâêîôû"

    private const val SENTENCE_MARKS = ".!?:|–—([{«»„“\"…"

    /** Acronyms longer than [ACRONYM_MAX] letters, or short ones that are also common words. */
    private val KNOWN_ACRONYMS = setOf(
        "NVIDIA", "NASCAR", "UNESCO", "UNICEF", "NAACP", "SWAT", "ASAP", "ESPN", "HBO", "DLC", "RPG",
        "MMORPG", "GPU", "CPU", "SSD", "HDMI", "USB", "WWII", "WWI", "LGBTQ", "ADAC", "NATO", "NASA",
        "FIFA", "UEFA", "ASMR", "IKEA", "HTML", "JSON", "AMD", "OLED", "QLED", "HDR", "IMAX", "SNES", "COVID", "NFT",
    )

    /** Short words in capitals that are words, not acronyms (English and German). */
    private val COMMON_SHORT = setOf(
        // English
        "a", "an", "the", "and", "or", "but", "so", "if", "of", "to", "in", "on", "at", "by", "for", "from",
        "up", "out", "off", "as", "is", "are", "was", "were", "be", "been", "am", "do", "does", "did", "done",
        "has", "have", "had", "not", "no", "yes", "it", "its", "he", "she", "we", "you", "me", "my", "him",
        "her", "his", "our", "us", "they", "them", "who", "how", "why", "what", "when", "this", "that", "with",
        "all", "any", "new", "old", "best", "top", "big", "huge", "one", "two", "six", "ten", "more", "most",
        "very", "just", "now", "here", "only", "can", "will", "get", "got", "make", "made", "go", "went",
        "come", "see", "saw", "look", "real", "day", "days", "life", "time", "year", "live", "love", "game",
        "food", "home", "back", "last", "full", "free", "fast", "easy", "hard", "good", "bad", "epic",
        "wow", "omg", "lol", "wtf", "vs", "ever", "into", "over", "than", "then", "like", "much", "many",
        "some", "part", "next", "song", "band", "show", "news", "city", "car", "cars", "cat", "dog", "man",
        "men", "girl", "boy", "kid", "kids", "won", "win", "lost", "lose", "help", "stop", "run", "try",
        "tried", "test", "buy", "sell", "own", "way", "end", "let", "say", "said", "far", "too", "also",
        "each", "even", "both", "why", "why", "week", "work", "play", "move", "food", "fun", "hot", "cold",
        // German
        "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "und", "oder", "aber", "ist",
        "sind", "war", "hat", "habe", "ich", "du", "er", "sie", "es", "wir", "ihr", "mit", "von", "zu",
        "zum", "zur", "im", "in", "am", "an", "auf", "aus", "bei", "für", "nach", "vor", "wie", "was", "wer",
        "wo", "so", "nur", "noch", "mal", "mein", "dein", "sein", "neu", "neue", "alt", "alle", "viel",
        "sehr", "gut", "ganz", "kein", "nie", "jetzt", "hier", "da", "ja", "nein", "auch", "doch", "dann",
        "mehr", "tag", "jahr", "geht", "gibt", "ohne", "über", "weg", "los", "uns", "euch", "wird", "kann",
        "muss", "will", "soll", "tun", "macht", "zeit", "spiel", "auto", "haus", "geld", "welt", "test",
    )

    /** Short German function words; any of them makes a title read as German. */
    private val GERMAN_WORDS = setOf(
        "der", "die", "das", "und", "ist", "nicht", "ich", "mit", "ein", "eine", "auf", "für", "wie", "wir",
        "sich", "auch", "dem", "den", "des", "zum", "zur", "oder", "aber", "noch", "mein", "meine", "kein",
    )

    // ---------------------------------------------------------------------------------------------
    // Punctuation and emoji

    /** The punctuation and emoji part of [calm] on its own. */
    fun calmMarks(title: String): String {
        val out = StringBuilder(title.length)
        var i = 0
        val n = title.length
        while (i < n) {
            val c = title[i]
            when {
                isBang(c) -> {
                    // a run of ! and ? (and their full-width and double forms)
                    var j = i
                    var bang = false
                    var ask = false
                    var count = 0
                    while (j < n && isBang(title[j])) {
                        when (title[j]) {
                            '!', '！' -> bang = true
                            '?', '？' -> ask = true
                            '‼' -> { bang = true; count++ }
                            else -> { bang = true; ask = true; count++ } // ⁉ ⁈
                        }
                        count++
                        j++
                    }
                    if (count <= 1) {
                        out.append(c)
                    } else {
                        val first = if (title[i] == '?' || title[i] == '？' || title[i] == '⁈') '?' else '!'
                        out.append(first)
                        if (bang && ask) out.append(if (first == '?') '!' else '?')
                    }
                    i = j
                }
                c == '.' -> {
                    var j = i
                    while (j < n && title[j] == '.') j++
                    out.append(if (j - i > 3) "..." else title.substring(i, j))
                    i = j
                }
                else -> {
                    val end = emojiEnd(title, i)
                    if (end < 0) {
                        out.append(c)
                        i++
                    } else {
                        // keep the first emoji of a run, drop the others and the spaces between them
                        out.append(title, i, end)
                        var j = end
                        while (true) {
                            var k = j
                            while (k < n && title[k].isWhitespace()) k++
                            val next = if (k < n) emojiEnd(title, k) else -1
                            if (next < 0) break
                            j = next
                        }
                        i = j
                    }
                }
            }
        }
        return out.toString()
    }

    private fun isBang(c: Char) = c == '!' || c == '?' || c == '！' || c == '？' || c == '‼' || c == '⁉' || c == '⁈'

    /**
     * The end of the emoji (a whole cluster: skin tone, variation selector, keycap, flag pair,
     * joined sequence, tags) that starts at [i], or -1 when no emoji starts there.
     */
    internal fun emojiEnd(text: String, i: Int): Int {
        val n = text.length
        val cp = text.codePointAt(i)
        var j = i + Character.charCount(cp)
        // keycaps: 1️⃣ #️⃣
        if (cp in '0'.code..'9'.code || cp == '#'.code || cp == '*'.code) {
            var k = j
            if (k < n && text[k] == '️') k++
            return if (k < n && text[k] == '⃣') k + 1 else -1
        }
        if (isRegional(cp)) {
            if (j < n && isRegional(text.codePointAt(j))) j += 2
            return j
        }
        if (!isEmojiBase(cp)) return -1
        while (j < n) {
            val next = text.codePointAt(j)
            when {
                next == 0xFE0F || next == 0xFE0E || next == 0x20E3 || next in 0x1F3FB..0x1F3FF || next in 0xE0020..0xE007F ->
                    j += Character.charCount(next)
                next == 0x200D && j + 1 < n && isEmojiBase(text.codePointAt(j + 1)) -> {
                    j += 1
                    j += Character.charCount(text.codePointAt(j))
                }
                else -> break
            }
        }
        return j
    }

    private fun isRegional(cp: Int) = cp in 0x1F1E6..0x1F1FF

    private fun isEmojiBase(cp: Int): Boolean =
        cp in 0x1F300..0x1FAFF || // pictographs, emoticons, transport, supplemental, extended-A
            cp in 0x1F000..0x1F02F || cp in 0x1F0A0..0x1F0FF || // mahjong, cards
            cp in 0x1F170..0x1F1E5 || cp in 0x1F200..0x1F2FF || // 🅰 🆕, enclosed ideographs
            cp in 0x2600..0x27BF || // misc symbols and dingbats (☀ ✨ ❤ ✅)
            cp in 0x2B05..0x2B07 || cp == 0x2B1B || cp == 0x2B1C || cp == 0x2B50 || cp == 0x2B55 ||
            cp in 0x231A..0x231B || cp in 0x23E9..0x23F3 || cp in 0x23F8..0x23FA ||
            cp == 0x25B6 || cp == 0x25C0 || cp in 0x25FB..0x25FE ||
            cp == 0x3030 || cp == 0x303D || cp == 0x3297 || cp == 0x3299
}
