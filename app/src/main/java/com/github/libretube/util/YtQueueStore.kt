package com.github.libretube.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.extensions.toID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** YT: a saved queue put back into PlayingQueue that waits for a player; [announce]: the home shows the resume pill. */
data class YtRestored(val announce: Boolean)

/**
 * YT: keeps the playing queue over a restart of the app (closed, swiped from the recent apps,
 * killed by Android, a reboot, an update). The one saved copy of the queue: the queue sheet, the
 * resume pill (YtQueueResume) and the mini player restore (YtMiniRestore) all put back this one.
 *
 * Saving: every change of PlayingQueue (add, remove, move, clear, the next video) is written
 * [YtQueuePersist.DEBOUNCE_MS] after it, at most [YtQueuePersist.MAX_WAIT_MS] late, as JSON in
 * the app's private files (temp file + rename, on one background thread), at most
 * [YtQueuePersist.MAX_ITEMS] videos. [flush] writes at once (service end, memory trim, soft close,
 * the app going to the background). An empty queue is never written, so the player's end (which
 * empties the queue) keeps the saved one; it goes only on [delete] (the user cleared the queue,
 * or it played to its end). Positions are not saved here: the watch position ledger
 * (YtPositionLedger) has them, and the service starts each video there.
 *
 * Restoring: at the start of the process (and when the main activity is created anew while no
 * player runs) the saved queue goes back into the empty PlayingQueue, its current item set, and
 * nothing plays ([restored] is set; the home shows the resume pill). A video started meanwhile
 * joins it (YtQueueResume.join) instead of replacing it.
 */
