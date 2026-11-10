package com.github.libretube.ui.fragments

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.KeyEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.trackPipAnimationHintView
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.constraintlayout.motion.widget.TransitionAdapter
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.core.os.postDelayed
import androidx.core.view.WindowCompat
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commit
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.libretube.R
import com.github.libretube.api.JsonHelper
import com.github.libretube.api.obj.ChapterSegment
import com.github.libretube.api.obj.Segment
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.api.obj.Streams
import com.github.libretube.compat.PictureInPictureCompat
import com.github.libretube.compat.PictureInPictureParamsCompat
import com.github.libretube.constants.IntentData
import com.github.libretube.databinding.ExoStyledPlayerControlViewBinding
import com.github.libretube.databinding.FragmentPlayerBinding
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.enums.FileType
import com.github.libretube.enums.PlayerCommand
import com.github.libretube.enums.PlayerEvent
import com.github.libretube.enums.SbSkipOptions
import com.github.libretube.enums.ShareObjectType
import com.github.libretube.extensions.formatShort
import com.github.libretube.extensions.parcelable
import com.github.libretube.extensions.serializableExtra
import com.github.libretube.extensions.toID
import com.github.libretube.extensions.togglePlayPauseState
import com.github.libretube.extensions.updateIfChanged
import com.github.libretube.helpers.BackgroundHelper
import com.github.libretube.helpers.DownloadHelper
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.helpers.PlayerHelper.getCurrentSegment
import com.github.libretube.helpers.ThemeHelper
import com.github.libretube.helpers.WindowHelper
import com.github.libretube.obj.ShareData
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.services.AbstractPlayerService
import com.github.libretube.services.OfflinePlayerService
import com.github.libretube.services.OnlinePlayerService
import com.github.libretube.ui.activities.AbstractPlayerHostActivity
import com.github.libretube.ui.activities.MainActivity
import com.github.libretube.ui.activities.NoInternetActivity
import com.github.libretube.ui.adapters.VideoCardsAdapter
import com.github.libretube.ui.base.BaseActivity
import com.github.libretube.ui.dialogs.AddToPlaylistDialog
import com.github.libretube.ui.dialogs.PlayOfflineDialog
import com.github.libretube.ui.dialogs.ShareDialog
import com.github.libretube.ui.extensions.animateDown
import com.github.libretube.ui.extensions.getSystemInsets
import com.github.libretube.ui.extensions.setOnBackPressed
import com.github.libretube.ui.extensions.setupSubscriptionButton
import com.github.libretube.ui.interfaces.CustomPlayerCallback
import com.github.libretube.ui.interfaces.TimeFrameReceiver
import com.github.libretube.ui.listeners.SeekbarPreviewListener
import com.github.libretube.ui.models.ChaptersViewModel
import com.github.libretube.ui.models.CommentsViewModel
import com.github.libretube.ui.models.CommonPlayerViewModel
import com.github.libretube.ui.models.PlayerViewModel
import com.github.libretube.ui.sheets.CommentsSheet
import com.github.libretube.ui.yt.YtClock
import com.github.libretube.ui.yt.YtPlaybackClock
import com.github.libretube.ui.yt.YtSeekReason
import com.github.libretube.ui.yt.player.YtPlayerHost
import com.github.libretube.util.OfflineTimeFrameReceiver
import com.github.libretube.util.OnlineTimeFrameReceiver
import com.github.libretube.util.PlayingQueue
import com.github.libretube.util.TextUtils
import com.github.libretube.util.TextUtils.toTimeInSeconds
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.io.path.exists
import kotlin.math.absoluteValue


