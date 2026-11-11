package com.github.libretube.ui.yt.news

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.ln

/**
 * YT: groups the videos of the News overlay that report the same story into one
 * [YtNewsCard.Story], on the device and without any service: by the words of their titles.
 * Pure and deterministic (the same items and edits give the same cards and keys), so it runs in
 * the JVM tests.
 *
 * A title becomes a set of words: folded (case, umlauts and accents), German and English stop
 * words and the generic words of news titles ("Live", "Breaking", "heute", …) out, the words of
 * the video's own channel name out, dates and clock times out, a light suffix stemmer (CISTEM
 * style, so "Zölle"/"Zöllen" and "Präsident"/"Präsidenten" meet). Each word weighs by how rare it
 * is among the items (IDF), more for name-like words (acronyms such as "EU" or "AfD", words with
 * digits such as "G7", capitalised words). Two videos are alike by the weighted Dice coefficient
 * of their words; a German compound and its part ("Heizungsgesetz", "Heizung") count half.
 *
 * Grouping is agglomerative with average linkage and deliberately conservative: two videos only
 * link if they share at least two words (three for the same channel, whose recurring formats
 * share their template), one of them with four letters or more or a number, within [WINDOW_MS] of each
 * other; groups join while their mean similarity stays above [GROUP_MIN] and they span at most
 * [WINDOW_MS], with at most [MAX_MEMBERS] videos. The same politician alone, or two generic
 * words, never make a story.
 *
 * The user's edits ([YtNewsStoryEdits]) win: a video taken out of a story is never grouped
 * automatically again, and videos the user merged stay together.
 */
object YtNewsStory {
    /** Videos further apart than this are not the same story (also the span of a story). */
    const val WINDOW_MS = 48L * 60 * 60 * 1000

    /** At most this many videos join one story by themselves (the user may merge more). */
    const val MAX_MEMBERS = 8

    /** The similarity two videos need to link. */
    const val PAIR_MIN = 0.3

    /** The mean similarity two groups need to join. */
    const val GROUP_MIN = 0.24

    /** The cards of [items] (newest first, as YtNewsMerge gives them), with [edits] applied. */
    fun cards(items: List<YtNewsItem>, edits: YtNewsStoryEdits = YtNewsStoryEdits()): List<YtNewsCard> {
        if (items.isEmpty()) return emptyList()
        val unique = items.distinctBy { it.id }
        val groups = groups(unique, edits)
        return groups.map { members -> card(members.map { unique[it] }) }
    }

