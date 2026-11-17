package com.github.libretube.util

import android.os.SystemClock
import android.util.Log
import com.github.libretube.LibreTubeApp
import com.github.libretube.api.JsonHelper
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.ui.yt.YtTestHooks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.Executors

/**
 * YT: the seen ledger. Which videos were seen (marked by YtSeenRules), kept in its own small file
 * (yt_seen.json), independent of the watch positions, which a re-opened video, a deleted history
 * entry or a card without a duration can change. All marks of cards, search, the hide filter,
 * the archive and the SSI eviction read [isSeen]. Part of the backup ([all], [restore]).
 */
object YtSeenLedger {
    private const val TAG = "YtSeenLedger"
    private const val FILE = "yt_seen.json"
    private const val TRACKS = 24

    @Serializable
    private data class Stored(
        val seeded: Boolean = false,
        val prevented: Int = 0,
        val entries: List<YtSeenRules.Entry> = emptyList(),
    )

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "yt-seen-ledger").apply { isDaemon = true } }

    private var seeded = false
    private var prevented = 0

    /** How the last few videos were played in this process (YtSeenRules.Track); guarded by [lock]. */
    private val tracks = object : LinkedHashMap<String, YtSeenRules.Track>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, YtSeenRules.Track>?): Boolean = size > TRACKS
    }

    private val flow: MutableStateFlow<Map<String, YtSeenRules.Entry>> by lazy {
        val stored = load()
        seeded = stored.seeded
        prevented = stored.prevented
        MutableStateFlow(stored.entries.associateBy { it.id }).also { if (!seeded) scope.launch { seed() } }
    }

    /** The entries by video id, for the cards. */
    val entries: StateFlow<Map<String, YtSeenRules.Entry>> get() = flow

    fun all(): List<YtSeenRules.Entry> = flow.value.values.toList()

    fun contains(videoId: String): Boolean = videoId in flow.value

    /** The one decision "seen" (see [YtSeenRules.isSeen]). */
    fun isSeen(videoId: String, positionMs: Long?, durationSec: Long?, strict: Boolean = false): Boolean =
        YtSeenRules.isSeen(flow.value[videoId], positionMs, durationSec, strict)

    /** Whether the entry of [videoId] also counts for "Gesehenes ausblenden" (YtSeenRules.finished). */
    fun finished(videoId: String): Boolean = flow.value[videoId]?.let(YtSeenRules::finished) == true

    /**
     * A player snapshot of [videoId] ([seek]: taken after a seek): marks it when the rules say so;
     * counts a position write ([previousMs] before, [valueMs] now) that the old rule would have
     * turned into "unseen".
     */
    fun note(
        videoId: String,
        positionMs: Long,
        durationMs: Long,
        ended: Boolean,
        isLive: Boolean,
        seek: Boolean,
        previousMs: Long? = null,
        valueMs: Long? = null,
    ) {
        flow // start
        if (valueMs != null && YtSeenRules.preventedUnmark(contains(videoId), previousMs, valueMs, durationMs)) {
            synchronized(lock) { prevented++ }
            save()
        }
        if (isLive) return
        val track = synchronized(lock) {
            YtSeenRules.track(tracks[videoId], positionMs, durationMs, SystemClock.elapsedRealtime(), seek)
                .also { tracks[videoId] = it }
        }
        val old = flow.value[videoId]
        val marks = if (old != null) {
            // a mark that stands only moves its best share on
            ended || YtSeenRules.reached(positionMs, durationMs)
        } else {
            YtSeenRules.shouldMark(positionMs, durationMs, ended, isLive, track)
        }
        if (!marks) return
        val next = YtSeenRules.mark(old, videoId, positionMs, durationMs, ended, System.currentTimeMillis())
        if (old != null && old.share >= next.share && old.durationSec == next.durationSec) return
        put(next)
        YtTestHooks.log("YtSeen") { "mark $videoId share=${next.share} ended=$ended run=${track.runMs}" }
    }

    /** Manual "Als gesehen markieren" (the card's hold menu, LibreTube's "mark as watched"). */
    fun markSeen(videoId: String, durationSec: Long?) {
        put(YtSeenRules.Entry(videoId, System.currentTimeMillis(), 1f, durationSec?.takeIf { it > 0 }))
    }

    /**
     * Manual "Als ungesehen markieren": the mark goes, and so does the watch position, or the old
     * rule would show the video as seen again.
     */
    fun markUnseen(videoId: String) {
        forget(videoId)
        scope.launch { runCatching { Database.watchPositionDao().deleteByVideoId(videoId) } }
    }

    /**
     * The user took [videoId] out of the watch history (LibreTube's "mark as unwatched", the
     * history page): its mark goes with it.
     */
    fun forget(videoId: String) {
        synchronized(lock) {
            tracks.remove(videoId)
            if (videoId !in flow.value) return
            flow.value = flow.value - videoId
        }
        save()
    }

    /** The user cleared the watch history together with the watch positions: all marks go. */
    fun clear() {
        synchronized(lock) {
            tracks.clear()
            flow.value = emptyMap()
        }
        save()
    }

    /** Reads the file off the main thread (app start), so the first card does not wait for it. */
    fun warm() {
        io.execute { runCatching { flow } }
    }

    /** From a backup: entries are added, a newer one for the same video wins. */
    fun restore(list: List<YtSeenRules.Entry>) {
        if (list.isEmpty()) return
        synchronized(lock) {
            val merged = HashMap(flow.value)
            for (e in list) if ((merged[e.id]?.at ?: Long.MIN_VALUE) < e.at) merged[e.id] = e
            flow.value = merged
        }
        save()
    }

    private fun put(entry: YtSeenRules.Entry) {
        synchronized(lock) { flow.value = flow.value + (entry.id to entry) }
        save()
    }

    /** The Diagnose line "Gesehen". */
    fun diagnosis(): String {
        val list = flow.value.values
        val last = list.maxOfOrNull { it.at }?.let { YtCrashLog.formatTime(it) } ?: "none"
        return "${list.size} entries, last mark $last, $prevented automatic un-marks prevented, seeded=$seeded"
    }

    /**
     * Takes over what stored positions call watched (YtSeenRules.seed): the first run and the
     * account import. Marks that stand stay. Returns how many were found.
     */
    fun adopt(positionsMs: Map<String, Long>, durationsSec: Map<String, Long>): Int {
        val found = YtSeenRules.seed(positionsMs, durationsSec, System.currentTimeMillis())
        if (found.isEmpty()) return 0
        synchronized(lock) { flow.value = found.associateBy { it.id } + flow.value }
        save()
        return found.size
    }

    /** First run: what the position tables call watched (durations from the history and the feed). */
    private suspend fun seed() {
        try {
            val positions = Database.watchPositionDao().getAll().associate { it.videoId to it.position }
            val durations = HashMap<String, Long>()
            runCatching { Database.feedDao().getAll().forEach { f -> f.duration?.let { durations[f.videoId] = it } } }
            runCatching { Database.watchHistoryDao().getAll().forEach { h -> h.duration?.let { durations[h.videoId] = it } } }
            val found = adopt(positions, durations)
            synchronized(lock) { seeded = true }
            save()
            YtTestHooks.log("YtSeen") { "seeded $found of ${positions.size} positions" }
        } catch (e: Exception) {
            Log.e(TAG, "seeding failed", e)
        }
    }

    private fun file(): File = File(LibreTubeApp.instance.filesDir, FILE)

    private fun load(): Stored = runCatching {
        file().takeIf { it.exists() }?.readText()?.let { JsonHelper.json.decodeFromString(Stored.serializer(), it) }
    }.getOrNull() ?: Stored()

    /** Writes the whole file (temp + rename) on one background thread; a newer state replaces a waiting one. */
    private fun save() {
        io.execute {
            runCatching {
                val snapshot = synchronized(lock) { Stored(seeded, prevented, flow.value.values.toList()) }
                val target = file()
                val tmp = File(target.parentFile, "$FILE.tmp")
                tmp.writeText(JsonHelper.json.encodeToString(Stored.serializer(), snapshot))
                if (!tmp.renameTo(target)) {
                    target.delete()
                    tmp.renameTo(target)
                }
            }.onFailure { Log.e(TAG, "saving failed", it) }
        }
    }
}
