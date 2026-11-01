package com.github.libretube.ui.models

import android.os.SystemClock
import android.util.Log
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.obj.ChapterSegment
import com.github.libretube.api.obj.Comment
import com.github.libretube.api.obj.CommentsPage
import com.github.libretube.api.obj.Streams
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.helpers.CommentMoments
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.SceneMode
import com.github.libretube.helpers.ScenePlayback
import com.github.libretube.helpers.SceneVerdict
import com.github.libretube.helpers.YtCommentMoments
import com.github.libretube.helpers.YtSceneUnlock
import com.github.libretube.ui.yt.YtClock
import com.github.libretube.ui.yt.YtPlaybackClock
import com.github.libretube.ui.yt.YtSeek
import com.github.libretube.ui.yt.YtTestHooks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** A comment and the times it names. */
data class SceneComment(val comment: Comment, val moments: CommentMoments)

/**
 * YT (comments follow the scene): the comment pages of the video playing, each fetched once and
 * shared by the classic sheet's list, the scene index and the Side and Movo pages (which used to
 * fetch page 1 a second time). A page that failed is fetched anew when asked for again.
 */
class CommentPageCache(private val scope: CoroutineScope) {
    private val lock = Any()
    private var current: String? = null
    private var job: Job = SupervisorJob(scope.coroutineContext[Job])
    private val pending = HashMap<String, Deferred<CommentsPage>>()
    private val done = HashMap<String, CommentsPage>()

    /** Gets every page fetched for a video, on a background thread. */
    var onPage: ((videoId: String, page: CommentsPage) -> Unit)? = null

    /** The page of [videoId] that [key] names (null: the first one), from the cache or fetched. */
    suspend fun page(videoId: String, key: String?): CommentsPage {
        val slot = key ?: FIRST
        val deferred = synchronized(lock) {
            if (videoId != current) clearLocked(videoId)
            pending.getOrPut(slot) {
                CoroutineScope(job + Dispatchers.IO).async {
                    val page = fetch(videoId, key)
                    synchronized(lock) { if (current == videoId) done[slot] = page }
                    onPage?.invoke(videoId, page)
                    page
                }
            }
        }
        return try {
            deferred.await()
        } catch (e: Exception) {
            // a page that failed is asked for anew; one still on its way stays
            if (!deferred.isActive) synchronized(lock) { if (pending[slot] === deferred) pending.remove(slot) }
            throw e
        }
    }

    /** Whether the page that [key] names is here already. */
    fun has(videoId: String, key: String?): Boolean =
        synchronized(lock) { current == videoId && done.containsKey(key ?: FIRST) }

    /** The pages of [videoId] read so far. */
    fun pagesRead(videoId: String): Int = synchronized(lock) { if (current == videoId) done.size else 0 }

    /** The pages of [videoId] that are here. */
    fun pagesOf(videoId: String): List<CommentsPage> =
        synchronized(lock) { if (current == videoId) done.values.toList() else emptyList() }

    /** Forgets everything, except the pages of [keep] if they are the ones here. */
    fun clear(keep: String? = null) {
        synchronized(lock) {
            if (keep == null || current != keep) clearLocked(null)
        }
    }

    private fun clearLocked(next: String?) {
        job.cancel()
        job = SupervisorJob(scope.coroutineContext[Job])
        pending.clear()
        done.clear()
        current = next
    }

    private suspend fun fetch(videoId: String, key: String?): CommentsPage {
        fixture?.invoke(videoId, key)?.let { return it }
        return if (key == null) {
            MediaServiceRepository.instance.getComments(videoId)
        } else {
            MediaServiceRepository.instance.getCommentsNextPage(videoId, key)
        }
    }

    companion object {
        private const val FIRST = "\u0000first"

        /** Pages of the emulator checks instead of the network (YtSceneTestHooks), null on a phone. */
        @Volatile
        var fixture: ((videoId: String, key: String?) -> CommentsPage?)? = null
    }
}

