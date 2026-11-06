package com.github.libretube.ui.yt.shorts

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.api.obj.Streams
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.enums.FileType
import com.github.libretube.extensions.toAndroidUri
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.player.SabrMediaSource
import com.github.libretube.player.manifest.SabrManifest
import com.github.libretube.ui.interfaces.TimeFrameReceiver
import com.github.libretube.util.OfflineTimeFrameReceiver
import com.github.libretube.util.OnlineTimeFrameReceiver
import com.github.libretube.util.YoutubeHlsPlaylistParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * YT: what one short plays. A downloaded short plays from its files (and works offline and in
 * the emulator checks); every other one from the streams the app's video player would use.
 */
class YtShortMedia(
    val videoId: String,
    /** The online information, null for a download. */
    val streams: Streams?,
    val localVideo: Path?,
    val localAudio: Path?,
) {
    val isLocal: Boolean get() = localVideo != null || localAudio != null

    /** The length as the information has it, in ms (0 if unknown); the player knows it better. */
    val durationMs: Long get() = (streams?.duration ?: 0L).coerceAtLeast(0L) * 1000L

    /** Shorts the information lists as related: what the view adds at the end of a list. */
    fun relatedShorts(): List<StreamItem> = streams?.relatedStreams.orEmpty()
        .filter { it.url != null && (it.isShort || (it.duration ?: 0L) in 1L..60L) }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object YtShortsSource {
    private const val TAG = "YtShorts"

    /** The files of a download first, else the online streams; null if neither is there. */
    suspend fun resolve(videoId: String): YtShortMedia? = withContext(Dispatchers.IO) {
        val download = runCatching { Database.downloadDao().findById(videoId) }.getOrNull()
        val items = download?.downloadItems.orEmpty()
        val video = items.firstOrNull { it.type == FileType.VIDEO && runCatching { it.path.exists() }.getOrDefault(false) }?.path
        val audio = items.firstOrNull { it.type == FileType.AUDIO && runCatching { it.path.exists() }.getOrDefault(false) }?.path
        if (video != null || audio != null) return@withContext YtShortMedia(videoId, null, video, audio)

        val streams = try {
            MediaServiceRepository.instance.getStreams(videoId)
        } catch (e: Exception) {
            Log.w(TAG, "no streams for $videoId: ${e.message}")
            return@withContext null
        }
        YtShortMedia(videoId, streams, null, null)
    }

    /** Puts [media] into [player] as the app's video player would; false if nothing can play. */
    fun apply(context: Context, player: ExoPlayer, media: YtShortMedia): Boolean {
        if (media.isLocal) {
            val factory = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context))
            val sources = listOfNotNull(media.localVideo, media.localAudio).map {
                factory.createMediaSource(MediaItem.fromUri(it.toAndroidUri()))
            }
            if (sources.size == 1) player.setMediaSource(sources.first())
            else player.setMediaSource(MergingMediaSource(*sources.toTypedArray()))
            return true
        }
        val streams = media.streams ?: return false
        val videoId = media.videoId
        return when {
            // the same order as OnlinePlayerService: SABR, then DASH, then HLS
            !streams.isLive && streams.serverAbrStreamingUrl != null && streams.videoPlaybackUstreamerConfig != null -> {
                val item = MediaItem.Builder()
                    .setUri(streams.serverAbrStreamingUrl.toUri())
                    .setMimeType("application/vnd.yt-ump")
                    .build()
                val source: MediaSource = SabrMediaSource.Factory(SabrManifest(videoId, streams)).createMediaSource(item)
                player.setMediaSource(source)
                true
            }
            !streams.isLive && streams.videoStreams.any { it.url?.startsWith("sabr://") != true } -> {
                val dash = PlayerHelper.createDashSource(
                    streams.copy(videoStreams = streams.videoStreams.filter { it.url?.startsWith("sabr://") != true }),
                    context,
                )
                player.setMediaItem(MediaItem.Builder().setUri(dash).setMimeType(MimeTypes.APPLICATION_MPD).build())
                true
            }
            streams.hls != null -> {
                val item = MediaItem.Builder().setUri(streams.hls.toUri()).setMimeType(MimeTypes.APPLICATION_M3U8).build()
                val source = HlsMediaSource.Factory(DefaultDataSource.Factory(context))
                    .setPlaylistParserFactory(YoutubeHlsPlaylistParser.Factory())
                    .createMediaSource(item)
                player.setMediaSource(source)
                true
            }
            else -> false
        }
    }

    /** The still frames for the timeline's preview, or null if the short has none. */
    fun frames(context: Context, media: YtShortMedia): TimeFrameReceiver? = when {
        media.localVideo != null -> runCatching { OfflineTimeFrameReceiver(context, media.localVideo) }.getOrNull()
        !media.streams?.previewFrames.isNullOrEmpty() -> OnlineTimeFrameReceiver(context, media.streams!!.previewFrames)
        else -> null
    }
}
