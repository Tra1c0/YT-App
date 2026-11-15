package com.github.libretube.ui.yt.archive.risk

import com.github.libretube.ui.yt.archive.YtArchiveCandidate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What is sent to the AI, how its answers are read, the daily limit and the cache. */
class YtRiskAiRulesTest {
    private fun item(risk: Double, reason: String?) = YtRiskAiRules.Item(risk, reason)

    @Test
    fun promptSendsOnlyTitleChannelAndDescriptionStartUnderLocalNumbers() {
        val batch = listOf(
            YtArchiveCandidate("dQw4w9WgXcQ", "UCsecretid", "Kanal A", "Titel A", "x".repeat(500) + " https://a.b", 1L, 10L),
            YtArchiveCandidate("abcdefghijk", null, null, "Titel B", null, null, null),
        )
        val prompt = YtRiskAiRules.prompt(batch)
        assertFalse(prompt.contains("dQw4w9WgXcQ"))
        assertFalse(prompt.contains("UCsecretid"))
        val list = Json.parseToJsonElement(prompt.substringAfter("\n")).jsonArray
        assertEquals(2, list.size)
        val first = list[0].jsonObject
        assertEquals(setOf("id", "channel", "title", "description"), first.keys)
        assertEquals("1", first["id"]!!.jsonPrimitive.content)
        assertEquals("Kanal A", first["channel"]!!.jsonPrimitive.content)
        assertEquals(YtRiskText.DESCRIPTION_CHARS, first["description"]!!.jsonPrimitive.content.length)
        assertEquals("2", list[1].jsonObject["id"]!!.jsonPrimitive.content)
        assertTrue(YtRiskAiRules.system(german = true).contains("in German"))
        assertTrue(YtRiskAiRules.system(german = false).contains("in English"))
    }

    @Test
    fun parsesThePlainAnswer() {
        assertEquals(
            mapOf("1" to item(0.05, "Normales Kochvideo"), "2" to item(0.8, "Geleakter Song, Urheberrecht")),
            YtRiskAiRules.parse(
                """{"items":[{"id":"1","risk":0.05,"reason":"Normales Kochvideo"},{"id":"2","risk":0.8,"reason":"Geleakter Song, Urheberrecht"}]}""",
            ),
        )
    }

    @Test
    fun toleratesFencesSurroundingTextBareListsAndOtherNames() {
        val fenced = "```json\n[{\"id\":1,\"risk\":\"0.4\",\"reason\":\"Drama\"}]\n```"
        assertEquals(mapOf("1" to item(0.4, "Drama")), YtRiskAiRules.parse(fenced))
        val chatty = "Here is my estimate:\n{\"videos\":[{\"id\":\"3\",\"risk\":0.2,\"reason\":\"x\"}]}\nHope it helps."
        assertEquals(mapOf("3" to item(0.2, "x")), YtRiskAiRules.parse(chatty))
        // percentages, commas and missing reasons
        assertEquals(
            mapOf("1" to item(0.35, null), "2" to item(0.5, null)),
            YtRiskAiRules.parse("""[{"id":"1","risk":35},{"id":"2","risk":"0,5","reason":"  "}]"""),
        )
    }

    @Test
    fun dropsUnusableItemsAndUnreadableAnswers() {
        assertEquals(
            mapOf("4" to item(1.0, null)),
            YtRiskAiRules.parse("""[{"id":"1","risk":-0.2},{"id":"2","risk":250},{"risk":0.5},{"id":"3","risk":"high"},{"id":"4","risk":1}]"""),
        )
        assertNull(YtRiskAiRules.parse(null))
        assertNull(YtRiskAiRules.parse("   "))
        assertNull(YtRiskAiRules.parse("I cannot help with that."))
        assertNull(YtRiskAiRules.parse("""{"note":"no list"}"""))
    }

    @Test
    fun reasonsAreShortenedToTwelveWordsOnOneLine() {
        assertEquals(
            "eins zwei drei vier fünf sechs sieben acht neun zehn elf zwölf",
            YtRiskAiRules.reason("\"eins zwei drei vier\nfünf sechs sieben acht neun zehn elf zwölf dreizehn\""),
        )
        assertTrue(YtRiskAiRules.reason("x".repeat(300))!!.length <= 121)
        assertNull(YtRiskAiRules.reason(" \n "))
    }

    @Test
    fun schemaIsClosedForStrictServices() {
        val closed = YtRiskAiRules.schema()
        assertEquals("false", closed["additionalProperties"]!!.jsonPrimitive.content)
        assertNull(YtRiskAiRules.schema(closed = false)["additionalProperties"])
    }

    @Test
    fun dailyLimitResetsOnTheNextDay() {
        val state = YtRiskAiState()
        repeat(YtRiskAiRules.MAX_REQUESTS_PER_DAY) { assertTrue(state.take("2026-11-15")) }
        assertFalse(state.take("2026-11-15"))
        assertEquals(YtRiskAiRules.MAX_REQUESTS_PER_DAY, state.requestsOn("2026-11-15"))
        assertEquals(0, state.requestsOn("2026-11-16"))
        assertTrue(state.take("2026-11-16"))
        assertEquals(1, state.requests)
    }

    @Test
    fun cacheDropsOldAndExcessAnswers() {
        val day = YtRiskModel.DAY_MS
        val now = 1_700_000_000_000L
        val state = YtRiskAiState()
        state.cache["old"] = YtRiskAiAnswer(0.1, null, now - (YtRiskAiRules.CACHE_DAYS + 1) * day)
        repeat(YtRiskAiRules.MAX_CACHE + 10) { state.cache["v$it"] = YtRiskAiAnswer(0.1, null, now - it) }
        state.prune(now)
        assertFalse("old" in state.cache)
        assertTrue(state.cache.size <= YtRiskAiRules.MAX_CACHE)
        // the newest stay
        assertTrue("v0" in state.cache)
    }
}
