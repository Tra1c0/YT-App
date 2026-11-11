package com.github.libretube.ui.yt.news

import com.anthropic.core.jsonMapper
import com.anthropic.models.beta.messages.BetaMessage
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The request and answer mapping of the summaries through the real SDK, against a fake server on
 * the loopback interface with hand-written answers. No request leaves the machine.
 */
class YtNewsSummaryApiTest {
    private val doc = YtNewsTranscriptDoc.build(
        listOf(
            YtNewsCue(4.0, "Der Bundestag hat das Gesetz beschlossen."),
            YtNewsCue(70.0, "Die Opposition kündigt eine Klage an."),
        ),
    )
    private val sources = listOf(YtNewsSource("vid1", "Kanal: Titel", doc))
    private val server = FakeServer()

    @After
    fun stop() = server.close()

    private fun index(quote: String) = doc.text.codePointCount(0, doc.text.indexOf(quote))

    private val answer = """
        {"id":"msg_1","type":"message","role":"assistant","model":"${YtNewsSummaryApi.MODEL}",
         "content":[
          {"type":"thinking","thinking":"","signature":"sig"},
          {"type":"text","text":"- "},
          {"type":"text","text":"Der Bundestag beschließt das Gesetz.","citations":[
            {"type":"char_location","cited_text":"Der Bundestag hat das Gesetz beschlossen.","document_index":0,
             "document_title":"Kanal: Titel","start_char_index":${index("Der Bundestag")},"end_char_index":48}]},
          {"type":"text","text":"\n- Die Opposition "},
          {"type":"text","text":"will klagen.","citations":[
            {"type":"char_location","cited_text":"Die Opposition kündigt eine Klage an.","document_index":0,
             "document_title":"Kanal: Titel","start_char_index":${index("Die Opposition")},"end_char_index":99}]}
         ],
         "stop_reason":"end_turn","stop_sequence":null,
         "usage":{"input_tokens":120,"output_tokens":40}}
    """.trimIndent()

