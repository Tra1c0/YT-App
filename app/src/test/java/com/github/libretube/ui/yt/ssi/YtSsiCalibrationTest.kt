package com.github.libretube.ui.yt.ssi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YtSsiCalibrationTest {
    private val id = "dQw4w9WgXcQ"
    private val set = YtSsiBenchmark.Examples(
        name = null,
        toleranceMs = 3_000,
        examples = listOf(
            YtSsiBenchmark.Example("a", id, 10_000, 12_000, YtSsiBenchmark.Kind.QUESTION),
            YtSsiBenchmark.Example("b", id, 50_000, 52_000, YtSsiBenchmark.Kind.QUESTION),
            YtSsiBenchmark.Example("c", null, 0, 0, YtSsiBenchmark.Kind.NONE),
        ),
    )

    private fun hit(ms: Long) = YtSsiBenchmark.Found(id, ms, null, null, ms)
    private val right1 = YtSsiBenchmark.Answer(listOf(hit(11_000)), 5)
    private val right2 = YtSsiBenchmark.Answer(listOf(hit(90_000), hit(51_000)), 5)
    private val wrong = YtSsiBenchmark.Answer(listOf(hit(200_000)), 5)
    private val nothing = YtSsiBenchmark.Answer(emptyList(), 5)

    /**
     * A low cut-off finds both questions but also answers the "none" example; a high one finds
     * nothing; in between one question and a right empty answer.
     */
    private fun answers(): Map<Float, List<YtSsiBenchmark.Answer>> = YtSsiCalibration.GRID.associateWith { cut ->
        when {
            cut < 0.75f -> listOf(right1, right2, wrong)
            cut < 0.79f -> listOf(right1, nothing, nothing)
            cut < 0.81f -> listOf(right1, right2, nothing)
            else -> listOf(nothing, nothing, nothing)
        }
    }

    @Test
    fun picksTheValueWithTheMostRightAnswers() {
        val result = YtSsiCalibration.result(set, answers(), learntOffset = 0.01f, missing = 2)
        assertEquals(YtSsiCalibration.GRID.size, result.points.size)
        // 0.80: both questions right and the "none" one empty = 3 of 3
        assertEquals(0.80f, result.best!!.cut, 1e-6f)
        assertEquals(3, result.best!!.right)
        val low = result.points.first()
        assertEquals(1.0, low.metrics.recall3, 1e-9)
        assertEquals(0.0, low.metrics.emptyRight!!, 1e-9)
        assertEquals(2, low.right)
        val high = result.points.last()
        assertEquals(1.0, high.metrics.noMatch, 1e-9)
        assertEquals(2, result.missing)
        assertEquals(0.01f, result.learntOffset, 1e-6f)
    }

    @Test
    fun tiesGoToTheDefaultThenToTheStricterValue() {
        val same = YtSsiCalibration.GRID.associateWith { listOf(right1, nothing, nothing) }
        assertEquals(YtSsiVectorIndex.MIN_COSINE, YtSsiCalibration.result(set, same, 0f, 0).best!!.cut, 1e-6f)
        // without the default in the sweep: 0.76 and 0.80 are as close, the stricter wins
        val around = mapOf(0.76f to listOf(right1, nothing, nothing), 0.80f to listOf(right1, nothing, nothing))
        assertEquals(0.80f, YtSsiCalibration.result(set, around, 0f, 0).best!!.cut, 1e-6f)
        assertNull(YtSsiCalibration.best(emptyList()))
    }

    @Test
    fun theAppliedValueIsBoundedAndTheDefaultIsTheReset() {
        assertEquals(YtSsiCalibration.MAX, YtSsiCalibration.base(0.99f), 1e-6f)
        assertEquals(YtSsiCalibration.MIN, YtSsiCalibration.base(0.10f), 1e-6f)
        assertEquals(0.80f, YtSsiCalibration.base(0.80f), 1e-6f)
        assertEquals(YtSsiVectorIndex.MIN_COSINE, YtSsiCalibration.base(null), 1e-6f)
        assertEquals("cut-off base 0.780 (default)", YtSsiCalibration.describe(null))
        assertEquals("cut-off base 0.800 (calibrated)", YtSsiCalibration.describe(0.8f))
    }

    @Test
    fun learningStillMovesTheCalibratedCutOff() {
        val now = 1_000_000L
        val state = YtSsiLearnState(cutOffset = 0.02, at = now)
        assertEquals(0.82f, YtSsiLearning.minCosine(state, now, YtSsiCalibration.base(0.80f)), 1e-6f)
        assertEquals(0.80f, YtSsiLearning.minCosine(state, now), 1e-6f)
        assertEquals(0.02f, YtSsiLearning.cutOffset(state, now), 1e-6f)
    }
}
