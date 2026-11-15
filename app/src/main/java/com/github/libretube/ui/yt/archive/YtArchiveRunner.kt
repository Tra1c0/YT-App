package com.github.libretube.ui.yt.archive

import android.content.Context
import android.util.Log
import com.github.libretube.db.DatabaseHelper
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.extensions.toID
import com.github.libretube.ui.yt.archive.YtArchiveRules.PlanItem
import com.github.libretube.util.YtStreamError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.TimeZone

/**
 * YT: one run of the archive (started by YtArchiveWorker). What a run does depends on its mode:
 * - [MODE_TEXT]: tier 1 for new feed videos (risk scored first, high risk starts [MODE_URGENT]),
 *   the light checks of [YtArchiveRules.checks], the window labels, then the budget;
 * - [MODE_MEDIA]: the text part, then audio/144p by the budget plan, then the budget;
 * - [MODE_URGENT]: audio for high-risk entries (within the hard cap), the notification, the
 *   audio analysis if the user wants scripts made automatically;
 * - [MODE_CHECK]: only the light checks (a gone video to confirm);
 * - [MODE_NOW]: one video by hand ("Im Archiv sichern"), pinned.
 * Every request is paced and counted by [YtArchiveThrottle]; a block ends the run.
 */
class YtArchiveRunner(private val context: Context) {
    private val store = YtArchive.store
    private val fetcher = YtArchiveFetcher(store)
    private var throttle = YtArchive.throttle(context)
    private val started = now()
    private var errorsInRow = 0
    private var blocked = false
    private var requests = 0

    /** Media saves that failed in this run: not tried again in it. */
    private val failed = HashSet<String>()

    private fun now() = System.currentTimeMillis()
    private fun offset() = TimeZone.getDefault().getOffset(now()).toLong()

    private fun goOn(): Boolean =
        !blocked && errorsInRow < YtArchiveThrottle.ERRORS_IN_ROW && now() - started < YtArchiveThrottle.RUN_BUDGET_MS

    /** The random pause before every request but the first of the run. */
    private suspend fun pace() {
        if (requests++ > 0) delay(YtArchiveThrottle.jitterMs())
    }

    suspend fun run(mode: String, videoId: String?) {
        if (YtArchiveThrottle.paused(throttle, now())) {
            Log.i(TAG, "paused until ${throttle.backoffUntil}, $mode skipped")
            return
        }
        try {
            when (mode) {
                MODE_TEXT -> {
                    textPass()
                    checkPass()
                    labelPass()
                    enforceBudget()
                }
                MODE_MEDIA -> {
                    textPass()
                    labelPass()
                    mediaPass()
                    enforceBudget()
                }
                MODE_URGENT -> urgentPass()
                MODE_CHECK -> checkPass()
                MODE_NOW -> videoId?.let { saveByHand(it) }
            }
        } finally {
            throttle = YtArchiveThrottle.afterRun(throttle, now(), mode, blocked)
            YtArchive.saveThrottle(context, throttle)
        }
    }

    /**
     * Runs one network step; a block pauses the archive and ends the run, a gone video goes to
     * [onGone], any other error counts towards [YtArchiveThrottle.ERRORS_IN_ROW]. Null on failure.
     */
    private suspend fun <T> guarded(what: String, onGone: (YtStreamError.Kind) -> Unit = {}, block: suspend () -> T): T? = try {
        block().also { errorsInRow = 0 }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        when (val typed = fetcher.typed(e)) {
            is YtArchiveFetcher.Blocked -> {
                blocked = true
                throttle = YtArchiveThrottle.afterBlock(throttle, now(), "$what: ${typed.message}")
                Log.w(TAG, "blocked ($what): ${typed.message}")
            }
            is YtArchiveFetcher.Gone -> onGone(typed.kind)
            else -> {
                errorsInRow++
                throttle = YtArchiveThrottle.afterError(throttle, now(), "$what: ${typed.javaClass.simpleName} ${typed.message.orEmpty().take(60)}")
                Log.i(TAG, "$what failed: $typed")
            }
        }
        null
    }

