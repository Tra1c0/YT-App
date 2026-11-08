package com.github.libretube.ui.yt

import java.text.Normalizer
import java.util.Locale

/**
 * YT: the pure logic of the guide (filtering and which sections are open), on plain strings so it
 * is tested on the JVM. The texts come from [YtGuide.resolve].
 */
object YtGuideRules {
    data class Entry(val key: String, val title: String, val body: String, val where: String? = null)

    data class Group(val key: String, val title: String, val entries: List<Entry>, val note: String? = null)

    /**
     * Lower case, accents and umlauts folded ("ä" → "a", "ß" → "ss"), runs of white space as one
     * blank: "Lautstarke" and "LAUTSTÄRKE" both find "Lautstärke".
     */
    fun normalize(text: String): String {
        val folded = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(MARKS, "")
            .replace("ß", "ss")
        return folded.trim().replace(SPACES, " ")
    }

    /** The words of [query]; an empty list means "no filter". */
    fun terms(query: String): List<String> =
        normalize(query).split(' ').filter { it.isNotEmpty() }.distinct()

    /**
     * Whether [entry] of the section titled [groupTitle] matches: every term is found in the
     * entry's title, text or settings path, or in the section title ("player" lists the player).
     */
    fun matches(entry: Entry, groupTitle: String, terms: List<String>): Boolean {
        if (terms.isEmpty()) return true
        val haystack = normalize(listOfNotNull(groupTitle, entry.title, entry.body, entry.where).joinToString(" "))
        return terms.all { haystack.contains(it) }
    }

    /** [groups] with only the matching entries; sections left empty drop out, order is kept. */
    fun filter(groups: List<Group>, query: String): List<Group> {
        val terms = terms(query)
        if (terms.isEmpty()) return groups
        return groups.mapNotNull { group ->
            val kept = group.entries.filter { matches(it, group.title, terms) }
            if (kept.isEmpty()) null else group.copy(entries = kept)
        }
    }

    /**
     * Whether the section [key] at [index] shows its entries: while searching every hit is open;
     * otherwise what the user chose ([chosen]), or only the first section.
     */
    fun expanded(key: String, index: Int, query: String, chosen: Map<String, Boolean>): Boolean {
        if (terms(query).isNotEmpty()) return true
        return chosen[key] ?: (index == 0)
    }

    /** All entries of [groups], counted. */
    fun count(groups: List<Group>): Int = groups.sumOf { it.entries.size }

    private val MARKS = Regex("\\p{Mn}+")
    private val SPACES = Regex("\\s+")
}
