package com.github.libretube.ui.yt.news

import kotlinx.serialization.Serializable

/**
 * A news channel: [id] from its URL, else known by [name] only. [auto]: the app marked it
 * itself because a video of it played with the category "News & Politics".
 */
@Serializable
data class YtNewsChannel(
    val id: String? = null,
    val name: String = "",
    val auto: Boolean = false,
)

/**
 * What is stored: the news channels, and the keys of channels the user unmarked ([refused]), so
 * the category check never marks them again.
 */
@Serializable
data class YtNewsChannelList(
    val channels: List<YtNewsChannel> = emptyList(),
    val refused: List<String> = emptyList(),
)

/**
 * YT: the rules behind the news channels (YtNewsChannels), kept apart from the preferences for
 * the JVM tests. A channel is matched by id where both sides have one (two channels may share a
 * name), else by its name, as the paused channels (YtPauseRules).
 */
object YtNewsChannelRules {
    /** At most this many channels and refused keys are kept (the oldest go first). */
    const val CAP = 300

    /** YouTube's category of news videos, as the stream info names it. */
    const val NEWS_CATEGORY = "News & Politics"

    /** Whether the stream info's [category] is news (also spelled with "and"). */
    fun isNewsCategory(category: String?): Boolean {
        val c = category?.trim()?.lowercase() ?: return false
        return c == "news & politics" || c == "news and politics" || c == "news &amp; politics"
    }

    /** The key a channel is stored under in [YtNewsChannelList.refused]: its id, else its name in lower case. */
    fun key(id: String?, name: String?): String? =
        id?.trim()?.takeIf { it.isNotEmpty() } ?: normalize(name).takeIf { it.isNotEmpty() }

    fun same(entry: YtNewsChannel, id: String?, name: String?): Boolean {
        val i = id?.trim()?.takeIf { it.isNotEmpty() }
        if (entry.id != null && i != null) return entry.id == i
        val n = normalize(name)
        return n.isNotEmpty() && normalize(entry.name) == n
    }

    fun find(list: YtNewsChannelList, id: String?, name: String?): YtNewsChannel? =
        list.channels.firstOrNull { same(it, id, name) }

    fun isNews(list: YtNewsChannelList, id: String?, name: String?): Boolean = find(list, id, name) != null

    /** [list] with the channel marked by the user (a refusal of it is forgotten). */
    fun mark(list: YtNewsChannelList, id: String?, name: String?): YtNewsChannelList {
        val key = key(id, name) ?: return list
        val entry = YtNewsChannel(id?.trim()?.takeIf { it.isNotEmpty() }, name.orEmpty().trim(), auto = false)
        return YtNewsChannelList(
            channels = (list.channels.filterNot { same(it, id, name) } + entry).takeLast(CAP),
            refused = list.refused.filterNot { it == key || it == normalize(name) },
        )
    }

    /** [list] without the channel; it is remembered as refused, so the category never marks it again. */
    fun unmark(list: YtNewsChannelList, id: String?, name: String?): YtNewsChannelList {
        val key = key(id, name) ?: return list
        return YtNewsChannelList(
            channels = list.channels.filterNot { same(it, id, name) },
            refused = (list.refused.filterNot { it == key } + key).takeLast(CAP),
        )
    }

    /** [mark] or [unmark], whichever the channel is not. */
    fun toggle(list: YtNewsChannelList, id: String?, name: String?): YtNewsChannelList =
        if (isNews(list, id, name)) unmark(list, id, name) else mark(list, id, name)

    /**
     * [list] after a video of the channel played with [category]: a news video of a channel that
     * is neither marked nor refused marks it as [YtNewsChannel.auto].
     * @return [list] itself when nothing changes
     */
    fun detect(list: YtNewsChannelList, id: String?, name: String?, category: String?): YtNewsChannelList {
        if (!isNewsCategory(category)) return list
        val key = key(id, name) ?: return list
        if (isNews(list, id, name)) return list
        if (key in list.refused || normalize(name).let { it.isNotEmpty() && it in list.refused }) return list
        val entry = YtNewsChannel(id?.trim()?.takeIf { it.isNotEmpty() }, name.orEmpty().trim(), auto = true)
        return list.copy(channels = (list.channels + entry).takeLast(CAP))
    }

    fun normalize(name: String?): String = name.orEmpty().trim().lowercase()
}
