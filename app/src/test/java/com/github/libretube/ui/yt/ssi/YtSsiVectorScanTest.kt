package com.github.libretube.ui.yt.ssi

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Instant's vector scan at SSI-1's scale (50 000 passages of e5-small's 384 dimensions), on
 * synthetic vectors: the parallel scan finds what one thread finds, a cancelled scan stops, and
 * a scan stays far below Instant's 1 s (the bound is generous, so slow CI machines pass).
 */
class YtSsiVectorScanTest {
    private val dim = 384
    private val rows = 50_000

    private fun index(): Pair<YtSsiVectorIndex, List<FloatArray>> {
        val random = Random(7)
        val index = YtSsiVectorIndex("m", dim)
        val probes = ArrayList<FloatArray>()
        // in chunks of 5 000, like videos added over time
        var id = 1L
        repeat(rows / 5_000) { chunk ->
            val ids = LongArray(5_000) { id++ }
            val scales = FloatArray(5_000)
            val data = ByteArray(5_000 * dim)
            for (r in 0 until 5_000) {
                val v = YtSsiVectors.normalize(FloatArray(dim) { random.nextFloat() * 2 - 1 })
                if (r == 1_234) probes += v
                val (q, scale) = YtSsiVectors.quantize(v)
                scales[r] = scale
                q.copyInto(data, r * dim)
            }
            index.add(ids, scales, data)
        }
        return index to probes
    }

    @Test
    fun parallelScanFindsWhatOneThreadFinds() = runBlocking {
        val (index, probes) = index()
        assertEquals(rows, index.size)
        for (probe in probes.take(3)) {
            val one = index.search(probe, 200, threads = 1)
            val two = index.search(probe, 200, threads = 2)
            assertEquals(2, index.lastThreads)
            assertEquals(one.map { it.first }, two.map { it.first })
            assertEquals(200, two.size)
        }
        // the probe itself (row 1 234 of its chunk) comes first
        val best = index.search(probes[4], 5).first()
        assertEquals(4 * 5_000L + 1_235L, best.first)
        assertEquals(1f, best.second, 0.02f)
    }

    @Test
    fun aCancelledScanStops() = runBlocking {
        val (index, probes) = index()
        assertNull(withTimeoutOrNull(1) { repeat(50) { index.search(probes[0], 200) } })
    }

    @Test
    fun fiftyThousandPassagesInWellUnderASecond() = runBlocking {
        val (index, probes) = index()
        repeat(3) { index.search(probes[it], 200) } // warm-up
        val times = (0 until 5).map {
            val t = System.nanoTime()
            index.search(probes[it % probes.size], 200)
            (System.nanoTime() - t) / 1_000_000
        }.sorted()
        val median = times[times.size / 2]
        println("vector scan 50000 x $dim: median $median ms (runs $times)")
        assertTrue("median $median ms", median < 600)
    }

    @Test
    fun smallIndexesUseOneThread() = runBlocking {
        val index = YtSsiVectorIndex("m", 8)
        val v = YtSsiVectors.normalize(FloatArray(8) { it + 1f })
        val (q, s) = YtSsiVectors.quantize(v)
        index.add(longArrayOf(5), floatArrayOf(s), q)
        assertEquals(5L, index.search(v, 3).single().first)
        assertEquals(1, index.lastThreads)
        assertTrue(index.search(FloatArray(3), 3).isEmpty())
    }
}
