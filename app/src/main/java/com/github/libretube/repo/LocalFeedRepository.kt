package com.github.libretube.repo

import android.util.Log
import com.github.libretube.api.SubscriptionHelper
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.api.obj.Subscription
import com.github.libretube.api.toStreamItem
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.db.obj.SubscriptionsFeedItem
import com.github.libretube.enums.ContentFilter
import com.github.libretube.extensions.parallelMap
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.NewPipeExtractorInstance
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.ui.dialogs.ShareDialog.Companion.YOUTUBE_FRONTEND_URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.feed.FeedInfo
import org.schabi.newpipe.extractor.stream.ContentAvailability
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

class LocalFeedRepository : FeedRepository {
    private val relevantTabs =
        listOf(
            ContentFilter.LIVESTREAMS to ChannelTabs.LIVESTREAMS,
            ContentFilter.VIDEOS to ChannelTabs.VIDEOS,
            ContentFilter.SHORTS to ChannelTabs.SHORTS
        ).mapNotNull { (filter, tab) ->
            if (filter.isEnabled) tab else null
        }.toTypedArray()

    private val durationBetweenAutomaticRefreshes = Duration.ofHours(AUTO_REFRESH_HOURS)

    override fun isRefreshDue(): Boolean {
        val lastRefreshMillis =
            PreferenceHelper.getLong(PreferenceKeys.LAST_LOCAL_FEED_REFRESH_TIMESTAMP_MILLIS, 0)
        return Instant.now().toEpochMilli() - lastRefreshMillis >= durationBetweenAutomaticRefreshes.toMillis()
    }

    override suspend fun submitFeedItemChange(feedItem: SubscriptionsFeedItem) {
        DatabaseHolder.Database.feedDao().update(feedItem)
    }

    override suspend fun removeChannel(channelId: String) {
        DatabaseHolder.Database.feedDao().delete(channelId)
    }

    override suspend fun getFeed(
        refresh: FeedRefresh,
        onProgressUpdate: (FeedProgress) -> Unit
    ): List<StreamItem> {
        val nowMillis = Instant.now().toEpochMilli()
        val minimumDateMillis = nowMillis - Duration.ofDays(MAX_FEED_AGE_DAYS).toMillis()

        val channelIdsToRefresh = when (refresh) {
            is FeedRefresh.All -> {
                val channelIds = SubscriptionHelper.getSubscriptionChannelIds()
                // remove all channels that are no longer subscribed to, e.g. when the user switched
                // the account
                DatabaseHolder.Database.feedDao().deleteAllExcept(channelIds)
                channelIds
            }
            is FeedRefresh.Automatically -> emptyList()
            is FeedRefresh.OnlySome -> refresh.channelIds
        }

        if (refresh is FeedRefresh.Automatically) {
            // YT: the cached feed comes back at once, however old: a refresh of every channel
            // takes long and runs in the background when due (isRefreshDue, see
            // SubscriptionsViewModel). Before, a stale cache was "refreshed" with an empty
            // channel list and stamped as fresh, so the feed never updated without a pull.
            // An empty cache (first start, account switch) is fetched by SubscriptionHelper.getFeed.
            return DatabaseHolder.Database.feedDao().getAll().map(SubscriptionsFeedItem::toStreamItem)
        }

        DatabaseHolder.Database.feedDao().cleanUpOlderThan(minimumDateMillis)
        refreshFeed(channelIdsToRefresh, minimumDateMillis, onProgressUpdate)
        PreferenceHelper.putLong(PreferenceKeys.LAST_LOCAL_FEED_REFRESH_TIMESTAMP_MILLIS, nowMillis)

        return DatabaseHolder.Database.feedDao().getAll().map(SubscriptionsFeedItem::toStreamItem)
    }

