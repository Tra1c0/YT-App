package com.github.libretube.ui.yt.feed

/** What a card the user hands to the queue does. */
enum class YtQueueMove {
    /** Plays right after the current video. */
    NEXT,

    /** Goes to the end of the queue. */
    APPEND,

    /** Nothing plays: it waits in the draft until the user starts it. */
    DRAFT,

    /** Taken back: no player to take it, or it is the video that plays. */
    REJECT,
}

/**
 * YT: the queue rules of the home screens, kept apart from PlayingQueue (which needs Android) so
 * they can be tested on the JVM. Videos are matched by their id ([key]).
 */
object YtQueueRule {
    /**
     * A card dropped on the mini player, the user's rule: with nothing still to come it plays
     * next, else it goes to the end. Without a player, or onto the video that plays, it is taken
     * back.
     */
    fun drop(hasPlayer: Boolean, currentId: String?, videoId: String?, upcoming: Int): YtQueueMove = when {
        !hasPlayer || videoId == null || videoId == currentId -> YtQueueMove.REJECT
        upcoming <= 0 -> YtQueueMove.NEXT
        else -> YtQueueMove.APPEND
    }

    /**
     * A card swiped to the right: to the end of the queue while a player runs, else into the
     * draft (nothing starts by itself).
     */
    fun swipe(hasPlayer: Boolean, currentId: String?, videoId: String?): YtQueueMove = when {
        videoId == null -> YtQueueMove.REJECT
        !hasPlayer -> YtQueueMove.DRAFT
        videoId == currentId -> YtQueueMove.REJECT
        else -> YtQueueMove.APPEND
    }

    /**
     * [draft] with [item] at its end, once (another copy goes first).
     * @return the new draft, or [draft] itself if [item] has no key
     */
    fun <T> addToDraft(draft: List<T>, item: T, key: (T) -> String?): List<T> {
        val id = key(item) ?: return draft
        return draft.filter { key(it) != id } + item
    }

    /** The queue a tap starts while a draft waits: the tapped video, then the draft without it. */
    fun <T> startWithDraft(tapped: T, draft: List<T>, key: (T) -> String?): List<T> {
        val id = key(tapped)
        return listOf(tapped) + draft.filter { id == null || key(it) != id }
    }

    /**
     * Whether a tap on another card keeps the queue: true while at least one of the videos still
     * to come ([upcoming], their ids) was queued by the user ([queued]). A queue that only holds
     * what a playlist or channel filled in is cut back as before.
     */
    fun keepsQueue(upcoming: List<String?>, queued: Set<String>): Boolean =
        queued.isNotEmpty() && upcoming.any { it != null && it in queued }
}