    @Test
    fun requestShapeAndCitedStatements() {
        server.answer(200, answer)
        val client = YtNewsSummaryApi.client("sk-ant-test-key", server.url, retries = 0)
        val result = try {
            runBlocking { YtNewsSummaryApi.summarize(client, sources) }
        } finally {
            client.close()
        }
        assertEquals(
            YtNewsSummaryApi.Result.Done(
                listOf(
                    YtNewsStatement("Der Bundestag beschließt das Gesetz.", "vid1", 4),
                    YtNewsStatement("Die Opposition will klagen.", "vid1", 70),
                ),
            ),
            result,
        )

        val request = server.requests.poll(5, TimeUnit.SECONDS)!!
        assertTrue(request.line, request.line.startsWith("POST /v1/messages"))
        // the SDK sends the key in one of its two auth headers, depending on its API version
        val auth = request.headers["x-api-key"] ?: request.headers["authorization"]?.removePrefix("Bearer ")
        assertEquals("sk-ant-test-key", auth)
        assertTrue(request.headers["anthropic-beta"].orEmpty().contains("server-side-fallback-2026-07-01"))
        val body = Json.parseToJsonElement(request.body).jsonObject
        assertEquals(YtNewsSummaryApi.MODEL, body["model"]!!.jsonPrimitive.content)
        assertEquals("default", body["fallbacks"]!!.jsonPrimitive.content)
        val output = body["output_config"]!!.jsonObject
        assertEquals("low", output["effort"]!!.jsonPrimitive.content)
        // citations do not mix with structured outputs; thinking stays at the model's default
        assertNull(output["format"])
        assertNull(body["thinking"])
        val messages = body["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        val content = messages[0].jsonObject["content"]!!.jsonArray
        val document = content[0].jsonObject
        assertEquals("document", document["type"]!!.jsonPrimitive.content)
        assertEquals("true", document["citations"]!!.jsonObject["enabled"]!!.jsonPrimitive.content)
        assertEquals("Kanal: Titel", document["title"]!!.jsonPrimitive.content)
        val source = document["source"] as JsonObject
        assertEquals("text", source["type"]!!.jsonPrimitive.content)
        assertEquals(doc.text, source["data"]!!.jsonPrimitive.content)
        assertEquals("text", content[1].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun refusalIsCheckedBeforeTheContent() {
        server.answer(
            200,
            """{"id":"msg_2","type":"message","role":"assistant","model":"${YtNewsSummaryApi.MODEL}",
               "content":[{"type":"text","text":"- Teil","citations":[{"type":"char_location","cited_text":"x",
               "document_index":0,"document_title":null,"start_char_index":0,"end_char_index":1}]}],
               "stop_reason":"refusal","stop_details":{"type":"refusal","category":null,"explanation":null},
               "usage":{"input_tokens":1,"output_tokens":1}}""",
        )
        val client = YtNewsSummaryApi.client("k", server.url, retries = 0)
        val result = try {
            runBlocking { YtNewsSummaryApi.summarize(client, sources) }
        } finally {
            client.close()
        }
        assertEquals(YtNewsSummaryApi.Result.Failed(YtNewsSummaryFailure.REFUSED), result)
    }

    @Test
    fun answerWithoutCitationsGivesNoSummary() {
        val message = jsonMapper().readValue(
            """{"id":"m","type":"message","role":"assistant","model":"x","content":[{"type":"text","text":"- Ohne Quelle."}],
               "stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}""",
            BetaMessage::class.java,
        )
        val answer = YtNewsSummaryApi.answer(message) as YtNewsSummaryApi.Answer.Blocks
        assertEquals(listOf(YtNewsAnswerBlock("- Ohne Quelle.")), answer.blocks)
        assertTrue(YtNewsCitations.statements(answer.blocks, sources).isEmpty())
    }

    @Test
    fun keyCheckErrorsMapToReasons() {
        val auth = """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""
        val limit = """{"type":"error","error":{"type":"rate_limit_error","message":"slow down"}}"""
        val overloaded = """{"type":"error","error":{"type":"overloaded_error","message":"busy"}}"""
        val model = """{"type":"model","id":"${YtNewsSummaryApi.MODEL}","display_name":"x","created_at":"2026-01-01T00:00:00Z"}"""
        server.answer(401, auth)
        server.answer(429, limit)
        server.answer(529, overloaded)
        server.answer(200, model)
        val client = YtNewsSummaryApi.client("k", server.url, retries = 0)
        try {
            assertEquals(YtNewsSummaryFailure.INVALID_KEY, YtNewsSummaryApi.check(client))
            assertEquals(YtNewsSummaryFailure.RATE_LIMITED, YtNewsSummaryApi.check(client))
            assertEquals(YtNewsSummaryFailure.SERVICE, YtNewsSummaryApi.check(client))
            assertNull(YtNewsSummaryApi.check(client))
        } finally {
            client.close()
        }
        val request = server.requests.poll(5, TimeUnit.SECONDS)!!
        assertTrue(request.line, request.line.startsWith("GET /v1/models/${YtNewsSummaryApi.MODEL}"))
    }

    @Test
    fun unknownModelOfTheSettings() {
        server.answer(404, """{"type":"error","error":{"type":"not_found_error","message":"model: nope"}}""")
        val client = YtNewsSummaryApi.client("k", server.url, retries = 0)
        try {
            assertEquals(YtNewsSummaryFailure.MODEL_UNKNOWN, YtNewsSummaryApi.check(client, "nope"))
        } finally {
            client.close()
        }
        val request = server.requests.poll(5, TimeUnit.SECONDS)!!
        assertTrue(request.line, request.line.startsWith("GET /v1/models/nope"))
    }

    @Test
    fun noServerMeansOffline() {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val client = YtNewsSummaryApi.client("k", "http://127.0.0.1:$port", retries = 0)
        try {
            assertEquals(YtNewsSummaryFailure.OFFLINE, YtNewsSummaryApi.check(client))
        } finally {
            client.close()
        }
    }

    @Test
    fun theKeyIsNotInTheRequestText() {
        val params = YtNewsSummaryApi.params(sources)
        assertFalse(params.toString().contains("sk-ant"))
    }

    /** One canned answer per connection, in order; records what it was sent. */
    private class FakeServer : AutoCloseable {
        class Request(val line: String, val headers: Map<String, String>, val body: String)

        private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        private val answers = LinkedBlockingQueue<Pair<Int, String>>()
        val requests = LinkedBlockingQueue<Request>()
        val url = "http://127.0.0.1:${socket.localPort}"

        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val connection = runCatching { socket.accept() }.getOrNull() ?: break
                    connection.use { c ->
                        val input = BufferedInputStream(c.getInputStream())
                        val line = readLine(input)
                        val headers = HashMap<String, String>()
                        while (true) {
                            val header = readLine(input)
                            if (header.isEmpty()) break
                            headers[header.substringBefore(':').trim().lowercase()] = header.substringAfter(':').trim()
                        }
                        val length = headers["content-length"]?.toIntOrNull() ?: 0
                        val body = ByteArray(length)
                        var read = 0
                        while (read < length) {
                            val n = input.read(body, read, length - read)
                            if (n < 0) break
                            read += n
                        }
                        requests += Request(line, headers, String(body, Charsets.UTF_8))
                        val (code, text) = answers.poll(5, TimeUnit.SECONDS) ?: (500 to "{}")
                        val bytes = text.toByteArray(Charsets.UTF_8)
                        val head = "HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n" +
                            "request-id: req_test\r\nConnection: close\r\n\r\n"
                        c.getOutputStream().apply {
                            write(head.toByteArray(Charsets.US_ASCII))
                            write(bytes)
                            flush()
                        }
                    }
                }
            }
        }

        fun answer(code: Int, body: String) {
            answers += code to body
        }

        private fun readLine(input: BufferedInputStream): String {
            val out = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0 || b == '\n'.code) break
                if (b != '\r'.code) out.write(b)
            }
            return out.toString(Charsets.UTF_8.name())
        }

        override fun close() = socket.close()
    }
}
