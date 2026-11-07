package com.github.libretube.services

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.annotation.CallSuper
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.core.app.ServiceCompat
import androidx.core.os.bundleOf
import androidx.core.os.postDelayed
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.github.libretube.R
import com.github.libretube.api.JsonHelper
import com.github.libretube.api.obj.Segment
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.IntentData
import com.github.libretube.enums.PlayerCommand
import com.github.libretube.enums.PlayerEvent
import com.github.libretube.enums.SbSkipOptions
import com.github.libretube.extensions.TAG
import com.github.libretube.helpers.YtSpeedRules
import com.github.libretube.extensions.parcelableExtra
import com.github.libretube.extensions.toID
import com.github.libretube.extensions.toastFromMainThread
import com.github.libretube.extensions.updateParameters
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.helpers.PlayerHelper.getCurrentSegment
import com.github.libretube.ui.activities.MainActivity
import com.github.libretube.ui.yt.YtTestHooks
import com.github.libretube.util.DefaultTrackSelectorWithAudioQualitySupport
import com.github.libretube.util.NowPlayingNotification
import com.github.libretube.util.PlayingQueue
import com.github.libretube.util.PlayingQueueMode
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@UnstableApi
abstract class AbstractPlayerService : MediaLibraryService(), MediaLibrarySession.Callback {
    private var mediaLibrarySession: MediaLibrarySession? = null
    var exoPlayer: ExoPlayer? = null

    private var notificationProvider: NowPlayingNotification? = null
    var trackSelector: DefaultTrackSelector? = null

    lateinit var videoId: String

    /** Whether [videoId] holds a video yet. */
    protected fun isVideoIdSet() = ::videoId.isInitialized

    var isTransitioning = false
        private set

    val handler = Handler(Looper.getMainLooper())

    // SponsorBlock Segment data
    private var sponsorBlockAutoSkip = true
    protected val sponsorBlockConfig = PlayerHelper.getSponsorBlockCategories()
    private var sponsorBlockSegments = listOf<Segment>()

    /**
     * Whether the service should automatically play the next video after the current video finished.
     *
     * If set to `false`, the player UI views have to handle autoplay themselves.
     */
    protected var shouldHandleAutoplay = true

    // YT: keeps the service in the foreground from one queue item to the next (see YtForegroundHold)
    private val foregroundHold = YtForegroundHold()
    private val releaseHold = Runnable { releaseForegroundHold(null) }

    // YT: a closed mini player kept paused for a minute (see YtServiceSoftClose)
    val ytSoftClose = YtServiceSoftClose(this) { mediaLibrarySession }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            super.onIsPlayingChanged(isPlaying)

