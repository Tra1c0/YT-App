package com.github.libretube.api

import com.github.libretube.api.obj.StreamItem
import com.github.libretube.api.obj.Subscription
import com.github.libretube.db.obj.SubscriptionsFeedItem
import com.github.libretube.repo.FeedProgress
import com.github.libretube.repo.FeedRefresh
import com.github.libretube.repo.FeedRepository
import com.github.libretube.repo.UserDataRepository
import com.github.libretube.repo.UserDataRepositoryHelper
import com.github.libretube.repo.YtFeedFlight

object SubscriptionHelper {
    @Suppress("DEPRECATION")
    private val userDataRepository: UserDataRepository
        get() = UserDataRepositoryHelper.userDataRepository
    @Suppress("DEPRECATION")
    private val feedRepository: FeedRepository
        get() = UserDataRepositoryHelper.feedRepository

    suspend fun subscribe(
        channelId: String, name: String, uploaderAvatar: String?, verified: Boolean
    ) = userDataRepository.subscribe(channelId, name, uploaderAvatar, verified)

    suspend fun unsubscribe(channelId: String) {
        userDataRepository.unsubscribe(channelId)
        // remove videos from (local) feed
        feedRepository.removeChannel(channelId)
    }
    suspend fun isSubscribed(channelId: String) = userDataRepository.isSubscribed(channelId)
    suspend fun importSubscriptions(newChannels: List<String>) =
        userDataRepository.importSubscriptions(newChannels)

    suspend fun getSubscriptions() =
        userDataRepository.getSubscriptions().sortedBy { it.name.lowercase() }

    suspend fun getSubscriptionChannelIds() = userDataRepository.getSubscriptionChannelIds()
    // YT: a network refresh joins the one already running (YtFeedFlight); reading the cached
    // feed (Automatically) never waits for it
    private val feedFlight = YtFeedFlight<List<StreamItem>>()

    suspend fun getFeed(refresh: FeedRefresh, onProgressUpdate: (FeedProgress) -> Unit = {}): List<StreamItem> {
        if (refresh is FeedRefresh.Automatically) {
            val cached = feedRepository.getFeed(refresh, onProgressUpdate)
            // nothing cached yet (first start, account switch): there is nothing to show, so
            // fetch every channel now. Channels without a recent video leave the cache empty,
            // so that waits for the next due time instead of running on every open.
            if (cached.isNotEmpty() || !isFeedRefreshDue()) return cached
            return getFeed(FeedRefresh.All, onProgressUpdate)
        }
        return feedFlight.run(onProgressUpdate) { progress -> feedRepository.getFeed(refresh, progress) }
    }

    /** YT: whether the cached feed is old enough for a refresh in the background. */
    fun isFeedRefreshDue() = feedRepository.isRefreshDue()

    suspend fun submitFeedItemChange(feedItem: SubscriptionsFeedItem) =
        feedRepository.submitFeedItemChange(feedItem)

    suspend fun submitSubscriptionChannelInfosChanged(subscriptions: List<Subscription>) =
        userDataRepository.submitSubscriptionChannelInfosChanged(subscriptions)
}
