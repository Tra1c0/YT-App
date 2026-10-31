package com.github.libretube.ui.yt

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.ui.yt.feed.YtQueueMove
import com.github.libretube.ui.yt.feed.YtQueueRule
import com.github.libretube.util.PlayingQueue
import com.github.libretube.util.PlayingQueueMode

/** What handing a card to the queue did: [move], and its place among the videos still to come (1 = next). */
data class YtQueued(val move: YtQueueMove, val position: Int)

/**
 * YT: the one way the home screens hand videos to the queue. A card swiped to the right goes
 * to the end of the queue ([appendToEnd]); while nothing plays it waits in a [draft] with a
 * pill "N in der Warteschlange · Abspielen" instead, and nothing starts by itself. A card
 * dropped on the mini player follows the user's rule ([dropOnMini]). A tap on a card keeps
 * what the user queued ([playKeepingQueue]): the tapped video plays now, the queued ones after
 * it.
 */
object YtQueue {
    /** Cards queued while nothing plays, in their order; a tap on a card or "Abspielen" starts them. */
    val draft = mutableStateListOf<StreamItem>()

    /** Tokens still flying to the draft pill: the pill counts a card once its token landed. */
    var draftInFlight by mutableIntStateOf(0)

    /** Counts the tokens that landed on the draft pill; each one makes the pill take it in. */
    var draftLanded by mutableIntStateOf(0)

    /** Where the draft pill is, in window px, while it shows (the tokens fly there). */
    var draftPillBounds by mutableStateOf<Rect?>(null)

    /** The videos the user queued, so a tap on another card keeps them. */
    private val queued = HashSet<String>()

    /** True while a player plays online videos; an offline queue takes no online videos. */
    fun hasPlayer(): Boolean =
        PlayingQueue.getCurrent() != null && PlayingQueue.queueMode == PlayingQueueMode.ONLINE

    private fun currentId(): String? = PlayingQueue.getCurrent()?.url?.toID()

    private fun idOf(video: StreamItem): String? = video.url?.toID()

    /** The draft count the pill shows: what is queued and has landed. */
    fun draftShown(): Int = (draft.size - draftInFlight).coerceAtLeast(0)

    /**
     * A card swiped to the right: to the end of the queue while a player runs, else into the
     * draft. Rejected for the video that plays.
     */
    fun appendToEnd(video: StreamItem): YtQueued {
        val id = idOf(video)
        return when (YtQueueRule.swipe(hasPlayer(), currentId(), id)) {
            YtQueueMove.APPEND -> {
                if (PlayingQueue.ytAppend(video) < 0) return reject(id, "append")
                queued += id!!
                YtQueued(YtQueueMove.APPEND, PlayingQueue.upcomingCount())
                    .also { YtTestHooks.log("YtQueue") { "append pos=${it.position} id=$id" } }
            }
            YtQueueMove.DRAFT -> {
                val next = YtQueueRule.addToDraft(draft.toList(), video, ::idOf)
                draft.clear()
                draft.addAll(next)
                YtQueued(YtQueueMove.DRAFT, next.size)
                    .also { YtTestHooks.log("YtQueue") { "draft n=${next.size} id=$id" } }
            }
            else -> reject(id, "append")
        }
    }

    /**
     * A card dropped on the docked mini player, the user's rule: with nothing still to come it
     * plays next, else it goes to the end. Rejected without a player or for the video that
     * plays. On success it sets YtDock.lastDrop and counts YtDock.accepted, which the mini
     * player answers with its accept animation and label.
     * @return what the drop did, null if it was rejected
     */
    fun dropOnMini(video: StreamItem): YtDropResult? {
        val id = idOf(video)
        val move = YtQueueRule.drop(hasPlayer(), currentId(), id, PlayingQueue.upcomingCount())
        val index = when (move) {
            YtQueueMove.NEXT -> PlayingQueue.ytInsertNext(video)
            YtQueueMove.APPEND -> PlayingQueue.ytAppend(video)
            else -> -1
        }
        if (index < 0 || id == null) {
            reject(id, "drop")
            return null
        }
        queued += id
        val next = move == YtQueueMove.NEXT
        val result = YtDropResult(next = next, position = if (next) 1 else PlayingQueue.upcomingCount(), title = video.title.orEmpty())
        announce(result)
        YtTestHooks.log("YtQueue") { if (next) "drop next id=$id" else "drop append pos=${result.position} id=$id" }
        return result
    }

    /** Lets the docked mini player show what a video handed to it did. */
    fun announce(result: YtDropResult) {
        YtDock.lastDrop = result
        YtDock.accepted++
    }

    /**
     * A tap on a card. With a draft waiting and nothing playing, the tapped video starts and the
     * draft follows; with videos the user queued still to come, the tapped video plays now and
     * they follow; otherwise the video plays as before (the queue then starts anew).
     */
    fun playKeepingQueue(context: Context, video: StreamItem, seconds: Long = 0) {
        val id = idOf(video) ?: return
        if (draft.isNotEmpty() && !hasPlayer()) {
            val list = YtQueueRule.startWithDraft(video, draft.toList(), ::idOf)
            clearDraft()
            queued.clear()
            list.drop(1).mapNotNullTo(queued, ::idOf)
            PlayingQueue.setStreams(list)
            YtTestHooks.log("YtQueue") { "start draft n=${list.size} id=$id" }
            NavigationHelper.navigateVideo(context, PlayerData(id, timestamp = seconds, keepQueue = true))
            return
        }
        if (hasPlayer() && keepsQueue()) {
            // right after the current video, so the queued ones follow the tapped one
            PlayingQueue.ytInsertNext(video)
            YtTestHooks.log("YtQueue") { "play keeping queue id=$id" }
            NavigationHelper.navigateVideo(context, PlayerData(id, timestamp = seconds, keepQueue = true))
            return
        }
        queued.clear()
        NavigationHelper.navigateVideo(context, PlayerData(id, timestamp = seconds))
    }

    /** "Abspielen" on the draft pill: the draft plays from its first video. */
    fun playDraft(context: Context) {
        val first = draft.firstOrNull() ?: return
        playKeepingQueue(context, first)
    }

    fun clearDraft() {
        draft.clear()
        draftInFlight = 0
    }

    /**
     * A player started some other way while a draft waited (search, a channel page): the draft
     * goes to the end of its queue, as the user had asked for these videos.
     */
    fun mergeDraftIntoPlayer() {
        if (draft.isEmpty() || !hasPlayer()) return
        val videos = draft.toList()
        clearDraft()
        for (video in videos) {
            if (PlayingQueue.ytAppend(video) >= 0) idOf(video)?.let(queued::add)
        }
        YtTestHooks.log("YtQueue") { "draft merged n=${videos.size}" }
    }

    private fun keepsQueue(): Boolean {
        val streams = PlayingQueue.getStreams()
        val upcoming = streams.drop(PlayingQueue.currentIndex() + 1).map { idOf(it) }
        return YtQueueRule.keepsQueue(upcoming, queued)
    }

    private fun reject(id: String?, what: String): YtQueued {
        YtTestHooks.log("YtQueue") { "$what rejected id=$id player=${hasPlayer()}" }
        return YtQueued(YtQueueMove.REJECT, 0)
    }
}