@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerFragment : Fragment(R.layout.fragment_player), CustomPlayerCallback, YtPlayerHost {
    private var _binding: FragmentPlayerBinding? = null
    val binding get() = _binding!!

    private val playerControlsBinding get() = binding.player.binding
    private val playerBackgroundBinding get() = binding.player.backgroundBinding

    private val commonPlayerViewModel: CommonPlayerViewModel by activityViewModels()
    private val viewModel: PlayerViewModel by viewModels()
    private val commentsViewModel: CommentsViewModel by activityViewModels()
    private val chaptersViewModel: ChaptersViewModel by activityViewModels()

    /** YT: video page details of the selected design, null for the classic layout. */
    private var ytWatch: com.github.libretube.ui.yt.YtWatchController? = null
    private var ytShowDescription = false
    private lateinit var playerController: MediaController

    // Video information passed by the intent
    private lateinit var videoId: String
    private var playlistId: String? = null
    private var channelId: String? = null
    var isOffline: Boolean = false
        private set

    // data and objects stored for the player
    private lateinit var streams: Streams

    private val handler = Handler(Looper.getMainLooper())

    private var seekBarPreviewListener: SeekbarPreviewListener? = null

    // True when the video was closed through the close button on PiP mode
    private var closedVideo = false

    private var autoPlayCountdownEnabled = PlayerHelper.autoPlayCountdown

    // YT: the user closed this page and its player is kept paused for a minute (YtSoftClose)
    private var ytSoftClosed = false

    /**
     * The orientation of the `fragment_player.xml` that's currently used
     * This is needed in order to figure out if the current layout is the landscape one or not.
     */
    private var playerLayoutOrientation = Int.MIN_VALUE

    // Activity that's active during PiP, can be used for controlling its lifecycle.
    private var pipActivity: Activity? = null

    // check if pip is entered via the dedicated button
    private var isEnteringPiPMode = false

    private val baseActivity get() = activity as AbstractPlayerHostActivity
    private val windowInsetsControllerCompat
        get() = WindowCompat
            .getInsetsController(requireActivity().window, requireActivity().window.decorView)

    private val fullscreenDialog by lazy {
        object : Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen) {
            @Deprecated("Deprecated in Java", ReplaceWith("onbackpressedispatcher and callback"))
            override fun onBackPressed() {
                unsetFullscreen()
            }

            override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
                if (_binding?.player?.onKeyUp(keyCode, event) == true) {
                    return true
                }

                return super.onKeyUp(keyCode, event)
            }
        }
    }

    /**
     * Receiver for all actions in the PiP mode
     */
    private val playerActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!::playerController.isInitialized) return
            val event = intent.serializableExtra<PlayerEvent>(PlayerHelper.CONTROL_TYPE) ?: return

            if (PlayerHelper.handlePlayerAction(playerController, event)) return

            when (event) {
                PlayerEvent.Next -> {
                    PlayingQueue.getNext()?.let { playVideo(it) }
                }

                PlayerEvent.Prev -> {
                    PlayingQueue.getPrev()?.let { playVideo(it) }
                }

                PlayerEvent.Background -> {
                    switchToAudioMode()
                    // wait some time in order for the service to get started properly
                    handler.postDelayed(500) {
                        pipActivity?.moveTaskToBack(false)
                        pipActivity = null
                    }
                }

                else -> Unit
            }
        }
    }

    private var bufferingTimeoutTask: Runnable? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            PictureInPictureCompat.setPictureInPictureParams(requireActivity(), pipParams)

            if (isPlaying && PlayerHelper.sponsorBlockEnabled) {
                handler.postDelayed(
                    this@PlayerFragment::checkForSegments,
                    100
                )
            }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            super.onEvents(player, events)

            if (events.containsAny(
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_PLAY_WHEN_READY_CHANGED
                ) && _binding != null
            ) {
                updatePlayPauseButton()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // set the playback speed to one if having reached the end of a livestream
            if (playbackState == Player.STATE_BUFFERING && streams.isLive &&
                playerController.duration - playerController.currentPosition < 700
            ) {
                playerController.setPlaybackSpeed(1f)
            }

            // check if video has ended, next video is available and autoplay is enabled/the video is part of a played playlist.
            if (playbackState == Player.STATE_ENDED) {
                playerBackgroundBinding.sbSkipBtn.isGone = true

                // if the current tracks are empty, the player is transitioning at the moment
                val isTransitioning = playerController.currentTracks.isEmpty
                if ((PlayingQueue.hasNext() || PlayerHelper.autoPlayEnabled) && autoPlayCountdownEnabled && !isTransitioning) {
                    showAutoPlayCountdown()
                } else {
                    binding.player.showControllerPermanently()
                }
            }

            // listen for the stop button in the notification
            if (playbackState == PlaybackState.STATE_STOPPED &&
                PictureInPictureCompat.isInPictureInPictureMode(requireActivity())
            ) {
                // finish PiP by finishing the activity
                activity?.finish()
            }

            // Buffering timeout after 10 Minutes
            if (playbackState == Player.STATE_BUFFERING) {
                if (bufferingTimeoutTask == null) {
                    bufferingTimeoutTask = Runnable {
                        playerController.pause()
                    }
                }

                handler.postDelayed(bufferingTimeoutTask!!, PlayerHelper.MAX_BUFFER_DELAY)
            } else {
                bufferingTimeoutTask?.let { handler.removeCallbacks(it) }
            }

            super.onPlaybackStateChanged(playbackState)
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            super.onMediaMetadataChanged(mediaMetadata)

            // JSON-encode as work-around for https://github.com/androidx/media/issues/564
            val maybeStreams: Streams? = mediaMetadata.extras?.getString(IntentData.streams)?.let {
                JsonHelper.json.decodeFromString(it)
            }
            maybeStreams?.let { streams ->
                this@PlayerFragment.streams = streams
                viewModel.segments.postValue(emptyList())
                updatePlayerView()
                // YT: the queue moved on to a music video while YT is in the background
                ytMusicAudioIfHidden()
            }
        }

        override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) {
            super.onPlaylistMetadataChanged(mediaMetadata)

            mediaMetadata.extras?.getString(IntentData.videoId)?.let {
                videoId = it
                if (_binding != null) playerBackgroundBinding.autoplayCountdown.cancelAndHideCountdown()

                // fix: if the fragment is recreated, play the current video, and not the initial one
                arguments?.run {
                    val playerData =
                        parcelable<PlayerData>(IntentData.playerData)!!.copy(videoId = videoId)
                    putParcelable(IntentData.playerData, playerData)
                }
            }

            // JSON-encode as work-around for https://github.com/androidx/media/issues/564
            val segments: List<Segment>? =
                mediaMetadata.extras?.getString(IntentData.segments)?.let {
                    JsonHelper.json.decodeFromString(it)
                }
            viewModel.segments.postValue(segments.orEmpty())
        }

        /**
         * Catch player errors to prevent the app from stopping
         */
        override fun onPlayerError(error: PlaybackException) {
            super.onPlayerError(error)
            try {
                playerController.play()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            super.onMediaItemTransition(mediaItem, reason)
            if (mediaItem == null) {
                toggleVideoInfoVisibility(false)
                disableController()
                binding.titleTextView.text = ""
            }
        }

        // YT: every seek reaches the shared playback clock (rewind memory, scene comments, loops)
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            super.onPositionDiscontinuity(oldPosition, newPosition, reason)
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                YtPlaybackClock.onSeek(oldPosition.positionMs, newPosition.positionMs)
            }
        }
    }

    private var screenshotBitmap: Bitmap? = null
    private val openScreenshotFile =
        registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
            if (uri == null) {
                screenshotBitmap = null
                return@registerForActivityResult
            }

            CoroutineScope(Dispatchers.IO).launch {
                context?.contentResolver?.openOutputStream(uri)?.use { outputStream ->
                    screenshotBitmap?.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                }
                screenshotBitmap = null

                withContext(Dispatchers.Main) {
                    Snackbar.make(
                        requireView(),
                        R.string.screenshot_saved,
                        2500
                    ).apply {
                        setAction(R.string.share) {
                            startActivity(Intent.createChooser(with(Intent()) {
                                setAction(Intent.ACTION_SEND)
                                setType("image/png")
                                putExtra(Intent.EXTRA_STREAM, uri)
                            }, null))
                        }
                        show()
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // broadcast receiver for PiP actions
        ContextCompat.registerReceiver(
            requireContext(),
            playerActionReceiver,
            IntentFilter(PlayerHelper.getIntentActionName(requireContext())),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentPlayerBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)
        // YT: the mini player takes the form of the design's navigation bar
        com.github.libretube.ui.yt.YtMiniPlayer.attach(this, binding)
        // YT: the features of round 12 hook into the page, and the shared playback clock reads
        // this page's controller
        com.github.libretube.ui.yt.player.YtPlayerModules.onViewCreated(this)
        YtPlaybackClock.attach(ytClockReader)

        // manually apply additional padding for edge-to-edge compatibility
        activity?.getSystemInsets()?.let { systemBars ->
            with(binding.root) {
                setPadding(
                    paddingLeft,
                    paddingTop + systemBars.top,
                    paddingRight,
                    paddingBottom
                )
            }
        }


        val playerData = requireArguments().parcelable<PlayerData>(IntentData.playerData)!!
        playerData.videoId?.let { videoId = it }
        isOffline = playerData.isOffline
        playlistId = playerData.playlistId
        channelId = playerData.channelId

        // remember if playback already started once and only restart playback if that's the first run
        val createNewSession = !requireArguments().getBoolean(IntentData.alreadyStarted)
        requireArguments().putBoolean(IntentData.alreadyStarted, true)

        // YT: the page of a player that goes on (the activity was created again, e.g. by a
        // rotation) comes back docked if it was docked
        // or docked from the start: the closed mini player brought back by a long press on Abos
        // (that one rises onto the bar in the design's motion, the rotated one stays put)
        // (a page made anew: the restart starts a new session, the kept player is attached to)
        val ytRestored = savedInstanceState == null && com.github.libretube.ui.yt.mini.YtMiniRestore.takeStartDocked(playerData.videoId)
        val restoreDocked = (!createNewSession && viewModel.ytDocked) || ytRestored

        if (restoreDocked) baseActivity.requestOrientationChange() else changeOrientationMode()

        playerLayoutOrientation = resources.configuration.orientation
        resources.configuration.let { ytLastSize = Triple(it.orientation, it.screenWidthDp, it.screenHeightDp) }

        initializeTransitionLayout(restoreDocked, ytRestored)
        initializeOnClickActions()

        // YT: not for the page rebuilt because fullscreen was just left in landscape
        val ytLeftFullscreen = viewModel.ytLeftFullscreen
        viewModel.ytLeftFullscreen = false
        if (com.github.libretube.ui.yt.player.YtPageLayoutRules.fullscreenAtStart(
                autoFullscreen = PlayerHelper.autoFullscreenEnabled,
                landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
                docked = restoreDocked,
                leftFullscreen = ytLeftFullscreen,
            )
        ) {
            setFullscreen()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                requireActivity().trackPipAnimationHintView(binding.player)
            }
        }

        chaptersViewModel.chaptersLiveData.observe(viewLifecycleOwner) {
            binding.player.setCurrentChapterName()
            playerControlsBinding.exoProgress.setChapters(it.orEmpty())
        }

        viewModel.segments.observe(viewLifecycleOwner) { segments ->
            binding.descriptionLayout.setSegments(segments)
            playerControlsBinding.exoProgress.setSegments(segments)
            playerControlsBinding.sbToggle.isVisible = segments.isNotEmpty()
            getHighlight(segments)?.let {
                lifecycleScope.launch(Dispatchers.IO) { initializeHighlight(it) }
            }
        }

        val localDownloadVersion = if (!isOffline && createNewSession) {
            runBlocking(Dispatchers.IO) {
                DatabaseHolder.Database.downloadDao().findById(videoId)
            }
        } else null
        if (localDownloadVersion != null) {
            // the dialog must also be visible when in fullscreen, thus we need to use the activity's
            // fragment manager and not the one from [PlayerFragment]
            val fragmentManager = requireActivity().supportFragmentManager

            fragmentManager.setFragmentResultListener(
                PlayOfflineDialog.PLAY_OFFLINE_DIALOG_REQUEST_KEY, viewLifecycleOwner
            ) { _, bundle ->
                isOffline = bundle.getBoolean(IntentData.isPlayingOffline)

                // start a new playback session - the method will read `isOffline` and decide whether
                // to play the downloaded video based on it, so it's enough to set `isOffline` here
                attachToPlayerService(playerData, true)
            }

            val downloadInfo = DownloadHelper.extractDownloadInfoText(
                requireContext(),
                localDownloadVersion
            ).toTypedArray()

            PlayOfflineDialog().apply {
                arguments = bundleOf(
                    IntentData.videoId to videoId,
                    IntentData.videoTitle to localDownloadVersion.download.title,
                    IntentData.downloadInfo to downloadInfo
                )
            }.show(fragmentManager, null)
        } else {
            attachToPlayerService(playerData, createNewSession)
        }

        val onBackPressedCallback = object : OnBackPressedCallback(true) {
            // YT: true while a back swipe goes to a feature of round 12, so the page stays open
            private var ytBackHeld = false

            override fun handleOnBackPressed() {
                ytBackHeld = false
                // YT: a feature of round 12 may take back first (e.g. to close what it shows);
                // the page opens fully again if the swipe had shrunk it
                if (com.github.libretube.ui.yt.player.YtPlayerModules.onBack(this@PlayerFragment)) {
                    binding.playerMotionLayout.transitionToStart()
                    return
                }
                if (commonPlayerViewModel.isFullscreen.value == true) unsetFullscreen(rebuildAfter = true)
                else {
                    binding.playerMotionLayout.setTransitionDuration(com.github.libretube.ui.yt.YtMiniPlayer.minimizeDuration())
                    binding.playerMotionLayout.transitionToEnd()
                    baseActivity.minimizePlayerContainerLayout()
                    baseActivity.requestOrientationChange()
                }
            }

            override fun handleOnBackStarted(backEvent: BackEventCompat) {
                ytBackHeld = com.github.libretube.ui.yt.player.YtPlayerModules.wantsBack(this@PlayerFragment)
            }

            override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                if (ytBackHeld) return
                binding.playerMotionLayout.progress = backEvent.progress
            }

            override fun handleOnBackCancelled() {
                ytBackHeld = false
                binding.playerMotionLayout.transitionToStart()
            }
        }
        setOnBackPressed(onBackPressedCallback)

        commonPlayerViewModel.isMiniPlayerVisible.observe(viewLifecycleOwner) { isMiniPlayerVisible ->
            // re-add the callback on top of the back pressed dispatcher listeners stack,
            // so that it's the first one to become called while the full player is visible
            if (!isMiniPlayerVisible) {
                onBackPressedCallback.remove()
                setOnBackPressed(onBackPressedCallback)
            }

            // if the player is minimized, the fragment behind the player should handle the event
            onBackPressedCallback.isEnabled = isMiniPlayerVisible != true
        }

        toggleVideoInfoVisibility(false)
    }

    private fun attachToPlayerService(playerData: PlayerData, startNewSession: Boolean) {
        val (serviceClass, args) = if (isOffline) {
            val isNoInternet = activity is NoInternetActivity

            OfflinePlayerService::class.java to bundleOf(
                IntentData.playerData to playerData
                    .copy(downloadTab = playerData.downloadTab ?: DownloadTab.VIDEO),
                IntentData.noInternet to isNoInternet
            )
        } else {
            OnlinePlayerService::class.java to bundleOf(
                IntentData.playerData to playerData,
                IntentData.audioOnly to false
            )
        }

        BackgroundHelper.startMediaService(
            requireContext(),
            serviceClass,
            if (startNewSession) args else Bundle.EMPTY,
        ) {
            if (_binding == null) {
                playerController.sendCustomCommand(
                    AbstractPlayerService.stopServiceCommand,
                    Bundle.EMPTY
                )
                playerController.release()
                return@startMediaService
            }

            playerController = it
            playerController.addListener(playerListener)
            connectToPlayerView(playerController)
            updatePlayPauseButton()
            // YT: the features of round 12 get the connected controller
            com.github.libretube.ui.yt.player.YtPlayerModules.onController(this, playerController)
            // YT: a page that attaches to a closed player still kept (soft close) takes it back
            com.github.libretube.ui.yt.mini.YtSoftClose.attached(playerController, if (::videoId.isInitialized) videoId else null)

            if (!startNewSession) {
                // JSON-encode as work-around for https://github.com/androidx/media/issues/564
                val streams: Streams? =
                    playerController.mediaMetadata.extras?.getString(IntentData.streams)
                        ?.let { json ->
                            JsonHelper.json.decodeFromString(json)
                        }

                // reload the streams data and playback, metadata apparently no longer exists
                if (streams == null) {
                    playVideo(videoId)
                    return@startMediaService
                }

                this.streams = streams
                updatePlayerView()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initializeTransitionLayout(restoreDocked: Boolean = false, ytRestored: Boolean = false) {
        baseActivity.setPlayerContainerProgress(0f)

        var transitionStartId = 0
        var transitionEndId = 0

        binding.playerMotionLayout.addTransitionListener(object : TransitionAdapter() {
            override fun onTransitionChange(
                motionLayout: MotionLayout?,
                startId: Int,
                endId: Int,
                progress: Float
            ) {
                if (_binding == null) return

                baseActivity.setPlayerContainerProgress(progress.absoluteValue)
                disableController()
                commonPlayerViewModel.setSheetExpand(false)
                transitionEndId = endId
                transitionStartId = startId
            }

            override fun onTransitionCompleted(motionLayout: MotionLayout?, currentId: Int) {
                if (_binding == null) return

                if (currentId == transitionStartId) {
                    commonPlayerViewModel.isMiniPlayerVisible.value = false
                    viewModel.ytDocked = false
                    // re-enable captions
                    binding.player.updateCurrentSubtitle(viewModel.currentCaptionId)
                    binding.player.useController = true
                    commonPlayerViewModel.setSheetExpand(true)
                    baseActivity.setPlayerContainerProgress(0f)
                    changeOrientationMode()

                    // clear search bar focus to avoid keyboard popups
                    baseActivity.clearSearchViewFocus()
                } else if (currentId == transitionEndId) {
                    commonPlayerViewModel.isMiniPlayerVisible.value = true
                    viewModel.ytDocked = true
                    // disable captions temporarily
                    binding.player.updateCurrentSubtitle(null)
                    disableController()
                    commonPlayerViewModel.setSheetExpand(null)
                    playerBackgroundBinding.sbSkipBtn.isGone = true

                    baseActivity.setPlayerContainerProgress(1f)
                    baseActivity.requestOrientationChange()
                }

                updateMaxSheetHeight()
            }
        })

        binding.playerMotionLayout
            .addSwipeDownListener {
                if (commonPlayerViewModel.isMiniPlayerVisible.value == true) {
                    closeMiniPlayer()
                }
            }

        binding.playerMotionLayout.progress = 1F
        // YT: a page created while the activity is not in its window yet (the activity was
        // created again: rotation, a new look) keeps a progress set now only until it is attached
        // and then jumps to it, without a transition: transitionToStart() here did nothing (the
        // layout's last position is still 0), so a rotated player came back as a docked one the
        // page, the bar and the mini player had not heard of, under the bar and not usable. The
        // page starts once it is attached: open as always, or docked again if it was docked.
        com.github.libretube.ui.yt.mini.YtPageStart.start(binding.playerMotionLayout, restoreDocked, rise = ytRestored)

        val activity = requireActivity()
        PictureInPictureCompat.setPictureInPictureParams(activity, pipParams)
    }

    // YT: the swiped-away mini player is closed once, by its animation's end or by the fallback
    private var ytMiniClosed = false
    private val ytCloseMini = Runnable {
        if (!ytMiniClosed && _binding != null && isAdded) {
            ytMiniClosed = true
            killPlayerFragment()
        }
    }

    private fun closeMiniPlayer() {
        ytRememberClosed()
        // YT: the bar no longer carries the player that is swiped away
        com.github.libretube.ui.yt.YtMiniPlayer.undock()
        // YT: the page is never left half way down (cut behind the bar): an animation that is
        // cancelled runs no end action, so a fallback closes it too; whichever comes first does
        binding
            .playerMotionLayout
            .animateDown(
                duration = 300L,
                dy = 500F,
                onEnd = { ytCloseMini.run() }
            )
        binding.playerMotionLayout.removeCallbacks(ytCloseMini)
        binding.playerMotionLayout.postDelayed(ytCloseMini, 600L)
    }

    // actions that don't depend on video information
    private fun initializeOnClickActions() {
        binding.closeImageView.setOnClickListener {
            ytRememberClosed()
            killPlayerFragment()
        }
        playerControlsBinding.closeImageButton.setOnClickListener {
            ytRememberClosed()
            killPlayerFragment()
        }

        binding.playImageView.setOnClickListener {
            if (::playerController.isInitialized) playerController.togglePlayPauseState()
        }

        activity?.supportFragmentManager
            ?.setFragmentResultListener(
                CommentsSheet.HANDLE_LINK_REQUEST_KEY,
                viewLifecycleOwner
            ) { _, bundle ->
                bundle.getString(IntentData.url)?.let { handleLink(it) }
            }

        binding.commentsToggle.setOnClickListener {
            if (!this::streams.isInitialized) return@setOnClickListener
            // set the max height to not cover the currently playing video
            updateMaxSheetHeight()
            commentsViewModel.videoIdLiveData.updateIfChanged(videoId)
            CommentsSheet()
                .apply { arguments = bundleOf(IntentData.channelAvatar to streams.uploaderAvatar) }
                .show(childFragmentManager)
        }

        // share button
        binding.relPlayerShare.setOnClickListener {
            if (!this::streams.isInitialized) return@setOnClickListener
            val bundle = bundleOf(
                IntentData.id to videoId,
                IntentData.shareObjectType to ShareObjectType.VIDEO,
                IntentData.shareData to ShareData(
                    title = streams.title,
                    previewImageUrl = streams.thumbnailUrl,
                    currentPosition = playerController.currentPosition / 1000
                )
            )
            val newShareDialog = ShareDialog()
            newShareDialog.arguments = bundle
            newShareDialog.show(childFragmentManager, ShareDialog::class.java.name)
        }

        binding.relPlayerBackground.setOnClickListener {
            // start the background mode
            switchToAudioMode()
        }

        binding.relPlayerPip.isVisible = isPipAvailable()

        binding.relPlayerPip.setOnClickListener {
            // YT: with the sound wave on, YT's own floating window, and the app goes behind it
            if (floatOnOwn()) {
                requireActivity().moveTaskToBack(false)
                return@setOnClickListener
            }
            com.github.libretube.ui.yt.YtPipVisualizer.beforePip(requireContext())
            PictureInPictureCompat.enterPictureInPictureMode(requireActivity(), pipParams)
            isEnteringPiPMode = true
        }

        binding.relatedRecView.layoutManager = LinearLayoutManager(
            context,
            if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                LinearLayoutManager.HORIZONTAL
            } else {
                LinearLayoutManager.VERTICAL
            },
            false
        )

        // YT: timeline color of the selected design (e.g. lime in Movo)
        com.github.libretube.helpers.YtDesign.current().timelineColor?.let { color ->
            playerControlsBinding.exoProgress.setPlayedColor(color)
            playerControlsBinding.exoProgress.setScrubberColor(color)
            // YT: in Frequency the played part shimmers with the highs
            com.github.libretube.ui.yt.FrequencyTimeBar.attach(playerControlsBinding.exoProgress, color)
        }

        // YT: the selected design shows its own video page details, reusing the classic buttons
        ytWatch = com.github.libretube.ui.yt.YtWatch.attach(
            this,
            binding.linLayout,
            com.github.libretube.ui.yt.YtWatchActions(
                onSeek = { millis -> if (::playerController.isInitialized) playerController.seekTo(millis) },
                onSave = { binding.relPlayerSave.performClick() },
                onShare = { binding.relPlayerShare.performClick() },
                onDownload = { binding.relPlayerDownload.performClick() },
                onAudioOnly = { binding.relPlayerBackground.performClick() },
                onComments = { binding.commentsToggle.performClick() },
                onOpenChannel = { binding.playerChannel.performClick() },
                // the design draws the description itself; the classic one stays hidden
                onShowDescription = {
                    ytShowDescription = false
                    _binding?.descriptionLayout?.isVisible = false
                },
                currentPosition = {
                    if (::playerController.isInitialized) playerController.currentPosition else 0L
                },
                onLink = { link -> handleLink(link) },
                // YT: dragging share onto the timeline, if a feature of round 12 offers it
                shareScrub = com.github.libretube.ui.yt.player.YtPlayerModules.shareScrub(this),
            ),
        )
        if (ytWatch != null) {
            binding.descriptionLayout.isVisible = false
            // where the page slides under the video: the design's edge instead of a hard cut
            com.github.libretube.ui.yt.YtWatchEdge.install(binding.playerScrollView)
            (binding.optionsLL.parent as? View)?.isVisible = false
            binding.playerChannel.isVisible = false
            binding.commentsToggle.isVisible = false
            chaptersViewModel.currentChapterIndex.observe(viewLifecycleOwner) { index ->
                ytWatch?.chapterIndex = index ?: -1
            }
        }

        binding.relPlayerSave.setOnClickListener {
            if (!::streams.isInitialized) return@setOnClickListener

            AddToPlaylistDialog().apply {
                arguments = bundleOf(IntentData.videoInfo to streams.toStreamItem(videoId))
            }.show(childFragmentManager, AddToPlaylistDialog::class.java.name)
        }

        playerControlsBinding.skipPrev.setOnClickListener {
            PlayingQueue.getPrev()?.let { prev -> playVideo(prev) }
        }

        playerControlsBinding.skipNext.setOnClickListener {
            PlayingQueue.getNext()?.let { next -> playVideo(next) }
        }

        binding.relPlayerDownload.setOnClickListener {
            if (!this::streams.isInitialized) return@setOnClickListener

            DownloadHelper.startDownloadDialog(requireContext(), childFragmentManager, videoId)
        }

        binding.relPlayerScreenshot.setOnClickListener {
            if (!this::streams.isInitialized) return@setOnClickListener
            val surfaceView =
                binding.player.videoSurfaceView as? SurfaceView ?: return@setOnClickListener

            val bmp = Bitmap.createBitmap(
                surfaceView.width,
                surfaceView.height,
                Bitmap.Config.ARGB_8888
            )

            PixelCopy.request(surfaceView, bmp, { _ ->
                screenshotBitmap = bmp
                val currentPosition =
                    playerController.currentPosition.toFloat() / 1000
                openScreenshotFile.launch("${streams.title}-${currentPosition}.png")
            }, handler)
        }

        binding.playerChannel.setOnClickListener {
            if (!this::streams.isInitialized) return@setOnClickListener

            NavigationHelper.navigateChannel(requireContext(), streams.uploaderUrl)
        }

        binding.descriptionLayout.handleLink = this::handleLink
    }

    private fun updateMaxSheetHeight() {
        val systemBars = baseActivity.getSystemInsets() ?: return
        val maxHeight = binding.root.height - (binding.player.height + systemBars.top)
        commonPlayerViewModel.maxSheetHeightPx = maxHeight
        chaptersViewModel.maxSheetHeightPx = maxHeight
    }

    fun switchToAudioMode() {
        // YT: audio only needs no floating window; the video is handed back first
        com.github.libretube.ui.yt.YtFloatingPlayer.close(pause = false)
        playerController.sendCustomCommand(
            AbstractPlayerService.runPlayerActionCommand,
            bundleOf(PlayerCommand.TOGGLE_AUDIO_ONLY_MODE.name to true)
        )
        // disable autoplay countdown while the audio player is running
        // otherwise playback of the next video wouldn't start automatically because
        // it awaits the start of the autoplay countdown
        playerController.sendCustomCommand(
            AbstractPlayerService.runPlayerActionCommand, bundleOf(
                PlayerCommand.SET_AUTOPLAY_COUNTDOWN_ENABLED.name to false
            )
        )

        binding.player.detachPlayer()

        playerController.release()
        killPlayerFragment()

        NavigationHelper.openAudioPlayerFragment(requireContext(), offlinePlayer = isOffline)
    }

    private fun updateFullscreenOrientation() {
        if (PlayerHelper.autoFullscreenEnabled || !this::streams.isInitialized) return

        baseActivity.requestedOrientation = PlayerHelper.getFullscreenOrientation(streams.isShort)
    }

    private fun setFullscreen() {
        // set status bar icon color to white
        windowInsetsControllerCompat.isAppearanceLightStatusBars = false

        commonPlayerViewModel.isFullscreen.value = true
        updateFullscreenOrientation()

        commonPlayerViewModel.setSheetExpand(null)

        openOrCloseFullscreenDialog(true)

        binding.player.updateMarginsByFullscreenMode()
    }

    /**
     * YT: [rebuildAfter] for the user leaving fullscreen with the page staying open (button, swipe
     * down, back): the screen may have turned inside fullscreen, where the page is not rebuilt,
     * so it is rebuilt now for the orientation it shows (see YtPageLayoutRules).
     */
    @SuppressLint("SourceLockedOrientationActivity")
    fun unsetFullscreen(rebuildAfter: Boolean = false) {
        if (activity == null || _binding == null) return

        commonPlayerViewModel.isFullscreen.value = false

        if (!PlayerHelper.autoFullscreenEnabled) {
            baseActivity.requestedOrientation = baseActivity.screenOrientationPref
        }

        openOrCloseFullscreenDialog(false)

        binding.player.updateMarginsByFullscreenMode()

        // set status bar icon color back to theme color after fullscreen dialog closed!
        windowInsetsControllerCompat.isAppearanceLightStatusBars =
            !ThemeHelper.isDarkMode(requireContext())

        if (rebuildAfter) {
            // YT: the player view is back on the open page, which may have turned meanwhile: the
            // minimize drag measures the page anew; once the gesture that left fullscreen has
            // ended and the dialog is gone, the page is rebuilt if it is in the wrong layout
            com.github.libretube.ui.yt.player.YtPlayerModules.onConfigurationChanged(this)
            binding.root.post { rebuildAfterFullscreen() }
        }
    }

    /** YT: see [unsetFullscreen]; only for the open page (a minimize may have started since). */
    private fun rebuildAfterFullscreen() {
        val page = _binding ?: return
        if (!isResumed || commonPlayerViewModel.isFullscreen.value == true) return
        if (commonPlayerViewModel.isMiniPlayerVisible.value == true || page.playerMotionLayout.progress != 0f) return
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (restartActivityIfNeeded() && landscape) viewModel.ytLeftFullscreen = true
    }

    /**
     * Enter/exit fullscreen or toggle it depending on the current state
     */
    override fun toggleFullscreen() {
        binding.player.hideController()

        val isFullscreen = commonPlayerViewModel.isFullscreen.value == true
        if (!isFullscreen) {
            // go to fullscreen mode
            setFullscreen()
        } else {
            // exit fullscreen mode
            unsetFullscreen(rebuildAfter = true)
        }
    }

    private fun openOrCloseFullscreenDialog(open: Boolean) {
        val playerView = binding.player
        (playerView.parent as ViewGroup).removeView(playerView)

        if (open) {
            fullscreenDialog.addContentView(
                binding.player,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            )
            fullscreenDialog.show()
            playerView.currentWindow = fullscreenDialog.window
        } else {
            binding.playerMotionLayout.addView(playerView)
            playerView.currentWindow = null
            fullscreenDialog.dismiss()
        }

        WindowHelper.toggleFullscreen(fullscreenDialog.window!!, open)
    }

    override fun onPause() {
        // check whether the screen is on
        val isInteractive = requireContext().getSystemService<PowerManager>()!!.isInteractive

        // disable video stream since it's not needed when screen off or when PiP is not
        // enabled, except when the user is intentionally entering PiP mode via the dedicated button
        if (!isInteractive && !isEnteringPiPMode) {
            // disable the autoplay countdown while the screen is off or when PiP is not enabled
            setAutoPlayCountdownEnabled(false)

            // disable loading the video track while screen is off or when PiP is not enabled
            setVideoTrackTypeDisabled(true)
        }

        // pause player if screen off or app is put the background, except when
        // the user is intentionally entering PiP mode via the dedicated button
        if (PlayerHelper.pausePlayerOnScreenOffEnabled && !isInteractive && !isEnteringPiPMode) {
            playerController.pause()
        }

        isEnteringPiPMode = false

        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        // YT: music in the background needs no picture (setting "Musik im Hintergrund nur als Audio");
        // onResume turns the video track on again at the same position
        ytMusicAudioIfHidden(stopped = true)
    }

    /** YT: rests the video track of a music video while this page is hidden and no picture shows. */
    private fun ytMusicAudioIfHidden(stopped: Boolean = false) {
        if (isOffline || !this::streams.isInitialized) return
        if (!stopped && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        val activity = activity ?: return
        val pictureVisible = activity.isInPictureInPictureMode ||
            com.github.libretube.ui.yt.YtFloatingPlayer.isShowing
        if (com.github.libretube.util.YtMusicRules.audioInBackground(
                com.github.libretube.helpers.YtMusicPlayback.audioInBackground,
                com.github.libretube.helpers.YtMusicPlayback.isMusic(streams),
                pictureVisible,
                activity.isChangingConfigurations,
            )
        ) {
            setVideoTrackTypeDisabled(true)
        }
    }

    override fun onResume() {
        super.onResume()

        if (closedVideo) {
            closedVideo = false
        }

        // re-enable the autoplay countdown
        setAutoPlayCountdownEnabled(PlayerHelper.autoPlayCountdown)

        // re-enable and load video stream
        setVideoTrackTypeDisabled(false)

        // YT: back from YT's own floating window: the video returns to this page; a turn of
        // the screen that came meanwhile is applied once it is back
        _binding?.let {
            com.github.libretube.ui.yt.YtFloatingPlayer.returnTo(it.player) {
                if (_binding != null && isResumed) restartActivityIfNeeded()
            }
        }
        // YT: the sound wave may have been switched on or off meanwhile (auto-enter PiP follows)
        runCatching { PictureInPictureCompat.setPictureInPictureParams(requireActivity(), pipParams) }
    }

    private fun setAutoPlayCountdownEnabled(enabled: Boolean) {
        if (!::playerController.isInitialized) return

        this.autoPlayCountdownEnabled = enabled

        playerController.sendCustomCommand(
            AbstractPlayerService.runPlayerActionCommand, bundleOf(
                PlayerCommand.SET_AUTOPLAY_COUNTDOWN_ENABLED.name to enabled
            )
        )
    }

    private fun setVideoTrackTypeDisabled(disabled: Boolean) {
        if (!::playerController.isInitialized) return

        playerController.sendCustomCommand(
            AbstractPlayerService.runPlayerActionCommand, bundleOf(
                PlayerCommand.SET_VIDEO_TRACK_TYPE_DISABLED.name to disabled
            )
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        com.github.libretube.ui.yt.YtPipVisualizer.hide()
        // YT: the floating window goes with the page (its controller is released below)
        com.github.libretube.ui.yt.YtFloatingPlayer.close(pause = false)

        handler.removeCallbacksAndMessages(null)

        if (::playerController.isInitialized && playerController.isConnected) {
            playerController.removeListener(playerListener)
            // YT: a player closed softly stays paused in its service for a minute (YtSoftClose)
            if (!ytSoftClosed) {
                playerController.pause()

                playerController.sendCustomCommand(
                    AbstractPlayerService.stopServiceCommand,
                    Bundle.EMPTY
                )
            }
            playerController.release()
        }

        // disable the auto PiP mode for SDK >= 32
        // wrapped in runCatching since the activity may already be finishing/destroyed
        // at this point, which makes the system throw an IllegalStateException
        runCatching {
            PictureInPictureCompat
                .setPictureInPictureParams(requireActivity(), pipParams)
        }

        runCatching {
            if (fullscreenDialog.isShowing) fullscreenDialog.dismiss()
        }

        runCatching {
            // unregister the receiver for player actions
            context?.unregisterReceiver(playerActionReceiver)
        }

        // restore the orientation that's used by the main activity
        baseActivity.requestOrientationChange()

        _binding = null
    }

    /**
     * Manually kill the player fragment - call instead of using onDestroy directly
     */
    private fun killPlayerFragment() {
        binding.playerMotionLayout.transitionToEnd()

        commonPlayerViewModel.isMiniPlayerVisible.value = false

        if (commonPlayerViewModel.isFullscreen.value == true) {
            // wait for the mini player transition to finish
            // that guarantees that the navigation bar is shown properly
            // before we kill the player fragment
            binding.playerMotionLayout.addTransitionListener(object : TransitionAdapter() {
                override fun onTransitionCompleted(motionLayout: MotionLayout?, currentId: Int) {
                    super.onTransitionCompleted(motionLayout, currentId)

                    baseActivity.supportFragmentManager.commit {
                        remove(this@PlayerFragment)
                    }
                }
            })

            unsetFullscreen()
        } else {
            baseActivity.supportFragmentManager.commit {
                remove(this@PlayerFragment)
            }
        }
    }

    private fun checkForSegments() {
        if (!playerController.isPlaying || !PlayerHelper.sponsorBlockEnabled) return

        handler.postDelayed(this::checkForSegments, 100)
        if (viewModel.segments.value.isNullOrEmpty()) return

        val segmentData = playerController.getCurrentSegment(
            viewModel.segments.value.orEmpty(),
            viewModel.sponsorBlockConfig
        )

        if (segmentData != null && commonPlayerViewModel.isMiniPlayerVisible.value != true) {
            val (segment, sbSkipOption) = segmentData

            val autoSkipTemporarilyDisabled =
                !binding.player.sponsorBlockAutoSkip && sbSkipOption != SbSkipOptions.OFF

            if (sbSkipOption in arrayOf(
                    SbSkipOptions.AUTOMATIC_ONCE,
                    SbSkipOptions.MANUAL
                ) || autoSkipTemporarilyDisabled
            ) {
                if (!PictureInPictureCompat.isInPictureInPictureMode(requireActivity())) {
                    playerBackgroundBinding.sbSkipBtn.isVisible = true
                }
                playerBackgroundBinding.sbSkipBtn.setOnClickListener {
                    playerController.seekTo((segment.segmentStartAndEnd.second * 1000f).toLong())
                    segment.skipped = true
                }
            }
        } else {
            playerBackgroundBinding.sbSkipBtn.isGone = true
        }
    }

    private fun setPlayerDefaults() {
        // reset the player view
        playerControlsBinding.exoProgress.clearSegments()
        playerControlsBinding.sbToggle.isGone = true

        // reset the comments to become reloaded later
        commentsViewModel.reset()

        // hide the button to skip SponsorBlock segments manually
        playerBackgroundBinding.sbSkipBtn.isGone = true

        // use the video's default audio track when starting playback
        playerController.sendCustomCommand(
            AbstractPlayerService.runPlayerActionCommand, bundleOf(
                PlayerCommand.SET_AUDIO_ROLE_FLAGS.name to C.ROLE_FLAG_MAIN
            )
        )

        setAutoPlayCountdownEnabled(PlayerHelper.autoPlayCountdown)

        // set the default subtitle if available
        binding.player.updateCurrentSubtitle(viewModel.currentCaptionId)

        // set the default resolution
        binding.player.setToDefaultResolution()

        // YT: a speed the user stored for this channel wins over the music rule
        if (streams.category == Streams.CATEGORY_MUSIC &&
            com.github.libretube.helpers.YtChannelSpeed.find(streams.uploaderUrl?.toID()) == null
        ) {
            playerController.setPlaybackSpeed(1f)
        }
    }

    /**
     * Manually skip to another video.
     *
     * You many only call this if the video is of the same type as the type of the currently running
     * video, i.e. either both are online or both are offline.
     */
    override fun playVideo(videoId: String) {
        playerController.sendCustomCommand(
            AbstractPlayerService.runPlayerActionCommand,
            bundleOf(PlayerCommand.PLAY_VIDEO_BY_ID.name to videoId)
        )
    }

    /**
     * Play the next video.
     */
    fun playNextVideo() {
        playerController.sendCustomCommand(
            AbstractPlayerService.runPlayerActionCommand,
            bundleOf(PlayerCommand.PLAY_NEXT_VIDEO.name to null)
        )
    }

    private fun dismissCommentsSheet() {
        // close comment bottom sheet if opened for next video
        childFragmentManager.fragments
            .filterIsInstance<CommentsSheet>()
            .firstOrNull()
            ?.dismiss()
    }

    private fun toggleVideoInfoVisibility(show: Boolean) {
        binding.descriptionLayout.collapseDescription()
        binding.descriptionLayout.isInvisible = !show
        binding.relatedRecView.isInvisible = !show
        binding.playerChannel.isInvisible = !show
        playerBackgroundBinding.videoTransitionProgress.isVisible = !show

        // YT: the design shows its own creator row and decides when the description is visible
        if (ytWatch != null) {
            binding.playerChannel.isVisible = false
            if (show) binding.descriptionLayout.isVisible = ytShowDescription
        }
    }

    private fun connectToPlayerView(player: Player) {
        // initialize the player view actions
        binding.player.initialize(
            chaptersViewModel,
            commonPlayerViewModel,
            viewModel,
            viewLifecycleOwner,
            this,
            player
        )
    }

    @SuppressLint("SetTextI18n")
    private fun updatePlayerView() {
        dismissCommentsSheet()

        setPlayerDefaults()

        binding.player.useController = false

        if (binding.playerMotionLayout.progress != 1.0f) {
            // show controllers when not in picture in picture mode
            val inPipMode = PictureInPictureCompat.isInPictureInPictureMode(requireActivity())
            if (!inPipMode) {
                binding.player.useController = true
            }
        }

        viewModel.isOrientationChangeInProgress = false

        binding.descriptionLayout.setStreams(streams)

        // YT: video page details of the selected design
        ytWatch?.videoId = videoId
        ytWatch?.streams = streams

        // YT: tint the area below the video with the main color of the thumbnail
        val ytColorTarget = binding.playerScrollView
        val ytContext = requireContext()
        val ytThumbnailUrl = streams.thumbnailUrl
        viewLifecycleOwner.lifecycleScope.launch {
            com.github.libretube.helpers.YtVideoColors.apply(ytContext, ytColorTarget, ytThumbnailUrl)
        }

        toggleVideoInfoVisibility(true)

        binding.apply {
            ImageHelper.loadImage(streams.uploaderAvatar, binding.playerChannelImage, true)
            binding.playerChannelImage.isVisible = streams.uploaderAvatar != null

            playerChannelName.text = streams.uploader
            titleTextView.text = streams.title

            playerChannelSubCount.text = context?.getString(
                R.string.subscribers,
                streams.uploaderSubscriberCount.formatShort()
            )
            playerChannelSubCount.isVisible = streams.uploaderSubscriberCount >= 0

            relPlayerDownload.isVisible = !streams.isLive && !isOffline
        }
        playerControlsBinding.exoTitle.text = streams.title

        // init the chapters recyclerview
        chaptersViewModel.chaptersLiveData.postValue(streams.chapters)

        lifecycleScope.launch {
            showRelatedStreams()
        }

        // update the subscribed state
        if (streams.uploaderUrl != null) {
            binding.playerSubscribe.setupSubscriptionButton(
                streams.uploaderUrl!!.toID(),
                streams.uploader,
                streams.uploaderAvatar,
                streams.uploaderVerified
            )
        } else {
            binding.playerSubscribe.isGone = true
        }

        // seekbar preview setup
        playerControlsBinding.seekbarPreview.isGone = true
        seekBarPreviewListener?.let { playerControlsBinding.exoProgress.removeSeekBarListener(it) }

        lifecycleScope.launch {
            val timeFrameReceiver = getTimeFrameReceiver() ?: return@launch
            val listener = SeekbarPreviewListener(
                timeFrameReceiver,
                playerControlsBinding,
                streams.duration * 1000
            )

            seekBarPreviewListener = listener
            playerControlsBinding.exoProgress.addSeekBarListener(listener)
        }

        if (binding.playerMotionLayout.progress == 0f && PlayerHelper.autoFullscreenShortsEnabled && streams.isShort) {
            setFullscreen()
        }

        // it's possible that the highlight segment was loaded before the streams info finished loading
        // in this case, we have to initialize the video highlight chapter once again
        getHighlight(viewModel.segments.value.orEmpty())?.let {
            lifecycleScope.launch(Dispatchers.IO) { initializeHighlight(it) }
        }

        // YT: the features of round 12 learn of the video
        com.github.libretube.ui.yt.player.YtPlayerModules.onVideo(this, videoId, streams)
    }

    private suspend fun showRelatedStreams() {
        if (!PlayerHelper.relatedStreamsEnabled) return

        val relatedStreams = if (isOffline) {
            withContext(Dispatchers.IO) {
                DatabaseHolder.Database.downloadDao().getAll()
                    .filter { it.download.videoId != videoId }
                    .map { it.download.toStreamItem() }
            }
        } else {
            streams.relatedStreams.filter { !it.title.isNullOrBlank() }
        }

        val relatedLayoutManager = binding.relatedRecView.layoutManager as LinearLayoutManager
        binding.relatedRecView.adapter = VideoCardsAdapter(
            columnWidthDp = if (relatedLayoutManager.orientation == LinearLayoutManager.HORIZONTAL) 250f else null
        ).also { adapter ->
            adapter.submitList(relatedStreams)
        }
    }

    private fun showAutoPlayCountdown() {
        if (!PlayingQueue.hasNext()) return

        disableController()
        playerBackgroundBinding.autoplayCountdown.setHideSelfListener {
            // could fail if the video already got closed before
            runCatching {
                playerBackgroundBinding.autoplayCountdown.isGone = true
                binding.player.useController = true
            }
        }
        playerBackgroundBinding.autoplayCountdown.startCountdown {
            playNextVideo()
        }
    }

    /**
     * Handle a link clicked in the description
     */
    private fun handleLink(link: String) {
        // get video id if the link is a valid youtube video link
        val uri = link.toUri()
        val videoId = TextUtils.getVideoIdFromUri(uri)

        if (videoId.isNullOrEmpty()) {
            // YT: only web links leave the app; other schemes in someone else's text are ignored
            if (!com.github.libretube.helpers.YtGuard.isWebLink(uri)) return
            // not a YouTube video link, thus handle normally
            val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)

            // start PiP mode if enabled
            onUserLeaveHint()
            runCatching { startActivity(intent) }

            return
        }

        // check if the video is the current video and has a valid time
        if (videoId == this.videoId) {
            // try finding the time stamp of the url and seek to it if found
            uri.getQueryParameter("t")?.toTimeInSeconds()?.let {
                playerController.seekTo(it * 1000)
            }
        } else {
            // YouTube video link without time or not the current video, thus load in player
            playVideo(videoId)
        }
    }

    private fun updatePlayPauseButton() {
        val playPauseAction = PlayerHelper.getPlayPauseActionIcon(playerController)
        binding.playImageView.setImageResource(playPauseAction)
    }

    private suspend fun getTimeFrameReceiver(): TimeFrameReceiver? = withContext(Dispatchers.IO) {
        return@withContext if (isOffline) {
            if (!::videoId.isInitialized) return@withContext null

            val downloadItems =
                DatabaseHolder.Database.downloadDao().getDownloadById(videoId)?.downloadItems
            downloadItems?.firstOrNull { it.path.exists() && it.type == FileType.VIDEO }?.path?.let {
                OfflineTimeFrameReceiver(requireContext(), it)
            }
        } else {
            if (!::streams.isInitialized) return@withContext null

            OnlineTimeFrameReceiver(requireContext(), streams.previewFrames)
        }
    }

    private fun getHighlight(segments: List<Segment>): Segment? {
        return segments.firstOrNull { it.category == PlayerHelper.SPONSOR_HIGHLIGHT_CATEGORY }
    }

    private suspend fun initializeHighlight(highlight: Segment) {
        val frameReceiver = getTimeFrameReceiver() ?: return

        val highlightStart = highlight.segmentStartAndEnd.first.toLong()
        val frame = withContext(Dispatchers.IO) {
            frameReceiver.getFrameAtTime(highlightStart * 1000)
        }
        val highlightChapter = ChapterSegment(
            title = getString(R.string.chapters_videoHighlight),
            start = highlightStart,
            highlightDrawable = frame?.toDrawable(requireContext().resources)
        )
        chaptersViewModel.chaptersLiveData.postValue(
            chaptersViewModel.chapters.plus(highlightChapter).sortedBy { it.start }
        )
    }

    /**
     * Use the sensor mode if auto fullscreen is enabled
     */
    @SuppressLint("SourceLockedOrientationActivity")
    private fun changeOrientationMode() {
        if (PlayerHelper.autoFullscreenEnabled) {
            // enable auto rotation
            baseActivity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        } else {
            // go to portrait mode
            baseActivity.requestedOrientation =
                (requireActivity() as BaseActivity).screenOrientationPref
        }
    }


    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        if (isInPictureInPictureMode) {
            // hide and disable exoPlayer controls
            disableController()

            binding.player.updateCurrentSubtitle(null)
            playerBackgroundBinding.sbSkipBtn.isGone = true

            openOrCloseFullscreenDialog(true)
            pipActivity = activity
            // YT: the sound wave below the floating window, if switched on
            activity?.let { host ->
                com.github.libretube.ui.yt.YtPipVisualizer.show(
                    host,
                    position = {
                        if (::playerController.isInitialized && playerController.isPlaying) playerController.currentPosition else null
                    },
                    speed = {
                        if (::playerController.isInitialized) playerController.playbackParameters.speed else 1f
                    },
                )
            }
        } else {
            com.github.libretube.ui.yt.YtPipVisualizer.hide()
            binding.player.useController = true

            // close button got clicked in PiP mode
            // pause the video and keep the app alive
            if (lifecycle.currentState == Lifecycle.State.CREATED) {
                playerController.pause()
                closedVideo = true
            }

            binding.player.updateCurrentSubtitle(viewModel.currentCaptionId)

            // unset fullscreen if it's not been enabled before the start of PiP
            if (commonPlayerViewModel.isFullscreen.value != true) {
                openOrCloseFullscreenDialog(false)
            }
        }
    }

    fun onUserLeaveHint() {
        if (shouldStartPiP() || com.github.libretube.ui.yt.YtPipVisualizer.testPip) {
            // YT: with the sound wave on, the video floats in YT's own window, the wave with it
            if (floatOnOwn()) return
            com.github.libretube.ui.yt.YtPipVisualizer.beforePip(requireContext())
            PictureInPictureCompat.enterPictureInPictureMode(requireActivity(), pipParams)
        }
    }

    /**
     * YT: whether leaving the app floats the video in YT's own window (sound wave on, overlay
     * allowed). Only from the main screen: it hands Home to this page and can reopen it; the
     * offline screen keeps Android's picture-in-picture.
     */
    private fun floatsOnOwn(): Boolean {
        val context = context ?: return false
        return activity is MainActivity && com.github.libretube.ui.yt.YtFloatingPlayer.wanted(context)
    }

    /** YT: floats the video in YT's own window; false when it does not (then PiP as before). */
    private fun floatOnOwn(): Boolean {
        if (_binding == null || !::playerController.isInitialized || !floatsOnOwn()) return false
        return com.github.libretube.ui.yt.YtFloatingPlayer.show(requireActivity(), playerController, binding.player, isOffline)
    }

    private val pipParams: PictureInPictureParamsCompat
        get() = run {
            val isPlaying = ::playerController.isInitialized && playerController.isPlaying

            PictureInPictureParamsCompat.Builder()
                .setActions(
                    PlayerHelper.getPiPModeActions(
                        requireActivity(),
                        isPlaying
                    )
                )
                // YT: with the sound wave on, YT floats in its own window instead (onUserLeaveHint)
                .setAutoEnterEnabled(isPlaying && !floatsOnOwn())
                .apply {
                    if (isPlaying) {
                        setAspectRatio(playerController.videoSize)
                    }
                }
                .build()
        }

    /**
     * Detect whether PiP is supported and enabled
     */
    private fun isPipAvailable() =
        PictureInPictureCompat.isPictureInPictureAvailable(requireContext())
                && PictureInPictureCompat.isPictureInPictureEnabled(requireContext())

    private fun shouldStartPiP(): Boolean {
        return isPipAvailable() && ::playerController.isInitialized && playerController.isPlaying
    }

    /**
     * Check if the activity needs to be recreated due to an orientation change
     * If true, the activity will be automatically restarted
     */
    private fun restartActivityIfNeeded(): Boolean {
        // YT: decided by YtPageLayoutRules: by the orientation the activity is locked to now
        // (upstream: by the orientation setting, which auto fullscreen overrides with the
        // sensor), and not while the video floats in YT's own window (it would lose its controller)
        val orientation = resources.configuration.orientation
        val rebuild = com.github.libretube.ui.yt.player.YtPageLayoutRules.rebuild(
            fullscreen = commonPlayerViewModel.isFullscreen.value == true,
            orientation = orientation,
            layoutOrientation = playerLayoutOrientation,
            requested = baseActivity.requestedOrientation,
            rebuilding = viewModel.isOrientationChangeInProgress,
            floating = com.github.libretube.ui.yt.YtFloatingPlayer.isShowing,
        )
        if (rebuild) {
            // remember the current position before recreating the activity
            playerLayoutOrientation = orientation

            viewModel.isOrientationChangeInProgress = true

            // detach player view from player to stop surface rendering
            binding.player.detachPlayer()

            if (::playerController.isInitialized) playerController.release()

            activity?.recreate()
        }
        return rebuild
    }

    // YT: orientation and screen size of the last configuration this page heard of
    private var ytLastSize: Triple<Int, Int, Int>? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (_binding == null ||
            // If in PiP mode, orientation is given as landscape.
            PictureInPictureCompat.isInPictureInPictureMode(requireActivity()) ||
            // YT: the page is behind YT's own floating window
            com.github.libretube.ui.yt.YtFloatingPlayer.isFloating
        ) {
            return
        }

        // YT: the page changed its size without a rebuild (not for a change of theme, say):
        // running gestures end, the minimize drag measures the page anew
        val ytSize = Triple(newConfig.orientation, newConfig.screenWidthDp, newConfig.screenHeightDp)
        if (ytSize != ytLastSize) {
            ytLastSize = ytSize
            com.github.libretube.ui.yt.player.YtPlayerModules.onConfigurationChanged(this)
        }

        // YT: a docked player stays docked when the phone turns (it is no video to fill the screen)
        if (PlayerHelper.autoFullscreenEnabled && commonPlayerViewModel.isMiniPlayerVisible.value != true) {
            when (newConfig.orientation) {
                // go to fullscreen mode
                Configuration.ORIENTATION_LANDSCAPE -> setFullscreen()
                // exit fullscreen if not landscape
                else -> unsetFullscreen()
            }
        }

        restartActivityIfNeeded()
    }

    private fun disableController() {
        binding.player.useController = false
        binding.player.hideController()
    }

    fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return _binding?.player?.onKeyUp(keyCode, event) ?: false
    }

    override fun onDestroyView() {
        // YT: the features of round 12 let go of the page while its views still exist
        com.github.libretube.ui.yt.player.YtPlayerModules.onDestroyView(this)
        YtPlaybackClock.detach(ytClockReader)
        super.onDestroyView()
        _binding = null
    }

    override fun getVideoId(): String {
        return videoId
    }

    override fun isVideoShort(): Boolean {
        return ::streams.isInitialized && streams.isShort
    }

    override fun isVideoLive(): Boolean {
        return ::streams.isInitialized && streams.isLive
    }

    // YT: the page as the host of the features of round 12 (YtPlayerHost). Each method does what
    // the page's own buttons and gestures already do; playVideo and toggleFullscreen are above.

    /** What the shared playback clock reads; null while no controller is connected. */
    private val ytClockReader: () -> YtClock? = reader@{
        if (!::playerController.isInitialized || !playerController.isConnected) return@reader null
        YtClock(
            videoId = if (::videoId.isInitialized) videoId else null,
            positionMs = playerController.currentPosition,
            durationMs = playerController.duration.coerceAtLeast(0L),
            playing = playerController.isPlaying,
            speed = playerController.playbackParameters.speed,
            isLive = ::streams.isInitialized && streams.isLive,
        )
    }

    override fun fragment(): Fragment = this

    override fun controller(): MediaController? =
        if (::playerController.isInitialized) playerController else null

    override fun pageBinding(): FragmentPlayerBinding? = _binding

    override fun controls(): ExoStyledPlayerControlViewBinding? = _binding?.player?.binding

    override fun currentVideoId(): String? = if (::videoId.isInitialized) videoId else null

    override fun currentStreams(): Streams? = if (::streams.isInitialized) streams else null

    override fun playPrev(): Boolean {
        if (!::playerController.isInitialized) return false
        // as the previous button of the controls
        val prev = PlayingQueue.getPrev() ?: return false
        playVideo(prev)
        return true
    }

    override fun playNext(): Boolean {
        if (!::playerController.isInitialized) return false
        val next = nextCandidate()?.url?.toID() ?: return false
        playVideo(next)
        return true
    }

    override fun nextCandidate(): StreamItem? {
        // the queue, as the next button and autoplay go through it
        PlayingQueue.getNext()?.let { id ->
            PlayingQueue.getStreams().firstOrNull { it.url?.toID() == id }?.let { return it }
        }
        // what autoplay takes once the queue is done (AbstractPlayerService.playNextVideo)
        if (isOffline || !PlayerHelper.autoPlayEnabled || !PlayerHelper.relatedStreamsEnabled) return null
        if (!::streams.isInitialized) return null
        return streams.relatedStreams.firstOrNull { !it.isLive }?.takeIf { it.url != null }
    }

    override fun minimize() {
        val page = _binding ?: return
        if (commonPlayerViewModel.isFullscreen.value == true) unsetFullscreen()
        // as back does
        page.playerMotionLayout.setTransitionDuration(com.github.libretube.ui.yt.YtMiniPlayer.minimizeDuration())
        page.playerMotionLayout.transitionToEnd()
        baseActivity.minimizePlayerContainerLayout()
        baseActivity.requestOrientationChange()
    }

    override fun maximize() {
        // as a tap on the mini player or a link to the playing video does
        _binding?.playerMotionLayout?.transitionToStart()
    }

    override fun close() {
        if (_binding != null) {
            ytRememberClosed()
            killPlayerFragment()
        }
    }

    /** YT: the user closes the player; a long press on Abos can bring it back for a while. */
    private fun ytRememberClosed() {
        val controller = if (::playerController.isInitialized) playerController else null
        com.github.libretube.ui.yt.mini.YtMiniRestore.closed(
            videoId = if (::videoId.isInitialized) videoId else null,
            positionMs = controller?.currentPosition ?: 0L,
            durationMs = controller?.duration ?: 0L,
            offline = isOffline,
        )
        // the player itself is kept paused for a minute, so a long press brings it back as it is
        if (controller != null && !ytSoftClosed) {
            ytSoftClosed = com.github.libretube.ui.yt.mini.YtSoftClose.close(
                requireContext(),
                controller,
                videoId = if (::videoId.isInitialized) videoId else null,
                offline = isOffline,
                live = (this::streams.isInitialized && streams.isLive) || controller.isCurrentMediaItemLive,
            )
        }
    }

    override fun audioMode() {
        if (_binding != null && ::playerController.isInitialized) switchToAudioMode()
    }

    override fun share(seconds: Long?, direct: Boolean) {
        if (!::streams.isInitialized || !::videoId.isInitialized) return
        val position = seconds
            ?: if (::playerController.isInitialized) playerController.currentPosition / 1000 else null
        if (!direct) {
            // the dialog of the share button
            val bundle = bundleOf(
                IntentData.id to videoId,
                IntentData.shareObjectType to ShareObjectType.VIDEO,
                IntentData.shareData to ShareData(
                    title = streams.title,
                    previewImageUrl = streams.thumbnailUrl,
                    currentPosition = position
                )
            )
            val shareDialog = ShareDialog()
            shareDialog.arguments = bundle
            shareDialog.show(childFragmentManager, ShareDialog::class.java.name)
            return
        }
        // straight to the system's share sheet, the time in the link
        val link = "${ShareDialog.YOUTUBE_SHORT_URL}/$videoId" + (position?.let { "?t=$it" } ?: "")
        val intent = Intent(Intent.ACTION_SEND)
            .putExtra(Intent.EXTRA_TEXT, link)
            .putExtra(Intent.EXTRA_TITLE, streams.title)
            .setType("text/plain")
        runCatching { startActivity(Intent.createChooser(intent, getString(R.string.shareTo))) }
    }

    override fun seekTagged(positionMs: Long, reason: YtSeekReason) {
        if (!::playerController.isInitialized) return
        YtPlaybackClock.tagNextSeek(reason)
        playerController.seekTo(positionMs)
    }

    override fun showControlsPermanently(on: Boolean) {
        val player = _binding?.player ?: return
        if (on) player.showControllerPermanently() else player.showController()
    }
}
