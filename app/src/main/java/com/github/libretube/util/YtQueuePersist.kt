package com.github.libretube.util

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * YT: one video of the saved queue: what the queue sheet, the resume pill and the player need
 * (StreamItem itself carries more and is an upstream class whose fields may change).
 */
@Serializable
data class YtSavedVideo(
    /** As the queue held it: "/watch?v=…" for online items, the bare id for downloads. */
    val url: String,
    val title: String? = null,
    val uploaderName: String? = null,
    val uploaderUrl: String? = null,
    val uploaderAvatar: String? = null,
    val thumbnail: String? = null,
    /** Seconds; null or <= 0 marks a live stream (StreamItem.isLive). */
    val duration: Long? = null,
    val uploaded: Long = 0,
    val uploadedDate: String? = null,
    val views: Long? = null,
    val verified: Boolean? = null,
    val isShort: Boolean = false,
)

/** YT: the playing queue as saved for the next start of the app (YtQueueStore). */
@Serializable
data class YtSavedQueue(
    /** Format version; a file of another version is ignored and deleted. */
    val version: Int = 0,
    val items: List<YtSavedVideo> = emptyList(),
    /** The video id of the current item; null or not in [items] falls back to [currentIndex]. */
    val currentId: String? = null,
    val currentIndex: Int = 0,
    /** The current item played to its end (the queue went on, or autoplay was held). */
    val endedId: String? = null,
    /** The queue of the offline player (downloads). */
    val offline: Boolean = false,
    /** The player ran audio-only. */
    val audio: Boolean = false,
    /** Wall clock of the save, for the Diagnose page; a queue is kept whatever its age. */
    val savedAt: Long = 0,
)

/**
 * YT: the pure rules of keeping the playing queue over a restart of the app: what is saved,
 * how it is read back, when it is written, restored and deleted, and how a video started while
 * a restored queue waits joins it. No Android, tested on the JVM (YtQueuePersistTest).
 */
object YtQueuePersist {
    const val VERSION = 1

    /** At most this many videos are saved (a channel queue can hold hundreds). */
    const val MAX_ITEMS = 500

    /** Of a long queue, this many videos before the current one stay; the rest is what comes next. */
    const val MAX_BEFORE = 50

    /** A change is written this long after the last one ... */
    const val DEBOUNCE_MS = 500L

    /** ... but never later than this after the first unwritten one (a playlist loading page by page). */
    const val MAX_WAIT_MS = 3_000L

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    /**
     * The snapshot of [items] (video ids by [key]) with [currentKey] as the current one, cut to
     * [cap] videos around it. Items without an id are left out and each id is kept once, the
     * first copy (PlayingQueue.currentIndex finds the first one too). [endedKey] is kept only
     * when it is the current item. Null if nothing is left.
     */
    fun <T> build(
        items: List<T>,
        currentKey: String?,
        endedKey: String?,
        offline: Boolean,
        audio: Boolean,
        now: Long,
        key: (T) -> String?,
        convert: (T) -> YtSavedVideo?,
        cap: Int = MAX_ITEMS,
        before: Int = MAX_BEFORE,
    ): YtSavedQueue? {
        val seen = HashSet<String>()
        val kept = ArrayList<Pair<String, YtSavedVideo>>(items.size)
        for (item in items) {
            val id = key(item)?.takeIf { it.isNotBlank() } ?: continue
            if (!seen.add(id)) continue
            val video = convert(item)?.takeIf { it.url.isNotBlank() } ?: continue
            kept += id to video
        }
        if (kept.isEmpty()) return null
        val at = kept.indexOfFirst { it.first == currentKey }
        val trimmed = trim(kept, at, cap, before)
        if (trimmed.isEmpty()) return null
        val index = trimmed.indexOfFirst { it.first == currentKey }
        val current = if (index >= 0) currentKey else null
        return YtSavedQueue(
            version = VERSION,
            items = trimmed.map { it.second },
            currentId = current,
            currentIndex = index.coerceAtLeast(0),
            endedId = endedKey?.takeIf { current != null && it == current },
            offline = offline,
            audio = audio,
            savedAt = now,
        )
    }

    /**
     * At most [cap] of [list]: up to [before] items before [currentIndex] and as many after it
     * as fit (earlier ones fill up when the current one is near the end). A negative
     * [currentIndex] keeps the first [cap].
     */
    fun <T> trim(list: List<T>, currentIndex: Int, cap: Int = MAX_ITEMS, before: Int = MAX_BEFORE): List<T> {
        if (cap <= 0) return emptyList()
        if (list.size <= cap) return list
        val cur = currentIndex.coerceAtLeast(0)
        val start = (cur - before.coerceIn(0, cap - 1)).coerceAtLeast(0).coerceAtMost(list.size - cap)
        return list.subList(start, start + cap).toList()
    }

    fun encode(queue: YtSavedQueue): String = json.encodeToString(YtSavedQueue.serializer(), queue)

    /**
     * The saved queue in [text], or null if there is none or it cannot be used: not JSON, another
     * version (an older or newer app), or no videos. What is read is cleaned up: items without a
     * url go, each video stays once (by [key]), [YtSavedQueue.currentIndex] is clamped, and a
     * [YtSavedQueue.currentId] that is not in the list is dropped. Never throws.
     */
    fun decode(text: String?, key: (YtSavedVideo) -> String?): YtSavedQueue? {
        if (text.isNullOrBlank()) return null
        val raw = runCatching { json.decodeFromString(YtSavedQueue.serializer(), text) }.getOrNull() ?: return null
        if (raw.version != VERSION) return null
        val seen = HashSet<String>()
        val items = raw.items.filter { v ->
            val id = runCatching { key(v) }.getOrNull()
            v.url.isNotBlank() && !id.isNullOrBlank() && seen.add(id)
        }
        if (items.isEmpty()) return null
        val current = raw.currentId?.takeIf { id -> items.any { key(it) == id } }
        val index = if (current != null) items.indexOfFirst { key(it) == current } else raw.currentIndex.coerceIn(0, items.size - 1)
        return raw.copy(
            items = items,
            currentId = current,
            currentIndex = index,
            endedId = raw.endedId?.takeIf { current != null && it == current },
        )
    }

