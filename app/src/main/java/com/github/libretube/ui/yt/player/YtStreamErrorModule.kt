package com.github.libretube.ui.yt.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.github.libretube.R
import com.github.libretube.api.obj.Streams
import com.github.libretube.constants.IntentData
import com.github.libretube.extensions.toastFromMainThread
import com.github.libretube.helpers.IntentHelper
import com.github.libretube.helpers.ThemeHelper
import com.github.libretube.helpers.YtDesign
import com.github.libretube.services.YtStreamFailure
import com.github.libretube.ui.yt.YtContrast
import com.github.libretube.ui.yt.YtInk
import com.github.libretube.ui.yt.YtPalette
import com.github.libretube.ui.yt.YtTheme
import com.github.libretube.ui.yt.palette
import com.github.libretube.ui.yt.YtTestHooks
import com.github.libretube.ui.yt.ytCardShape
import com.github.libretube.ui.yt.ytPillShape
import com.github.libretube.ui.yt.ytSurface
import com.github.libretube.ui.yt.ytSurfaceTones
import com.github.libretube.util.PlayingQueue
import com.github.libretube.util.YtStreamError
import com.github.libretube.util.YtStreamError.Kind
import com.google.android.material.color.MaterialColors

/**
 * YT: the player page when a video's streams could not be loaded (YtStreamFailure tells it
 * through the playlist metadata). Instead of the endless spinner over the picture and an empty
 * page below, the page shows why (per [Kind]) with "Erneut versuchen", "In YouTube öffnen" and,
 * if the queue has one, "Nächstes Video"; the list of next videos of the video before stays.
 * The mini player shows the short reason as its title. Every design and the classic page get
 * the same card, drawn in the design's surface (words in its YtInk, Pitfall P1).
 * While a page is in sight the service shows no toast for it.
 */
object YtStreamErrorModule : YtPlayerModule {
    private class Failure(val videoId: String, val kind: Kind, val detail: String, val next: String?)

    private class Page(val host: YtPlayerHost) : LifecycleEventObserver {
        var started = false
        var controller: MediaController? = null
        var view: ComposeView? = null
        var failure by mutableStateOf<Failure?>(null)

        val listener = object : Player.Listener {
            override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) = read(this@Page, mediaMetadata.extras)
        }

