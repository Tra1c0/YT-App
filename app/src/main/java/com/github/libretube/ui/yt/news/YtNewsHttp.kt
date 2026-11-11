package com.github.libretube.ui.yt.news

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * YT: the plain HTTP side of the summary services that are called without an SDK. One OkHttp
 * client without any logging interceptor: neither the key (a header) nor a request body (the
 * transcript) is ever written to a log. Free of Android, so the providers run on the JVM in tests.
 */
object YtNewsHttp {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    /** Long enough for a model reading a long transcript; a closed overlay cancels sooner. */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(150, TimeUnit.SECONDS)
            .build()
    }

    /** A thrown HTTP error answer, mapped to its reason by [failureOf]. */
    class Status(val code: Int, val body: String) : IOException("HTTP $code") {
        // the body may quote parts of the request; it stays out of messages and logs
        override fun toString(): String = "YtNewsHttp.Status($code)"
    }

    fun post(url: HttpUrl, headers: Map<String, String>, body: JsonElement): Request =
        Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }
            .post(json.encodeToString(JsonElement.serializer(), body).toRequestBody(JSON_TYPE))
            .build()

    fun get(url: HttpUrl, headers: Map<String, String>): Request =
        Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.get().build()

    /** Runs [request]; the body of a 2xx answer, else throws [Status]. Blocking. */
    fun execute(request: Request, client: OkHttpClient = this.client): String =
        client.newCall(request).execute().use { read(it) }

    /** Runs [request] and suspends; cancelling the coroutine cancels the call at once. */
    suspend fun await(request: Request, client: OkHttpClient = this.client): String =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use { read(it) } }
                    if (!cont.isActive) return
                    cont.resumeWith(result)
                }
            })
        }

    private fun read(response: Response): String {
        val text = response.body.string()
        if (!response.isSuccessful) throw Status(response.code, text)
        return text
    }

    /** The reason behind an error of a request: an HTTP answer, no network, or anything else. */
    fun failure(error: Throwable): YtNewsSummaryFailure = when (error) {
        is Status -> failureOf(error.code, error.body)
        is IOException -> YtNewsSummaryFailure.OFFLINE
        else -> YtNewsSummaryFailure.SERVICE
    }

    /** Words in an error's type or code that mean the account has no credit (not a rate limit). */
    private val CREDIT = listOf("insufficient_quota", "credit", "balance", "spend_limit", "usage_limit", "billing", "payment")

    /**
     * The reason of an HTTP error answer. The services agree on the status codes; the type and
     * code fields (never the free-text message) tell an empty account from too many requests.
     */
    fun failureOf(code: Int, body: String): YtNewsSummaryFailure {
        val marks = errorMarks(body)
        return when {
            code == 401 || code == 403 -> YtNewsSummaryFailure.INVALID_KEY
            // one service answers a wrong key with 400 and this reason
            code == 400 && body.contains("API_KEY_INVALID") -> YtNewsSummaryFailure.INVALID_KEY
            code == 402 -> YtNewsSummaryFailure.NO_CREDIT
            code == 429 && marks.any { mark -> CREDIT.any { mark.contains(it) } } -> YtNewsSummaryFailure.NO_CREDIT
            code == 429 -> YtNewsSummaryFailure.RATE_LIMITED
            code == 404 || marks.any { it.contains("model_not_found") } -> YtNewsSummaryFailure.MODEL_UNKNOWN
            else -> YtNewsSummaryFailure.SERVICE
        }
    }

    /** The lower-cased type, code and status fields of an error body, at the top or under "error". */
    fun errorMarks(body: String): List<String> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return emptyList()
        val error = root["error"] as? JsonObject
        return listOfNotNull(root, error).flatMap { obj ->
            listOf("type", "code", "status").mapNotNull { (obj[it] as? JsonPrimitive)?.contentOrNull?.lowercase() }
        }
    }

    /**
     * The base address of a self-chosen OpenAI-compatible server, or null when it is not allowed:
     * HTTPS, or plain HTTP only to this device or the local network (a server at home).
     */
    fun baseUrl(address: String): HttpUrl? {
        val raw = address.trim().let { if ("://" in it) it else "https://$it" }
        val url = raw.toHttpUrlOrNull() ?: return null
        if (url.scheme == "https") return url
        return url.takeIf { isLocal(it.host) }
    }

    /** localhost, loopback, or a private IPv4 network (10/8, 172.16/12, 192.168/16). */
    fun isLocal(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".local") || host == "::1") return true
        val parts = host.split('.').map { it.toIntOrNull() ?: -1 }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        return parts[0] == 127 || parts[0] == 10 ||
            (parts[0] == 172 && parts[1] in 16..31) ||
            (parts[0] == 192 && parts[1] == 168)
    }

    /** [base] with [path] appended segment by segment ("chat/completions"). */
    fun resolve(base: HttpUrl, path: String): HttpUrl =
        base.newBuilder().apply {
            // drop a trailing empty segment of "…/v1/"
            if (base.pathSegments.lastOrNull() == "") removePathSegment(base.pathSegments.size - 1)
            path.split('/').filter { it.isNotEmpty() }.forEach { addPathSegment(it) }
        }.build()
}
