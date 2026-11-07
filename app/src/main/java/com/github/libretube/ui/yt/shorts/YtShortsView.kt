package com.github.libretube.ui.yt.shorts

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.os.bundleOf
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.IntentData
import com.github.libretube.enums.ShareObjectType
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.ContextHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.helpers.YtDesign
import com.github.libretube.helpers.YtHearts
import com.github.libretube.obj.ShareData
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.ui.activities.AbstractPlayerHostActivity
import com.github.libretube.ui.dialogs.ShareDialog
import com.github.libretube.ui.fragments.DownloadTab
import com.github.libretube.ui.yt.YtPalettes
import com.github.libretube.ui.yt.YtTestHooks
import com.github.libretube.ui.yt.YtTheme
import com.github.libretube.ui.yt.palette
import com.github.libretube.ui.yt.ytNight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * YT: the Shorts view, a full-screen vertical pager of shorts over the app (one short per page,
 * swipe up for the next). It opens from the Shorts tab and from the Shorts rows of the feeds,
 * on the short that was tapped, with the list it came from; near the end of that list it adds
 * what [open]'s `more` loads, else the shorts related to the one on screen.
 *
 * A tap pauses, a double tap gives a heart (the hearts of Side, kept on the device), the
 * channel opens from its name, share and "open in video player" sit on the right; the hidden
 * timeline (8.1) and autoscroll (8.2) are in [YtShortsTimeline] and [YtShortsViewScreen]. Back
 * closes it. Playback: [YtShortsPlayer].
 */
object YtShortsView {
    private const val TAG = "yt_shorts_view"

    /**
     * Opens the view on [start] within [items]. False if the view is turned off in the
     * settings or cannot open here; the caller then opens the short as before.
     */
    fun open(
        context: Context,
        items: List<StreamItem>,
        start: StreamItem,
        more: (suspend () -> List<StreamItem>)? = null,
    ): Boolean {
        if (!YtShortsPrefs.viewEnabled) return false
        val activity = ContextHelper.tryUnwrapActivity<FragmentActivity>(context) ?: return false
        if (activity.supportFragmentManager.isStateSaved) return false
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return false
        if (root.findViewWithTag<View>(TAG) != null) return true
        val startId = start.url?.toID() ?: return false

        val list = items.filter { it.url != null }.distinctBy { it.url!!.toID() }
            .let { if (it.any { item -> item.url!!.toID() == startId }) it else listOf(start) + it }
        val startPage = YtShortsList.startPage(list, startId) { it.url?.toID() }
        YtShortsHost(activity, root, list, startPage, more).show()
        YtTestHooks.log(YtShortsPlayer.TAG) { "open n=${list.size} start=$startId" }
        return true
    }

    /** The old way: a short in the video player (the long press, and the button in the view). */
    fun openInPlayer(context: Context, videoId: String, seconds: Long = 0L, offline: Boolean = false) {
        NavigationHelper.navigateVideo(
            context,
            PlayerData(
                videoId = videoId,
                timestamp = seconds,
                isOffline = offline,
                downloadTab = if (offline) DownloadTab.VIDEO else null,
            ),
        )
    }

    internal const val VIEW_TAG = TAG

    /** The open view's session, for the emulator checks (YtShortsTestHooks); null while closed. */
    internal var current: YtShortsSession? = null
}

/** What the screen shows and changes; lives as long as the view is open. */
class YtShortsSession(
    context: Context,
    val scope: CoroutineScope,
    initial: List<StreamItem>,
    val startPage: Int,
    private val more: (suspend () -> List<StreamItem>)?,
) {
    val items = mutableStateListOf<StreamItem>().apply { addAll(initial) }
    val player = YtShortsPlayer(context, scope)

    /** A finger is on the screen (a swipe or a hold): autoscroll waits. */
    var fingerDown by mutableStateOf(false)

    /** The timeline is open: autoscroll waits. */
    var timelineOpen by mutableStateOf(false)

    /** The short the user chose to stay on (tapped the countdown): it loops. */
    var stayOn by mutableStateOf<String?>(null)

    /** The autoscroll setting, changed by the quick toggle too. */
    var autoscroll by mutableIntStateOf(YtShortsPrefs.autoscroll)

    var hearts by mutableStateOf(YtHearts.all())
        private set

    private var loadingMore = false
    private var moreDone = more == null
    private val relatedTried = HashSet<String>()
    private var resumeAfterStop = false

    fun idOf(item: StreamItem?): String? = item?.url?.toID()

    fun toggleHeart(videoId: String) {
        hearts = YtHearts.toggle(videoId)
    }

    /** Double tap: always a heart, never taking one away. */
    fun heart(videoId: String) {
        if (videoId !in hearts) hearts = YtHearts.toggle(videoId)
    }

    fun toggleAutoscroll(): Int = YtShortsPrefs.toggleAutoscroll().also { autoscroll = it }

    /** Near the end of the list: the source's next shorts, else those related to [page]'s short. */
    fun maybeLoadMore(page: Int) {
        if (!YtShortsList.shouldLoadMore(page, items.size, loadingMore, exhausted = false)) return
        val source = more
        val related = idOf(items.getOrNull(page))?.let { id ->
            player.known(id)?.takeIf { id !in relatedTried }
        }
        if (moreDone && related == null) return
        loadingMore = true
        scope.launch {
            var found: List<StreamItem> = emptyList()
            if (!moreDone && source != null) {
                found = runCatching { source() }.getOrDefault(emptyList())
                if (found.isEmpty()) moreDone = true
            }
            if (found.isEmpty() && related != null) {
                relatedTried += related.videoId
                found = related.relatedShorts()
            }
            val before = items.size
            val merged = YtShortsList.append(items.toList(), found) { it.url?.toID() }
            if (merged.size > before) {
                items.addAll(merged.subList(before, merged.size))
                YtTestHooks.log(YtShortsPlayer.TAG) { "more n=${merged.size - before}" }
            }
            loadingMore = false
        }
    }

    fun pauseForBackground() {
        resumeAfterStop = player.player.isPlaying
        player.player.pause()
    }

    fun resumeFromBackground() {
        if (resumeAfterStop && !player.userPaused) player.player.play()
        resumeAfterStop = false
    }

    fun release() = player.release()
}

