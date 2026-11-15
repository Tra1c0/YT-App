package com.github.libretube.ui.yt.ssi

import java.nio.ByteBuffer
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * YT: the vector side of SSI, pure. Passage vectors are L2-normalised and kept as int8 with one
 * scale per vector (a quarter of float32, the cosine changes by well under 1 %); the search is a
 * plain scan over all of them with a top-K heap, fast enough below some 200 000 passages.
 */
object YtSsiVectors {
    fun normalize(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += x.toDouble() * x
        val norm = sqrt(sum).toFloat()
        if (norm <= 0f) return v.copyOf()
        return FloatArray(v.size) { v[it] / norm }
    }

    /** [v] as int8 and its scale: v[i] ≈ q[i] * scale. */
    fun quantize(v: FloatArray): Pair<ByteArray, Float> {
        var max = 0f
        for (x in v) max = maxOf(max, abs(x))
        if (max <= 0f) return ByteArray(v.size) to 0f
        val scale = max / 127f
        return ByteArray(v.size) { (v[it] / scale).roundToInt().coerceIn(-127, 127).toByte() } to scale
    }

    fun dequantize(q: ByteArray, scale: Float): FloatArray = FloatArray(q.size) { q[it] * scale }

    /** The dot product of [query] with the int8 vector at [offset] in [data] (times its [scale]). */
    fun dot(query: FloatArray, data: ByteBuffer, offset: Int, scale: Float): Float {
        var sum = 0f
        for (i in query.indices) sum += query[i] * data.get(offset + i)
        return sum * scale
    }

    fun dot(query: FloatArray, q: ByteArray, scale: Float): Float {
        var sum = 0f
        for (i in query.indices) sum += query[i] * q[i]
        return sum * scale
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i]
            na += a[i].toDouble() * a[i]
            nb += b[i].toDouble() * b[i]
        }
        if (na <= 0 || nb <= 0) return 0f
        return (dot / (sqrt(na) * sqrt(nb))).toFloat()
    }

    /**
     * Mean pooling of a transformer's last hidden states ([hidden] = tokens × dim) over the tokens
     * the [mask] marks, then L2-normalised: the sentence vector of e5 and sentence-transformers.
     */
    fun meanPool(hidden: Array<FloatArray>, mask: LongArray): FloatArray {
        val dim = hidden.firstOrNull()?.size ?: return FloatArray(0)
        val out = FloatArray(dim)
        var count = 0
        for (t in hidden.indices) {
            if (mask.getOrElse(t) { 0L } == 0L) continue
            count++
            val row = hidden[t]
            for (d in 0 until dim) out[d] += row[d]
        }
        if (count > 0) for (d in 0 until dim) out[d] /= count
        return normalize(out)
    }

    /** The [k] best (score, index) pairs, best first. */
    class TopK(private val k: Int) {
        private val heap = PriorityQueue<Pair<Float, Int>>(k.coerceAtLeast(1) + 1, compareBy { it.first })

        fun offer(score: Float, index: Int) {
            if (k <= 0) return
            if (heap.size < k) {
                heap.add(score to index)
            } else if (score > heap.peek()!!.first) {
                heap.poll()
                heap.add(score to index)
            }
        }

        fun best(): List<Pair<Float, Int>> = heap.sortedByDescending { it.first }
    }
}

/**
 * The passage vectors of one model in memory, off the Java heap (direct buffers): int8 rows of
 * [dim] values with one scale each, in chunks so new videos are added without reading the rest
 * again. Rows of passages deleted since are found by the caller (the passage is gone) and skipped.
 */
class YtSsiVectorIndex(val model: String, val dim: Int) {
    private class Chunk(val ids: LongArray, val scales: FloatArray, val data: ByteBuffer)

    private val chunks = ArrayList<Chunk>()

    /** The highest passage id loaded: later ones are new. */
    var maxId: Long = 0
        private set

    val size: Int get() = synchronized(chunks) { chunks.sumOf { it.ids.size } }

    /** Adds rows; [vectors] holds [ids].size × [dim] int8 values. */
    fun add(ids: LongArray, scales: FloatArray, vectors: ByteArray) {
        if (ids.isEmpty()) return
        require(vectors.size == ids.size * dim && scales.size == ids.size)
        val buffer = ByteBuffer.allocateDirect(vectors.size)
        buffer.put(vectors)
        buffer.rewind()
        synchronized(chunks) {
            chunks += Chunk(ids, scales, buffer)
            maxId = maxOf(maxId, ids.maxOrNull() ?: 0)
        }
    }

    /** The [k] passages closest to [query] (normalised), best first, as (id, cosine). */
    fun search(query: FloatArray, k: Int): List<Pair<Long, Float>> {
        if (query.size != dim) return emptyList()
        val top = YtSsiVectors.TopK(k)
        val list = synchronized(chunks) { chunks.toList() }
        // the heap holds (chunk << 32 | row) as one index, so no id is boxed per row
        for ((c, chunk) in list.withIndex()) {
            for (row in chunk.ids.indices) {
                top.offer(YtSsiVectors.dot(query, chunk.data, row * dim, chunk.scales[row]), (c shl 20) or row)
            }
        }
        return top.best().map { (score, at) -> list[at ushr 20].ids[at and 0xFFFFF] to score }
    }

    companion object {
        /**
         * Which semantic neighbours count as found: e5 puts unrelated text at a cosine of about
         * 0.7, so a plain top-K would always "find" something. Kept are the ones at least
         * [MIN_COSINE] and within [BAND] of the best. Tuned on no real data yet.
         */
        const val MIN_COSINE = 0.78f
        const val BAND = 0.08f

        fun cut(found: List<Pair<Long, Float>>): List<Pair<Long, Float>> {
            val best = found.maxOfOrNull { it.second } ?: return emptyList()
            val floor = maxOf(MIN_COSINE, best - BAND)
            return found.filter { it.second >= floor }
        }
    }
}
