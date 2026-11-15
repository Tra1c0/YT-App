package com.github.libretube.ui.yt.archive.risk

import com.github.libretube.ui.yt.archive.YtArchiveCandidate
import java.io.IOException
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Scoring with the AI in batches: limits, the cache, failures, order, and the stored file. */
class YtRiskEngineTest {
    private var now = 1_763_200_000_000L // 2025-11-15 in UTC
    private val engine = YtRiskEngine(clock = { now }, zone = { ZoneOffset.UTC })

    private fun video(i: Int, title: String = "Gartenarbeit Folge $i") =
        YtArchiveCandidate("vid$i", "UC$i", "Kanal $i", title, "Beschreibung $i", null, null)

    /** A fake AI that answers every item of a request with [risk] and records the prompts. */
    private class FakeAi(val risk: Double = 0.97, val reason: String = "Leak eines Albums") : YtRiskAsk {
        val prompts = mutableListOf<String>()
        override suspend fun complete(system: String, user: String): String {
            prompts += user
            val ids = Json.parseToJsonElement(user.substringAfter("\n")).jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
            return "```json\n{\"items\":[" + ids.joinToString(",") { "{\"id\":\"$it\",\"risk\":$risk,\"reason\":\"$reason\"}" } + "]}\n```"
        }

        fun titles(request: Int) = Json.parseToJsonElement(prompts[request].substringAfter("\n")).jsonArray
            .map { it.jsonObject["title"]!!.jsonPrimitive.content }
    }

    @Test
    fun withoutAiTheLocalScoreStands() = runBlocking {
        val scores = engine.score(listOf(video(1)), TestWords, null, german = true)
        assertEquals(0.5 / 30.5, scores.single().risk, 1e-6)
        assertNull(engine.aiAnswer("vid1"))
    }

    @Test
    fun batchesOfTwentyFiveAndFourRequestsADay() = runBlocking {
        val ai = FakeAi()
        val candidates = (0 until 130).map { video(it) }
        val scores = engine.score(candidates, TestWords, ai, german = true)
        assertEquals(4, ai.prompts.size)
        assertTrue(ai.prompts.all { Json.parseToJsonElement(it.substringAfter("\n")).jsonArray.size == YtRiskAiRules.BATCH })
        assertEquals(100, scores.count { it.high })
        assertEquals("KI: Leak eines Albums", scores.first { it.high }.reason)
        assertEquals(30, scores.count { !it.high })

        // the same day: no more requests; the next day the rest
        engine.score(candidates, TestWords, ai, german = true)
        assertEquals(4, ai.prompts.size)
        now += YtRiskModel.DAY_MS
        engine.score(candidates, TestWords, ai, german = true)
        assertEquals(6, ai.prompts.size)
        assertTrue(engine.describe().endsWith("AI today 2/4"))
    }

    @Test
    fun aVideoIsNeverAskedTwiceEvenWithoutAnAnswer() = runBlocking {
        val silent = object : YtRiskAsk {
            var calls = 0
            override suspend fun complete(system: String, user: String): String {
                calls++
                return """{"items":[]}"""
            }
        }
        engine.score(listOf(video(1)), TestWords, silent, german = false)
        engine.score(listOf(video(1)), TestWords, silent, german = false)
        assertEquals(1, silent.calls)
        assertNull(engine.aiAnswer("vid1")!!.risk)
    }

    @Test
    fun aFailedRequestKeepsLocalScoresAndIsRetriedLater() = runBlocking {
        val failing = object : YtRiskAsk {
            var calls = 0
            override suspend fun complete(system: String, user: String): String {
                calls++
                throw IOException("offline")
            }
        }
        val candidates = (0 until 60).map { video(it) }
        val scores = engine.score(candidates, TestWords, failing, german = true)
        assertEquals("the first failure ends the AI part", 1, failing.calls)
        assertEquals(60, scores.size)
        assertTrue(scores.none { it.high })
        assertNull(engine.aiAnswer("vid0"))
        engine.score(candidates, TestWords, failing, german = true)
        assertEquals(2, failing.calls)
    }

    @Test
    fun cancellationIsPassedOn() {
        val cancelled = YtRiskAsk { _, _ -> throw CancellationException("closed") }
        try {
            runBlocking { engine.score(listOf(video(1)), TestWords, cancelled, german = true) }
            fail()
        } catch (e: CancellationException) {
            // expected
        }
    }

    @Test
    fun theHighestLocalRiskIsAskedFirst() = runBlocking {
        val ai = FakeAi(risk = 0.1)
        val title = "Full Movie geleakt – before it gets deleted"
        val candidates = (0 until 120).map { video(it) } + video(500, title)
        assertTrue(YtRiskEngine().score(listOf(candidates.last()), TestWords, null, german = true).single().high)
        engine.score(candidates, TestWords, ai, german = true)
        assertEquals(title, ai.titles(0).first())
        // the low answer takes the keyword video under the line
        assertFalse(engine.score(listOf(candidates.last()), TestWords, null, german = true).single().high)
    }

    @Test
    fun theStoredFileKeepsModelAndAiCache() = runBlocking {
        engine.observe(video(1, "Rechtsstreit um Musik"), true)
        engine.observe(video(2), false)
        engine.score(listOf(video(3)), TestWords, FakeAi(), german = true)
        val back = YtRiskEngine.decode(engine.encode()) { now }
        assertEquals(engine.encode(), back.encode())
        assertEquals(0.97, back.aiAnswer("vid3")!!.risk!!, 0.0)
        assertTrue(back.describe(), back.describe().startsWith("channels learned 2, labels 1 vanished / 1 stayed"))
        // a damaged file starts over
        assertTrue(YtRiskEngine.decode("{not json").describe().startsWith("channels learned 0, labels 0 vanished / 0 stayed"))
        assertTrue(YtRiskEngine.decode(null).describe().startsWith("channels learned 0"))
    }
}
