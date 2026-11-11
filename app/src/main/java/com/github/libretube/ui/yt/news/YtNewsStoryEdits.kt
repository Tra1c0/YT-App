package com.github.libretube.ui.yt.news

import kotlinx.serialization.Serializable

/** Two videos the user put into one story (the ids in order, [at] when). */
@Serializable
data class YtNewsStoryLink(val a: String, val b: String, val at: Long = 0L)

/** A video the user took out of a story: never grouped by itself again ([at] when). */
@Serializable
data class YtNewsStoryApart(val id: String, val at: Long = 0L)

/**
 * The user's own grouping of the News overlay's stories, by video ids: what they merged
 * ([links]) and what they took apart ([apart]). YtNewsStory honours it; YtNewsStories keeps it.
 */
@Serializable
data class YtNewsStoryEdits(
    val links: List<YtNewsStoryLink> = emptyList(),
    val apart: List<YtNewsStoryApart> = emptyList(),
) {
    fun isEmpty(): Boolean = links.isEmpty() && apart.isEmpty()
}

/**
 * YT: the rules behind the user's story edits, pure for the JVM tests. Merging links every pair
 * of the videos of both cards, so taking one of them out later leaves the others together;
 * taking a video out drops its links and keeps it apart. Old edits go with their videos.
 */
object YtNewsStoryEditRules {
    /** Edits are kept this long (the overlay shows a week of videos). */
    const val KEEP_MS = 8L * 24 * 60 * 60 * 1000

    /** At most this many links and apart videos are kept (the oldest go first). */
    const val CAP_LINKS = 600
    const val CAP_APART = 300

    /** [ids] (the videos of the cards merged) belong together from now on. */
    fun merge(edits: YtNewsStoryEdits, ids: List<String>, nowMs: Long): YtNewsStoryEdits {
        val members = ids.filter { it.isNotBlank() }.distinct().sorted()
        if (members.size < 2) return edits
        val fresh = LinkedHashMap<Pair<String, String>, YtNewsStoryLink>()
        for (link in edits.links) fresh[link.a to link.b] = link
        for (i in members.indices) for (j in i + 1 until members.size) {
            val pair = members[i] to members[j]
            fresh.remove(pair)
            fresh[pair] = YtNewsStoryLink(pair.first, pair.second, nowMs)
        }
        return prune(edits.copy(links = fresh.values.toList()), nowMs)
    }

    /** [id] leaves its story and stays alone (until the user merges it again). */
    fun detach(edits: YtNewsStoryEdits, id: String, nowMs: Long): YtNewsStoryEdits {
        if (id.isBlank()) return edits
        return prune(
            YtNewsStoryEdits(
                links = edits.links.filter { it.a != id && it.b != id },
                apart = edits.apart.filter { it.id != id } + YtNewsStoryApart(id, nowMs),
            ),
            nowMs,
        )
    }

    /** Without the edits older than [KEEP_MS] (or from the future), capped, the newest kept. */
    fun prune(edits: YtNewsStoryEdits, nowMs: Long): YtNewsStoryEdits {
        fun keeps(at: Long) = at <= nowMs && nowMs - at <= KEEP_MS
        val links = edits.links.filter { keeps(it.at) }.let { if (it.size > CAP_LINKS) it.sortedBy { l -> l.at }.takeLast(CAP_LINKS) else it }
        val apart = edits.apart.filter { keeps(it.at) }.let { if (it.size > CAP_APART) it.sortedBy { a -> a.at }.takeLast(CAP_APART) else it }
        return if (links.size == edits.links.size && apart.size == edits.apart.size) edits else YtNewsStoryEdits(links, apart)
    }
}