    // ---- candidates ----

    private suspend fun feedCandidates(): List<YtArchiveCandidate> {
        val feed = DatabaseHolder.Database.feedDao().getAll()
        val positions = DatabaseHolder.Database.watchPositionDao().getAll().associate { it.videoId to it.position }
        val videos = feed.map { item ->
            YtArchiveRules.FeedVideo(
                candidate = YtArchiveCandidate(
                    videoId = item.videoId,
                    channelId = item.uploaderUrl?.toID(),
                    channelName = item.uploaderName,
                    title = item.title.orEmpty(),
                    description = item.shortDescription,
                    uploaded = item.uploaded.takeIf { it > 0 },
                    durationSec = item.duration,
                ),
                isShort = item.isShort,
                watched = positions[item.videoId]?.let { DatabaseHelper.isVideoWatched(it, item.duration) } == true,
            )
        }
        return YtArchiveRules.candidates(videos, now())
    }

    private fun planItem(meta: YtArchiveMeta) = PlanItem(
        videoId = meta.videoId,
        current = meta.tier,
        textBytes = meta.textBytes,
        audioBytes = meta.audioBytes.takeIf { meta.tier.level >= YtArchiveTier.AUDIO.level },
        videoBytes = meta.videoBytes.takeIf { meta.tier == YtArchiveTier.VIDEO },
        durationSec = meta.durationSec,
        risk = meta.risk ?: 0.0,
        high = meta.high,
        uploaded = meta.uploaded,
        savedAt = meta.savedAt,
        protected = meta.protected,
    )

    // ---- passes ----

    /** Tier 1 for feed videos not in the archive yet, best (newest) first. */
    private suspend fun textPass() {
        val skipped = YtArchive.skipped(context)
        val fresh = feedCandidates()
            .filter { YtArchiveRules.validId(it.videoId) && !store.contains(it.videoId) && it.videoId !in skipped }
            .take(YtArchiveThrottle.TEXT_PER_RUN)
        if (fresh.isEmpty()) return
        val scores = runCatching { YtArchiveParts.risk.score(fresh) }.getOrDefault(emptyList()).associateBy { it.videoId }
        var urgent = false
        for (candidate in fresh) {
            if (!goOn()) break
            pace()
            guarded("text", onGone = {
                // gone before it was saved: a label all the same, and no second try
                YtArchiveParts.risk.observe(candidate, true)
                YtArchive.skip(context, candidate.videoId)
            }) {
                val streams = fetcher.streams(candidate.videoId)
                if (streams.isShort || streams.livestream) {
                    YtArchive.skip(context, candidate.videoId)
                    return@guarded
                }
                val score = scores[candidate.videoId]
                val meta = YtArchiveMeta.of(candidate, now()).copy(
                    risk = score?.risk,
                    riskReason = score?.reason,
                    high = score?.high == true,
                )
                store.put(meta)
                fetcher.saveText(meta, streams, now())
                if (meta.high) urgent = true
            }
        }
        if (urgent) YtArchive.runUrgent(context)
    }

    /** Audio and 144p by the plan, best ranked first, at most the run's and the day's count. */
    private suspend fun mediaPass() {
        var left = minOf(YtArchiveThrottle.MEDIA_PER_RUN, YtArchiveThrottle.mediaLeft(throttle, now(), offset()))
        val budget = YtArchive.budget()
        while (left > 0 && goOn()) {
            val metas = store.all()
            val items = metas.map(::planItem)
            val targets = YtArchiveRules.plan(items, budget, YtArchive.top(), now())
            val byId = metas.associateBy { it.videoId }
            val next = YtArchiveRules.upgrades(items, targets).firstOrNull { (item, target) ->
                val meta = byId[item.videoId]
                target.level >= YtArchiveTier.AUDIO.level && item.videoId !in failed && meta != null &&
                    YtArchiveRules.retryDue(meta.mediaFailures, meta.mediaFailedAt, now())
            } ?: break
            left--
            raise(next.first, next.second, targets, budget)
        }
    }

