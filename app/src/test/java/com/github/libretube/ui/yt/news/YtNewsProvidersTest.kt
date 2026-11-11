package com.github.libretube.ui.yt.news

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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The providers without an SDK against a fake server on the loopback interface, with
 * hand-written answers: request shape, answer mapping with the quote check, error reasons.
 * No request leaves the machine.
 */
class YtNewsProvidersTest {
    private val doc = YtNewsTranscriptDoc.build(
        listOf(
            YtNewsCue(4.0, "Der Bundestag hat das Gesetz am Donnerstag beschlossen."),
            YtNewsCue(70.0, "Die Opposition kündigt eine Klage in Karlsruhe an."),
            YtNewsCue(130.0, "In Kraft tritt es im Januar des nächsten Jahres."),
        ),
    )
    private val sources = listOf(YtNewsSource("vid1", "Kanal: Titel", doc))
    private val server = YtNewsFakeServer()
    private val base = "${server.url}/v1/".toHttpUrl()

    @After
    fun stop() = server.close()

    /** The JSON the model writes: two statements that check out, one invented. */
    private val statements = """{"statements":[
        {"text":"Der Bundestag beschließt das Gesetz.","line":1,"quote":"Der Bundestag hat das Gesetz am Donnerstag beschlossen"},
        {"text":"Die Opposition will klagen.","line":2,"quote":"kündigt eine Klage in Karlsruhe an"},
        {"text":"Der Kanzler tritt zurück.","line":3,"quote":"Der Kanzler erklärt seinen Rücktritt"}]}"""

    private val expected = listOf(
        YtNewsStatement("Der Bundestag beschließt das Gesetz.", "vid1", 4),
        YtNewsStatement("Die Opposition will klagen.", "vid1", 70),
    )

    private fun quoted(text: String) = JsonPrimitive(text).toString()

    private fun body(request: YtNewsFakeServer.Request) = Json.parseToJsonElement(request.body).jsonObject

    private fun next() = server.requests.poll(5, TimeUnit.SECONDS)!!

    // --- OpenAI ---------------------------------------------------------------------------------

    private val openAi by lazy { YtNewsOpenAi(base) }
    private val openAiSetup = YtNewsProviderSetup(YtNewsProviderKind.OPENAI, "sk-proj-test-key", YtNewsOpenAi.MODEL)

