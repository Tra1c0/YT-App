package com.github.libretube.ui.yt.feed

/**
 * YT: the keyword a watched video suggests for a topic ("Schlagwort „X“ zu <Thema>
 * hinzufügen?"): the most distinctive word or two-word phrase of its title and tags that the
 * topic does not have yet. Pure, so it is tested on the JVM.
 *
 * Candidates are the title's words and neighbouring word pairs (within one part of the title:
 * " - ", "|", ":" and brackets split it), its hashtags and short tags. Stop words (German and
 * English, plus words every video title has), numbers and emoji are never candidates. A word
 * scores for a capital inside a sentence (a name; less where the whole title is in title case),
 * for being an acronym or written like "iPhone", for its length, for repeats, for standing in
 * the tags and for being rare in the other titles of the feed ([suggest]'s corpus); a pair adds
 * its words' mean and a small bonus, more when both are names ("Raumstation ISS"). Words the topic has, its
 * name, turned down words and the channel's name are left out, as is anything that only repeats
 * a keyword ("Quantenphysik einfach" for the keyword "Quantenphysik").
 */
object YtTopicWords {
    /** Below this nothing is worth asking about. */
    const val MIN_SCORE = 1.5

    private val STOP_DE = """
        der die das den dem des ein eine einer eines einem einen und oder aber doch nicht kein keine keinen
        ist sind war waren wird werden wurde wurden hat haben hatte hatten bin bist sein seid gewesen
        ich du er sie es wir ihr mein meine meinen dein deine sein seine unser unsere euer eure
        mich dich sich uns euch mir dir ihm ihn ihnen was wer wie wo warum wann welche welcher welches
        dass da so auch noch nur schon mal jetzt hier dort mit ohne für von vom zum zur zu im in am an auf aus
        bei bis durch gegen nach seit über unter vor hinter neben zwischen um als wenn weil ob dann denn
        sehr mehr viel viele vielen alle alles allem ganz einfach wirklich immer nie heute morgen gestern
        neu neue neuen neues neuer teil folge kanal offiziell offizielle erste ersten zweite dritte beste besten
        komplett kompletten ganze ganzen gibt geht macht machen mache kann können konnte muss müssen soll sollte
        will wollen darf diese dieser dieses diesem diesen jeder jede jedes man selbst etwa mehr weniger
        hier heute endlich echt krass mega richtig deutsch deutsche english german warum wieso weshalb
        ja nein okay ok oh hey hallo tipps tipp tricks trick anleitung erklärt erklärung geschichte
    """

    private val STOP_EN = """
        the a an and or but not no nor is are was were be been being has have had do does did done
        i you he she it we they me him her us them my your his its our their this that these those
        what who whom how why when where which with without for from of to in on at by about into over
        under after before up down out off as if then than so too very more most much many all any some
        just only also now here there new official video videos full part episode ep live best top vs
        hd uhd 4k 8k reaction review shorts short vlog trailer teaser lyrics lyric audio music remastered
        remaster version edition feat ft ever never every one two three first last day days time
        how-to tutorial guide tips tricks explained explanation story history why can can't cannot will
        would should could get got make made makes really actually ultimate amazing epic insane crazy
        yes oh hey hi okay ok english german subtitles subs sub
    """

    private val STOP: Set<String> = (STOP_DE + " " + STOP_EN).split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .flatMap { listOf(YtFeedTopics.fold(it), YtFeedTopics.foldWide(it)) }
        .toSet()

    /** " - ", " | ", ":" and the like split a title into parts; a pair never crosses them. */
    private val BREAKS = Regex("""\s[-–—]\s|[|•·:;,.!?()\[\]{}"“”„«»/\\+=*~@<>]""")
    private val TOKEN = Regex("""[\p{L}\p{N}]+(?:['’\-][\p{L}\p{N}]+)*""")
    private val HASHTAG = Regex("""#([\p{L}][\p{L}\p{N}_]*)""")
    private val ROMAN = Regex("[IVXLC]{1,5}")

    /** One candidate and its score. */
    data class Scored(val text: String, val score: Double)

    private class Token(val text: String, val first: Boolean) {
        val letters = text.count { it.isLetter() }
        val acronym = letters in 2..6 && text.filter { it.isLetter() }.all { it.isUpperCase() }
        val shout = letters > 6 && text.filter { it.isLetter() }.all { it.isUpperCase() }
        val innerCaps = !acronym && !shout && text.drop(1).any { it.isUpperCase() } && text.any { it.isLowerCase() }
        val capital = text.first().isUpperCase()
        val folded = YtFeedTopics.fold(text)
    }

    /** Whether [word] is a stop word or no word at all (a number, too short). */
    fun isStop(word: String): Boolean {
        val letters = word.count { it.isLetter() }
        if (letters < 2) return true
        if (!word.first().isLetter()) return true
        // "II", "IV": numbers too
        if (ROMAN.matches(word)) return true
        val upper = word.filter { it.isLetter() }.all { it.isUpperCase() }
        // two letters only as an acronym ("KI", "AI")
        if (letters < 3 && !upper) return true
        return YtFeedTopics.fold(word) in STOP || YtFeedTopics.foldWide(word) in STOP
    }

