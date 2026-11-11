package com.github.libretube.ui.yt

import android.content.Context
import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.libretube.R
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.ContextHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.helpers.YtDesign
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.ui.activities.MainActivity
import com.github.libretube.ui.fragments.DownloadTab
import com.github.libretube.util.PlayingQueue
import com.github.libretube.util.YtQueuePersist
import com.github.libretube.util.YtQueueStore
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.io.path.exists

/**
 * YT: the page side of the queue kept over a restart (YtQueueStore). A restored queue waits in
 * PlayingQueue without a player; the home shows the resume pill "Weiter: <title> · N in der
 * Warteschlange" ([YtQueueResumePill], in the classic design a snackbar), which starts the
 * current item where it was left with the whole queue. Nothing plays by itself.
 *
 * A video started some other way meanwhile (a card, a search result, a link that opened the app)
 * joins the restored queue instead of replacing it ([join]): it plays now, the restored queue
 * follows it. Started from a playlist or channel, it goes at the end, where that list loads after
 * it. A queue set up by its caller (play all, the time box, the mini player restore) replaces it
 * as before, and so does a download (online and offline items never mix in one queue).
 */
object YtQueueResume {
    /** The main activity was created (at the end of its onCreate). */
    fun onActivityCreated(activity: MainActivity, savedInstanceState: Bundle?) {
        // created anew (not a rotation or a design switch): nothing else set a queue? Then the
        // saved one goes back now, with the pill. At the start of the process the store does
        // this itself once the file is read.
        if (savedInstanceState == null) YtQueueStore.applyIfLoaded(announce = true)
        // the classic design has no home pills: a snackbar offers it once
        if (YtDesign.current() != YtDesign.STANDARD) return
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                YtQueueStore.restored.filterNotNull().collect { state ->
                    if (state.announce) showSnackbar(activity)
                }
            }
        }
    }

    private fun showSnackbar(activity: MainActivity) {
        val current = PlayingQueue.getCurrent() ?: return
        val text = pillText(activity, current.ytTitle, PlayingQueue.size())
        // shown once per restore: hidden from now on, whether it is used or not
        YtQueueStore.hidePill()
        val anchor = activity.findViewById<android.view.View>(R.id.bottomNav)
        Snackbar.make(activity.findViewById(android.R.id.content), text, Snackbar.LENGTH_LONG)
            .setAction(R.string.yt_r25_queue_resume_action) { resume(activity) }
            .apply { if (anchor != null && anchor.isShown) anchorView = anchor }
            .show()
    }

    private fun pillText(context: Context, title: String?, count: Int): String {
        val name = title?.takeIf { it.isNotBlank() } ?: context.getString(R.string.yt_r25_queue_untitled)
        val resume = context.getString(R.string.yt_r25_queue_resume, name)
        return if (YtQueuePersist.showsCount(count)) "$resume · ${context.getString(R.string.yt_r25_queue_count, count)}" else resume
    }

    /**
     * NavigationHelper.navigateVideo starts [data] (main thread). With a restored queue waiting
     * (put back now if it is due: a link may open the app before the store read its file), the
     * video joins it and the player keeps it ([PlayerData.keepQueue]). Anything else goes on as is.
     */
    fun join(data: PlayerData): PlayerData {
        if (data.keepQueue || data.isOffline) return data
        val videoId = data.videoId?.toID()?.takeIf { it.isNotBlank() } ?: return data
        if (!YtQueueStore.applyIfCandidate(announce = false)) return data
        val list = PlayingQueue.getStreams()
        val key = { item: StreamItem -> item.url?.toID() }
        val item = list.firstOrNull { key(it) == videoId }
            ?: StreamItem(url = "/watch?v=$videoId", type = StreamItem.TYPE_STREAM)
        val place = YtQueuePersist.joinPlace(data.playlistId != null || data.channelId != null)
        val merged = YtQueuePersist.join(list, listOf(item), PlayingQueue.getCurrent()?.let(key), place, key)
        PlayingQueue.setStreams(merged)
        // the placeholder takes the video's details when the player loaded it (updateCurrent)
        PlayingQueue.updateCurrent(item)
        YtQueueStore.endRestored()
        YtTestHooks.log("YtQueue") { "joined restored queue id=$videoId place=$place n=${merged.size}" }
        return data.copy(keepQueue = true)
    }

    /**
     * A queue the caller puts together and starts itself ([videos], e.g. the home's queued cards
     * with the tapped one first) while a restored queue waits: the restored queue follows them.
     * Without one, [videos] as they are.
     */
    fun withRestored(videos: List<StreamItem>): List<StreamItem> {
        if (videos.isEmpty() || !YtQueueStore.applyIfCandidate(announce = false)) return videos
        val key = { item: StreamItem -> item.url?.toID() }
        val merged = YtQueuePersist.join(
            PlayingQueue.getStreams(), videos, PlayingQueue.getCurrent()?.let(key), YtQueuePersist.JoinPlace.BEFORE_CURRENT, key,
        )
        YtQueueStore.endRestored()
        return merged
    }

    /**
     * The pill: the restored queue's current item plays where it was left (the service takes
     * the position from the watch position ledger), the queue around it. A download plays from
     * its file if it is still there, else online.
     */
    fun resume(context: Context) {
        if (!YtQueueStore.applyIfCandidate(announce = false)) return
        val current = PlayingQueue.getCurrent() ?: return
        val id = current.url?.toID()?.takeIf { it.isNotBlank() } ?: return
        val saved = YtQueueStore.saved
        val audio = saved?.audio == true
        YtQueueStore.endRestored()
        YtTestHooks.log("YtQueue") { "resume restored queue id=$id n=${PlayingQueue.size()}" }
        if (saved?.offline != true) {
            NavigationHelper.navigateVideo(context, PlayerData(id, keepQueue = true), audioOnlyPlayerRequested = audio)
            return
        }
        val activity = ContextHelper.tryUnwrapActivity<FragmentActivity>(context)
            ?: return NavigationHelper.navigateVideo(context, PlayerData(id, keepQueue = true), audioOnlyPlayerRequested = audio)
        activity.lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching { Database.downloadDao().findById(id)?.downloadItems?.any { it.path.exists() } == true }.getOrDefault(false)
            }
            val data = if (file) {
                PlayerData(id, keepQueue = true, isOffline = true, downloadTab = if (audio) DownloadTab.AUDIO else DownloadTab.VIDEO)
            } else {
                PlayerData(id, keepQueue = true)
            }
            NavigationHelper.navigateVideo(context, data, audioOnlyPlayerRequested = audio)
        }
    }

    /** What the resume pill shows. */
    internal data class PillInfo(val title: String?, val count: Int)

    /**
     * The resume pill's content while it is to show (a restored queue waits and was announced),
     * else null. Follows the queue (an item added meanwhile counts).
     */
    @Composable
    internal fun rememberPill(): PillInfo? {
        val state by YtQueueStore.restored.collectAsState()
        val revision by PlayingQueue.revision.collectAsState()
        return remember(revision, state) {
            val current = PlayingQueue.getCurrent()
            if (state?.announce != true || current == null) null else PillInfo(current.ytTitle, PlayingQueue.size())
        }
    }

    /**
     * The resume pill in the home pill row (YtHomePills): tap resumes, the cross or a swipe
     * hides it (the queue stays).
     */
    @Composable
    internal fun Pill(look: PillLook, info: PillInfo) {
        val context = LocalContext.current
        val title = info.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.yt_r25_queue_untitled)
        val spoken = remember(title, info.count) { pillText(context, title, info.count) }
        SwipeAway(onGone = { YtQueueStore.hidePill() }) {
            PillRow(look, Modifier.testTag("yt_queue_resume_pill")) {
                // the whole text is the button (the cross beside it only hides the pill)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .testTag("yt_queue_resume_action")
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable(onClickLabel = stringResource(R.string.yt_r25_queue_resume_action)) { resume(context) }
                        .semantics(mergeDescendants = true) {
                            contentDescription = spoken
                            liveRegion = LiveRegionMode.Polite
                        }
                        .padding(end = 4.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_play), contentDescription = null, tint = look.text, modifier = Modifier.size(18.dp))
                    Text(
                        text = stringResource(R.string.yt_r25_queue_resume, title),
                        color = look.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = look.style(FontWeight.Medium),
                        modifier = Modifier
                            .padding(start = 10.dp)
                            .widthIn(max = 180.dp),
                    )
                    if (YtQueuePersist.showsCount(info.count)) {
                        Box(Modifier.padding(horizontal = 6.dp)) { Dot(look) }
                        Text(
                            text = stringResource(R.string.yt_r25_queue_count, info.count),
                            color = look.text,
                            maxLines = 1,
                            style = look.style(FontWeight.Bold),
                        )
                    }
                }
                Icon(
                    painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.yt_r25_queue_hide),
                    tint = look.text.copy(alpha = 0.7f),
                    modifier = Modifier
                        .testTag("yt_queue_resume_hide")
                        .padding(start = 2.dp)
                        .clip(CircleShape)
                        .clickable { YtQueueStore.hidePill() }
                        .padding(10.dp)
                        .size(16.dp),
                )
            }
        }
    }
}