    /**
     * Raises [item] tier by tier up to [target] with one stream-info request, making room by the
     * plan's fall backs first (within the soft budget, or the hard cap for high risk).
     */
    private suspend fun raise(item: PlanItem, target: YtArchiveTier, targets: Map<String, YtArchiveTier?>, budget: Long): Boolean {
        val id = item.videoId
        val limit = if (item.high) YtArchiveRules.hardCap(budget) else budget
        fun room(to: YtArchiveTier): Boolean {
            val current = store[id]?.let(::planItem) ?: return false
            val need = YtArchiveRules.sizeAt(current, to) - YtArchiveRules.sizeAt(current, current.current)
            val over = store.usedBytes() + need - limit
            if (over <= 0) return true
            // the entries as they are now (an earlier step of this raise may have given some up)
            val fresh = store.all().map(::planItem)
            val steps = YtArchiveRules.makeRoom(YtArchiveRules.steps(fresh, targets), over, excluding = id) ?: return false
            for (step in steps) store.downgrade(step.videoId, step.to, now())
            return true
        }
        if (!room(YtArchiveTier.entries.first { it.level == (item.current?.level ?: 0) + 1 })) {
            failed += id
            return false
        }
        pace()
        var counted = false
        val ok = guarded("media", onGone = { kind -> YtArchive.markVanished(id, kind, now()) }) {
            val streams = fetcher.streams(id)
            var meta = store[id] ?: return@guarded false
            if (meta.tier.level < YtArchiveTier.AUDIO.level) {
                meta = fetcher.saveAudio(meta, streams, now())
                counted = true
            }
            if (target == YtArchiveTier.VIDEO && meta.tier.level < YtArchiveTier.VIDEO.level && room(YtArchiveTier.VIDEO)) {
                fetcher.saveVideo(meta, streams, now())
                counted = true
            }
            true
        } == true
        if (counted) throttle = YtArchiveThrottle.countMedia(throttle, now(), offset())
        if (ok) {
            store.update(id) { it.copy(mediaFailures = 0, mediaFailedAt = null) }
        } else {
            failed += id
            if (!blocked) store.update(id) { it.copy(mediaFailures = it.mediaFailures + 1, mediaFailedAt = now()) }
        }
        return ok
    }

    /** High-risk entries get audio now (hard cap), then their notification and, if wanted, the audio analysis. */
    private suspend fun urgentPass() {
        val budget = YtArchive.budget()
        val due = store.all()
            .filter { it.high && it.tier == YtArchiveTier.TEXT && planItem(it).mediaAllowed && YtArchiveRules.retryDue(it.mediaFailures, it.mediaFailedAt, now()) }
            .sortedByDescending { it.risk ?: 0.0 }
            .take(YtArchiveThrottle.URGENT_PER_RUN)
        for (meta in due) {
            if (!goOn()) break
            val items = store.all().map(::planItem)
            val targets = YtArchiveRules.plan(items, budget, YtArchive.top(), now())
            raise(planItem(meta), YtArchiveTier.AUDIO, targets, budget)
            val saved = store[meta.videoId] ?: continue
            YtArchive.notifyHighRisk(context, saved)
            // the user asked for high-risk audio to be analysed at once; the script part caps it per day
            if (saved.tier.level >= YtArchiveTier.AUDIO.level && saved.scriptState == null) {
                script(saved, audio = true)
            }
        }
        // high-risk entries the media job raised meanwhile
        for (meta in store.all().filter { it.high && !it.notified }) YtArchive.notifyHighRisk(context, meta)
    }

    private suspend fun script(meta: YtArchiveMeta, audio: Boolean) {
        YtArchive.makeScript(meta, audio)
    }