    /**
     * The best keyword of a video ([title], [tags], by [channel]) for [topic], or null when none
     * scores at least [MIN_SCORE]. [corpus] are other titles (the feed's), for how rare a word is;
     * [skip] are words not to offer (already offered in this session).
     */
    fun suggest(
        title: String,
        tags: List<String>,
        topic: YtFeedTopic,
        corpus: List<String> = emptyList(),
        channel: String? = null,
        skip: Collection<String> = emptyList(),
    ): String? = rank(title, tags, topic, corpus, channel, skip).firstOrNull()?.takeIf { it.score >= MIN_SCORE }?.text

    /** Every candidate with its score, best first (ties: as they come in the title). */
    fun rank(
        title: String,
        tags: List<String>,
        topic: YtFeedTopic,
        corpus: List<String> = emptyList(),
        channel: String? = null,
        skip: Collection<String> = emptyList(),
    ): List<Scored> {
        val tagTexts = tags.map { it.trim().trimStart('#') }.filter { it.isNotEmpty() && it.length <= 40 }
        val tagWhole = tagTexts.mapTo(HashSet()) { YtFeedTopics.fold(it) }
        val tagWords = tagTexts.flatMap { TOKEN.findAll(it).map { m -> YtFeedTopics.fold(m.value) }.toList() }.toSet()
        val corpusFolded = corpus.map { YtFeedTopics.fold(it) }
        val found = LinkedHashMap<String, Scored>()

        fun offer(text: String, score: Double) {
            val key = YtFeedTopics.fold(text)
            val before = found[key]
            if (before == null || score > before.score) found[key] = Scored(text, score)
        }

        fun rarity(folded: String): Double {
            if (corpusFolded.size < 5) return 0.0
            val df = corpusFolded.count { YtFeedEngine.matches(it, folded) }
            return 1.0 - df.toDouble() / corpusFolded.size
        }

        // hashtags are the uploader's own keywords
        val hashtags = HASHTAG.findAll(title).map { it.groupValues[1] }.toList()
        val plain = HASHTAG.replace(title, " ")
        val parts = plain.split(BREAKS).map { part ->
            TOKEN.findAll(part).mapIndexed { i, m -> Token(m.value, i == 0) }.toList()
        }
        val words = parts.flatten().filter { !isStop(it.text) }
        // a title in title case ("How To Build A Rocket") says little with its capitals
        val titleCase = words.size >= 4 && words.count { it.capital } >= words.size * 0.8
        val capitalBonus = if (titleCase) 0.4 else 1.0
        val counts = words.groupingBy { it.folded }.eachCount()

        fun wordScore(t: Token): Double {
            var s = 1.0
            s += when {
                t.acronym -> 1.2
                t.innerCaps -> 1.0
                t.shout -> 0.4
                t.capital && !t.first -> capitalBonus
                t.capital -> 0.3
                else -> 0.0
            }
            s += 0.5 * minOf(t.letters, 12) / 12.0
            s += 0.4 * ((counts[t.folded] ?: 1) - 1).coerceAtMost(2)
            if (t.folded in tagWhole) s += 1.6 else if (t.folded in tagWords) s += 1.2
            s += rarity(t.folded)
            return s
        }

        for (part in parts) {
            for ((i, t) in part.withIndex()) {
                if (isStop(t.text)) continue
                val s = wordScore(t)
                offer(t.text, s)
                val n = part.getOrNull(i + 1) ?: continue
                if (isStop(n.text)) continue
                val pair = t.text + " " + n.text
                var ps = (s + wordScore(n)) / 2 + 0.3
                val names = { x: Token -> x.acronym || x.innerCaps || (x.capital && !x.first) || (x.capital && !titleCase) }
                if (names(t) && names(n)) ps += 0.8
                if (YtFeedTopics.fold(pair) in tagWhole) ps += 1.6
                offer(pair, ps)
            }
        }
        for (tag in hashtags) {
            if (isStop(tag)) continue
            offer(tag, 1.6 + 0.5 * minOf(tag.length, 12) / 12.0 + rarity(YtFeedTopics.fold(tag)))
        }
        // short tags that the title does not have
        for (tag in tagTexts) {
            val tokens = TOKEN.findAll(tag).map { it.value }.toList()
            if (tokens.isEmpty() || tokens.size > 3 || tokens.any { isStop(it) }) continue
            val text = tokens.joinToString(" ")
            if (YtFeedTopics.fold(text) in found) continue
            offer(text, 1.2 + 0.5 * minOf(text.length, 12) / 12.0 + rarity(YtFeedTopics.fold(text)))
        }

        val channelWords = channel?.let { c -> TOKEN.findAll(c).map { it.value }.toList() }.orEmpty()
        return found.values
            .filter { candidate -> !excluded(candidate.text, topic, channel, channelWords, skip) }
            .sortedByDescending { it.score }
    }

    private fun excluded(text: String, topic: YtFeedTopic, channel: String?, channelWords: List<String>, skip: Collection<String>): Boolean {
        val known = topic.keywords + topic.name
        // the same as a keyword, or only a keyword with more words, or a part of one
        if (known.any { YtFeedTopics.same(it, text) || YtFeedTopics.matches(text, it) || YtFeedTopics.matches(it, text) }) return true
        if ((topic.dismissed + skip).any { YtFeedTopics.same(it, text) }) return true
        if (channel != null && YtFeedTopics.same(channel, text)) return true
        if (channelWords.any { YtFeedTopics.same(it, text) }) return true
        return false
    }
}
