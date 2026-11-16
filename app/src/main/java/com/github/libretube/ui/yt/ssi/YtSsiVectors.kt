package com.github.libretube.ui.yt.ssi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.ByteBuffer
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * YT: the vector side of SSI, pure. Passage vectors are L2-normalised and kept as int8 with one
 * scale per vector (a quarter of float32, the cosine changes by well under 1 %); the search is a
 * plain scan over all of them with a top-K heap (two threads from 8 000 rows on; its time at
 * 50 000 passages is checked by YtSsiVectorScanTest, the phone's last one shows in Diagnose).
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

    /**
     * The [k] passages closest to [query] (normalised), best first, as (id, cosine). From
     * [PARALLEL_FROM] rows on, the rows are split into [threads] (at most [MAX_THREADS]) equal
     * parts scanned at the same time, each with its own top-K heap, merged at the end. Every
     * [SLICE] rows the scan checks its coroutine, so a new query cancels it at once.
     */
    suspend fun search(query: FloatArray, k: Int, threads: Int = MAX_THREADS): List<Pair<Long, Float>> {
        if (query.size != dim || k <= 0) return emptyList()
        val list = synchronized(chunks) { chunks.toList() }
        val total = list.sumOf { it.ids.size }
        if (total == 0) return emptyList()
        val parts = if (total < PARALLEL_FROM) 1 else threads.coerceIn(1, MAX_THREADS)
        lastThreads = parts
        val found = if (parts == 1) {
            scan(query, list, 0, total, k)
        } else {
            coroutineScope {
                (0 until parts).map { p ->
                    val from = (total.toLong() * p / parts).toInt()
                    val to = (total.toLong() * (p + 1) / parts).toInt()
                    async(Dispatchers.Default) { scan(query, list, from, to, k) }
                }.awaitAll().flatten()
            }
        }
        return found.sortedByDescending { it.first }.take(k).map { (score, at) -> list[at ushr 20].ids[at and 0xFFFFF] to score }
    }

    /** Threads the last [search] used (for Diagnose). */
    @Volatile
    var lastThreads: Int = 0
        private set

    /** The best (score, chunk << 20 | row) of the rows [from] until [to], counted over all chunks. */
    private suspend fun scan(query: FloatArray, list: List<Chunk>, from: Int, to: Int, k: Int): List<Pair<Float, Int>> {
        val top = YtSsiVectors.TopK(k)
        val row = ByteArray(dim)
        var first = 0
        for ((c, chunk) in list.withIndex()) {
            val rows = chunk.ids.size
            val start = maxOf(from, first) - first
            val end = minOf(to, first + rows) - first
            first += rows
            if (start >= end) continue
            // a view of its own: position and bulk reads do not disturb the other thread
            val view = chunk.data.duplicate()
            for (r in start until end) {
                if ((r - start) % SLICE == 0) currentCoroutineContext().ensureActive()
                view.position(r * dim)
                view.get(row, 0, dim)
                top.offer(YtSsiVectors.dot(query, row, chunk.scales[r]), (c shl 20) or r)
            }
        }
        return top.best()
    }

    companion object {
        /**
         * Which semantic neighbours count as found: e5 puts unrelated text at a cosine of about
         * 0.7, so a plain top-K would always "find" something. Kept are the ones at least
         * [MIN_COSINE] and within [BAND] of the best. [MIN_COSINE] is the default floor; the user
         * can calibrate it on their examples ([YtSsiCalibration]) and feedback moves it a little
         * on top ([YtSsiLearning.minCosine]).
         */
        const val MIN_COSINE = 0.78f
        const val BAND = 0.08f

        /** At most two threads, so playback and the screen stay smooth. */
        const val MAX_THREADS = 2

        /** Below this many rows one thread is quicker than starting a second. */
        const val PARALLEL_FROM = 8_000

        /** Rows between two checks for cancellation. */
        const val SLICE = 2_048

        fun cut(found: List<Pair<Long, Float>>, minCosine: Float = MIN_COSINE): List<Pair<Long, Float>> {
            val best = found.maxOfOrNull { it.second } ?: return emptyList()
            val floor = maxOf(minCosine, best - BAND)
            return found.filter { it.second >= floor }
        }
    }
}