    /**
     * Where a restored queue goes on: its current item, or the one after it if the current one
     * had played to its end (the last one then starts over: the service begins a watched video
     * at 0).
     */
    fun resumeIndex(queue: YtSavedQueue, key: (YtSavedVideo) -> String?): Int {
        if (queue.items.isEmpty()) return 0
        val byId = queue.currentId?.let { id -> queue.items.indexOfFirst { key(it) == id } } ?: -1
        val index = if (byId >= 0) byId else queue.currentIndex.coerceIn(0, queue.items.size - 1)
        val ended = queue.endedId != null && queue.endedId == key(queue.items[index])
        return if (ended && index + 1 < queue.items.size) index + 1 else index
    }

    /**
     * How long the writer waits before it writes, the first unwritten change at [firstChangeAt]
     * and the latest at [now]: [DEBOUNCE_MS] after the latest, but at most [MAX_WAIT_MS] after
     * the first. Never negative.
     */
    fun waitMs(firstChangeAt: Long, now: Long): Long =
        minOf(DEBOUNCE_MS, (firstChangeAt + MAX_WAIT_MS - now).coerceAtLeast(0L))

    /**
     * Whether the queue is written now. Nothing is written before the start decided about the
     * saved queue ([ready]: a new queue would overwrite it before it was restored), and an empty
     * queue never is: the queue is emptied when the player service ends or a new player starts,
     * and neither may lose the saved one (only [deleteOn] deletes it). Otherwise only a change
     * since the last write ([revision] vs [written]) is written, unless [force]d.
     */
    fun shouldWrite(ready: Boolean, queueEmpty: Boolean, revision: Int, written: Int, force: Boolean): Boolean =
        ready && !queueEmpty && (force || revision != written)

    /** What deletes the saved queue. Closing the player, the app or the task never does. */
    enum class Delete {
        /** The user cleared the queue (the queue sheet's "clear"). */
        USER_CLEARED,

        /** The last item ended and nothing comes after it. */
        FINISHED,
    }

    /** What the end of an item means for the saved queue. */
    enum class EndAction {
        /** Not a real end (the player was emptied to load another video) or repeat-one. */
        NONE,

        /** The queue goes on (or autoplay is held): the saved current item counts as watched. */
        ITEM_ENDED,

        /** Nothing comes after it: the queue is done and the saved one is deleted. */
        FINISHED,
    }

    /**
     * The player reached its end. [timelineEmpty]: the player was emptied (navigateVideo clears
     * it before loading the next video, which media3 reports as an end too). [hasNext]: the
     * queue has a next item (PlayingQueue.getNext, which wraps under repeat-all). Related videos
     * that autoplay may follow a finished queue: they start a new one, saved again.
     */
    fun endAction(timelineEmpty: Boolean, repeatOne: Boolean, hasNext: Boolean): EndAction = when {
        timelineEmpty || repeatOne -> EndAction.NONE
        hasNext -> EndAction.ITEM_ENDED
        else -> EndAction.FINISHED
    }

    /**
     * Whether the saved queue is put back into the empty queue now: something is saved, the
     * queue holds nothing (nothing else set one) and no player service runs (its queue is the
     * live one).
     */
    fun restoreCandidate(hasSaved: Boolean, queueEmpty: Boolean, playerAlive: Boolean): Boolean =
        hasSaved && queueEmpty && !playerAlive

    /** Where a video started while a restored queue waits goes into it. */
    enum class JoinPlace {
        /** Right before the restored current item: it plays now, the restored queue follows. */
        BEFORE_CURRENT,

        /** At the end: its own playlist or channel loads after it (the service does that only at the end). */
        AT_END,
    }

    /** [hasListContext]: the video was started from a playlist or channel that fills the queue after it. */
    fun joinPlace(hasListContext: Boolean): JoinPlace =
        if (hasListContext) JoinPlace.AT_END else JoinPlace.BEFORE_CURRENT

    /**
     * [list] with [items] put in at [place] relative to the item [anchorKey]: other copies of
     * them go first, so every video is in the list once. If the anchor is one of [items] the
     * list keeps its place. A missing anchor counts as the start of the list.
     */
    fun <T> join(list: List<T>, items: List<T>, anchorKey: String?, place: JoinPlace, key: (T) -> String?): List<T> {
        val seen = HashSet<String>()
        val adding = items.filter { item -> key(item)?.let(seen::add) ?: false }
        if (adding.isEmpty()) return list
        val ids = adding.mapNotNull(key).toHashSet()
        val rest = list.filter { key(it) !in ids }
        if (place == JoinPlace.AT_END) return rest + adding
        val anchorAt = if (anchorKey == null) -1 else list.indexOfFirst { key(it) == anchorKey }
        val insertAt = if (anchorAt < 0) 0 else list.subList(0, anchorAt).count { key(it) !in ids }
        return rest.subList(0, insertAt) + adding + rest.subList(insertAt, rest.size)
    }

    /** The resume pill names the count only for a real queue, not for a single video. */
    fun showsCount(size: Int): Boolean = size > 1
}