object YtQueueStore {
    private const val TAG = "YtQueueStore"
    private const val FILE_NAME = "yt_queue.json"

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "yt-queue-store").apply { isDaemon = true } }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var dir: File? = null
    private val lock = Any()

    @Volatile
    private var loaded = false

    /** The saved queue as on disk (null: none). Main thread after the load. */
    @Volatile
    var saved: YtSavedQueue? = null
        private set

    /** The start decided about the saved queue: from now on changes are written. */
    @Volatile
    private var ready = false

    /** The queue revision that is on disk, or that must not be written (cleared, restored). */
    private var written = -1

    /** The current item played to its end (see YtQueuePersist.EndAction.ITEM_ENDED). */
    private var endedId: String? = null

    /** The player runs audio-only. */
    @Volatile
    private var audio = false

    /** Player services alive (created, not yet gone); main thread. */
    private val services: MutableSet<Any> = Collections.newSetFromMap(WeakHashMap())

    private val restoredState = MutableStateFlow<YtRestored?>(null)

    /** A restored queue waiting in PlayingQueue for a player (null: none). */
    val restored: StateFlow<YtRestored?> = restoredState.asStateFlow()

    /** True while a restored queue waits for a player. */
    val pending: Boolean get() = restoredState.value != null

    private fun key(item: StreamItem): String? = item.url?.toID()?.takeIf { it.isNotBlank() }

    private fun key(video: YtSavedVideo): String? = video.url.toID().takeIf { it.isNotBlank() }

    /**
     * Once, from the Application: follows the queue and reads the saved one in the background;
     * then (on the main thread) puts it back if nothing else set a queue yet.
     */
    fun start(context: Context) {
        if (dir != null) return
        dir = context.applicationContext.filesDir
        scope.launch {
            var firstAt = 0L
            PlayingQueue.revision.collectLatest {
                val now = SystemClock.elapsedRealtime()
                if (firstAt == 0L) firstAt = now
                delay(YtQueuePersist.waitMs(firstAt, now))
                firstAt = 0L
                write(force = false, sync = false)
            }
        }
        io.execute {
            ensureLoaded()
            main.post {
                if (!applyIfCandidate(announce = true)) write(force = false, sync = false)
            }
        }
    }

    /** Reads the saved queue once (blocks while the background read runs). */
    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            saved = read()
            loaded = true
        }
    }

    /**
     * Puts the saved queue back into the empty PlayingQueue if no player runs (main thread).
     * A restored queue that still waits is put back again if a dying service emptied the queue
     * meanwhile. [announce]: the home shows the resume pill. True if a restored queue waits now.
     */
    fun applyIfCandidate(announce: Boolean): Boolean {
        ensureLoaded()
        ready = true
        val queue = saved ?: return false
        val state = restoredState.value
        if (state != null) {
            if (PlayingQueue.isEmpty()) put(queue)
            if (announce && !state.announce) restoredState.value = YtRestored(announce = true)
            return true
        }
        val empty = PlayingQueue.isEmpty() && PlayingQueue.getCurrent() == null
        if (!YtQueuePersist.restoreCandidate(hasSaved = true, queueEmpty = empty, playerAlive = services.isNotEmpty())) return false
        put(queue)
        restoredState.value = YtRestored(announce)
        return true
    }

    /**
     * [applyIfCandidate] without waiting for the file: at a cold start the main activity is
     * created before the background read is done, and the read itself puts the queue back then.
     */
    fun applyIfLoaded(announce: Boolean): Boolean = loaded && applyIfCandidate(announce)

    /** Fills PlayingQueue with [queue], its resume item as the current one. Nothing plays. */
    private fun put(queue: YtSavedQueue) {
        val items = queue.items.map(::toStreamItem)
        if (items.isEmpty()) return
        PlayingQueue.queueMode = if (queue.offline) PlayingQueueMode.OFFLINE else PlayingQueueMode.ONLINE
        PlayingQueue.setStreams(items)
        PlayingQueue.updateCurrent(items[YtQueuePersist.resumeIndex(queue, ::key).coerceIn(0, items.size - 1)])
        // the same queue as on disk: no need to write it again
        written = PlayingQueue.revision.value
    }

    /**
     * The mini player restore (YtMiniRestore) starts the closed video again: the queue goes back
     * from this saved copy. False if none is saved (the queue was cleared or played to its end).
     */
    fun putBackForRestore(): Boolean {
        ensureLoaded()
        val queue = saved ?: return false
        val items = queue.items.map(::toStreamItem)
        if (items.isEmpty()) return false
        PlayingQueue.setStreams(items)
        return true
    }

    /** The restored queue no longer waits (a player took it, or the user cleared it). */
    fun endRestored() {
        restoredState.value = null
    }

    /** The resume pill was swiped away: the restored queue stays, only the pill goes. */
    fun hidePill() {
        restoredState.value?.let { if (it.announce) restoredState.value = YtRestored(announce = false) }
    }

    /** A player service was created; while one runs its queue is the live one. */
    fun serviceUp(service: Any) {
        services += service
        endRestored()
    }

    /**
     * That player service is gone (may be called more than once: Android calls onDestroy again
     * after the service stopped itself). True the first time, unless another player service
     * runs: only then does the end empty the queue.
     */
    fun serviceDown(service: Any): Boolean = services.remove(service) && services.isEmpty()

    /** The player plays audio-only ([on]) or with video; saved with the queue. */
    fun playerMode(on: Boolean) {
        audio = on
    }

    /**
     * The player reached the end of its item (main thread). A finished queue is deleted; else
     * the current item is marked as played, so a restore goes on with the next one.
     */
    fun itemEnded(timelineEmpty: Boolean, repeatOne: Boolean) {
        when (YtQueuePersist.endAction(timelineEmpty, repeatOne, PlayingQueue.getNext() != null)) {
            YtQueuePersist.EndAction.NONE -> Unit
            YtQueuePersist.EndAction.FINISHED -> delete(YtQueuePersist.Delete.FINISHED)
            YtQueuePersist.EndAction.ITEM_ENDED -> {
                endedId = PlayingQueue.getCurrent()?.let(::key)
                write(force = true, sync = false)
            }
        }
    }

    /** Writes the queue now if it changed (service end: [sync], so it is on disk before the process may go). */
    fun flush(sync: Boolean = false) = write(force = false, sync = sync)

    /**
     * The saved queue goes: the user cleared the queue, or it played to its end. The queue as it
     * is now is not written again; the next change is.
     */
    fun delete(reason: YtQueuePersist.Delete) {
        Log.d(TAG, "delete: $reason")
        saved = null
        endedId = null
        written = PlayingQueue.revision.value
        endRestored()
        val target = file() ?: return
        io.execute { runCatching { target.delete() } }
    }

    private fun write(force: Boolean, sync: Boolean) {
        val revision = PlayingQueue.revision.value
        val streams = PlayingQueue.getStreams()
        if (!YtQueuePersist.shouldWrite(ready, streams.isEmpty(), revision, written, force)) return
        val current = PlayingQueue.getCurrent()?.let(::key)
        if (endedId != current) endedId = null
        val queue = YtQueuePersist.build(
            items = streams,
            currentKey = current,
            endedKey = endedId,
            offline = PlayingQueue.queueMode == PlayingQueueMode.OFFLINE,
            audio = audio,
            now = System.currentTimeMillis(),
            key = ::key,
            convert = ::toSaved,
        ) ?: return
        saved = queue
        written = revision
        val target = file() ?: return
        val task = Runnable { writeAtomically(target, YtQueuePersist.encode(queue)) }
        if (sync) {
            runCatching { io.submit(task).get(2, TimeUnit.SECONDS) }
        } else {
            io.execute(task)
        }
    }

    private fun file(): File? = dir?.let { File(it, FILE_NAME) }

    /** The saved queue from disk; an unreadable or unknown file is deleted (never a crash). */
    private fun read(): YtSavedQueue? {
        val target = file() ?: return null
        return runCatching {
            File(target.parentFile, "$FILE_NAME.tmp").delete()
            if (!target.exists()) return@runCatching null
            val queue = YtQueuePersist.decode(target.readText(), ::key)
            if (queue == null) {
                Log.w(TAG, "saved queue unreadable or of another version, deleted")
                target.delete()
            }
            queue
        }.getOrElse {
            Log.w(TAG, "reading the saved queue failed", it)
            runCatching { target.delete() }
            null
        }
    }

    private fun writeAtomically(target: File, text: String) {
        runCatching {
            val tmp = File(target.parentFile, "$FILE_NAME.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray())
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) error("rename failed")
            }
        }.onFailure { Log.w(TAG, "saving the queue failed", it) }
    }

    fun toStreamItem(video: YtSavedVideo) = StreamItem(
        url = video.url,
        type = StreamItem.TYPE_STREAM,
        title = video.title,
        thumbnail = video.thumbnail,
        uploaderName = video.uploaderName,
        uploaderUrl = video.uploaderUrl,
        uploaderAvatar = video.uploaderAvatar,
        uploadedDate = video.uploadedDate,
        duration = video.duration,
        views = video.views,
        uploaderVerified = video.verified,
        uploaded = video.uploaded,
        isShort = video.isShort,
    )

    private fun toSaved(item: StreamItem): YtSavedVideo? {
        val url = item.url?.takeIf { it.isNotBlank() } ?: return null
        return YtSavedVideo(
            url = url,
            title = item.title,
            uploaderName = item.uploaderName,
            uploaderUrl = item.uploaderUrl,
            uploaderAvatar = item.uploaderAvatar,
            thumbnail = item.thumbnail,
            duration = item.duration,
            uploaded = item.uploaded,
            uploadedDate = item.uploadedDate,
            views = item.views,
            verified = item.uploaderVerified,
            isShort = item.isShort,
        )
    }
}
