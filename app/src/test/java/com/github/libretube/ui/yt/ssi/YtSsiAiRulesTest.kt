package com.github.libretube.ui.yt.ssi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class YtSsiAiRulesTest {
    @Test
    fun readsTheAnswerLeniently() {
        val plan = YtSsiAiRules.parse(
            "Hier:\n```json\n{\"queries\":[\"ameisen kolonie\",\"Ameisen Kolonie\",\"ant colony\",\"a\",\"b\",\"c\"],\"keywords\":[\"  Staat \",\"\",\"Königin\"],\"extra\":1}\n```",
        )!!
        assertEquals(listOf("ameisen kolonie", "ant colony", "a", "b"), plan.queries)
        assertEquals(listOf("Staat", "Königin"), plan.keywords)
        assertNull(YtSsiAiRules.parse("no json"))
        assertNull(YtSsiAiRules.parse(null))
        assertEquals(plan, YtSsiAiRules.decode(YtSsiAiRules.encode(plan)))
    }

    @Test
    fun cacheAndSearches() {
        assertTrue(YtSsiAiRules.fresh(1_000, 1_000 + YtSsiAiRules.CACHE_MS - 1))
        assertFalse(YtSsiAiRules.fresh(1_000, 1_000 + YtSsiAiRules.CACHE_MS))
        val intent = YtSsiIntentParser.parse("Finde Ameisen Kolonien 2023", 1_800_000_000_000L, ZoneId.of("UTC"))
        assertEquals(
            listOf("Ameisen Kolonien", "ant colonies"),
            YtSsiAiRules.searches(intent, YtSsiAiPlan(queries = listOf("ameisen kolonien", "ant colonies"))),
        )
        val quote = YtSsiIntentParser.parse("\"das ist falsch\"", 0, ZoneId.of("UTC"))
        assertEquals(listOf("\"das ist falsch\""), YtSsiAiRules.searches(quote, null))
        assertTrue(YtSsiAiRules.schema().toString().contains("\"additionalProperties\":false"))
    }
}
