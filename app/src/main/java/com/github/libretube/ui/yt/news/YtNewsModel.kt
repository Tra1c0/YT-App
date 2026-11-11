package com.github.libretube.ui.yt.news

/*
 * YT: the data of the News overlay, free of Android so the rules run on the JVM. The overlay
 * renders a list of [YtNewsCard]s; a card leads with one [YtNewsItem] (one video): a single
 * video, or a [YtNewsCard.Story] of several sources with a timeline (grouped by YtNewsStory).
 */

/** Where a news item comes from. */
enum class YtNewsOrigin {
    /** A channel the user marked (or the app detected) as a news channel. */
    CHANNEL,

    /** One of the user's own feed topics (YtFeedTopic): its search results or a matching title. */
    TOPIC,
}

/**
 * One video as the News overlay sees it. [id] is the video id; [description] is the raw text
 * (YtNewsText cleans it for the card); [topics] are the names of the user's topics it counts for.
 */
data class YtNewsItem(
    val id: String,
    val title: String,
    val channelId: String? = null,
    val channelName: String? = null,
    val uploadedMs: Long = 0L,
    val description: String = "",
    val durationSec: Long = 0L,
    val isLive: Boolean = false,
    val isShort: Boolean = false,
    val isUpcoming: Boolean = false,
    val origin: YtNewsOrigin = YtNewsOrigin.CHANNEL,
    val topics: List<String> = emptyList(),
) {
    /** How the channel is known: its id, else its name (as YtChannelPause and the feed rules). */
    val channelKey: String? get() = channelId?.takeIf { it.isNotBlank() } ?: channelName?.trim()?.takeIf { it.isNotEmpty() }
}

/** One of the user's active feed topics, as far as the News overlay needs it. */
data class YtNewsTopic(val name: String, val keywords: List<String>)

/**
 * A page of the News pager. [key] is stable for the pager and the progress segments, [lead] is
 * the item whose headline, picture and tap target the card shows, [items] all items behind it.
 */
sealed interface YtNewsCard {
    val key: String
    val lead: YtNewsItem
    val items: List<YtNewsItem>

    /** One video. */
    data class Single(override val lead: YtNewsItem) : YtNewsCard {
        override val key: String get() = lead.id
        override val items: List<YtNewsItem> get() = listOf(lead)
    }

    /**
     * The same story from several videos (YtNewsStory groups them): [items] is its timeline,
     * newest first, and the newest video leads (the latest state of the story; views are not
     * compared, they favour older videos). [key] comes from the oldest video, so a story keeps
     * its key and page when new reports join it.
     */
    data class Story(override val key: String, override val items: List<YtNewsItem>) : YtNewsCard {
        init {
            require(items.size >= 2) { "a story has two videos or more" }
        }

        override val lead: YtNewsItem get() = items.first()

        /** The members newest first (the same list as [items]). */
        val timeline: List<YtNewsItem> get() = items

        /** How many channels report it (a video without a channel counts as one of its own). */
        val sources: Int get() = items.map { it.channelKey?.lowercase() ?: ("\u0000" + it.id) }.distinct().size
    }
}
