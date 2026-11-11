package com.github.libretube.util

import androidx.media3.common.Player
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.PlaylistsHelper
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.enums.PlaylistType
import com.github.libretube.extensions.move
import com.github.libretube.extensions.runCatchingIO
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.util.PlayingQueue.queueMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Collections

object PlayingQueue {
    // queue is a synchronized list to be safely accessible from different coroutine threads
    private val queue = Collections.synchronizedList(mutableListOf<StreamItem>())
    private var currentStream: StreamItem? = null

    private val queueJobs = mutableListOf<Job>()

    private val changes = MutableStateFlow(0)

    /**
     * YT: counts the changes of the queue (and of its current video), so a screen can follow
     * it, e.g. the next video shown by the mini player.
     */
    val revision: StateFlow<Int> = changes.asStateFlow()

    private fun changed() = changes.update { it + 1 }

    /**
     * Current use case of the queue. Do NOT add any offline videos while the [queueMode] is online
     * or vice versa.
     */
    var queueMode: PlayingQueueMode = PlayingQueueMode.ONLINE

    // wrapper around PlayerHelper#repeatMode for compatibility
    var repeatMode: Int
        get() = PlayerHelper.repeatMode
        set(value) {
            PlayerHelper.repeatMode = value
            // YT: under repeat-all the next video wraps around to the first one
            changed()
        }

    private fun clearJobs() {
        queueJobs.forEach {
            it.cancel()
        }
        queueJobs.clear()
    }

    fun clear() {
        clearJobs()
        queue.clear()
        currentStream = null
        changed()
    }

    /**
     * Remove all items after the current [StreamItem] from the queue
     *
     * I.e., the current and all previous streams are kept
     */
    fun clearAfterCurrent() {
        clearJobs()
        synchronized(queue) {
            val newQueue = queue.filterIndexed { index, item -> index <= currentIndex() }
            setStreams(newQueue)
        }
    }

    /**
     * @param skipExisting Whether to skip the [streamItem] if it's already part of the queue
     */
    fun add(vararg streamItem: StreamItem, skipExisting: Boolean = false) = synchronized(queue) {
        for (stream in streamItem) {
            if ((skipExisting && contains(stream)) || stream.title.isNullOrBlank()) continue

            queue.remove(stream)
            queue.add(stream)
        }
        changed()
    }

    fun addAsNext(streamItem: StreamItem) = synchronized(queue) {
        if (currentStream == streamItem) return
        if (queue.contains(streamItem)) queue.remove(streamItem)
        queue.add(currentIndex() + 1, streamItem)
        changed()
    }

    // return the next item, or if repeating enabled and no video left, the first one of the queue
    fun getNext(): String? = synchronized(queue) {
        val nextItem = queue.getOrNull(currentIndex() + 1)
        if (nextItem != null) return nextItem.url?.toID()

        if (repeatMode == Player.REPEAT_MODE_ALL) return queue.firstOrNull()?.url?.toID()

        return null
    }

    // return the previous item, or if repeating enabled and no video left, the last one of the queue
    fun getPrev(): String? = synchronized(queue) {
        val prevItem = queue.getOrNull(currentIndex() - 1)
        if (prevItem != null) return prevItem.url?.toID()

        if (repeatMode == Player.REPEAT_MODE_ALL) return queue.lastOrNull()?.url?.toID()

        return null
    }

    fun hasPrev() = getPrev() != null

    fun hasNext() = getNext() != null

    fun updateCurrent(streamItem: StreamItem) = synchronized(queue) {
        currentStream = streamItem

        // YT: a placeholder of this video (only its id, put in by YtQueueResume.join) takes the
        // loaded details in its place
        val at = queue.indexOfFirst { it.url?.toID() == streamItem.url?.toID() }
        if (at >= 0 && queue[at].title.isNullOrBlank() && !streamItem.title.isNullOrBlank()) queue[at] = streamItem

        if (!contains(streamItem)) add(streamItem)
        changed()
    }

    fun isNotEmpty() = queue.isNotEmpty()

    fun isEmpty() = queue.isEmpty()

    fun size() = queue.size

    fun currentIndex(): Int = synchronized(queue) {
        return queue.indexOfFirst {
            it.url?.toID() == currentStream?.url?.toID()
        }.takeIf { it >= 0 } ?: 0
    }

    fun getCurrent(): StreamItem? = currentStream

    fun contains(streamItem: StreamItem) = synchronized(queue) {
        queue.any { it.url?.toID() == streamItem.url?.toID() }
    }

    // only returns a copy of the queue, no write access
    fun getStreams() = queue.toList()

    fun setStreams(streams: List<StreamItem>) = synchronized(queue) {
        queue.clear()

        queue.addAll(streams).also { changed() }
    }

    fun remove(index: Int) = synchronized(queue) {
        queue.removeAt(index)
        changed()
        return@synchronized
    }

    fun move(from: Int, to: Int) = synchronized(queue) {
        queue.move(from, to).also { changed() }
    }

    /**
     * YT: how many videos follow the current one, all of them while nothing plays. Unlike
     * [hasNext] it does not wrap around to the start under repeat-all; never negative.
     */
    fun upcomingCount(): Int = synchronized(queue) {
        YtQueueEdit.upcoming(queue, currentStream?.url?.toID()) { it.url?.toID() }
    }

