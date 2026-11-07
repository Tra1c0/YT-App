package com.github.libretube.repo

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class YtFeedFlightTest {
    @Test
    fun aSecondCallerJoinsTheRunningRefresh() = runBlocking {
        val flight = YtFeedFlight<Int>(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val runs = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val progressSeen = AtomicInteger()
        val first = async {
            flight.run({}) { progress ->
                runs.incrementAndGet()
                gate.await()
                progress(FeedProgress(1, 1))
                42
            }
        }
        withTimeout(2_000) { while (!flight.running) yield() }
        // undispatched: the second call registers before the gate opens
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            flight.run({ progressSeen.incrementAndGet() }) { runs.incrementAndGet(); 0 }
        }
        gate.complete(Unit)
        assertEquals(42, first.await())
        assertEquals(42, second.await())
        assertEquals(1, progressSeen.get())
        assertEquals(1, runs.get())
        assertFalse(flight.running)
    }

    @Test
    fun aCallerAfterTheRefreshStartsANewOne() = runBlocking {
        val flight = YtFeedFlight<Int>(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        assertEquals(1, flight.run({}) { 1 })
        assertEquals(2, flight.run({}) { 2 })
    }

    @Test
    fun aFailureReachesTheCallerAndTheNextCallRunsAgain() = runBlocking {
        val flight = YtFeedFlight<Int>(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val failed = runCatching { flight.run({}) { error("offline") } }
        assertTrue(failed.isFailure)
        assertEquals(3, flight.run({}) { 3 })
    }
}
