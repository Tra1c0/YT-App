package com.github.libretube.ui.yt.news

import kotlin.math.abs

/**
 * YT: the pure rules of the on-demand "Kurzfassung" of any video for transcripts beyond the
 * limit of one request ([YtNewsTranscriptDoc.MAX_CHARS]): at most [MAX_SLICES] slices, cut at
 * chapter starts when the video has them (else anywhere), and the merge of their statements.
 * Free of Android, so it runs on the JVM.
 */
object YtKurzSlicing {
    const val MAX_SLICES = 3

    /** At most this many statements in a merged summary. */
    const val MAX_MERGED = 8

    /** What a cue weighs in the document: its words plus the "[m:ss] " stamp and the line break. */
    private fun weight(cue: YtNewsCue) = cue.text.length + 10

    /**
     * [cues] in 2 or [MAX_SLICES] consecutive slices of about equal size, each (as far as the
     * limit allows) small enough for one request. With [chapterStarts] a slice starts where a
     * chapter does: of all chapter starts the ones nearest to the equal parts are taken.
     * Every cue lands in exactly one slice, in order; no slice is empty.
     */
    fun slices(cues: List<YtNewsCue>, chapterStarts: List<Long>, maxChars: Int = YtNewsTranscriptDoc.MAX_CHARS): List<List<YtNewsCue>> {
        if (cues.size < 2) return listOf(cues)
        val total = cues.sumOf { weight(it).toLong() }
        val wanted = ((total + maxChars - 1) / maxChars).toInt().coerceIn(2, MAX_SLICES).coerceAtMost(cues.size)
        // the weight before each cue
        val before = LongArray(cues.size)
        var sum = 0L
        for (i in cues.indices) {
            before[i] = sum
            sum += weight(cues[i])
        }
        // where a slice may start: the first cue at or after each chapter start, else any cue
        val byChapter = chapterStarts.distinct().sorted().mapNotNull { start ->
            cues.indexOfFirst { it.startSec >= start }.takeIf { it > 0 }
        }.distinct()
        val allowed = if (byChapter.isNotEmpty()) byChapter else (1 until cues.size).toList()
        val cuts = sortedSetOf<Int>()
        for (n in 1 until wanted) {
            val target = total * n / wanted
            val free = allowed.filter { it !in cuts }
            if (free.isEmpty()) break
            cuts += free.minBy { abs(before[it] - target) }
        }
        val edges = listOf(0) + cuts + cues.size
        return edges.zipWithNext { a, b -> cues.subList(a, b) }
    }

    /**
     * The statements of several slices as one list: taken in turns from each slice up to [max], so
     * every part of the video is represented, then in order of the video's time.
     */
    fun merge(parts: List<List<YtNewsStatement>>, max: Int = MAX_MERGED): List<YtNewsStatement> {
        val out = ArrayList<YtNewsStatement>()
        var round = 0
        while (out.size < max && parts.any { round < it.size }) {
            for (part in parts) {
                if (out.size < max) part.getOrNull(round)?.let { out += it }
            }
            round++
        }
        return out.sortedBy { it.seconds }
    }
}
