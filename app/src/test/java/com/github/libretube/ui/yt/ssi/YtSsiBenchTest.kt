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

    // ---- SSI-2: the user's example format through the harness ----

    /** The quotes fixture with 11-character ids, as real example files name videos. */
    private fun quotesWithRealIds(): YtSsiBench.Fixture {
        val fixture = YtSsiBench.load("ssi/bench_quotes_de.json")
        return fixture.copy(videos = fixture.videos.map { it.copy(id = it.id + "aaaaaaaaa") })
    }

    @Test
    fun userExamplesRunThroughTheHarness() {
        val examples = YtSsiBench.loadExamples("ssi/examples_user_format.json")
        assertEquals(5, examples.examples.size)
        assertEquals(1, examples.negatives)
        val metrics = YtSsiBench.runExamples(quotesWithRealIds(), examples)
        println("user format SSI-2: $metrics")
        assertEquals(4, metrics.positives)
        assertEquals(1.0, metrics.recall5, 1e-9)
        assertTrue(metrics.recall3 >= 0.75)
        assertEquals(0.0, metrics.noMatch, 1e-9)
        assertEquals(1.0, metrics.emptyRight!!, 1e-9)
        // word timings: the checked start is at most about 2 s off the verified start
        assertTrue((metrics.deviationP95Ms ?: Long.MAX_VALUE) <= 2_000)
    }

    @Test
    fun ssi2IsNotWorseThanTheSsi1WayOnTheSameExamples() {
        val examples = YtSsiBench.loadExamples("ssi/examples_user_format.json")
        val fixture = quotesWithRealIds()
        val now = YtSsiBench.runExamples(fixture, examples)
        val old = YtSsiBench.runExamples(fixture, examples, ssi1 = true)
        assertTrue(now.recall3 >= old.recall3)
        assertTrue((now.deviationP50Ms ?: 0) <= (old.deviationP50Ms ?: 0))
    }
}
