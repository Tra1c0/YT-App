package com.github.libretube.helpers

import android.util.Log
import com.github.libretube.api.JsonHelper
import com.github.libretube.api.SubscriptionHelper
import com.github.libretube.api.obj.Subscription
import com.github.libretube.extensions.toID
import com.github.libretube.repo.FeedRefresh
import com.github.libretube.ui.dialogs.ShareDialog.Companion.YOUTUBE_FRONTEND_URL
import com.github.libretube.ui.yt.feed.YtDeadSubsResult
import com.github.libretube.ui.yt.feed.YtDeadSubsRules
import com.github.libretube.ui.yt.feed.YtSubCheck
import com.github.libretube.ui.yt.feed.YtSubState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.feed.FeedInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/**
 * YT: finds subscribed channels without an upload for over a year ("Tote Abos finden"). Channels
 * with a video in the feed at hand (the local feed cache, or the account's feed) are alive
 * without a request. The others are checked politely: [CONCURRENCY] at a time with a pause
 * before each, by the channel's RSS feed (one small request) and, only when that fails, by its
 * page. Results are kept for a week (pref [KEY], YtDeadSubsRules) so a second look is instant.
 * Works the same for local subscriptions and an account: everything goes through
 * SubscriptionHelper.
 */
object YtDeadSubs {
    const val KEY = "yt_dead_subs_checks"
    private const val TAG = "YtDeadSubs"
    private const val CONCURRENCY = 3
    private val PAUSE_MILLIS = 200L..600L

    private val storeLock = Mutex()

    /** The subscriptions, sorted by name. */
    suspend fun subscriptions(): List<Subscription> = SubscriptionHelper.getSubscriptions()

    /**
     * Checks [subs] and returns the dead ones. [onProgress] gets (checked, to check) on the
     * main thread; it is not called when nothing needs a request.
     */
    suspend fun scan(subs: List<Subscription>, onProgress: (Int, Int) -> Unit): YtDeadSubsResult = withContext(Dispatchers.IO) {
        val ids = subs.map { it.url.toID() }
        val feed = runCatching { SubscriptionHelper.getFeed(FeedRefresh.Automatically) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrDefault(emptyList())
        val feedLatest = YtDeadSubsRules.latest(feed.map { it.uploaderUrl?.toID() to it.uploaded })
        val now = System.currentTimeMillis()
        val stored = storeLock.withLock { YtDeadSubsRules.prune(load(), ids, now) }
        val checks = stored.associateBy { it.id }.toMutableMap()
        val pending = YtDeadSubsRules.toCheck(ids, feedLatest, checks, now)

        if (pending.isNotEmpty()) {
            val done = AtomicInteger()
            val found = java.util.concurrent.ConcurrentLinkedQueue<YtSubCheck>()
            val permits = Semaphore(CONCURRENCY)
            withContext(Dispatchers.Main) { onProgress(0, pending.size) }
            try {
                coroutineScope {
                    pending.forEach { id ->
                        launch {
                            permits.withPermit {
                                delay(PAUSE_MILLIS.random())
                                check(id)?.let(found::add)
                            }
                            val count = done.incrementAndGet()
                            withContext(Dispatchers.Main) { onProgress(count, pending.size) }
                        }
                    }
                }
            } finally {
                // keep what was found even when the scan was cancelled, so the next one goes on
                // from there
                val added = found.toList()
                storeLock.withLock { save(YtDeadSubsRules.merge(YtDeadSubsRules.prune(load(), ids, now), added)) }
                added.forEach { checks[it.id] = it }
            }
        } else {
            storeLock.withLock { save(stored) }
        }
        YtDeadSubsRules.verdict(ids, feedLatest, checks, System.currentTimeMillis())
    }

    /** Unsubscribes [subs]; returns the ones that went (for an undo). */
    suspend fun unsubscribe(subs: List<Subscription>): List<Subscription> = withContext(Dispatchers.IO) {
        subs.filter { sub ->
            runCatching { SubscriptionHelper.unsubscribe(sub.url.toID()) }
                .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "unsubscribe ${sub.url}: $it") }
                .isSuccess
        }
    }

    /** Subscribes [subs] again (undo). */
    suspend fun resubscribe(subs: List<Subscription>) = withContext(Dispatchers.IO) {
        subs.forEach { sub ->
            runCatching { SubscriptionHelper.subscribe(sub.url.toID(), sub.name, sub.avatar, sub.verified) }
                .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "subscribe ${sub.url}: $it") }
        }
    }

    /** One channel: its RSS feed first, its page only when the feed fails. Null = no answer. */
    private fun check(id: String): YtSubCheck? {
        val url = "$YOUTUBE_FRONTEND_URL/channel/$id"
        try {
            val items = FeedInfo.getInfo(url).relatedItems
            return YtDeadSubsRules.fromDates(id, items.map(::uploadedMillis), System.currentTimeMillis())
        } catch (e: Exception) {
            Log.i(TAG, "feed of $id failed, trying the page: $e")
        }
        return try {
            val channel = ChannelInfo.getInfo(url)
            val tabs = channel.tabs.filter { tab ->
                tab.contentFilters.any { it == ChannelTabs.VIDEOS || it == ChannelTabs.LIVESTREAMS }
            }
            val dates = tabs.flatMap { tab ->
                ChannelTabInfo.getInfo(NewPipeExtractorInstance.extractor, tab).relatedItems
                    .filterIsInstance<StreamInfoItem>()
                    .map(::uploadedMillis)
            }
            YtDeadSubsRules.fromDates(id, dates, System.currentTimeMillis())
        } catch (e: ContentNotAvailableException) {
            // closed or terminated
            YtSubCheck(id, YtSubState.GONE, 0, System.currentTimeMillis())
        } catch (e: Exception) {
            Log.w(TAG, "page of $id failed: $e")
            null
        }
    }

    private fun uploadedMillis(item: StreamInfoItem): Long =
        item.uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli() ?: 0L

    private fun load(): List<YtSubCheck> = runCatching {
        JsonHelper.json.decodeFromString<List<YtSubCheck>>(PreferenceHelper.getString(KEY, "[]"))
    }.getOrDefault(emptyList())

    private fun save(checks: List<YtSubCheck>) {
        if (checks.isEmpty()) {
            PreferenceHelper.remove(KEY)
        } else {
            PreferenceHelper.putString(KEY, JsonHelper.json.encodeToString(checks))
        }
    }
}
