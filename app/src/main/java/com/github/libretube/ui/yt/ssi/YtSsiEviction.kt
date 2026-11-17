package com.github.libretube.ui.yt.ssi

import android.content.Context
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.ui.yt.archive.YtArchive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * YT: keeps SSI's index bounded (round 38): videos older than the window and, above the cap,
 * more of the oldest are evicted, one DB transaction each ([YtSsiDb.evict], which leaves a
 * tombstone). Protected: rated in results, in a saved research or the benchmark examples,
 * vanished or kept in the archive; watched videos go only when the cap cannot be met otherwise.
 */
object YtSsiEviction {
    fun windowDays(): Int = YtSsiIndexRules.windowDays(PreferenceHelper.getString(YtSsiPrefs.KEY_WINDOW_DAYS, YtSsiIndexRules.DEFAULT_WINDOW_DAYS.toString()))

    /** Evicts what is due; returns how many videos went. The vectors in memory are dropped when any did. */
    suspend fun run(context: Context, now: Long = System.currentTimeMillis()): Int = withContext(Dispatchers.IO) {
        val db = YtSsi.db
        val docs = db.docs()
        if (docs.isEmpty()) return@withContext 0
        val counts = db.passageCounts()
        val keep = HashSet<String>()
        runCatching { keep += db.feedbackVideoIds() }
        runCatching { YtSsi.research.list().forEach { r -> r.hits.forEach { keep += it.videoId } } }
        runCatching { YtSsiBenchRunner.examples(context)?.examples?.forEach { e -> e.videoId?.let { keep += it } } }
        val watched = runCatching { DatabaseHolder.Database.watchPositionDao().getAll().map { it.videoId }.toHashSet() }.getOrDefault(hashSetOf())
        val candidates = docs.values.map { d ->
            YtSsiIndexRules.Candidate(
                videoId = d.videoId,
                indexedAt = d.indexedAt,
                passages = counts[d.videoId] ?: 0,
                protected = d.vanished || d.videoId in keep || runCatching { YtArchive.store[d.videoId]?.protected == true }.getOrDefault(false),
                watched = d.videoId in watched,
            )
        }
        val victims = YtSsiIndexRules.evictions(candidates, now, windowDays())
        for (id in victims) {
            coroutineContext.ensureActive()
            db.evict(id)
        }
        if (victims.isNotEmpty()) YtSsi.dropVectors()
        victims.size
    }
}
