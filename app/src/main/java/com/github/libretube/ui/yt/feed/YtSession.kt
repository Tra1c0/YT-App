package com.github.libretube.ui.yt.feed

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * YT: the running time box ("Heute habe ich … Minuten"), shared by the home (which starts it),
 * the player service (YtSessionModule: end points, holding autoplay, counting the time) and
 * the mini player (which shows what is left). Main thread only.
 *
 * For the mini player: collect [state]; with a state, `remainingMs(SystemClock.elapsedRealtime())`
 * and `fraction(now)` give the time left (it only runs down while a video plays), `done` says the
 * time is up. rememberYtTimeLeft() in ui/yt does that once a second.
 */
object YtSession {
    private val current = MutableStateFlow<YtSessionState?>(null)

    /** The running time box, or null when there is none. */
    val state: StateFlow<YtSessionState?> = current.asStateFlow()

    private var keys = 0L

    val active: Boolean get() = current.value != null

    /** Starts a time box of [minutes] at [speed] with what [plan] holds; replaces a running one. */
    fun start(minutes: Int, speed: Float, plan: YtBoxPlan): YtSessionState {
        val state = YtSessionState(
            key = ++keys,
            minutes = minutes,
            speed = speed,
            budgetMs = minutes * 60_000L,
            items = plan.items,
            spare = plan.spare,
        )
        current.value = state
        return state
    }

    /** Changes the running time box; nothing happens without one. */
    fun update(transform: (YtSessionState) -> YtSessionState) {
        current.update { it?.let(transform) }
    }

    /** Changes the time box only if it is still the one with [key] (a late background result). */
    fun updateIf(key: Long, transform: (YtSessionState) -> YtSessionState): Boolean {
        var changed = false
        current.update { state ->
            if (state == null || state.key != key) return@update state
            changed = true
            transform(state)
        }
        return changed
    }

    fun end() {
        current.value = null
    }
}