    @Test
    fun openAiRequestShapeAndVerifiedStatements() {
        server.answer(
            200,
            """{"id":"resp_1","object":"response","status":"completed","output":[
                {"type":"reasoning","id":"rs_1","summary":[]},
                {"type":"message","id":"msg_1","role":"assistant","content":[
                  {"type":"output_text","text":${quoted(statements)},"annotations":[]}]}]}""",
        )
        val result = runBlocking { openAi.summarize(openAiSetup, sources) }
        assertEquals(YtNewsSummaryResult.Done(expected), result)

        val request = next()
        assertTrue(request.line, request.line.startsWith("POST /v1/responses "))
        assertEquals("Bearer sk-proj-test-key", request.headers["authorization"])
        val body = body(request)
        assertEquals(YtNewsOpenAi.MODEL, body["model"]!!.jsonPrimitive.content)
        assertEquals("false", body["store"]!!.jsonPrimitive.content)
        assertEquals("low", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertTrue(body["input"]!!.jsonPrimitive.content.contains("L2 [1:10] Die Opposition"))
        val format = body["text"]!!.jsonObject["format"]!!.jsonObject
        assertEquals("json_schema", format["type"]!!.jsonPrimitive.content)
        assertEquals("true", format["strict"]!!.jsonPrimitive.content)
        assertEquals("object", format["schema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        // the key goes in the header only
        assertFalse(request.body.contains("sk-proj"))
        assertFalse(openAiSetup.toString().contains("sk-proj"))
    }

    @Test
    fun openAiOwnModelGetsNoReasoningSetting() {
        val own = YtNewsProviderSetup(YtNewsProviderKind.OPENAI, "sk-x", "some-other-model")
        val body = openAi.body(own, sources)
        assertEquals("some-other-model", body["model"]!!.jsonPrimitive.content)
        assertNull(body["reasoning"])
    }

    @Test
    fun openAiRefusalAndEmptyAnswer() {
        server.answer(
            200,
            """{"status":"completed","output":[{"type":"message","content":[
                {"type":"refusal","refusal":"I can't help with that."}]}]}""",
        )
        server.answer(200, """{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[]}""")
        assertEquals(YtNewsSummaryResult.Failed(YtNewsSummaryFailure.REFUSED), runBlocking { openAi.summarize(openAiSetup, sources) })
        assertEquals(YtNewsSummaryResult.Failed(YtNewsSummaryFailure.REFUSED), runBlocking { openAi.summarize(openAiSetup, sources) })
    }

    @Test
    fun openAiErrorsMapToReasons() {
        server.answer(401, """{"error":{"message":"Incorrect API key provided: sk-proj-***","type":"invalid_request_error","param":null,"code":"invalid_api_key"}}""")
        server.answer(429, """{"error":{"message":"You exceeded your current quota.","type":"insufficient_quota","param":null,"code":"insufficient_quota"}}""")
        server.answer(429, """{"error":{"message":"No credits.","type":"insufficient_quota","param":null,"code":"credit_balance_exhausted"}}""")
        server.answer(429, """{"error":{"message":"Rate limit reached, try again in 20s.","type":"rate_limit_error","param":null,"code":"slow_down"}}""")
        server.answer(500, """{"error":{"message":"The server had an error.","type":"server_error","param":null,"code":null}}""")
        server.answer(404, """{"error":{"message":"The model does not exist.","type":"invalid_request_error","param":"model","code":"model_not_found"}}""")
        val reasons = (1..6).map {
            runCatching { runBlocking { openAi.summarize(openAiSetup, sources) } }.fold({ null }, { openAi.failure(it) })
        }
        assertEquals(
            listOf(
                YtNewsSummaryFailure.INVALID_KEY,
                YtNewsSummaryFailure.NO_CREDIT,
                YtNewsSummaryFailure.NO_CREDIT,
                YtNewsSummaryFailure.RATE_LIMITED,
                YtNewsSummaryFailure.SERVICE,
                YtNewsSummaryFailure.MODEL_UNKNOWN,
            ),
            reasons,
        )
    }

    @Test
    fun openAiKeyCheckListsModels() {
        server.answer(200, """{"object":"list","data":[{"id":"x","object":"model"}]}""")
        server.answer(401, """{"error":{"type":"invalid_request_error","code":"invalid_api_key","message":"bad"}}""")
        assertNull(openAi.check(openAiSetup))
        assertEquals(YtNewsSummaryFailure.INVALID_KEY, openAi.check(openAiSetup))
        val request = next()
        assertTrue(request.line, request.line.startsWith("GET /v1/models "))
        assertEquals("Bearer sk-proj-test-key", request.headers["authorization"])
    }

    @Test
    fun noServerMeansOffline() {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val dead = "http://127.0.0.1:$port/v1/".toHttpUrl()
        assertEquals(YtNewsSummaryFailure.OFFLINE, YtNewsOpenAi(dead).check(openAiSetup))
        val gemini = YtNewsGemini(dead)
        val error = runCatching { runBlocking { gemini.summarize(geminiSetup, sources) } }.exceptionOrNull()!!
        assertEquals(YtNewsSummaryFailure.OFFLINE, gemini.failure(error))
    }

    // --- Gemini ---------------------------------------------------------------------------------

    private val geminiSetup = YtNewsProviderSetup(YtNewsProviderKind.GEMINI, "AIzaTestKey", YtNewsGemini.MODEL)

    @Test
    fun geminiRequestShapeAndVerifiedStatements() {
        server.answer(
            200,
            """{"candidates":[{"content":{"role":"model","parts":[
                {"text":"thinking about it","thought":true},
                {"text":${quoted(statements)}}]},"finishReason":"STOP"}],
               "usageMetadata":{"promptTokenCount":10}}""",
        )
        val result = runBlocking { YtNewsGemini(base).summarize(geminiSetup, sources) }
        assertEquals(YtNewsSummaryResult.Done(expected), result)
        val request = next()
        assertTrue(request.line, request.line.startsWith("POST /v1/models/${YtNewsGemini.MODEL}:generateContent "))
        assertEquals("AIzaTestKey", request.headers["x-goog-api-key"])
        assertFalse(request.line.contains("AIza"))
        assertNull(request.headers["authorization"])
        val body = body(request)
        val config = body["generationConfig"]!!.jsonObject
        assertEquals("application/json", config["responseMimeType"]!!.jsonPrimitive.content)
        assertEquals("object", config["responseJsonSchema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(
            body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content.contains("neutral"),
        )
        assertTrue(
            body["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content.contains("L1 [0:04]"),
        )
    }

    @Test
    fun geminiBlockedAndErrors() {
        server.answer(200, """{"promptFeedback":{"blockReason":"SAFETY"}}""")
        server.answer(200, """{"candidates":[{"content":{"parts":[{"text":"{}"}]},"finishReason":"SAFETY"}]}""")
        server.answer(
            400,
            """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT",
               "details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"API_KEY_INVALID"}]}}""",
        )
        server.answer(429, """{"error":{"code":429,"message":"You exceeded your current quota, please check your plan.","status":"RESOURCE_EXHAUSTED"}}""")
        server.answer(503, """{"error":{"code":503,"message":"The model is overloaded.","status":"UNAVAILABLE"}}""")
        val gemini = YtNewsGemini(base)
        val reasons = (1..5).map {
            runCatching { runBlocking { gemini.summarize(geminiSetup, sources) } }
                .fold({ (it as YtNewsSummaryResult.Failed).reason }, { gemini.failure(it) })
        }
        assertEquals(
            listOf(
                YtNewsSummaryFailure.REFUSED,
                YtNewsSummaryFailure.REFUSED,
                YtNewsSummaryFailure.INVALID_KEY,
                YtNewsSummaryFailure.RATE_LIMITED,
                YtNewsSummaryFailure.SERVICE,
            ),
            reasons,
        )
    }

    @Test
    fun geminiKeyCheck() {
        server.answer(200, """{"models":[]}""")
        assertNull(YtNewsGemini(base).check(geminiSetup))
        val request = next()
        assertTrue(request.line, request.line.startsWith("GET /v1/models?pageSize=1 "))
        assertEquals("AIzaTestKey", request.headers["x-goog-api-key"])
    }

    // --- Mistral and compatible servers ----------------------------------------------------------

    private fun chatAnswer(content: String) =
        """{"id":"c1","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":$content},
           "finish_reason":"stop"}]}"""

    private fun mistral() = YtNewsChatCompletions(YtNewsProviderKind.MISTRAL, base, YtNewsChatCompletions.MISTRAL_MODEL)
    private val mistralSetup = YtNewsProviderSetup(YtNewsProviderKind.MISTRAL, "mistralKey123", YtNewsChatCompletions.MISTRAL_MODEL)

    @Test
    fun mistralRequestShapeAndVerifiedStatements() {
        server.answer(200, chatAnswer(quoted(statements)))
        assertEquals(YtNewsSummaryResult.Done(expected), runBlocking { mistral().summarize(mistralSetup, sources) })
        val request = next()
        assertTrue(request.line, request.line.startsWith("POST /v1/chat/completions "))
        assertEquals("Bearer mistralKey123", request.headers["authorization"])
        val body = body(request)
        assertEquals(YtNewsChatCompletions.MISTRAL_MODEL, body["model"]!!.jsonPrimitive.content)
        val messages = body["messages"]!!.jsonArray
        assertEquals(listOf("system", "user"), messages.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        val format = body["response_format"]!!.jsonObject
        assertEquals("json_schema", format["type"]!!.jsonPrimitive.content)
        val schema = format["json_schema"]!!.jsonObject
        assertEquals("true", schema["strict"]!!.jsonPrimitive.content)
        assertTrue(schema["schema"] is JsonObject)
    }

    @Test
    fun mistralErrorsAndContentParts() {
        server.answer(401, """{"object":"error","message":"Unauthorized","type":"unauthorized","code":"1000"}""")
        server.answer(429, """{"object":"error","message":"Requests rate limit exceeded","type":"rate_limited","code":"1300"}""")
        server.answer(502, "<html>bad gateway</html>")
        val reasons = (1..3).map {
            runCatching { runBlocking { mistral().summarize(mistralSetup, sources) } }.fold({ null }, { mistral().failure(it) })
        }
        assertEquals(listOf(YtNewsSummaryFailure.INVALID_KEY, YtNewsSummaryFailure.RATE_LIMITED, YtNewsSummaryFailure.SERVICE), reasons)
        // a reasoning model answers in parts; only the text parts count
        val parts = buildJsonArray {
            add(buildJsonObject { put("type", "thinking"); put("thinking", "hm") })
            add(buildJsonObject { put("type", "text"); put("text", statements) })
        }
        assertEquals(statements, YtNewsChatCompletions.text(chatAnswer(parts.toString())))
        assertNull(YtNewsChatCompletions.text("""{"choices":[{"message":{"content":null,"refusal":"no"},"finish_reason":"stop"}]}"""))
        assertNull(YtNewsChatCompletions.text("""{"choices":[{"message":{"content":"{}"},"finish_reason":"content_filter"}]}"""))
    }

    @Test
    fun compatibleFallsBackToJsonModeAndWorksWithoutKey() {
        val compatible = YtNewsChatCompletions(YtNewsProviderKind.COMPATIBLE, null, null)
        val setup = YtNewsProviderSetup(YtNewsProviderKind.COMPATIBLE, "", "llama3.2", "${server.url}/v1")
        server.answer(400, """{"error":{"message":"response_format json_schema is not supported","type":"invalid_request_error"}}""")
        server.answer(200, chatAnswer(quoted("Hier ist das JSON:\n```json\n$statements\n```")))
        assertEquals(YtNewsSummaryResult.Done(expected), runBlocking { compatible.summarize(setup, sources) })
        val first = next()
        val second = next()
        assertEquals("json_schema", body(first)["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("json_object", body(second)["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertNull(second.headers["authorization"])
        assertEquals("llama3.2", body(second)["model"]!!.jsonPrimitive.content)
        // 402 (an empty prepaid account on several such services)
        server.answer(402, """{"error":{"message":"Insufficient Balance","type":"unknown_error"}}""")
        val error = runCatching { runBlocking { compatible.summarize(setup.copyKey("sk-abc"), sources) } }.exceptionOrNull()!!
        assertEquals(YtNewsSummaryFailure.NO_CREDIT, compatible.failure(error))
        assertEquals("Bearer sk-abc", next().headers["authorization"])
    }

    @Test
    fun compatibleWithoutAllowedAddressSendsNothing() {
        val compatible = YtNewsChatCompletions(YtNewsProviderKind.COMPATIBLE, null, null)
        val setup = YtNewsProviderSetup(YtNewsProviderKind.COMPATIBLE, "k", "m", "http://example.com/v1")
        assertEquals(YtNewsSummaryResult.Failed(YtNewsSummaryFailure.SERVICE), runBlocking { compatible.summarize(setup, sources) })
        assertEquals(YtNewsSummaryFailure.SERVICE, compatible.check(setup))
        assertNull(server.requests.poll(300, TimeUnit.MILLISECONDS))
    }

    private fun YtNewsProviderSetup.copyKey(key: String) = YtNewsProviderSetup(kind, key, model, address)

    // --- shared rules ---------------------------------------------------------------------------

    @Test
    fun addressRules() {
        assertEquals("https://api.groq.com/openai/v1/", YtNewsHttp.baseUrl("https://api.groq.com/openai/v1/").toString())
        assertEquals("https://api.deepseek.com/", YtNewsHttp.baseUrl("api.deepseek.com").toString())
        assertTrue(YtNewsHttp.baseUrl("http://localhost:11434/v1") != null)
        assertTrue(YtNewsHttp.baseUrl("http://127.0.0.1:1234/v1") != null)
        assertTrue(YtNewsHttp.baseUrl("http://192.168.1.20:11434/v1") != null)
        assertTrue(YtNewsHttp.baseUrl("http://10.0.0.5/v1") != null)
        assertTrue(YtNewsHttp.baseUrl("http://172.20.1.1/v1") != null)
        assertNull(YtNewsHttp.baseUrl("http://172.32.1.1/v1"))
        assertNull(YtNewsHttp.baseUrl("http://example.com/v1"))
        assertNull(YtNewsHttp.baseUrl("http://8.8.8.8/v1"))
        assertNull(YtNewsHttp.baseUrl("ftp://x"))
        val base = YtNewsHttp.baseUrl("https://h/openai/v1/")!!
        assertEquals("https://h/openai/v1/chat/completions", YtNewsHttp.resolve(base, "chat/completions").toString())
        assertEquals("https://h/v1/models", YtNewsHttp.resolve(YtNewsHttp.baseUrl("https://h/v1")!!, "models").toString())
    }

    @Test
    fun keyOwnersAndUsability() {
        assertEquals(YtNewsProviderKind.ANTHROPIC, YtNewsProviders.mismatch(YtNewsProviderKind.OPENAI, "sk-ant-api03-x"))
        assertEquals(YtNewsProviderKind.OPENAI, YtNewsProviders.mismatch(YtNewsProviderKind.ANTHROPIC, "sk-proj-x"))
        assertEquals(YtNewsProviderKind.GEMINI, YtNewsProviders.mismatch(YtNewsProviderKind.MISTRAL, "AIzaX"))
        assertNull(YtNewsProviders.mismatch(YtNewsProviderKind.OPENAI, "sk-proj-x"))
        assertNull(YtNewsProviders.mismatch(YtNewsProviderKind.MISTRAL, "AbCdEf123"))
        assertNull(YtNewsProviders.mismatch(YtNewsProviderKind.COMPATIBLE, "sk-anything"))

        assertFalse(YtNewsProviders.usable(null, true, null, null))
        assertTrue(YtNewsProviders.usable(YtNewsProviderKind.OPENAI, true, null, null))
        assertFalse(YtNewsProviders.usable(YtNewsProviderKind.GEMINI, false, "x", null))
        assertTrue(YtNewsProviders.usable(YtNewsProviderKind.COMPATIBLE, false, "llama3.2", "http://localhost:11434/v1"))
        assertFalse(YtNewsProviders.usable(YtNewsProviderKind.COMPATIBLE, true, null, "http://localhost:11434/v1"))
        assertFalse(YtNewsProviders.usable(YtNewsProviderKind.COMPATIBLE, true, "m", "http://example.com"))

        assertEquals(YtNewsOpenAi.MODEL, YtNewsProviders.model(YtNewsProviderKind.OPENAI, "  "))
        assertEquals("gpt-x", YtNewsProviders.model(YtNewsProviderKind.OPENAI, " gpt-x "))
        assertNull(YtNewsProviders.model(YtNewsProviderKind.COMPATIBLE, null))
    }

    @Test
    fun cacheKeysNameProviderAndModel() {
        // the first provider with its default keeps the keys of summaries cached before the choice
        assertNull(YtNewsProviders.cacheScope(YtNewsProviderKind.ANTHROPIC, YtNewsSummaryApi.MODEL, null))
        assertEquals("vid", YtNewsSummaryCacheRules.scoped(null, "vid"))
        val openAi = YtNewsSummaryCacheRules.scoped(YtNewsProviders.cacheScope(YtNewsProviderKind.OPENAI, "gpt-a", null), "vid")
        val other = YtNewsSummaryCacheRules.scoped(YtNewsProviders.cacheScope(YtNewsProviderKind.OPENAI, "gpt-b", null), "vid")
        val anthropicOwn = YtNewsSummaryCacheRules.scoped(YtNewsProviders.cacheScope(YtNewsProviderKind.ANTHROPIC, "other", null), "vid")
        val local = YtNewsProviders.cacheScope(YtNewsProviderKind.COMPATIBLE, "m", "http://localhost:11434/v1")
        assertEquals(4, setOf("vid", openAi, other, anthropicOwn).size)
        assertEquals("compatible@localhost/m", local)
        // and their files differ too
        assertEquals(4, listOf("vid", openAi, other, anthropicOwn).map(YtNewsSummaryCacheRules::fileName).toSet().size)
    }

    @Test
    fun errorBodiesAreNotInMessages() {
        val status = YtNewsHttp.Status(400, """{"error":{"message":"bad input: L1 [0:04] Der Bundestag"}}""")
        assertFalse(status.toString().contains("Bundestag"))
        assertFalse(status.message.orEmpty().contains("Bundestag"))
    }
}

/** One canned answer per connection, in order; records what it was sent. */
class YtNewsFakeServer : AutoCloseable {
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
                        "Connection: close\r\n\r\n"
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