/**
 * YT (comments follow the scene): what the comments of the video playing may show while it plays.
 * Lives in the activity's CommentsViewModel, so the classic sheet, its replies and the Side and
 * Movo pages share one state:
 * - [cache]: the video's comment pages, fetched once;
 * - the index: every comment read so far with the times it names ([indexed]); in the scene mode
 *   further pages are read in the background, within a budget ([autoScan], [scanMore]);
 * - how far the viewer has seen ([YtSceneUnlock]), fed by YtSceneModule from YtPlaybackClock;
 * - [epoch]: grows only when a verdict may have changed (a time was passed, a comment tapped open,
 *   another mode or video), so the lists rebind then and not on every tick of the clock.
 * Main thread, except where a function says otherwise.
 */
class CommentScene(private val scope: CoroutineScope) {
    val cache = CommentPageCache(scope)

    private val chosen = MutableStateFlow(readMode())

    /** The mode the viewer chose (setting and chips), also for a live stream. */
    val chosenMode: StateFlow<SceneMode> = chosen.asStateFlow()

    private val active = MutableStateFlow(chosen.value)

    /** The mode for the video playing: off for a live stream. */
    val mode: StateFlow<SceneMode> = active.asStateFlow()

    private val epochs = MutableStateFlow(0)
    val epoch: StateFlow<Int> = epochs.asStateFlow()

    private val steps = MutableStateFlow(0L)

    /** The step of the position in the scene mode (YtCommentMoments.sceneStep): the scene list follows it. */
    val sceneStep: StateFlow<Long> = steps.asStateFlow()

    private val items = MutableStateFlow<List<SceneComment>>(emptyList())

    /** Every comment read so far, in the order of the pages. */
    val indexed: StateFlow<List<SceneComment>> = items.asStateFlow()

    private val scanningState = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = scanningState.asStateFlow()

    private val exhaustedState = MutableStateFlow(false)

    /** All pages of the video were read. */
    val exhausted: StateFlow<Boolean> = exhaustedState.asStateFlow()

    @Volatile
    var videoId: String? = null
        private set

    @Volatile
    private var durationMs = 0L
    private var chapterStarts: List<Long> = emptyList()

    /** The chapters of the video playing, sorted. */
    var chapters: List<ChapterSegment> = emptyList()
        private set

    var isLive = false
        private set

    private val unlock = YtSceneUnlock()

    // false: the next reading of the clock starts the mark there (a mode switched on)
    private var tracking = false

    @Volatile
    private var playback = ScenePlayback()

    private val moments = ConcurrentHashMap<String, CommentMoments>()
    private val index = LinkedHashMap<String, SceneComment>()
    private val revealed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var boundariesDirty = false
    private var boundaries = LongArray(0)
    private var nextBoundary = Long.MAX_VALUE
    private var scanJob: Job? = null

    init {
        cache.onPage = ::onPage
    }

    /** Where the playback is now: position, how far it was seen, length and chapters. */
    fun playback(): ScenePlayback = playback