        override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
            when (event) {
                Lifecycle.Event.ON_START -> if (!started) {
                    started = true
                    YtStreamFailure.pageShown()
                }
                Lifecycle.Event.ON_STOP -> stop()
                Lifecycle.Event.ON_DESTROY -> {
                    stop()
                    source.lifecycle.removeObserver(this)
                }
                else -> Unit
            }
        }

        fun stop() {
            if (!started) return
            started = false
            YtStreamFailure.pageHidden()
        }
    }

    private var page: Page? = null

    override fun onViewCreated(host: YtPlayerHost) {
        page?.let { release(it) }
        val page = Page(host)
        this.page = page
        host.fragment().viewLifecycleOwner.lifecycle.addObserver(page)
    }

    override fun onController(host: YtPlayerHost, controller: MediaController) {
        val page = page?.takeIf { it.host === host } ?: return
        page.controller?.removeListener(page.listener)
        page.controller = controller
        controller.addListener(page.listener)
        // a page that connects after the load failed (made anew, or attached late)
        read(page, controller.playlistMetadata.extras)
    }

    override fun onVideo(host: YtPlayerHost, videoId: String, streams: Streams) {
        val page = page?.takeIf { it.host === host } ?: return
        hide(page)
    }

    override fun onDestroyView(host: YtPlayerHost) {
        val page = page?.takeIf { it.host === host } ?: return
        release(page)
        this.page = null
    }

    private fun release(page: Page) {
        page.controller?.removeListener(page.listener)
        page.controller = null
        page.stop()
        page.failure = null
        page.view?.let { (it.parent as? ViewGroup)?.removeView(it) }
        page.view = null
    }

    /** The playlist metadata of a load: a failure shows, a load that starts again ends it. */
    private fun read(page: Page, extras: Bundle?) {
        val id = extras?.getString(IntentData.videoId) ?: return
        val kind = YtStreamFailure.kindOf(extras)
        if (kind == null) {
            hide(page)
            return
        }
        show(page, Failure(id, kind, extras.getString(YtStreamFailure.KEY_DETAIL).orEmpty(), YtStreamError.nextAfter(id, PlayingQueue.getNext())))
    }

    private fun hide(page: Page) {
        if (page.failure == null) return
        page.failure = null
        page.view?.isVisible = false
    }

    private fun show(page: Page, failure: Failure) {
        val binding = page.host.pageBinding() ?: return
        val context = binding.root.context
        YtTestHooks.log("YtStreamError") { "video=${failure.videoId} kind=${failure.kind}" }
        // no spinner over the picture for a load that has ended
        binding.player.backgroundBinding.videoTransitionProgress.isVisible = false
        // the next videos of the video before stay to pick from
        if ((binding.relatedRecView.adapter?.itemCount ?: 0) > 0) binding.relatedRecView.isInvisible = false
        // the docked mini player reads its title and channel from these views
        binding.titleTextView.text = context.getString(YtStreamFailure.titleRes(failure.kind))
        binding.playerChannelName.text = ""
        // a design's page would keep showing the video before
        page.host.clearVideoDetails()
        val view = page.view ?: create(page, binding.linLayout).also { page.view = it }
        view.isVisible = true
        page.failure = failure
    }

    private fun create(page: Page, container: LinearLayout): ComposeView {
        val design = YtDesign.current()
        val palette = design.palette
        val standard = if (palette == null) StandardColors.of(container) else null
        val view = ComposeView(container.context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val failure = page.failure ?: return@setContent
                val onRetry = { retry(page, failure) }
                val onOpen = { openOutside(context, failure.videoId) }
                val onNext = failure.next?.let { next -> { page.host.playVideo(next) } }
                if (palette != null) {
                    YtTheme(palette) { DesignCard(palette, failure, onRetry, onOpen, onNext) }
                } else {
                    Card(standard!!, failure, onRetry, onOpen, onNext)
                }
            }
        }
        container.addView(
            view,
            0,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        return view
    }

    private fun retry(page: Page, failure: Failure) {
        hide(page)
        // loading again: the spinner over the picture as for any load
        page.host.pageBinding()?.player?.backgroundBinding?.videoTransitionProgress?.isVisible = true
        page.host.playVideo(failure.videoId)
    }

    /** The video in another app (the YouTube app or a browser), never in YT itself. */
    private fun openOutside(context: Context, videoId: String) {
        val link = "https://www.youtube.com/watch?v=$videoId"
        val view = Intent(Intent.ACTION_VIEW, link.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val handlers = IntentHelper.getResolveInfo(context, link)
        val own = handlers.filter { it.activityInfo.packageName == context.packageName }
            .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
        val others = handlers.map { it.activityInfo.packageName }.filter { it != context.packageName }.distinct()
        val intent = when {
            YOUTUBE_APP in others -> view.setPackage(YOUTUBE_APP)
            others.size == 1 -> view.setPackage(others.first())
            else -> Intent.createChooser(view, null)
                .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, own.toTypedArray())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { context.toastFromMainThread(R.string.yt_stream_error_open_failed) }
    }

    private const val YOUTUBE_APP = "com.google.android.youtube"

    /** The colours of the classic page (Material theme of the views). */
    private class StandardColors(val back: Color, val text: Color, val textSecondary: Color, val accent: Color, val onAccent: Color) {
        companion object {
            fun of(view: android.view.View): StandardColors {
                fun attr(id: Int) = Color(MaterialColors.getColor(view, id))
                return StandardColors(
                    back = attr(com.google.android.material.R.attr.colorSurfaceContainerHigh),
                    text = attr(com.google.android.material.R.attr.colorOnSurface),
                    textSecondary = attr(com.google.android.material.R.attr.colorOnSurfaceVariant),
                    accent = attr(androidx.appcompat.R.attr.colorPrimary),
                    onAccent = attr(com.google.android.material.R.attr.colorOnPrimary),
                )
            }
        }
    }

    /** What the card is drawn with: its fill, its words, its buttons. */
    private class Look(
        val card: Modifier,
        val title: Color,
        val body: Color,
        val primaryBack: Color,
        val primaryText: Color,
        val secondaryText: Color,
        val buttonShape: Shape,
        val font: FontFamily?,
    )

    @Composable
    private fun DesignCard(palette: YtPalette, failure: Failure, onRetry: () -> Unit, onOpen: () -> Unit, onNext: (() -> Unit)?) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val shape = ytCardShape(palette)
        val look = remember(palette) {
            // the card stands on the page, which is the design's background or the video's glow
            // over the theme's background: words readable on the card over both
            val under = intArrayOf(palette.background.toArgb() or (0xFF shl 24), ThemeHelper.getThemeColor(context, android.R.attr.colorBackground) or (0xFF shl 24))
            val ink = YtInk.onAll(palette, under.flatMap { ytSurfaceTones(palette, it).toList() }.toIntArray())
            val accent = palette.accent.toArgb() or (0xFF shl 24)
            Look(
                card = Modifier.ytSurface(palette, shape),
                title = ink.text,
                body = ink.textSecondary,
                primaryBack = palette.accent,
                primaryText = Color(YtContrast.readable(palette.onAccent.toArgb(), accent)),
                secondaryText = ink.text,
                buttonShape = ytPillShape(palette),
                font = palette.font,
            )
        }
        Content(look, failure, onRetry, onOpen, onNext)
    }

    @Composable
    private fun Card(colors: StandardColors, failure: Failure, onRetry: () -> Unit, onOpen: () -> Unit, onNext: (() -> Unit)?) {
        val look = remember(colors) {
            val shape = RoundedCornerShape(16.dp)
            Look(
                card = Modifier.clip(shape).background(colors.back),
                title = colors.text,
                body = colors.textSecondary,
                primaryBack = colors.accent,
                primaryText = colors.onAccent,
                secondaryText = colors.text,
                buttonShape = RoundedCornerShape(50),
                font = null,
            )
        }
        Content(look, failure, onRetry, onOpen, onNext)
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Content(look: Look, failure: Failure, onRetry: () -> Unit, onOpen: () -> Unit, onNext: (() -> Unit)?) {
        Column(
            Modifier
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .fillMaxWidth()
                .then(look.card)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(YtStreamFailure.titleRes(failure.kind)),
                color = look.title,
                fontFamily = look.font,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(YtStreamFailure.bodyRes(failure.kind)),
                color = look.body,
                fontFamily = look.font,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            if (failure.detail.isNotBlank()) {
                Text(
                    failure.detail,
                    color = look.body,
                    fontFamily = look.font,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    maxLines = 3,
                )
            }
            FlowRow(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // the way out that fits the reason comes first
                val retryFirst = YtStreamError.retryMayHelp(failure.kind)
                val retry = stringResource(R.string.yt_stream_error_retry)
                val open = stringResource(R.string.yt_stream_error_open)
                if (retryFirst) {
                    Button(look, retry, primary = true, onRetry)
                    Button(look, open, primary = false, onOpen)
                } else {
                    Button(look, open, primary = true, onOpen)
                    Button(look, retry, primary = false, onRetry)
                }
                if (onNext != null) Button(look, stringResource(R.string.yt_stream_error_next), primary = false, onNext)
            }
        }
    }

    @Composable
    private fun Button(look: Look, label: String, primary: Boolean, onClick: () -> Unit) {
        val base = Modifier
            .heightIn(min = 40.dp)
            .clip(look.buttonShape)
        val filled = if (primary) {
            base.background(look.primaryBack)
        } else {
            base.border(1.dp, look.secondaryText.copy(alpha = 0.5f), look.buttonShape)
        }
        androidx.compose.foundation.layout.Box(
            filled
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                color = if (primary) look.primaryText else look.secondaryText,
                fontFamily = look.font,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
            )
        }
    }
}