    /**
     * The groups of [items] as lists of indices, each sorted, the groups in the order of their
     * first item.
     */
    fun groups(items: List<YtNewsItem>, edits: YtNewsStoryEdits = YtNewsStoryEdits()): List<List<Int>> {
        val n = items.size
        val apart = edits.apart.mapTo(HashSet()) { it.id }
        val words = weigh(items.map { words(it.title, it.channelName) })
        val sim = Array(n) { DoubleArray(n) }
        val strong = Array(n) { BooleanArray(n) }
        for (i in 0 until n) for (j in i + 1 until n) {
            if (items[i].id in apart || items[j].id in apart) continue
            val link = link(items[i], items[j], words[i], words[j])
            sim[i][j] = link.dice
            sim[j][i] = link.dice
            strong[i][j] = link.strong
            strong[j][i] = link.strong
        }
        // average linkage, best pair first; ties keep the earlier groups (deterministic)
        val clusters = (0 until n).mapTo(ArrayList()) { arrayListOf(it) }
        while (true) {
            var best = GROUP_MIN
            var bi = -1
            var bj = -1
            for (a in clusters.indices) for (b in a + 1 until clusters.size) {
                val ca = clusters[a]
                val cb = clusters[b]
                if (ca.size + cb.size > MAX_MEMBERS) continue
                var sum = 0.0
                var any = false
                for (i in ca) for (j in cb) {
                    sum += sim[i][j]
                    if (strong[i][j]) any = true
                }
                if (!any) continue
                val mean = sum / (ca.size * cb.size)
                if (mean < best || (mean == best && bi >= 0)) continue
                if (span(items, ca + cb) > WINDOW_MS) continue
                best = mean
                bi = a
                bj = b
            }
            if (bi < 0) break
            clusters[bi].addAll(clusters[bj])
            clusters.removeAt(bj)
        }
        // the user's merges: union the groups their links touch
        val groupOf = IntArray(n)
        clusters.forEachIndexed { g, members -> members.forEach { groupOf[it] = g } }
        val parent = IntArray(clusters.size) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            return r
        }
        if (edits.links.isNotEmpty()) {
            val index = HashMap<String, Int>(n * 2)
            items.forEachIndexed { i, item -> index[item.id] = i }
            for (link in edits.links) {
                val a = index[link.a] ?: continue
                val b = index[link.b] ?: continue
                val ra = find(groupOf[a])
                val rb = find(groupOf[b])
                if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb)
            }
        }
        val joined = LinkedHashMap<Int, MutableList<Int>>()
        for (i in 0 until n) joined.getOrPut(find(groupOf[i])) { ArrayList() } += i
        return joined.values.map { it.sorted() }.sortedBy { it.first() }
    }

    /** One card for [members] (in the order of the items): a single, or a story newest first. */
    private fun card(members: List<YtNewsItem>): YtNewsCard {
        if (members.size == 1) return YtNewsCard.Single(members[0])
        // newest first; the same (or no) time keeps the items' order (sortedWith is stable)
        val timeline = members.sortedWith(compareBy<YtNewsItem> { it.uploadedMs <= 0L }.thenByDescending { it.uploadedMs })
        return YtNewsCard.Story(storyKey(members), timeline)
    }

    /**
     * The pager key of a story: its oldest video, so new reports joining it on a refresh keep it
     * (and the page) the same.
     */
    fun storyKey(members: List<YtNewsItem>): String {
        val oldest = members.minWithOrNull(
            compareBy<YtNewsItem> { it.uploadedMs <= 0L }.thenBy { it.uploadedMs }.thenBy { it.id },
        ) ?: return "story:"
        return "story:" + oldest.id
    }

    private fun span(items: List<YtNewsItem>, members: List<Int>): Long {
        var min = Long.MAX_VALUE
        var max = Long.MIN_VALUE
        for (i in members) {
            val t = items[i].uploadedMs
            if (t < min) min = t
            if (t > max) max = t
        }
        return max - min
    }

    /** How two videos relate: [dice] their similarity, [strong] whether they may link at all. */
    data class Link(val dice: Double, val shared: Double, val strong: Boolean)

    /** The similarity of [a] and [b] with their weighed words [wa] and [wb]. */
    fun link(a: YtNewsItem, b: YtNewsItem, wa: Map<String, Double>, wb: Map<String, Double>): Link {
        if (wa.isEmpty() || wb.isEmpty()) return Link(0.0, 0.0, false)
        var common = 0.0
        var count = 0.0
        var anchor = false
        val onlyA = ArrayList<String>()
        for ((word, w) in wa) {
            val other = wb[word]
            if (other == null) {
                onlyA += word
                continue
            }
            common += minOf(w, other)
            count += 1.0
            if (word.length >= 4 || word.any { it.isDigit() }) anchor = true
        }
        // a compound and its part: "heizungsgesetz" and "heizung", half the weight
        val used = HashSet<String>()
        for (x in onlyA) {
            if (x.length < COMPOUND_PART || x.any { it.isDigit() }) continue
            for ((y, w) in wb) {
                if (y in used || y in wa || y.length < COMPOUND_PART || y.any { it.isDigit() }) continue
                val (short, long) = if (x.length <= y.length) x to y else y to x
                if (short.length == long.length || !(long.startsWith(short) || long.endsWith(short))) continue
                common += 0.5 * minOf(wa.getValue(x), w)
                count += 0.5
                used += y
                break
            }
        }
        val total = wa.values.sum() + wb.values.sum()
        val dice = if (total <= 0.0) 0.0 else 2.0 * common / total
        val bothTimed = a.uploadedMs > 0L && b.uploadedMs > 0L
        val near = bothTimed && abs(a.uploadedMs - b.uploadedMs) <= WINDOW_MS
        val sameChannel = a.channelKey != null && a.channelKey.equals(b.channelKey, ignoreCase = true)
        val strong = near && anchor &&
            if (sameChannel) count >= 3.0 && dice >= PAIR_MIN + 0.1 else count >= 2.0 && dice >= PAIR_MIN
        return Link(if (near) dice else 0.0, count, strong)
    }

    /** The shorter word of a compound pair has at least this many letters. */
    private const val COMPOUND_PART = 6

    /**
     * The words of [title]: folded and stemmed, the stop words, generic words and the words of
     * [channel] out; each with its boost (name-like words weigh more).
     */
    fun words(title: String, channel: String? = null): Map<String, Double> {
        val skip = HashSet<String>()
        channel?.let { name -> TOKEN.split(fold(name)).filter { it.isNotEmpty() }.forEach { skip += it } }
        val text = DATE.replace(CLOCK.replace(title, " "), " ")
        val result = LinkedHashMap<String, Double>()
        var first = true
        for (raw in TOKEN.split(text)) {
            if (raw.isEmpty()) continue
            val start = first
            first = false
            val word = fold(raw)
            if (word.isEmpty() || word in STOP || word in skip) continue
            val digits = word.any { it.isDigit() }
            val upper = raw.count { it.isUpperCase() }
            val acronym = !digits && upper >= 2 && raw.length <= 6
            if (digits) {
                // a plain year or a single digit tells nothing ("2026", "5")
                if (word.all { it.isDigit() } && (word.length < 2 || YEAR.matches(word))) continue
            } else if (word.length < 3 && !acronym) {
                continue
            }
            val stem = if (digits || acronym) word else stem(word)
            if (stem in STOP || stem.length < 2) continue
            val boost = when {
                acronym -> 1.6
                digits -> 1.4
                // a capital inside the title: a name, or a German noun
                !start && raw[0].isUpperCase() && upper == 1 -> 1.2
                else -> 1.0
            }
            result[stem] = maxOf(result[stem] ?: 0.0, boost)
        }
        return result
    }

    /**
     * The words of all titles weighed by their rarity among them, 1 + ln(N/df), times their
     * boost. A word in one title weighs as one in two: a shared word must not count less than the
     * words only one side has, or small batches never group.
     */
    fun weigh(words: List<Map<String, Double>>): List<Map<String, Double>> {
        val docs = words.count { it.isNotEmpty() }.coerceAtLeast(2).toDouble()
        val df = HashMap<String, Int>()
        for (w in words) for (k in w.keys) df[k] = (df[k] ?: 0) + 1
        return words.map { w ->
            w.mapValues { (k, boost) -> boost * (1.0 + ln(docs / (df[k] ?: 1).coerceAtLeast(2))) }
        }
    }

    /** Lower case, "ß" as "ss", accents and umlaut dots gone: "Zölle" → "zolle". */
    fun fold(text: String): String =
        MARKS.replace(Normalizer.normalize(text.lowercase().replace("ß", "ss"), Normalizer.Form.NFD), "")

    /**
     * A light stemmer for German and English (after CISTEM): endings "em", "er", "nd" and then
     * "t", "e", "s", "n" come off while the word stays long enough; English "ing" and "ed" too.
     */
    fun stem(word: String): String {
        if (word.length <= 4 || word.any { !it.isLetter() }) return word
        var w = word
        if (w.length > 6 && w.endsWith("ing")) w = w.dropLast(3)
        else if (w.length > 5 && w.endsWith("ed")) w = w.dropLast(2)
        while (w.length > 4) {
            w = when {
                w.length > 5 && (w.endsWith("em") || w.endsWith("er") || w.endsWith("nd")) -> w.dropLast(2)
                w.last() in "tesn" -> w.dropLast(1)
                else -> break
            }
        }
        return w
    }

    private val MARKS = Regex("\\p{Mn}+")
    private val TOKEN = Regex("[^\\p{L}\\p{N}]+")

    /** 10.11.2026, 10.11., 10/11 */
    private val DATE = Regex("\\b\\d{1,2}[./]\\d{1,2}(?:[./]\\d{2,4})?\\.?")

    /** 20:00, 8.15 Uhr */
    private val CLOCK = Regex("\\b\\d{1,2}[:.]\\d{2}(?:\\s*uhr)?\\b", RegexOption.IGNORE_CASE)
    private val YEAR = Regex("(19|20)\\d{2}")

    /**
     * German and English function words and the generic words of news titles, folded (and in
     * both their plain and stemmed form where they differ).
     */
    private val STOP: Set<String> = run {
        val plain = """
            der die das den dem des ein eine einer eines einem einen und oder aber auch als am an auf aus bei
            bis durch fur gegen hat haben hatte ist sind war waren wird werden wurde wurden im in ins mit
            nach neben noch nun nur ob ohne seit so sich sie er es wir ihr ich du uber um unter von vor vom
            zum zur zu was wer wie wo warum weshalb wieso nicht kein keine mehr man mal schon sehr doch
            dass dies diese dieser dieses jetzt hier da dann denn weil wenn gibt geht ganz alle alles
            neue neuer neues neuen erste ersten viel viele soll sollen kann konnen muss mussen will
            bei beim sein seine ihre ihren unser euer jede jeder jedes etwa rund fast laut
            the a an and or but of to in on at by for with from as is are was were be been has have had
            will would can could should may might not no yes this that these those it its he she they we
            you his her their our your what who why how when where which after before over under into
            out up down off about than then there here more most new first last just now says say said
            vs via amid against than very all any some one two three
            live news breaking update updates heute aktuell aktuelle aktuellen nachrichten eilmeldung
            video videos interview analyse doku dokumentation reportage podcast kommentar
            liveticker ticker highlights folge teil uhr sendung ausgabe episode full watch latest today
            report reports explained shorts short tagesschau tagesthemen journal
            morgen gestern abend woche wochen tag tage jahr jahre minuten stunden
            montag dienstag mittwoch donnerstag freitag samstag sonntag
            monday tuesday wednesday thursday friday saturday sunday
            januar februar marz april mai juni juli august september oktober november dezember
            january february march june july october december
            klartext exklusiv exclusive official offiziell stream livestream
            wichtig wichtigste top best uberblick zusammenfassung recap clip
        """.trimIndent().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { fold(it) }
        (plain + plain.map { stem(it) }).toHashSet()
    }
}
