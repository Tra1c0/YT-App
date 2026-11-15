package com.github.libretube.ui.yt.ssi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the synthetic fixtures through [YtSsiBench]. The thresholds guard the lexical pipeline
 * (segmenter, intent, BM25, fusion, constraints, play start) against regressions; they say
 * nothing about quality on real videos, which needs verified examples from the user.
 */
class YtSsiBenchTest {
    private fun report(name: String) = YtSsiBench.run(YtSsiBench.load("ssi/$name.json")).also { println(it.summary()) }

    @Test
    fun exactQuotesAndWordings() {
        val report = report("bench_quotes_de")
        assertEquals(1.0, report.recallAtK, 1e-9)
        assertTrue(report.ndcgAtK > 0.9)
        // word timings: the play start is within the lead before the quote (1.5 s) or at its sentence
        assertTrue(report.maxDeviationMs <= 2_000)
    }

    @Test
    fun hardConstraints() {
        val report = report("bench_constraints")
        assertEquals(1.0, report.recallAtK, 1e-9)
        assertEquals(1.0, report.noMatchRate, 1e-9)
    }

    @Test
    fun noMatchIsAnAnswer() {
        assertEquals(1.0, report("bench_nomatch").noMatchRate, 1e-9)
    }

    @Test
    fun captionLinesEnglish() {
        val report = report("bench_lines_en")
        assertEquals(1.0, report.recallAtK, 1e-9)
        // line timing: within a line of the moment
        assertTrue(report.maxDeviationMs <= 6_000)
    }

    @Test
    fun metricsOfAMiss() {
        val fixture = YtSsiBench.load("ssi/bench_quotes_de.json")
        val missing = fixture.copy(cases = listOf(YtSsiBench.Case("\"diesen Satz gibt es nicht\"", listOf(YtSsiBench.Moment("q1", 0, 1000)))))
        val report = YtSsiBench.run(missing)
        assertEquals(0.0, report.recallAtK, 1e-9)
        assertEquals(0.0, report.ndcgAtK, 1e-9)
    }
}