    private suspend fun refreshFeed(
        channelIds: List<String>,
        minimumDateMillis: Long,
        onProgressUpdate: (FeedProgress) -> Unit
    ) {
        if (channelIds.isEmpty()) return

        // YT: a sliding window of CHANNEL_CONCURRENCY channels instead of rounds of five that
        // each waited for their slowest channel (one full channel fetch held up four quick RSS
        // checks). The pause against throttling stays: after every CHANNEL_BATCH_SIZE fully
        // fetched channels, new channels wait CHANNEL_BATCH_DELAY before they start.
        val startedAt = System.currentTimeMillis()
        val totalExtractionCount = AtomicInteger()
        val fullFetchCount = AtomicInteger()
        val pauseUntil = AtomicLong(0)
        val permits = Semaphore(CHANNEL_CONCURRENCY)
        val changedChannels = ConcurrentLinkedQueue<Subscription>()
        withContext(Dispatchers.Main) {
            onProgressUpdate(FeedProgress(0, channelIds.size))
        }

        coroutineScope {
            channelIds.map { channelId ->
                launch(Dispatchers.IO) {
                    permits.withPermit {
                        val wait = pauseUntil.get() - System.currentTimeMillis()
                        if (wait > 0) delay(wait)
                        try {
                            val (channel, items) = getRelatedStreams(channelId, minimumDateMillis)
                            if (items.isNotEmpty()) {
                                // count the channels we had to fully fetch
                                if (fullFetchCount.incrementAndGet() % CHANNEL_BATCH_SIZE == 0) {
                                    pauseUntil.set(
                                        System.currentTimeMillis() + CHANNEL_BATCH_DELAY.random()
                                    )
                                }
                                DatabaseHolder.Database.feedDao()
                                    .insertAll(items.map(StreamItem::toFeedItem))
                            }
                            if (channel != null) changedChannels.add(channel)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e(channelId, e.stackTraceToString())
                        } finally {
                            withContext(NonCancellable + Dispatchers.Main) {
                                onProgressUpdate(
                                    FeedProgress(totalExtractionCount.incrementAndGet(), channelIds.size)
                                )
                            }
                        }
                    }
                }
            }
        }

        // update subscriptions channels in case they've changed (e.g. different avatar or name)
        if (changedChannels.isNotEmpty()) {
            SubscriptionHelper.submitSubscriptionChannelInfosChanged(changedChannels.toList())
        }
        Log.i(
            "YtFeed",
            "refreshed ${channelIds.size} channels (${fullFetchCount.get()} fully) in " +
                "${System.currentTimeMillis() - startedAt} ms"
        )
    }

    private suspend fun getRelatedStreams(
        channelId: String,
        minimumDateMillis: Long
    ): Pair<Subscription?, List<StreamItem>> {
        val channelUrl = "$YOUTUBE_FRONTEND_URL/channel/${channelId}"
        val feedInfo = FeedInfo.getInfo(channelUrl)
        val feedInfoItems = feedInfo.relatedItems.associateBy { it.url.toID() }

        val mostRecentChannelVideo = feedInfo.relatedItems.maxBy {
            it.uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli() ?: 0
        } ?: return Pair(null, emptyList())

        // check if the channel has at least one video whose upload time is newer than the maximum
        // feed ago and which is not yet stored in the database
        val mostRecentUploadTime =
            mostRecentChannelVideo.uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli() ?: 0
        val hasNewerUploads =
            mostRecentUploadTime > minimumDateMillis && !DatabaseHolder.Database.feedDao()
                .contains(mostRecentChannelVideo.url.toID())
        if (!hasNewerUploads) return Pair(null, emptyList())

        val channelInfo = ChannelInfo.getInfo(channelUrl)
        val channelAvatar = channelInfo.avatars.maxByOrNull { it.height }?.url
        val subscription =
            Subscription(channelId, channelInfo.name, channelAvatar, channelInfo.isVerified)

        val relevantInfoTabs = channelInfo.tabs.filter { tab ->
            relevantTabs.any { tab.contentFilters.contains(it) }
        }

        val related = relevantInfoTabs.parallelMap { tab ->
            runCatching {
                ChannelTabInfo.getInfo(NewPipeExtractorInstance.extractor, tab).relatedItems
            }.getOrElse { emptyList() }
        }.flatten().filterIsInstance<StreamInfoItem>()
            .filter {
                it.contentAvailability in arrayOf(
                    ContentAvailability.AVAILABLE,
                    ContentAvailability.UPCOMING,
                    ContentAvailability.UNKNOWN
                )
            }

        val streamItems = related.map { item ->
            // avatar is not always included in these info items, thus must be taken from channel info response
            item.toStreamItem(
                channelAvatar,
                // shorts fetched via the shorts tab don't have upload dates so we fall back to the feedInfo
                feedInfoItems[item.url.toID()]
            )
        }.filter { it.uploaded > minimumDateMillis }
        return Pair(subscription, streamItems)
    }

    companion object {
        /**
         * Amount of channels that are imported concurrently (UserDataRepository).
         */
        const val CHANNEL_CHUNK_SIZE = 5

        /** YT: amount of channels that are fetched at the same time (a sliding window). */
        const val CHANNEL_CONCURRENCY = 8

        /** YT: age of the cached feed after which opening the tab refreshes it in the background. */
        private const val AUTO_REFRESH_HOURS = 3L

        /**
         * Maximum amount of feeds that should be fetched together, before a delay should be applied.
         */
        const val CHANNEL_BATCH_SIZE = 50

        /**
         * Millisecond delay after fetching `BATCH_SIZE` channels to avoid throttling.
         *
         * A channel is only counted as fetched when it had a recent upload, requiring to fetch
         * the channelInfo via Innertube.
         */
        val CHANNEL_BATCH_DELAY = (500L..1500L)

        private const val MAX_FEED_AGE_DAYS = 30L // 30 days
    }
}