package com.github.libretube.ui.yt.ssi

import java.util.Locale

/**
 * YT (SSI-2 gap): "Schwelle kalibrieren", pure. The meaning search keeps neighbours at least at
 * its cut-off ([YtSsiVectorIndex.MIN_COSINE] until calibrated) and within the band of the best.
 * The calibration answers the user's imported examples once per value of [GRID] (the learnt
 * offset added, as the search would use it) and measures each: Recall@3, no-match and, with
 * "none" examples, how often those got no hit. The best value answers the most examples right
 * (a match expected: a right hit in the first 3; none expected: no hit); ties go to the better
 * Recall@1, then to the value closer to the default, then to the stricter one. Applying it is
 * bounded to [MIN]..[MAX]; the reset goes back to the default; feedback learning keeps moving
 * its offset on top.
 */
object YtSsiCalibration {
    val GRID = listOf(0.72f, 0.74f, 0.76f, 0.78f, 0.80f, 0.82f, 0.84f)
    const val MIN = 0.70f
    const val MAX = 0.86f

    /** One value of the sweep and how the examples fared with it. */
    data class Point(val cut: Float, val metrics: YtSsiBenchmark.Metrics) {
        /** Examples answered right: a right hit in the first 3, or no hit where none is expected. */
        val right: Int
            get() = Math.round(metrics.recall3 * metrics.positives).toInt() +
                Math.round((metrics.emptyRight ?: 0.0) * (metrics.examples - metrics.positives)).toInt()
    }

    /** The result of one sweep: its points in [GRID] order and the best of them. */
    data class Result(val points: List<Point>, val best: Point?, val learntOffset: Float, val missing: Int)

    fun bound(cut: Float): Float = cut.coerceIn(MIN, MAX)

    /** The base cut-off in use: the calibrated one when set (bounded), else the default. */
    fun base(calibrated: Float?): Float = calibrated?.let(::bound) ?: YtSsiVectorIndex.MIN_COSINE

    fun best(points: List<Point>): Point? = points.maxWithOrNull(
        compareBy<Point> { it.right }
            .thenBy { it.metrics.recall1 }
            // in thousandths, so float noise does not decide between two equally close values
            .thenByDescending { Math.round(kotlin.math.abs(it.cut - YtSsiVectorIndex.MIN_COSINE) * 1000) }
            .thenBy { it.cut },
    )

    /** The points of a sweep from the answers per grid value. */
    fun result(set: YtSsiBenchmark.Examples, answers: Map<Float, List<YtSsiBenchmark.Answer>>, learntOffset: Float, missing: Int, at: Long = 0): Result {
        val points = GRID.mapNotNull { cut -> answers[cut]?.let { Point(cut, YtSsiBenchmark.evaluate(set, it, at)) } }
        return Result(points, best(points), learntOffset, missing)
    }

    /** For Diagnose: "cut-off 0.800 (calibrated)" or "cut-off 0.780 (default)". */
    fun describe(calibrated: Float?): String =
        "cut-off base %.3f (%s)".format(Locale.ROOT, base(calibrated), if (calibrated == null) "default" else "calibrated")
}
