package com.github.libretube.ui.yt.ssi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class YtSsiVectorsTest {
    private fun random(dim: Int, seed: Int) = Random(seed).let { r -> YtSsiVectors.normalize(FloatArray(dim) { r.nextFloat() * 2 - 1 }) }

    @Test
    fun int8KeepsTheCosine() {
        val a = random(384, 1)
        val b = random(384, 2)
        val (q, scale) = YtSsiVectors.quantize(b)
        val exact = YtSsiVectors.cosine(a, b)
        val approx = YtSsiVectors.dot(a, q, scale)
        assertEquals(exact, approx, 0.01f)
        assertEquals(1f, YtSsiVectors.dot(b, q, scale), 0.01f)
    }

    @Test
    fun topKKeepsTheBest() {
        val top = YtSsiVectors.TopK(3)
        listOf(0.1f, 0.9f, 0.5f, 0.7f, 0.2f).forEachIndexed { i, s -> top.offer(s, i) }
        assertEquals(listOf(1, 3, 2), top.best().map { it.second })
    }

    @Test
    fun meanPoolingUsesTheMask() {
        val hidden = arrayOf(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f), floatArrayOf(100f, 100f))
        val pooled = YtSsiVectors.meanPool(hidden, longArrayOf(1, 1, 0))
        assertEquals(0.7071f, pooled[0], 1e-3f)
        assertEquals(0.7071f, pooled[1], 1e-3f)
    }

    @Test
    fun indexFindsTheNearestAcrossChunks() {
        val index = YtSsiVectorIndex("m", 16)
        val vectors = (0 until 50).map { random(16, it) }
        fun add(range: IntRange) {
            val rows = range.map { YtSsiVectors.quantize(vectors[it]) }
            index.add(range.map { it + 100L }.toLongArray(), rows.map { it.second }.toFloatArray(), rows.flatMap { it.first.toList() }.toByteArray())
        }
        add(0 until 20)
        add(20 until 50)
        assertEquals(50, index.size)
        assertEquals(149L, index.maxId)
        val found = kotlinx.coroutines.runBlocking { index.search(vectors[33], 5) }
        assertEquals(133L, found.first().first)
        assertEquals(1f, found.first().second, 0.02f)
        assertEquals(5, found.size)
    }

    @Test
    fun theCutKeepsOnlyCloseOnes() {
        val found = listOf(1L to 0.86f, 2L to 0.83f, 3L to 0.77f, 4L to 0.70f)
        assertEquals(listOf(1L, 2L), YtSsiVectorIndex.cut(found).map { it.first })
        // nothing near: nothing found (no-match is an answer)
        assertTrue(YtSsiVectorIndex.cut(listOf(1L to 0.72f, 2L to 0.70f)).isEmpty())
    }
}