    /** The light checks: vanished entries to confirm, then entries in the window not checked lately. */
    private suspend fun checkPass() {
        val items = store.all().map {
            YtArchiveRules.CheckItem(it.videoId, it.uploaded, it.savedAt, it.checkedAt, it.vanished, it.vanishLabelled)
        }
        for (id in YtArchiveRules.checks(items, now(), YtArchiveThrottle.CHECKS_PER_RUN)) {
            if (!goOn()) break
            val meta = store[id] ?: continue
            pace()
            guarded("check") {
                val there = fetcher.oEmbedOk(id)
                when {
                    meta.vanished && there -> {
                        // a passing block, not gone: the entry is a normal one again
                        store.update(id) { it.copy(vanishedAt = null, vanishedKind = null, checkedAt = now()) }
                    }
                    meta.vanished -> confirmVanished(id)
                    there -> store.update(id) { it.copy(checkedAt = now()) }
                    else -> {
                        // oEmbed also refuses public videos with embedding off: the stream info decides
                        pace()
                        try {
                            fetcher.streams(id)
                            store.update(id) { it.copy(checkedAt = now()) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            val typed = fetcher.typed(e)
                            if (typed !is YtArchiveFetcher.Gone) throw typed
                            YtArchive.markVanished(id, typed.kind, now())
                            confirmVanished(id)
                        }
                    }
                }
            }
        }
    }

    /** A vanish is sure: one label for the estimator, the script if the user wants it made automatically. */
    private suspend fun confirmVanished(id: String) {
        val meta = store.update(id) { it.copy(vanishLabelled = true, checkedAt = now()) } ?: return
        runCatching { YtArchiveParts.risk.observe(meta.candidate(), true) }
        if (YtArchive.autoScript() && meta.scriptState == null) script(meta, audio = false)
    }

    /** Entries that left the window still there: one "did not vanish" label each. */
    private fun labelPass() {
        val now = now()
        for (meta in store.all().filter { YtArchiveRules.windowLabelDue(it, now) }) {
            runCatching { YtArchiveParts.risk.observe(meta.candidate(), false) }
            store.update(meta.videoId) { it.copy(windowLabelled = true) }
        }
    }

    /** Over the budget (a smaller setting, protected entries grew): the plan's fall backs until it fits. */
    private fun enforceBudget() {
        val budget = YtArchive.budget()
        val used = store.usedBytes()
        if (used <= budget) return
        val items = store.all().map(::planItem)
        val targets = YtArchiveRules.plan(items, budget, YtArchive.top(), now())
        val steps = YtArchiveRules.steps(items, targets)
        val take = YtArchiveRules.makeRoom(steps, used - budget) ?: steps
        for (step in take) store.downgrade(step.videoId, step.to, now())
    }

    /** "Im Archiv sichern": text, audio and (if allowed) 144p, pinned so it is never given up. */
    private suspend fun saveByHand(videoId: String) {
        if (!YtArchiveRules.validId(videoId)) return
        pace()
        guarded("by hand") {
            val streams = fetcher.streams(videoId)
            var meta = store[videoId] ?: YtArchiveMeta.of(
                YtArchiveCandidate(
                    videoId = videoId,
                    channelId = streams.uploaderUrl?.toID(),
                    channelName = streams.uploader,
                    title = streams.title,
                    description = streams.description,
                    uploaded = streams.uploaded,
                    durationSec = streams.duration,
                ),
                now(),
            )
            meta = meta.copy(pinned = true)
            store.put(meta)
            meta = fetcher.saveText(meta, streams, now())
            if (streams.livestream || streams.duration !in 1..YtArchiveRules.MAX_MEDIA_DURATION_SEC) return@guarded
            if (meta.tier.level < YtArchiveTier.AUDIO.level) meta = fetcher.saveAudio(meta, streams, now())
            if (YtArchive.top() == YtArchiveTier.VIDEO && meta.tier.level < YtArchiveTier.VIDEO.level) fetcher.saveVideo(meta, streams, now())
            throttle = YtArchiveThrottle.countMedia(throttle, now(), offset())
        }
    }

    companion object {
        private const val TAG = "YtArchiveRunner"
        const val MODE_TEXT = "text"
        const val MODE_MEDIA = "media"
        const val MODE_URGENT = "urgent"
        const val MODE_CHECK = "check"
        const val MODE_NOW = "now"
    }
}
