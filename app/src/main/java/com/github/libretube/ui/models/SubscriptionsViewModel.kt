package com.github.libretube.ui.models

import android.content.Context
import android.os.Parcelable
import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.libretube.R
import com.github.libretube.api.SubscriptionHelper
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.api.obj.Subscription
import com.github.libretube.db.obj.SubscriptionGroup
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.repo.FeedProgress
import com.github.libretube.repo.FeedRefresh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

class SubscriptionsViewModel : ViewModel() {
    var videoFeed = MutableLiveData<List<StreamItem>?>()

    var subscriptions = MutableLiveData<List<Subscription>?>()
    val feedProgress = MutableLiveData<FeedProgress?>()

    var subFeedRecyclerViewState: Parcelable? = null

    val groups = MutableLiveData<List<SubscriptionGroup>>()
    var groupToEdit: SubscriptionGroup? = null

    /** YT: true while the feed loads (also when the result equals the list shown). */
    val feedLoading = MutableLiveData(false)
    private var feedJob: Job? = null
    @Volatile private var feedJobRefreshes = false

    fun fetchFeed(context: Context, refresh: FeedRefresh) {
        val refreshes = refresh !is FeedRefresh.Automatically
        // YT: a refresh while one runs (a pull during the background refresh, a second pull)
        // waits for the running one instead of starting another walk over every channel
        if (feedJob?.isActive == true && (feedJobRefreshes || !refreshes)) return
        feedJobRefreshes = refreshes
        feedLoading.value = true
        feedJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!loadFeed(context, refresh, showErrors = true)) return@launch
                // YT: the cache came at once; when it is old, the channels are fetched in the
                // background while the cached list stays on screen
                if (!refreshes && SubscriptionHelper.isFeedRefreshDue()) {
                    feedJobRefreshes = true
                    loadFeed(context, FeedRefresh.All, showErrors = false)
                }
            } finally {
                feedLoading.postValue(false)
            }
        }
    }

    /** @return false if loading failed. */
    private suspend fun loadFeed(context: Context, refresh: FeedRefresh, showErrors: Boolean): Boolean {
        val startedAt = System.currentTimeMillis()
        val videoFeed = try {
            SubscriptionHelper.getFeed(refresh) { feedProgress ->
                this@SubscriptionsViewModel.feedProgress.postValue(feedProgress)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (showErrors) context.toastFromMainDispatcher(R.string.server_error)
            Log.e(TAG(), e.toString())
            return false
        }
        Log.i(TAG(), "feed ($refresh) loaded ${videoFeed.size} videos in ${System.currentTimeMillis() - startedAt} ms")
        this@SubscriptionsViewModel.videoFeed.postValue(videoFeed)
        videoFeed.firstOrNull { !it.isUpcoming }?.uploaded?.let {
            PreferenceHelper.updateLastFeedWatchedTime(it, false)
        }
        return true
    }

    fun fetchSubscriptions(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val subscriptions = try {
                SubscriptionHelper.getSubscriptions()
            } catch (e: Exception) {
                context.toastFromMainDispatcher(R.string.server_error)
                Log.e(TAG(), e.toString())
                return@launch
            }
            this@SubscriptionsViewModel.subscriptions.postValue(subscriptions)
        }
    }
}
