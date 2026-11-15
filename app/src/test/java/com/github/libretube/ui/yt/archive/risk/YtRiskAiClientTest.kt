package com.github.libretube.ui.yt.archive.risk

import com.github.libretube.ui.yt.news.YtNewsFakeServer
import com.github.libretube.ui.yt.news.YtNewsGemini
import com.github.libretube.ui.yt.news.YtNewsHttp
import com.github.libretube.ui.yt.news.YtNewsOpenAi
import com.github.libretube.ui.yt.news.YtNewsProviderKind
import com.github.libretube.ui.yt.news.YtNewsProviderSetup
import com.github.libretube.ui.yt.news.YtNewsSummaryApi
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plain completion request to each provider against a fake server on the loopback interface:
 * request shape (address, key header, schema) and the answer text. No request leaves the machine.
 */
class YtRiskAiClientTest {
    private val server = YtNewsFakeServer()
    private val base = "${server.url}/v1/".toHttpUrl()
    private val addresses = YtRiskAiClient.Addresses(openAi = base, gemini = base, mistral = base, anthropic = server.url, anthropicRetries = 0)

    private val items = """{"items":[{"id":"1","risk":0.7,"reason":"Geleakter Song"}]}"""
    private fun quoted(text: String) = JsonPrimitive(text).toString()
    private fun next() = server.requests.poll(5, TimeUnit.SECONDS)!!
    private fun body(request: YtNewsFakeServer.Request) = Json.parseToJsonElement(request.body).jsonObject

    @After
    fun stop() = server.close()

    @Test
    fun openAiResponsesWithStrictSchema() {
        server.answer(
            200,
            """{"output":[{"type":"reasoning","summary":[]},{"type":"message","content":[{"type":"output_text","text":${quoted(items)}}]}]}""",
        )
        val setup = YtNewsProviderSetup(YtNewsProviderKind.OPENAI, "sk-proj-k", YtNewsOpenAi.MODEL)
        val text = runBlocking { YtRiskAiClient(setup, addresses).complete("SYS", "USER") }
        assertEquals(items, text)
        val request = next()
        assertTrue(request.line, request.line.startsWith("POST /v1/responses"))
        assertEquals("Bearer sk-proj-k", request.headers["authorization"])
        val body = body(request)
        assertEquals("SYS", body["instructions"]!!.jsonPrimitive.content)
        assertEquals("USER", body["input"]!!.jsonPrimitive.content)
        assertEquals("false", body["store"]!!.jsonPrimitive.content)
        assertEquals("low", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        val format = body["text"]!!.jsonObject["format"]!!.jsonObject
        assertEquals("json_schema", format["type"]!!.jsonPrimitive.content)
        assertEquals(YtRiskAiRules.schema(), format["schema"])
        assertEquals(mapOf("1" to YtRiskAiRules.Item(0.7, "Geleakter Song")), YtRiskAiRules.parse(text))
    }

    @Test
    fun geminiKeyInTheHeaderAndModelInThePath() {
        server.answer(200, """{"candidates":[{"content":{"parts":[{"text":${quoted(items)}}]},"finishReason":"STOP"}]}""")
        val setup = YtNewsProviderSetup(YtNewsProviderKind.GEMINI, "AIza-k", YtNewsGemini.MODEL)
        assertEquals(items, runBlocking { YtRiskAiClient(setup, addresses).complete("SYS", "USER") })
        val request = next()
        assertTrue(request.line, request.line.startsWith("POST /v1/models/${YtNewsGemini.MODEL}:generateContent"))
        assertEquals("AIza-k", request.headers["x-goog-api-key"])
        assertTrue(!request.line.contains("AIza-k"))
        val config = body(request)["generationConfig"]!!.jsonObject
        assertEquals(YtRiskAiRules.schema(closed = false), config["responseJsonSchema"])
    }

    @Test
    fun compatibleServerFallsBackToJsonModeWithoutKey() {
        server.answer(400, """{"error":{"message":"response_format json_schema not supported"}}""")
        server.answer(200, """{"choices":[{"message":{"role":"assistant","content":${quoted(items)}},"finish_reason":"stop"}]}""")
        val setup = YtNewsProviderSetup(YtNewsProviderKind.COMPATIBLE, "", "llama3.2", "${server.url}/v1")
        assertEquals(items, runBlocking { YtRiskAiClient(setup, addresses).complete("SYS", "USER") })
        val first = next()
        assertTrue(first.line, first.line.startsWith("POST /v1/chat/completions"))
        assertNull(first.headers["authorization"])
        assertEquals("json_schema", body(first)["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val second = body(next())
        assertEquals("json_object", second["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val messages = second["messages"]!!.jsonArray
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("USER", messages[1].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun mistralErrorIsThrownWithItsReason() {
        server.answer(429, """{"error":{"type":"rate_limited"}}""")
        val setup = YtNewsProviderSetup(YtNewsProviderKind.MISTRAL, "m-k", "mistral-small-latest")
        val error = runCatching { runBlocking { YtRiskAiClient(setup, addresses).complete("SYS", "USER") } }.exceptionOrNull()
        assertTrue(error is YtNewsHttp.Status)
        assertEquals(429, (error as YtNewsHttp.Status).code)
    }

    @Test
    fun anthropicThroughTheSdkJoinsTheTextBlocks() {
        server.answer(
            200,
            """{"id":"msg_1","type":"message","role":"assistant","model":"${YtNewsSummaryApi.MODEL}",
               "content":[{"type":"thinking","thinking":"","signature":"sig"},
                          {"type":"text","text":"```json\n"},{"type":"text","text":${quoted(items)}}],
               "stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":10}}""",
        )
        val setup = YtNewsProviderSetup(YtNewsProviderKind.ANTHROPIC, "sk-ant-k", YtNewsSummaryApi.MODEL)
        val text = runBlocking { YtRiskAiClient(setup, addresses).complete("SYS", "USER") }
        assertEquals("```json\n$items", text)
        assertEquals(mapOf("1" to YtRiskAiRules.Item(0.7, "Geleakter Song")), YtRiskAiRules.parse(text))
        val request = next()
        assertTrue(request.line, request.line.startsWith("POST /v1/messages"))
        val body = body(request)
        assertEquals(YtNewsSummaryApi.MODEL, body["model"]!!.jsonPrimitive.content)
        assertEquals("low", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("USER", body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun anthropicRefusalGivesNoText() {
        server.answer(
            200,
            """{"id":"msg_2","type":"message","role":"assistant","model":"m","content":[{"type":"text","text":"partial"}],
               "stop_reason":"refusal","stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}""",
        )
        val setup = YtNewsProviderSetup(YtNewsProviderKind.ANTHROPIC, "sk-ant-k", "some-other-model")
        assertNull(runBlocking { YtRiskAiClient(setup, addresses).complete("SYS", "USER") })
        assertNull(body(next())["output_config"])
    }
}
