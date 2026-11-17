package com.github.libretube.util

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

    /**
     * A player snapshot of [videoId]: marks it when the rules say so; counts a position write
     * ([previousMs] before, [valueMs] now) that the old rule would have turned into "unseen".
     */
    fun note(
        videoId: String,
        positionMs: Long,
        durationMs: Long,
        ended: Boolean,
        isLive: Boolean,
        previousMs: Long? = null,
        valueMs: Long? = null,
    ) {
        flow // start
        if (valueMs != null && YtSeenRules.preventedUnmark(contains(videoId), previousMs, valueMs, durationMs)) {
            synchronized(lock) { prevented++ }
            save()
        }
        if (!YtSeenRules.shouldMark(positionMs, durationMs, ended, isLive)) return
        val old = flow.value[videoId]
        // a mark that stands only moves its best share and time on when something changed
        val next = YtSeenRules.mark(old, videoId, positionMs, durationMs, ended, System.currentTimeMillis())
        if (old != null && old.share >= next.share && old.durationSec == next.durationSec) return
        put(next)
        YtTestHooks.log("YtSeen") { "mark $videoId share=${next.share} ended=$ended" }
    }

    /** Manual "Als gesehen markieren" from the card's hold menu. */
    fun markSeen(videoId: String, durationSec: Long?) {
        put(YtSeenRules.Entry(videoId, System.currentTimeMillis(), 1f, durationSec?.takeIf { it > 0 }))
    }

    /**
     * Manual "Als ungesehen markieren": the mark goes, and so does the watch position, or the old
     * rule would show the video as seen again.
     */
    fun markUnseen(videoId: String) {
        synchronized(lock) { flow.value = flow.value - videoId }
        save()
        scope.launch { runCatching { Database.watchPositionDao().deleteByVideoId(videoId) } }
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

    /** First run: what the position tables say was watched to 90 % (history and feed durations). */
    private suspend fun seed() {
        try {
            val positions = Database.watchPositionDao().getAll().associate { it.videoId to it.position }
            val durations = HashMap<String, Long>()
            runCatching { Database.feedDao().getAll().forEach { f -> f.duration?.let { durations[f.videoId] = it } } }
            runCatching { Database.watchHistoryDao().getAll().forEach { h -> h.duration?.let { durations[h.videoId] = it } } }
            val found = YtSeenRules.seed(positions, durations, System.currentTimeMillis())
            synchronized(lock) {
                // what was marked meanwhile stays
                flow.value = found.associateBy { it.id } + flow.value
                seeded = true
            }
            save()
            YtTestHooks.log("YtSeen") { "seeded ${found.size} of ${positions.size} positions" }
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
