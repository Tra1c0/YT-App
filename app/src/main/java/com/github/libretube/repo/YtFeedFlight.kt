package com.github.libretube.repo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CopyOnWriteArrayList

/**
 * YT: one network refresh of the subscription feed at a time. The home's pull, the
 * subscriptions tab's pull and its background refresh all asked for a full refresh on their
 * own, so switching tabs during a refresh started a second walk over every channel next to
 * the first (twice the requests, and YouTube throttles sooner). A caller that comes while a
 * refresh runs now waits for that one and gets its result and its progress.
 *
 * The refresh runs in its own scope, so a caller that goes away (the home cancels its load job
 * on every reload) does not cancel it for the others.
 */
class YtFeedFlight<T>(private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
    private class Flight<T>(val deferred: Deferred<T>, val listeners: MutableList<(FeedProgress) -> Unit>)

    private val lock = Mutex()
    private var current: Flight<T>? = null

    /** True while a refresh runs. */
    val running: Boolean get() = current?.deferred?.isActive == true

    /**
     * Runs [block], or joins the refresh already running. [onProgress] gets the progress of
     * whichever refresh this call waits for.
     */
    suspend fun run(onProgress: (FeedProgress) -> Unit, block: suspend ((FeedProgress) -> Unit) -> T): T {
        val flight = lock.withLock {
            current?.takeIf { it.deferred.isActive }?.also { it.listeners.add(onProgress) }
                ?: run {
                    val listeners = CopyOnWriteArrayList<(FeedProgress) -> Unit>(listOf(onProgress))
                    val deferred = scope.async {
                        block { progress -> listeners.forEach { it(progress) } }
                    }
                    Flight(deferred, listeners).also { current = it }
                }
        }
        return flight.deferred.await()
    }
}