    /**
     * YT: what a caller that set up the queue itself (PlayerData.keepQueue) gets instead of
     * [clearAfterCurrent] while a player runs. The previous queue's background fetches stop
     * (they would add the rest of the old playlist or channel to this queue), and each video is
     * in the queue once, the copy added last staying. Without that, "play all" of the watch
     * history adds the playing video a second time, and [currentIndex] then finds the first
     * copy, so the queue starts over and over.
     */
    fun ytKeepQueue() {
        clearJobs()
        synchronized(queue) {
            if (YtQueueEdit.keepLastOfEach(queue) { it.url?.toID() }) changed()
        }
    }

    /**
     * YT: plays [streamItem] right after the current video, or puts it first while nothing
     * plays or the queue is empty. It is in the queue once (matched by its video id); the
     * current video is never added again. [addAsNext] stays as it is (it fails on an empty
     * queue).
     * @return its index in the queue, or -1 if it was not added
     */
    fun ytInsertNext(streamItem: StreamItem): Int = synchronized(queue) {
        YtQueueEdit.insertNext(queue, streamItem, currentStream?.url?.toID()) { it.url?.toID() }
            .also { if (it >= 0) changed() }
    }

    /**
     * YT: puts [streamItem] at the end of the queue (also of an empty one). It is in the queue
     * once (matched by its video id); the current video is never added again.
     * @return its index in the queue, or -1 if it was not added
     */
    fun ytAppend(streamItem: StreamItem): Int = synchronized(queue) {
        YtQueueEdit.append(queue, streamItem, currentStream?.url?.toID()) { it.url?.toID() }
            .also { if (it >= 0) changed() }
    }

    /**
     * Adds a list of videos to the current queue while updating the position of the current stream
     * @param isMainList whether the videos are part of the list that initially has been used to
     * start the queue, either from a channel or playlist. If it's false, the current stream won't
     * be touched, since it's an independent list.
     */
    private fun addToQueueAsync(
        streams: List<StreamItem>, currentStreamItem: StreamItem? = null, isMainList: Boolean = true
    ) = synchronized(queue) {
        if (!isMainList) {
            add(*streams.toTypedArray())
            return
        }
        val currentStream = currentStreamItem ?: this.currentStream
        // if the stream already got added to the queue earlier, although it's not yet
        // been found in the playlist, remove it and re-add it later
        var reAddStream = true
        if (currentStream != null && streams.any { it.url?.toID() == currentStream.url?.toID() }) {
            queue.removeAll { it.url?.toID() == currentStream.url?.toID() }
            reAddStream = false
        }
        // add all new stream items to the queue
        add(*streams.toTypedArray())

        if (currentStream != null && reAddStream) {
            // re-add the stream to the end of the queue
            updateCurrent(currentStream)
        }
        changed()
    }

    private suspend fun fetchMoreFromPlaylist(
        playlistId: String,
        nextPage: String?,
        isMainList: Boolean
    ) {
        var playlistNextPage = nextPage
        while (playlistNextPage != null) {
            MediaServiceRepository.instance.getPlaylistNextPage(playlistId, playlistNextPage).run {
                addToQueueAsync(relatedStreams, isMainList = isMainList)
                playlistNextPage = this.nextpage
            }
        }
    }

    fun insertPlaylist(playlistId: String, playlistType: PlaylistType, newCurrentStream: StreamItem?) = runCatchingIO {
        val playlist = PlaylistsHelper.getPlaylist(playlistId, playlistType)
        val isMainList = newCurrentStream != null
        addToQueueAsync(playlist.relatedStreams, newCurrentStream, isMainList)
        if (playlist.nextpage == null) return@runCatchingIO
        fetchMoreFromPlaylist(playlistId, playlist.nextpage, isMainList)
    }.let { queueJobs.add(it) }

    private suspend fun fetchMoreFromChannel(channelId: String, nextPage: String?) {
        var channelNextPage = nextPage
        var pageIndex = 1
        while (channelNextPage != null && pageIndex < 10) {
            MediaServiceRepository.instance.getChannelNextPage(channelId, channelNextPage).run {
                addToQueueAsync(relatedStreams)
                channelNextPage = this.nextpage
                pageIndex++
            }
        }
    }

    private fun insertChannel(channelId: String, newCurrentStream: StreamItem) = runCatchingIO {
        val channel = MediaServiceRepository.instance.getChannel(channelId)
        addToQueueAsync(channel.relatedStreams, newCurrentStream)
        if (channel.nextpage == null) return@runCatchingIO
        fetchMoreFromChannel(channelId, channel.nextpage)
    }.let { queueJobs.add(it) }

    fun insertByVideoId(videoId: String) = runCatchingIO {
        val streams = MediaServiceRepository.instance.getStreams(videoId.toID())
        add(streams.toStreamItem(videoId))
    }

    fun updateQueue(
        streamItem: StreamItem,
        playlistId: String?,
        channelId: String?,
    ) {
        updateCurrent(streamItem)

        if (playlistId != null) {
            val playlistType = PlaylistsHelper.getPlaylistType(playlistId)
            insertPlaylist(playlistId, playlistType, streamItem)
        } else if (channelId != null) {
            insertChannel(channelId, streamItem)
        }
    }
}

enum class PlayingQueueMode {
    ONLINE,
    OFFLINE
}