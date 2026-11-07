package com.github.libretube.repo

import com.github.libretube.api.obj.StreamItem
import com.github.libretube.db.obj.SubscriptionsFeedItem

data class FeedProgress(
    val currentProgress: Int,
    val total: Int
)

sealed class FeedRefresh {
    object Automatically: FeedRefresh()
    object All: FeedRefresh()
    class OnlySome(val channelIds: List<String>): FeedRefresh()
}

interface FeedRepository {
    /**
     * Loads the user's video feed based on the subscribed channels.
     * Depending on [refresh], the feed is refetched from YouTube.
     */
    suspend fun getFeed(
        refresh: FeedRefresh,
        onProgressUpdate: (FeedProgress) -> Unit = {}
    ): List<StreamItem>
    suspend fun removeChannel(channelId: String) {}

    /** YT: whether the cached feed should be refreshed in the background (see LocalFeedRepository). */
    fun isRefreshDue(): Boolean = false
    suspend fun submitFeedItemChange(feedItem: SubscriptionsFeedItem) {}
}