            // Start or pause watch position timer
            if (!isPlaying) {
                saveWatchPosition()
            } else {
                // YT: playing again, so the service is in the foreground: the "tap to resume" note is stale
                YtResumeNotification.cancel(this@AbstractPlayerService)
                // YT: it plays again, so a later error gets its attempts anew
                playAttempts = 0
                recoveringFrom = null
            }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            // YT: the next item is in the player: end the hold once media3's notification
            // controller has seen it too
            if (!timeline.isEmpty && foregroundHold.holding) scheduleHoldRelease(YtForegroundHold.GRACE_MS)
        }

        override fun onPlayerError(error: PlaybackException) {
            // YT: no toast for an error the service recovers from by itself (a live stream that
            // fell behind its window); the others name their error code, since media3's text
            // alone ("Source error") does not tell what went wrong
            if (tryRecover(error)) return
            toastFromMainThread(
                getString(R.string.yt_play_error, error.localizedMessage.orEmpty(), error.errorCodeName)
            )
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            super.onPlaybackStateChanged(playbackState)

            when (playbackState) {
                Player.STATE_ENDED -> {
                    saveWatchPosition()
                }

                Player.STATE_READY -> {
                    isTransitioning = false
                }
            }
        }
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle
    ): ListenableFuture<SessionResult> {
        when (customCommand.customAction) {
            START_SERVICE_ACTION -> {
                // YT: a new start ends a soft close (the kept player is this service's again)
                ytSoftClose.open(play = false)
                PlayingQueue.queueMode =
                    if (isOfflinePlayer) PlayingQueueMode.OFFLINE else PlayingQueueMode.ONLINE

                CoroutineScope(Dispatchers.IO).launch {
                    onServiceCreated(args)
                    withContext(Dispatchers.Main) {
                        updateNotification()
                    }

                    if (::videoId.isInitialized) startPlayback()
                }
            }

            STOP_SERVICE_ACTION -> {
                onDestroy()
            }

            RUN_PLAYER_COMMAND_ACTION -> {
                runPlayerCommand(args)
            }
        }

        return super.onCustomCommand(session, controller, customCommand, args)
    }

    // YT: attempts to get over a playback error in a row (see YtPlayRecovery), and the error
    // being recovered from: onPlayerError and STATE_IDLE both report it, only one attempt counts
    private var playAttempts = 0
    private var recoveringFrom: PlaybackException? = null

    /** YT: true if the service handles [error] by itself (then no toast and no end of the service). */
    protected fun tryRecover(error: PlaybackException): Boolean {
        if (error === recoveringFrom) return true
        if (!recover(error)) return false
        recoveringFrom = error
        return true
    }

    /** YT: starts the recovery from [error]; false if there is none. Live streams add their own. */
    protected open fun recover(error: PlaybackException): Boolean = recoverPlayback(error)

    /** YT: prepares the player again at the same position after an error it can get over. */
    protected fun recoverPlayback(error: PlaybackException): Boolean {
        val player = exoPlayer ?: return false
        if (YtPlayRecovery.decide(error.errorCode, playAttempts) != YtPlayRecovery.Action.PREPARE_AGAIN) return false
        val delay = YtPlayRecovery.delayMs(playAttempts)
        playAttempts++
        Log.w(TAG(), "playback failed (${error.errorCodeName}), prepare again, attempt $playAttempts", error)
        val id = if (isVideoIdSet()) videoId else null
        handler.postDelayed({
            // another video or another player meanwhile, or it got going by itself
            if (exoPlayer !== player || (isVideoIdSet() && videoId != id)) return@postDelayed
            if (player.playbackState != Player.STATE_IDLE || player.playerError == null) return@postDelayed
            // ExoPlayer keeps the position of the failure; prepare() resumes there
            player.prepare()
        }, delay)
        return true
    }

    /**
     * YT: skips media3's notification updates while the queue moves to its next item, so the
     * service does not leave the foreground in between (see YtForegroundHold). Everything else
     * goes to media3 as before.
     */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        // YT: softly closed: no notification; only the removal (session taken out) goes through
        if (ytSoftClose.closed) {
            if (!isSessionAdded(session)) super.onUpdateNotification(session, false)
            return
        }
        if (foregroundHold.skip()) return
        super.onUpdateNotification(session, startInForegroundRequired)
    }

    private fun scheduleHoldRelease(delayMs: Long) {
        handler.removeCallbacks(releaseHold)
        handler.postDelayed(releaseHold, delayMs)
    }

    private fun releaseForegroundHold(forSeq: Int?) {
        if (forSeq == null) handler.removeCallbacks(releaseHold)
        val held = foregroundHold.holding
        if (foregroundHold.release(forSeq)) triggerNotificationUpdate()
        if (held && !foregroundHold.holding) YtTestHooks.log("YtQueue") { "foreground hold released" }
    }

    /**
     * YT: Android refused to start the service in the foreground (Android 12+, the app in the
     * background). media3 catches the exception on most paths and reports it here; the thumbnail
     * path is caught in NowPlayingNotification. A service outside the foreground may be stopped
     * by the system at any time, so playback pauses and a note offers to continue with a tap
     * (which may start the foreground again).
     */
    private fun onForegroundStartDenied() {
        YtTestHooks.log("YtQueue") { "foreground start denied, paused" }
        Log.w(TAG(), "the system refused to start the player service in the foreground")
        exoPlayer?.pause()
        YtResumeNotification.show(this, playerIntent(), exoPlayer?.mediaMetadata?.title)
    }

    open fun runPlayerCommand(args: Bundle) {
        // YT: the commands of the round-12 modules come under their own key; a command the
        // modules do not take, and every upstream command, goes on as before
        if (args.containsKey(YtServiceModules.KEY) && YtServiceModules.command(this, exoPlayer, args)) return

        when {
            args.containsKey(PlayerCommand.SKIP_SILENCE.name) -> exoPlayer?.skipSilenceEnabled =
                args.getBoolean(PlayerCommand.SKIP_SILENCE.name)

            args.containsKey(PlayerCommand.SET_VIDEO_TRACK_TYPE_DISABLED.name) -> trackSelector?.updateParameters {
                setTrackTypeDisabled(
                    C.TRACK_TYPE_VIDEO,
                    args.getBoolean(PlayerCommand.SET_VIDEO_TRACK_TYPE_DISABLED.name)
                )
            }

            args.containsKey(PlayerCommand.SET_AUDIO_ROLE_FLAGS.name) -> {
                trackSelector?.updateParameters {
                    setPreferredAudioRoleFlags(args.getInt(PlayerCommand.SET_AUDIO_ROLE_FLAGS.name))
                }
            }

            args.containsKey(PlayerCommand.SET_AUDIO_LANGUAGE.name) -> {
                trackSelector?.updateParameters {
                    setPreferredAudioLanguage(args.getString(PlayerCommand.SET_AUDIO_LANGUAGE.name))
                }
            }

            args.containsKey(PlayerCommand.SET_RESOLUTION.name) -> {
                trackSelector?.updateParameters {
                    // YT: on a low battery the height may be capped, with 30 fps (YtBatteryQualityModule)
                    val resolution = YtBatteryQualityModule.resolution(
                        args.getInt(PlayerCommand.SET_RESOLUTION.name), args
                    )
                    setMinVideoSize(Int.MIN_VALUE, resolution)
                    setMaxVideoSize(Int.MAX_VALUE, resolution)
                    setMaxVideoFrameRate(YtBatteryQualityModule.maxFrameRate())
                }
            }

            args.containsKey(PlayerCommand.SET_CAPTION_TRACK.name) -> {
                val exoPlayer = exoPlayer ?: return

                val captionId = args.getString(PlayerCommand.SET_CAPTION_TRACK.name)
                val caption = PlayerHelper.getCaptionTracks(exoPlayer).firstOrNull { it.id == captionId }

                trackSelector?.updateParameters {
                    val enableCaptions = caption != null

                    if (enableCaptions) {
                        setPreferredTextRoleFlags(caption.roleFlags)
                        setPreferredTextLanguage(caption.language)
                    }

                    setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enableCaptions)
                }
            }

            args.containsKey(PlayerCommand.PLAY_VIDEO_BY_ID.name) -> {
                navigateVideo(args.getString(PlayerCommand.PLAY_VIDEO_BY_ID.name) ?: return)
            }

            args.containsKey(PlayerCommand.PLAY_NEXT_VIDEO.name) -> {
                playNextVideo()
            }

            args.containsKey(PlayerCommand.TOGGLE_AUDIO_ONLY_MODE.name) -> {
                isAudioOnlyPlayer = args.getBoolean(PlayerCommand.TOGGLE_AUDIO_ONLY_MODE.name)
                trackSelector?.updateParameters {
                    setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, isAudioOnlyPlayer)
                }
                updateNotification()
            }

            args.containsKey(PlayerCommand.SET_SB_AUTO_SKIP_ENABLED.name) -> {
                sponsorBlockAutoSkip = args.getBoolean(PlayerCommand.SET_SB_AUTO_SKIP_ENABLED.name)
            }

            args.containsKey(PlayerCommand.SET_AUTOPLAY_COUNTDOWN_ENABLED.name) -> {
                shouldHandleAutoplay =
                    !args.getBoolean(PlayerCommand.SET_AUTOPLAY_COUNTDOWN_ENABLED.name)
            }
        }
    }

    /**
     * Plays the next video.
     *
     * If a video ID is explicitly given, it is played next,
     * otherwise a queued video (which includes playlists) is chosen.
     *
     * If no video is queued and autoplay is enabled, a related video is used.
     */
    fun playNextVideo(videoId: String? = null, relatedStreams: List<StreamItem>? = null) {
        if (PlayingQueue.repeatMode == Player.REPEAT_MODE_ONE) {
            exoPlayer?.seekTo(0)
            return
        }

        // YT: a module may hold autoplay back (e.g. once the time box is used up); a video
        // asked for by its id still plays
        if (videoId == null && YtServiceModules.holdAutoplay()) {
            YtTestHooks.log("YtQueue") { "autoplay held" }
            return
        }

        if (!shouldHandleAutoplay) {
            return
        }

        val nextVideo = videoId ?: PlayingQueue.getNext()
        ?: relatedStreams?.firstOrNull { !it.isLive }?.url?.toID()
            ?.takeIf { PlayerHelper.autoPlayEnabled && PlayerHelper.relatedStreamsEnabled }
        ?: return

        // YT: for the emulator check of a queue's end (scripts/gestures/f.sh)
        YtTestHooks.log("YtQueue") { "autoplay next=$nextVideo audio=$isAudioOnlyPlayer" }
        navigateVideo(nextVideo)
    }

    /**
     * Clear the currently playing video and start playing the new provided [videoId]
     *
     * If overriding, clearing old data must be done BEFORE calling [super.navigateVideo()]!
     */
    @CallSuper
    open fun navigateVideo(videoId: String) {
        // YT: another video in this service ends a soft close
        ytSoftClose.open(play = false)
        // YT: the modules drop what belonged to the video before (a loop, an end point)
        YtServiceModules.onNavigate(videoId)
        sponsorBlockSegments = emptyList()
        // YT: hold the foreground before the player is emptied (media3 would leave it at the
        // empty timeline and could not get back into it from the background)
        val holdSeq = foregroundHold.begin()
        scheduleHoldRelease(YtForegroundHold.MAX_MS)
        YtTestHooks.log("YtQueue") { "foreground hold for $videoId" }
        exoPlayer?.clearMediaItems()

        this.videoId = videoId

        CoroutineScope(Dispatchers.IO).launch {
            try {
                startPlayback()
            } finally {
                // loaded or failed: a failed load (no streams, no network) must not keep the
                // old notification; the item that did arrive releases earlier (onTimelineChanged)
                handler.post {
                    if (foregroundHold.holding && foregroundHold.seq == holdSeq) {
                        scheduleHoldRelease(YtForegroundHold.GRACE_MS)
                    }
                }
            }
        }
    }

    protected fun setSponsorBlockSegments(segments: List<Segment>) {
        sponsorBlockSegments = segments
        if (!PlayerHelper.sponsorBlockEnabled) return

        updatePlaylistMetadata {
            // JSON-encode as work-around for https://github.com/androidx/media/issues/564
            val segments = JsonHelper.json.encodeToString(sponsorBlockSegments)
            setExtras(bundleOf(IntentData.segments to segments))
        }

        checkForSegments()
    }

    /**
     * Check for SponsorBlock segments. This method automatically schedules itself to repeat every
     * 100ms using [handler], so it's not needed to schedule it manually.
     */
    private fun checkForSegments() {
        handler.postDelayed(this::checkForSegments, 100)

        val (currentSegment, sbSkipOption) = exoPlayer?.getCurrentSegment(
            sponsorBlockSegments,
            sponsorBlockConfig
        ) ?: return

        if (sbSkipOption in arrayOf(SbSkipOptions.AUTOMATIC, SbSkipOptions.AUTOMATIC_ONCE) && sponsorBlockAutoSkip) {
            exoPlayer?.seekTo(currentSegment.segmentStartAndEnd.second.toLong() * 1000)
            currentSegment.skipped = true

            if (PlayerHelper.sponsorBlockNotifications) toastFromMainThread(R.string.segment_skipped)
        }
    }

    protected fun updatePlaylistMetadata(updateAction: MediaMetadata.Builder.() -> Unit) {
        handler.post {
            exoPlayer?.playlistMetadata = MediaMetadata.Builder()
                .apply(updateAction)
                // send a unique timestamp to notify that the metadata changed, even if playing the same video twice
                .setTrackNumber(System.currentTimeMillis().mod(Int.MAX_VALUE))
                .build()
        }
    }

    private fun handlePlayerAction(event: PlayerEvent) {
        if (PlayerHelper.handlePlayerAction(exoPlayer ?: return, event)) return

        when (event) {
            PlayerEvent.Next -> {
                navigateVideo(PlayingQueue.getNext() ?: return)
            }

            PlayerEvent.Prev -> {
                navigateVideo(PlayingQueue.getPrev() ?: return)
            }

            PlayerEvent.Stop -> {
                onDestroy()
            }

            else -> Unit
        }
    }

    /**
     * Trigger a notification update with an updated PendingIntent.
     */
    private fun updateNotification() {
        notificationProvider?.notificationIntent = playerIntent()
        triggerNotificationUpdate()
    }

    /** The intent that opens this player in the app (the notification's tap). */
    private fun playerIntent() = Intent(this, getIntentActivity()).apply {
        putExtra(IntentData.maximizePlayer, true)
        putExtra(IntentData.offlinePlayer, isOfflinePlayer)
        putExtra(IntentData.audioOnly, isAudioOnlyPlayer)
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    abstract val isOfflinePlayer: Boolean
    var isAudioOnlyPlayer: Boolean = false

    val watchPositionsEnabled
        get() =
            (PlayerHelper.watchPositionsAudio && isAudioOnlyPlayer) || (PlayerHelper.watchPositionsVideo && !isAudioOnlyPlayer)

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        // YT: a softly closed player takes no outside controllers (lock screen, car, Bluetooth)
        if (ytSoftClose.closed && controllerInfo.packageName != packageName) return null
        return mediaLibrarySession
    }

    override fun onCreate() {
        super.onCreate()

        notificationProvider = NowPlayingNotification(this) { onForegroundStartDenied() }
        setMediaNotificationProvider(notificationProvider!!)
        // YT: media3 reports a refused foreground start here instead of crashing (Android 12+)
        setListener(object : MediaSessionService.Listener {
            @RequiresApi(31)
            override fun onForegroundServiceStartNotAllowedException() = onForegroundStartDenied()
        })

        createPlayerAndMediaSession()
    }

    open fun getIntentActivity(): Class<*> = MainActivity::class.java

    abstract suspend fun onServiceCreated(args: Bundle)

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo
    ): MediaSession.ConnectionResult {
        val connectionResult = super.onConnect(session, controller)

        val mediaNotificationSessionCommands =
            connectionResult.availableSessionCommands.buildUpon()
                .also { builder ->
                    builder.addSessionCommands(
                        listOf(
                            startServiceCommand,
                            runPlayerActionCommand,
                            stopServiceCommand
                        )
                    )
                }
                .build()

        val playerCommands = connectionResult.availablePlayerCommands.buildUpon()
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .build()

        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(mediaNotificationSessionCommands)
            .setAvailablePlayerCommands(playerCommands)
            .build()
    }

    @OptIn(UnstableApi::class)
    private fun createPlayerAndMediaSession() {
        val trackSelector = DefaultTrackSelectorWithAudioQualitySupport(this)
        this.trackSelector = trackSelector

        val player = PlayerHelper.createPlayer(this, trackSelector)
        // prevent android from putting LibreTube to sleep when locked
        player.setWakeMode(if (isOfflinePlayer) C.WAKE_MODE_LOCAL else C.WAKE_MODE_NETWORK)
        player.addListener(playerListener)
        this.exoPlayer = player
        // YT: the modules get the player as soon as it exists
        YtServiceModules.onPlayer(player, handler)

        val forwardingPlayer = MediaSessionForwarder(player)

        // it's possible that this is called twice at the same time:
        // if the user rotates the screen while the initial media library session is still in creation
        // this results in a race condition which service is the first one to create a `MediaLibrarySession`
        // the other one will just crash
        try {
            mediaLibrarySession = MediaLibrarySession.Builder(this, forwardingPlayer, this)
                .setId(this.javaClass.name)
                .build()
        } catch (e: Exception) {
            Log.e(TAG(), "failed to start media library session because it already exists")
            e.printStackTrace()
            onDestroy()
        }
    }

    /**
     * Load the stream source and start the playback.
     *
     * This function should base its actions on the videoId variable.
     */
    @CallSuper
    open suspend fun startPlayback() {
        isTransitioning = true

        updatePlaylistMetadata {
            setExtras(bundleOf(IntentData.videoId to videoId))
        }
    }

    private fun saveWatchPosition() {
        if (isTransitioning || !watchPositionsEnabled || !::videoId.isInitialized) return

        exoPlayer?.let { PlayerHelper.saveWatchPosition(it, videoId) }
    }

    override fun onMediaButtonEvent(
        session: MediaSession,
        controllerInfo: MediaSession.ControllerInfo,
        intent: Intent
    ): Boolean {
        // YT: a softly closed player ignores media buttons (it must not start again by itself)
        if (ytSoftClose.closed) return true
        val event: KeyEvent = intent.parcelableExtra(Intent.EXTRA_KEY_EVENT) ?: return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                handlePlayerAction(PlayerEvent.Next)
                return true
            }

            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                handlePlayerAction(PlayerEvent.Prev)
                return true
            }

            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                handlePlayerAction(PlayerEvent.Rewind)
            }

            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                handlePlayerAction(PlayerEvent.Forward)
            }

            KeyEvent.KEYCODE_MEDIA_STOP -> {
                handlePlayerAction(PlayerEvent.Stop)
            }
        }

        return super.onMediaButtonEvent(session, controllerInfo, intent)
    }

    override fun onDestroy() {
        // wait for a short time before killing the mediaSession
        // as the playerController must be released before we finish the session
        // otherwise there would be a
        // java.lang.SecurityException: Session rejected the connection request.
        // because there can't be two active playerControllers at the same time.
        handler.postDelayed(50) {
            saveWatchPosition()

            notificationProvider = null

            handler.removeCallbacksAndMessages(null)
            // YT: the service goes away, and with it what the note would bring back
            YtResumeNotification.cancel(this)
            clearListener()

            // YT: the modules let go of this service's player before it is released (unless a
            // newer service handed its player over meanwhile)
            runCatching { YtServiceModules.release(exoPlayer) }

            runCatching {
                exoPlayer?.stop()
                exoPlayer?.release()
            }

            kotlin.runCatching {
                mediaLibrarySession?.release()
                mediaLibrarySession = null
            }

            PlayingQueue.clear()

            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()

            super.onDestroy()
            // YT: a mini player brought back meanwhile may start now (see YtMiniRestore); a
            // softly closed player is over
            ytSoftClose.gone()
            com.github.libretube.ui.yt.mini.YtMiniRestore.serviceGone()
        }
    }

    /**
     * Stop the service when app is removed from the task manager.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        onDestroy()
    }

    // YT: memory runs low: a softly closed player goes now (the restart path stays)
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (ytSoftClose.closed && com.github.libretube.ui.yt.mini.YtSoftCloseRules.releasesOnTrim(level)) onDestroy()
    }

    /**
     * [Player] wrapper that handles seeking actions (next/previous) itself instead of using the
     * default [Player] implementation
     */
    inner class MediaSessionForwarder(player: Player) : ForwardingPlayer(player) {
        override fun hasNextMediaItem(): Boolean {
            return PlayingQueue.hasNext()
        }

        override fun hasPreviousMediaItem(): Boolean {
            return PlayingQueue.hasPrev()
        }

        override fun seekToPrevious() {
            if (ytSoftClose.closed) return
            handlePlayerAction(PlayerEvent.Prev)
        }

        override fun seekToNext() {
            if (ytSoftClose.closed) return
            handlePlayerAction(PlayerEvent.Next)
        }

        // YT: a softly closed player does not play for any controller; the page that brings it
        // back ends the soft close first (YtServiceSoftClose.open)
        override fun play() {
            if (ytSoftClose.closed) return
            super.play()
        }

        override fun setPlayWhenReady(playWhenReady: Boolean) {
            if (playWhenReady && ytSoftClose.closed) return
            super.setPlayWhenReady(playWhenReady)
        }

        override fun getAvailableCommands(): Player.Commands {
            return super.getAvailableCommands().buildUpon()
                .addAll(COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_NEXT)
                .build()
        }

        // YT: speed and pitch from any controller stay in a range the audio processors handle
        // (see YtSpeedRules)
        override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
            super.setPlaybackParameters(
                PlaybackParameters(
                    YtSpeedRules.clampSpeed(playbackParameters.speed),
                    YtSpeedRules.clampPitch(playbackParameters.pitch)
                )
            )
        }

        override fun setPlaybackSpeed(speed: Float) {
            setPlaybackParameters(PlaybackParameters(speed, playbackParameters.pitch))
        }

        override fun isCommandAvailable(command: Int): Boolean {
            if (command == COMMAND_SEEK_TO_NEXT || command == COMMAND_SEEK_TO_PREVIOUS) return true

            return super.isCommandAvailable(command)
        }
    }

    companion object {
        private const val START_SERVICE_ACTION = "start_service_action"
        private const val STOP_SERVICE_ACTION = "stop_service_action"
        private const val RUN_PLAYER_COMMAND_ACTION = "run_player_command_action"

        val startServiceCommand = SessionCommand(START_SERVICE_ACTION, Bundle.EMPTY)
        val stopServiceCommand = SessionCommand(STOP_SERVICE_ACTION, Bundle.EMPTY)
        val runPlayerActionCommand = SessionCommand(RUN_PLAYER_COMMAND_ACTION, Bundle.EMPTY)
    }
}
