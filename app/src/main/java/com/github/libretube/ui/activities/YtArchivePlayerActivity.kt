package com.github.libretube.ui.activities

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.github.libretube.R
import com.github.libretube.ui.yt.archive.YtArchive
import com.github.libretube.ui.yt.archive.YtArchiveBoardIndex
import com.github.libretube.ui.yt.archive.YtArchiveFetcher
import com.github.libretube.ui.yt.archive.YtArchiveMeta
import com.github.libretube.ui.yt.archive.YtArchiveRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * YT: plays an archived copy (the archive of vanished videos): the 144p video with its audio
 * file merged, or the audio alone over the storyboard still of the moment (else the thumbnail).
 * Its own small ExoPlayer, apart from the playback service: the archived files are no download
 * LibreTube knows, and a vanished video has no online source for the service to fall back to.
 */
@OptIn(UnstableApi::class)
class YtArchivePlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private lateinit var still: ImageView
    private val ticker = Handler(Looper.getMainLooper())
    private var board: YtArchiveBoardIndex? = null
    private var boardDir: File? = null
    private var sheetIndex = -1
    private var sheet: Bitmap? = null
    private var lastFrame: YtArchiveRules.Frame? = null

    private val tick = object : Runnable {
        override fun run() {
            showStill()
            ticker.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val videoId = intent.getStringExtra(EXTRA_VIDEO_ID)
        if (videoId == null || !YtArchiveRules.validId(videoId)) {
            finish()
            return
        }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val view = PlayerView(this).apply {
            setShowNextButton(false)
            setShowPreviousButton(false)
            controllerShowTimeoutMs = 3000
        }
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        still = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        view.overlayFrameLayout?.addView(still, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val title = TextView(this).apply {
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
            textSize = 16f
            maxLines = 2
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        root.addView(title, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)

        lifecycleScope.launch {
            val meta = withContext(Dispatchers.IO) { YtArchive.store[videoId] }
            val files = YtArchive.store.files(videoId)
            if (meta == null || files == null || !files.audio.isFile) {
                failed()
                return@launch
            }
            title.text = meta.title
            withContext(Dispatchers.IO) { loadStills(meta) }
            start(meta, view)
        }
    }

    private fun loadStills(meta: YtArchiveMeta) {
        val files = YtArchive.store.files(meta.videoId) ?: return
        val index = File(files.storyboard, YtArchiveFetcher.BOARD_INDEX)
        board = runCatching { Json { ignoreUnknownKeys = true }.decodeFromString(YtArchiveBoardIndex.serializer(), index.readText()) }.getOrNull()
        boardDir = files.storyboard
        if (board == null && files.thumbnail.isFile) {
            val thumb = BitmapFactory.decodeFile(files.thumbnail.path)
            runOnUiThread { still.setImageBitmap(thumb) }
        }
    }

    private fun start(meta: YtArchiveMeta, view: PlayerView) {
        val files = YtArchive.store.files(meta.videoId) ?: return failed()
        val factory = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(this))
        fun source(file: File): MediaSource = factory.createMediaSource(MediaItem.fromUri(Uri.fromFile(file)))
        val audio = source(files.audio)
        val hasVideo = files.video.isFile
        val media = if (hasVideo) MergingMediaSource(source(files.video), audio) else audio
        still.visibility = if (hasVideo) android.view.View.GONE else android.view.View.VISIBLE
        val exo = ExoPlayer.Builder(this).build()
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = failed()
        })
        exo.setMediaSource(media)
        exo.prepare()
        exo.playWhenReady = true
        view.player = exo
        player = exo
        if (!hasVideo && board != null) ticker.post(tick)
    }

    /** The storyboard still at the player's position (audio only). */
    private fun showStill() {
        val board = board ?: return
        val dir = boardDir ?: return
        val position = player?.currentPosition ?: return
        val frame = YtArchiveRules.frameAt(position, board.durationPerFrameMs, board.perX, board.perY, board.total, board.sheets) ?: return
        if (frame == lastFrame) return
        lastFrame = frame
        if (frame.sheet != sheetIndex) {
            sheet = BitmapFactory.decodeFile(File(dir, "${frame.sheet}.jpg").path)
            sheetIndex = frame.sheet
        }
        val image = sheet ?: return
        val x = frame.x * board.frameWidth
        val y = frame.y * board.frameHeight
        if (x + board.frameWidth > image.width || y + board.frameHeight > image.height) return
        still.setImageBitmap(Bitmap.createBitmap(image, x, y, board.frameWidth, board.frameHeight))
    }

    private fun failed() {
        Toast.makeText(this, R.string.yt_r31_archive_play_failed, Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        ticker.removeCallbacks(tick)
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_VIDEO_ID = "yt_archive_video_id"
    }
}