    /** The details of a video arrived (YtSceneModule.onVideo): a new one starts from nothing. */
    fun onVideo(videoId: String, streams: Streams) {
        val same = videoId == this.videoId
        // pages of this video asked for a moment earlier (a design page) stay
        if (!same) clear(keepPagesOf = videoId)
        this.videoId = videoId
        durationMs = streams.duration.coerceAtLeast(0L) * 1000
        chapters = streams.chapters.sortedBy { it.start }
        chapterStarts = chapters.map { it.start * 1000 }
        isLive = streams.isLive
        if (!same) cache.pagesOf(videoId).forEach { onPage(videoId, it) }
        chosen.value = readMode()
        active.value = if (isLive) SceneMode.OFF else chosen.value
        if (!same) {
            // from the start: the readings that follow carry the mark (a resumed video waits as a jump)
            unlock.reset()
            playback = ScenePlayback(0L, 0L, durationMs, chapterStarts)
            tracking = active.value != SceneMode.OFF
        }
        bump()
        if (same) return
        // where the viewer stopped last time counts as seen
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching { Database.watchPositionDao().findById(videoId)?.position }.getOrNull()
            } ?: return@launch
            if (this@CommentScene.videoId == videoId && unlock.raiseTo(saved)) {
                YtTestHooks.log(TAG) { "seen up to the saved position $saved" }
                update(playback.positionMs)
            }
        }
    }

    /** A reading of YtPlaybackClock (YtSceneModule collects it while a mode is on). */
    fun onClock(clock: YtClock, wallMs: Long) {
        if (clock.videoId == null || clock.videoId != videoId) return
        if (tracking) {
            unlock.tick(clock.positionMs, clock.playing, clock.speed, wallMs)
        } else {
            unlock.restart(clock.positionMs, wallMs)
            tracking = true
        }
        update(clock.positionMs)
    }

    /** A seek YtPlaybackClock reported: never reveals anything by itself. */
    fun onSeek(seek: YtSeek, wallMs: Long) {
        if (!tracking) return
        unlock.seek(seek.toMs, wallMs)
        YtTestHooks.log(TAG) { "seek to=${seek.toMs} reason=${seek.reason} unlocked=${unlock.unlockedMs}" }
        update(seek.toMs)
    }

    private fun update(positionMs: Long) {
        val now = ScenePlayback(positionMs, unlock.unlockedMs, durationMs, chapterStarts)
        playback = now
        if (boundariesDirty) {
            boundariesDirty = false
            boundaries = YtCommentMoments.boundaries(moments.values.flatMap { it.ms })
            nextBoundary = YtCommentMoments.nextBoundary(boundaries, now.unlockedUpToMs)
        }
        if (now.unlockedUpToMs >= nextBoundary) {
            nextBoundary = YtCommentMoments.nextBoundary(boundaries, now.unlockedUpToMs)
            bump()
        }
        if (active.value == SceneMode.SCENE) steps.value = YtCommentMoments.sceneStep(positionMs)
    }

    private fun bump() {
        epochs.value = epochs.value + 1
        YtTestHooks.log(TAG) {
            val known = synchronized(index) { index.values.toList() }
            val hidden = known.count { verdict(it.comment).hidden }
            "epoch=${epochs.value} mode=${active.value.key} unlocked=${playback.unlockedUpToMs} " +
                "pos=${playback.positionMs} hidden=$hidden of ${known.size}"
        }
    }

    /** The viewer picks a mode (the chips); it is kept as the setting. */
    fun setMode(mode: SceneMode) {
        if (mode == chosen.value) return
        PreferenceHelper.putString(PREF_KEY, mode.key)
        applyMode(mode)
    }

    /** Reads the setting again (the settings screen may have changed it). */
    fun reloadMode() {
        val mode = readMode()
        if (mode != chosen.value) applyMode(mode)
    }

    private fun applyMode(mode: SceneMode) {
        val wasOff = active.value == SceneMode.OFF
        chosen.value = mode
        active.value = if (isLive) SceneMode.OFF else mode
        if (wasOff && active.value != SceneMode.OFF) {
            // switched on: up to the position now counts as seen, read at once so that no row
            // shows hidden for a moment before the first reading of the clock
            tracking = false
            onClock(YtPlaybackClock.now(), SystemClock.elapsedRealtime())
        }
        if (active.value == SceneMode.SCENE) steps.value = YtCommentMoments.sceneStep(playback.positionMs)
        bump()
        if (active.value == SceneMode.SCENE) autoScan()
    }

    /** The times [comment] names; parsed once. Any thread. */
    fun momentsOf(comment: Comment): CommentMoments {
        moments[comment.commentId]?.let { return it }
        val parsed = YtCommentMoments.parse(comment.commentText, videoId, durationMs)
        moments[comment.commentId] = parsed
        if (parsed.ms.isNotEmpty()) boundariesDirty = true
        return parsed
    }

    /** What [comment] may show now in [mode]. Any thread. */
    fun verdict(comment: Comment, mode: SceneMode = active.value): SceneVerdict =
        YtCommentMoments.classify(momentsOf(comment), playback, mode, comment.commentId in revealed)

    /** For a reply: hidden as in the mode, never left out (its comment is about the scene). */
    fun replyVerdict(comment: Comment): SceneVerdict =
        verdict(comment, if (active.value == SceneMode.SCENE) SceneMode.SPOILER else active.value)

    /** The viewer tapped a hidden comment open. */
    fun reveal(comment: Comment) {
        if (revealed.add(comment.commentId)) bump()
    }

    /** The chapter the position is in, or null. */
    fun currentChapter(): ChapterSegment? = chapters.getOrNull(YtCommentMoments.chapterIndex(playback))

    /**
     * The comments of the current scene, by their first time in it, then by likes. Any thread
     * (the classic sheet filters on a background thread).
     */
    fun sceneList(): List<SceneComment> {
        val now = playback
        val window = YtCommentMoments.sceneWindow(now)
        val known = synchronized(index) { index.values.toList() }
        return known
            .filter { it.moments.ms.any { time -> time in window } }
            .sortedWith(
                compareBy<SceneComment> { item -> item.moments.ms.first { time -> time in window } }
                    .thenByDescending { it.comment.likeCount },
            )
    }

    private fun sceneHits(): Int = sceneList().size

    /** Comments read so far. */
    fun indexedCount(): Int = synchronized(index) { index.size }

    /** On entering the scene mode: reads on until 8 comments of the scene are known, 6 pages at most. */
    fun autoScan() {
        val id = videoId ?: return
        scan((AUTO_PAGES - cache.pagesRead(id)).coerceAtLeast(0), AUTO_HITS)
    }

    /** "Weiter suchen": 5 pages more. */
    fun scanMore() = scan(MORE_PAGES, null)

    private fun scan(pages: Int, hits: Int?) {
        val id = videoId ?: return
        if (scanJob?.isActive == true || exhaustedState.value || isLive) return
        scanJob = scope.launch {
            scanningState.value = true
            try {
                // along the chain of pages: those read already come from the cache at once
                var key: String? = null
                var fetched = 0
                while (isActive) {
                    if (hits != null && sceneHits() >= hits) break
                    val known = cache.has(id, key)
                    if (!known && fetched >= pages) break
                    val page = cache.page(id, key)
                    if (!known) fetched++
                    key = page.nextpage ?: break
                }
                YtTestHooks.log(TAG) { "scan done: $fetched new pages, ${indexedCount()} comments read" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "the comment scan stopped", e)
            } finally {
                scanningState.value = false
            }
        }
    }

    // a page arrived (background thread): its comments go into the index
    private fun onPage(videoId: String, page: CommentsPage) {
        if (videoId != this.videoId) return
        val parsed = page.comments.map { SceneComment(it, momentsOf(it)) }
        synchronized(index) {
            parsed.forEach { index[it.comment.commentId] = it }
            items.value = index.values.toList()
        }
        if (page.disabled || page.nextpage == null) exhaustedState.value = true
    }

    /** Everything of the video goes (another video comes); the pages of [keepPagesOf] may stay. */
    fun clear(keepPagesOf: String? = null) {
        scanJob?.cancel()
        scanJob = null
        scanningState.value = false
        cache.clear(keepPagesOf)
        moments.clear()
        revealed.clear()
        synchronized(index) {
            index.clear()
            items.value = emptyList()
        }
        exhaustedState.value = false
        boundariesDirty = false
        boundaries = LongArray(0)
        nextBoundary = Long.MAX_VALUE
        tracking = false
    }

    companion object {
        /** The setting "Kommentare folgen der Szene": off, spoiler or scene (yt_r12_e_prefs.xml). */
        const val PREF_KEY = "yt_comments_scene"

        private const val TAG = "YtScene"
        private const val AUTO_PAGES = 6
        private const val AUTO_HITS = 8
        private const val MORE_PAGES = 5

        private fun readMode(): SceneMode =
            SceneMode.of(PreferenceHelper.getString(PREF_KEY, SceneMode.OFF.key))
    }
}