/** The view on the screen: the overlay, back, the lifecycle and the system bars. */
private class YtShortsHost(
    private val activity: FragmentActivity,
    private val root: ViewGroup,
    items: List<StreamItem>,
    startPage: Int,
    more: (suspend () -> List<StreamItem>)?,
) {
    private val scope = MainScope()
    private val session = YtShortsSession(activity, scope, items, startPage, more)
    private val view = ComposeView(activity)
    private val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
    private val lightStatus = bars.isAppearanceLightStatusBars
    private val lightNavigation = bars.isAppearanceLightNavigationBars
    private var closed = false
    private val closing = mutableStateOf(false)

    private val back = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = close()
    }

    private val lifecycle = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) = session.resumeFromBackground()
        override fun onStop(owner: LifecycleOwner) = session.pauseForBackground()
        override fun onDestroy(owner: LifecycleOwner) = remove()
    }

    fun show() {
        pauseMainPlayer()
        activity.onBackPressedDispatcher.addCallback(activity, back)
        activity.lifecycle.addObserver(lifecycle)
        // the view is dark: light icons in the system bars while it is open
        bars.isAppearanceLightStatusBars = false
        bars.isAppearanceLightNavigationBars = false

        val design = YtDesign.current()
        val palette = design.palette ?: YtPalettes.YouTube
        val style = YtShortsStyle(YtShortsLook.of(design, ytNight))
        view.tag = YtShortsView.VIEW_TAG
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            YtTheme(palette) {
                YtShortsViewScreen(
                    session = session,
                    style = style,
                    closing = closing.value,
                    actions = YtShortsActions(
                        onClose = ::close,
                        onClosed = ::remove,
                        onChannel = ::openChannel,
                        onShare = ::share,
                        onOpenInPlayer = ::openInPlayer,
                    ),
                )
            }
        }
        root.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        YtShortsView.current = session
    }

    /** Back or the close button: the screen fades out, then [remove]. */
    fun close() {
        if (closed) return
        back.remove()
        session.player.player.pause()
        closing.value = true
    }

    /** Takes the view away and frees the player (also when the activity goes). */
    fun remove() {
        if (closed) return
        closed = true
        back.remove()
        activity.lifecycle.removeObserver(lifecycle)
        bars.isAppearanceLightStatusBars = lightStatus
        bars.isAppearanceLightNavigationBars = lightNavigation
        if (YtShortsView.current === session) YtShortsView.current = null
        session.player.player.pause()
        // the composition goes with the view; the player only after it, so nothing on screen
        // still talks to a released player
        view.post {
            root.removeView(view)
            session.release()
            scope.cancel()
        }
        YtTestHooks.log(YtShortsPlayer.TAG) { "closed" }
    }

    // the video or audio player stops while shorts play (the audio focus would pause it too,
    // but only once the first short plays)
    private fun pauseMainPlayer() {
        (activity as? AbstractPlayerHostActivity)?.runOnPlayerFragment {
            if (this.view != null) runCatching { binding.player.player?.pause() }
            true
        }
    }

    private fun openChannel(item: StreamItem) {
        val url = item.uploaderUrl ?: return
        remove()
        NavigationHelper.navigateChannel(activity, url)
    }

    private fun share(item: StreamItem) {
        val id = item.url?.toID() ?: return
        if (activity.supportFragmentManager.isStateSaved) return
        ShareDialog().apply {
            arguments = bundleOf(
                IntentData.id to id,
                IntentData.shareObjectType to ShareObjectType.VIDEO,
                IntentData.shareData to ShareData(title = item.title, previewImageUrl = item.thumbnail),
            )
        }.show(activity.supportFragmentManager, ShareDialog::class.java.name)
    }

    private fun openInPlayer(item: StreamItem) {
        val id = item.url?.toID() ?: return
        val player = session.player
        val here = player.currentId == id
        val seconds = if (here) player.player.currentPosition / 1000L else 0L
        val offline = here && player.media?.isLocal == true
        remove()
        YtShortsView.openInPlayer(activity, id, seconds, offline)
    }
}
