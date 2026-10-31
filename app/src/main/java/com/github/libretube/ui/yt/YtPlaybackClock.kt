package com.github.libretube.ui.yt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** What the player plays, as the clock last read it. Empty while no player page is open. */
data class YtClock(
    val videoId: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playing: Boolean = false,
    val speed: Float = 1f,
    val isLive: Boolean = false,
)

/** Why the position jumped. Seeks a feature makes on purpose say so with [YtPlaybackClock.tagNextSeek]. */
enum class YtSeekReason {
    /** The user scrubbing the time bar (or the magnifier). */
    USER_SCRUB,

    /** A chip that jumps back (the return point). */
    CHIP,

    /** A loop jumping back to its start. */
    LOOP,

    /** A skipped segment. */
    SKIP,

    /** Anything else: double taps, chapters, links, the notification. */
    OTHER,
}

/** A jump of the position from [fromMs] to [toMs]. */
data class YtSeek(val fromMs: Long, val toMs: Long, val reason: YtSeekReason)

/**
 * YT: one clock for every feature that follows the playback (rewind memory, scene comments,
 * loops), instead of a polling loop each. [clock] ticks every 250 ms, but only while someone
 * collects it; [seeks] reports every jump of the position with its reason. The player page
 * hands over how to read its controller ([attach]) and reports the seeks ([onSeek]).
 * Main thread only.
 */
object YtPlaybackClock {
    private const val TICK_MS = 250L

    /** A tag only names the seek that follows soon after it, not one much later. */
    private const val TAG_VALID_NS = 2_000_000_000L

    private val state = MutableStateFlow(YtClock())

    /** The playback, read every 250 ms while collected. */
    val clock: StateFlow<YtClock> = state.asStateFlow()

    private val seekEvents = MutableSharedFlow<YtSeek>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Every jump of the position, with its reason. */
    val seeks: SharedFlow<YtSeek> = seekEvents.asSharedFlow()

    private var source: (() -> YtClock?)? = null
    private var tag: YtSeekReason? = null
    private var tagAtNs = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        // the clock reads the controller only while someone listens
        scope.launch {
            state.subscriptionCount
                .map { it > 0 }
                .distinctUntilChanged()
                .collectLatest { active ->
                    while (active) {
                        read()
                        delay(TICK_MS)
                    }
                }
        }
    }

    /** The player page hands over how to read its controller (null while none is connected). */
    fun attach(reader: () -> YtClock?) {
        source = reader
        read()
    }

    /** The page is gone; only the reader that is still attached is removed. */
    fun detach(reader: () -> YtClock?) {
        if (source !== reader) return
        source = null
        state.value = YtClock()
    }

    /** Reads the playback now, for code that needs it fresh without collecting [clock]. */
    fun now(): YtClock {
        read()
        return state.value
    }

    /** The next seek is made on purpose, for [reason]; call it right before the seek. */
    fun tagNextSeek(reason: YtSeekReason) {
        tag = reason
        tagAtNs = System.nanoTime()
    }

    /** Called by the player page for every seek the controller reports. */
    fun onSeek(fromMs: Long, toMs: Long) {
        val reason = tag?.takeIf { System.nanoTime() - tagAtNs <= TAG_VALID_NS } ?: YtSeekReason.OTHER
        tag = null
        seekEvents.tryEmit(YtSeek(fromMs, toMs, reason))
        read()
    }

    private fun read() {
        state.value = runCatching { source?.invoke() }.getOrNull() ?: YtClock()
    }
}
