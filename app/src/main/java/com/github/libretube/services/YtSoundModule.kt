package com.github.libretube.services

import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.github.libretube.ui.yt.sound.YtSoundEngine

/**
 * YT: hands the service's player to the sound engine (YtSound), which reads its position,
 * speed and whether it plays, on the main thread, while a sound-reactive UI listens.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object YtSoundModule : YtServiceModule {
    override fun onPlayer(exo: ExoPlayer, handler: Handler) = YtSoundEngine.attach(exo)

    override fun release(exo: ExoPlayer) = YtSoundEngine.detach(exo)
}
