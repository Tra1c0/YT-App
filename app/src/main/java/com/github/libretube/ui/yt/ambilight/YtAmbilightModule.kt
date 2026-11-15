package com.github.libretube.ui.yt.ambilight

import android.content.Context
import android.view.SurfaceView
import android.view.View
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.github.libretube.api.obj.Streams
import com.github.libretube.databinding.FragmentPlayerBinding
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.palette
import com.github.libretube.ui.yt.player.YtPlayerHost
import com.github.libretube.ui.yt.player.YtPlayerModule

/**
 * YT: the ambilight on the video player page (YtAmbilight does the work). The page's scroll view
 * draws the glow under its content, the player's background its bars; the media controller
 * says when the video plays, pauses or jumps. Everything is in place for as long as the page
 * has its view and draws nothing while the ambilight is off, so switching it on in the
 * settings shows at once.
 */
object YtAmbilightModule : YtPlayerModule {
    private var glue: Glue? = null

    override fun onViewCreated(host: YtPlayerHost) {
        glue?.dispose()
        val binding = host.pageBinding() ?: return
        glue = Glue(host, binding).also { it.install() }
    }

    override fun onController(host: YtPlayerHost, controller: MediaController) {
        glue?.takeIf { it.host === host }?.listenTo(controller)
    }

    override fun onVideo(host: YtPlayerHost, videoId: String, streams: Streams) {
        glue?.takeIf { it.host === host }?.video(streams)
    }

    override fun onConfigurationChanged(host: YtPlayerHost) {
        glue?.takeIf { it.host === host }?.relayout()
    }

    override fun onDestroyView(host: YtPlayerHost) {
        val g = glue ?: return
        if (g.host !== host) return
        g.dispose()
        glue = null
    }

    private class Glue(val host: YtPlayerHost, val binding: FragmentPlayerBinding) :
        YtAmbilight.Page, YtAmbilight.Listener, LifecycleEventObserver {
        private val scroll = binding.playerScrollView
        private val player = binding.player
        private val frame: View? = runCatching { player.backgroundBinding.exoContentFrame }.getOrNull()
        private val bars = YtAmbilightBarsDrawable(player, frame)
        private var oldBackground = player.background
        private var drewPage = false
        private var controller: MediaController? = null
        private var owner: LifecycleOwner? = null

        private val frameMoved = View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (l != ol || t != ot || r != or || b != ob) player.invalidate()
        }

        private val playerListener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                YtAmbilight.playback(this@Glue, isPlaying)
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                YtAmbilight.seeked(this@Glue)
            }
        }

        fun install() {
            val context = host.fragment().requireContext()
            scroll.under = { canvas, width, height ->
                drewPage = YtAmbilightPaint.page(canvas, width, height)
            }
            oldBackground = player.background
            player.background = bars
            frame?.addOnLayoutChangeListener(frameMoved)
            val (back, text, text2) = ink(context)
            YtAmbilight.attachPage(this, back, text, text2)
            YtAmbilight.addListener(this)
            host.controller()?.let { listenTo(it) }
            host.currentStreams()?.let { video(it) }
            val lifecycleOwner = host.fragment().viewLifecycleOwner
            owner = lifecycleOwner
            lifecycleOwner.lifecycle.addObserver(this)
        }

        fun dispose() {
            owner?.lifecycle?.removeObserver(this)
            owner = null
            controller?.removeListener(playerListener)
            controller = null
            YtAmbilight.removeListener(this)
            YtAmbilight.detachPage(this)
            frame?.removeOnLayoutChangeListener(frameMoved)
            scroll.under = null
            if (player.background === bars) player.background = oldBackground
        }

        fun listenTo(next: MediaController) {
            if (controller === next) return
            controller?.removeListener(playerListener)
            controller = next
            next.addListener(playerListener)
            YtAmbilight.playback(this, next.isPlaying)
        }

        fun video(streams: Streams) {
            YtAmbilight.video(this, streams.previewFrames, streams.thumbnailUrl)
        }

        fun relayout() {
            player.invalidate()
            scroll.invalidate()
        }

        /** The page's background and text colours: the design's palette or the classic theme. */
        private fun ink(context: Context): Triple<Int, Int, Int> {
            val palette = YtDesign.current().palette
            if (palette != null) {
                return Triple(palette.background.toArgb(), palette.text.toArgb(), palette.textSecondary.toArgb())
            }
            val back = ytThemeColor(context, android.R.attr.colorBackground, BLACK)
            val text = ytThemeColor(context, com.google.android.material.R.attr.colorOnSurface, WHITE)
            val text2 = ytThemeColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, text)
            return Triple(back, text, text2)
        }

        override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
            when (event) {
                Lifecycle.Event.ON_START -> YtAmbilight.pageStarted(this, true)
                Lifecycle.Event.ON_STOP -> YtAmbilight.pageStarted(this, false)
                else -> Unit
            }
        }

        // -- YtAmbilight.Page ---------------------------------------------------------------------

        override fun context(): Context? = host.fragment().context

        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        override fun surfaceView(): SurfaceView? = player.videoSurfaceView as? SurfaceView

        override fun place(): YtAmbilightPlace {
            val fragment = host.fragment()
            val activity = fragment.activity ?: return YtAmbilightPlace.HIDDEN
            if (!fragment.isAdded || fragment.view == null || activity.isInPictureInPictureMode) return YtAmbilightPlace.HIDDEN
            // fullscreen moves the player into a dialog
            if (player.parent !== binding.playerMotionLayout) {
                return if (player.isShown) YtAmbilightPlace.FULLSCREEN else YtAmbilightPlace.HIDDEN
            }
            if (!binding.playerMotionLayout.isShown) return YtAmbilightPlace.HIDDEN
            val progress = binding.playerMotionLayout.progress
            return when {
                progress <= 0.05f -> YtAmbilightPlace.OPEN
                progress >= 0.95f -> YtAmbilightPlace.DOCKED
                else -> YtAmbilightPlace.MOVING
            }
        }

        override fun barsShown(): Boolean = bars.barsShown()

        override fun positionMs(): Long = controller?.currentPosition ?: 0L

        // -- YtAmbilight.Listener -----------------------------------------------------------------

        override fun onAmbilight() {
            // the page glow only where the page is seen; once more when it goes, to clear it
            val pageSeen = scroll.isShown && scroll.alpha > 0f && YtAmbilight.place != YtAmbilightPlace.FULLSCREEN
            if ((pageSeen && YtAmbilight.showing) || drewPage) scroll.invalidate()
            if (bars.drewBars || (YtAmbilight.showing && player.isShown && bars.barsShown())) player.invalidate()
        }

        companion object {
            private const val BLACK = -0x1000000
            private const val WHITE = -0x1
        }
    }
}
