package com.github.libretube.ui.yt.account

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * YT: the few requests the account needs, made to youtube.com's own web API with the session of
 * the user: page config, account name, the lists of the "You" area. Cookies that YouTube renews
 * are written back through [onCookies]. Redirects are not followed, so the session never goes
 * to another host.
 */
internal class YtInnerTube(cookies: String, private val onCookies: (String) -> Unit) {
    private val jar = LinkedHashMap<String, String>().apply { putAll(parseCookies(cookies)) }
    private var clientVersion = FALLBACK_CLIENT_VERSION
    private var visitorData: String? = null
    private val hl = Locale.getDefault().language.ifBlank { "en" }
    private val gl = Locale.getDefault().country.ifBlank { "US" }

    private fun cookieHeader(): String {
        val all = LinkedHashMap(jar)
        // without a consent cookie, YouTube in the EU answers with its consent page
        if ("SOCS" !in all && "CONSENT" !in all) all["SOCS"] = "CAI"
        return all.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /** The SAPISIDHASH authorization that YouTube's web client sends for signed-in requests. */
    private fun authorization(): String? {
        val time = System.currentTimeMillis() / 1000
        fun hash(scheme: String, sid: String) = "$scheme ${time}_${sha1("$time $sid $ORIGIN")}"
        val parts = listOfNotNull(
            (jar["SAPISID"] ?: jar["__Secure-3PAPISID"])?.let { hash("SAPISIDHASH", it) },
            jar["__Secure-1PAPISID"]?.let { hash("SAPISID1PHASH", it) },
            jar["__Secure-3PAPISID"]?.let { hash("SAPISID3PHASH", it) },
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    private fun Request.Builder.session(): Request.Builder = apply {
        header("Cookie", cookieHeader())
        header("User-Agent", DESKTOP_UA)
        header("Accept-Language", "$hl-$gl,$hl;q=0.9,en;q=0.8")
        header("Origin", ORIGIN)
        header("X-Origin", ORIGIN)
        header("Referer", "$ORIGIN/")
        authorization()?.let { header("Authorization", it) }
        header("X-Goog-AuthUser", "0")
        header("X-Youtube-Client-Name", "1")
        header("X-Youtube-Client-Version", clientVersion)
        visitorData?.let { header("X-Goog-Visitor-Id", it) }
    }

    private fun execute(request: Request): String {
        client.newCall(request).execute().use { response ->
            if (absorb(response.headers("Set-Cookie"))) onCookies(jar.entries.joinToString("; ") { "${it.key}=${it.value}" })
            val body = response.body.string()
            if (!response.isSuccessful) throw IOException("YouTube: HTTP ${response.code}")
            return body
        }
    }

    /** Takes over cookies that YouTube renewed. Returns true if any changed. */
    private fun absorb(setCookies: List<String>): Boolean {
        var changed = false
        for (line in setCookies) {
            val pair = line.substringBefore(';')
            val name = pair.substringBefore('=').trim()
            val value = pair.substringAfter('=', "").trim()
            if (name.isEmpty() || value.isEmpty() || value == "EXPIRED") continue
            if (line.lowercase(Locale.ROOT).contains("max-age=0")) continue
            if (jar[name] != value) {
                jar[name] = value
                changed = true
            }
        }
        return changed
    }

    /** Reads the page config of youtube.com (client version, visitor id). True if signed in. */
    fun prepare(): Boolean {
        val html = execute(Request.Builder().url("$ORIGIN/?hl=$hl").session().get().build())
        Regex("\"INNERTUBE_CLIENT_VERSION\":\"([^\"]+)\"").find(html)?.let { clientVersion = it.groupValues[1] }
        Regex("\"VISITOR_DATA\":\"([^\"]+)\"").find(html)?.let { visitorData = it.groupValues[1] }
        return html.contains("\"LOGGED_IN\":true")
    }

    private fun post(endpoint: String, fields: JsonObjectBuilder.() -> Unit): JsonObject {
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "WEB")
                    put("clientVersion", clientVersion)
                    put("hl", hl)
                    put("gl", gl)
                    visitorData?.let { put("visitorData", it) }
                }
            }
            fields()
        }
        val request = Request.Builder()
            .url("$ORIGIN/youtubei/v1/$endpoint?prettyPrint=false")
            .session()
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        return Json.parseToJsonElement(execute(request)) as? JsonObject
            ?: throw IOException("YouTube: unexpected answer")
    }

    fun browse(browseId: String? = null, continuation: String? = null): JsonObject = post("browse") {
        browseId?.let { put("browseId", it) }
        continuation?.let { put("continuation", it) }
    }

    /** Walks a list page by page until it ends, [maxPages] is reached or [stop] says so. */
    fun pages(browseId: String, maxPages: Int, stop: () -> Boolean = { false }, onPage: (JsonObject) -> Unit) {
        var page = browse(browseId)
        onPage(page)
        val seen = HashSet<String>()
        var token = page.continuationToken()
        var count = 1
        while (token != null && count < maxPages && !stop() && seen.add(token)) {
            page = browse(continuation = token)
            onPage(page)
            token = page.continuationToken()
            count++
        }
    }

    fun accountInfo(): YtAccountInfo? {
        val menu = post("account/account_menu") {}
        val header = menu.find("activeAccountHeaderRenderer") as? JsonObject ?: return null
        val name = header["accountName"].text() ?: return null
        val handle = header["channelHandle"].text() ?: header["email"].text() ?: ""
        val photo = (header["accountPhoto"]?.find("thumbnails") as? JsonArray)?.lastOrNull().str("url")
        return YtAccountInfo(name, handle, photo?.let(::absoluteUrl))
    }

    /** The subscriptions as the side menu lists them, for when the channel list is empty. */
    fun guideChannels(): List<YtChannel> = post("guide") {}
        .findAll("guideEntryRenderer")
        .mapNotNull { entry ->
            val id = entry.find("browseEndpoint").str("browseId")?.takeIf { CHANNEL_ID.matches(it) }
                ?: return@mapNotNull null
            val name = (entry as? JsonObject)?.get("formattedTitle").text() ?: return@mapNotNull null
            YtChannel(id, name, entry.thumbnail(), false)
        }
        .distinctBy { it.id }

    /**
     * Rates [videoId] for the signed-in account: "like", "dislike" or "removelike", the three
     * endpoints YouTube's web client calls from its thumb buttons. Throws when YouTube does not
     * take it (HTTP error, or an answer without the usual response context).
     */
    fun rate(videoId: String, endpoint: String) {
        require(endpoint in RATE_ENDPOINTS)
        val answer = post("like/$endpoint") { putJsonObject("target") { put("videoId", videoId) } }
        if ("responseContext" !in answer) throw IOException("YouTube: unexpected answer")
    }

    /** The account's current rating of [videoId] as YouTube reports it ("LIKE", "DISLIKE", "INDIFFERENT"), if found. */
    fun likeStatus(videoId: String): String? =
        (post("next") { put("videoId", videoId) }.find("likeStatus") as? JsonPrimitive)?.contentOrNull

    fun logout() {
        execute(Request.Builder().url("$ORIGIN/logout").session().get().build())
    }

    companion object {
        const val ORIGIN = "https://www.youtube.com"
        private const val FALLBACK_CLIENT_VERSION = "2.20261015.01.00"
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
        private val JSON_TYPE = "application/json".toMediaType()
        val CHANNEL_ID = Regex("^UC[\\w-]{22}$")
        private val RATE_ENDPOINTS = setOf("like", "dislike", "removelike")

        private val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        fun parseCookies(header: String): Map<String, String> = header.split(';')
            .mapNotNull { part ->
                val name = part.substringBefore('=').trim()
                val value = part.substringAfter('=', "").trim()
                if (name.isEmpty() || value.isEmpty()) null else name to value
            }
            .toMap()

        private fun sha1(text: String): String = MessageDigest.getInstance("SHA-1")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

/** A channel of the account's subscriptions. */
internal data class YtChannel(val id: String, val name: String, val avatar: String?, val verified: Boolean)

/** A video of one of the account's lists. */
internal data class YtVideo(
    val id: String,
    val title: String?,
    val channel: String?,
    val channelId: String?,
    val seconds: Long?,
    val watchedPercent: Int?,
)

/** A playlist of the account. */
internal data class YtPlaylistRef(val id: String, val title: String)

// --- reading YouTube's answers: they change often, so the readers search by renderer name

internal fun JsonElement?.find(key: String): JsonElement? {
    when (this) {
        is JsonObject -> {
            this[key]?.let { return it }
            for (value in values) value.find(key)?.let { return it }
        }
        is JsonArray -> for (value in this) value.find(key)?.let { return it }
        else -> {}
    }
    return null
}

internal fun JsonElement?.findAll(key: String, out: MutableList<JsonElement> = mutableListOf()): List<JsonElement> {
    when (this) {
        is JsonObject -> for ((name, value) in this) {
            if (name == key) out.add(value) else value.findAll(key, out)
        }
        is JsonArray -> for (value in this) value.findAll(key, out)
        else -> {}
    }
    return out
}

internal fun JsonElement?.str(vararg path: String): String? {
    var element: JsonElement? = this
    for (name in path) element = (element as? JsonObject)?.get(name) ?: return null
    return (element as? JsonPrimitive)?.contentOrNull
}

/** Text of a YouTube text object: simpleText, runs or content. */
internal fun JsonElement?.text(): String? {
    val obj = this as? JsonObject ?: return (this as? JsonPrimitive)?.contentOrNull
    obj.str("simpleText")?.let { return it }
    obj.str("content")?.let { return it }
    (obj["runs"] as? JsonArray)?.let { runs ->
        return runs.joinToString("") { it.str("text").orEmpty() }.ifBlank { null }
    }
    return null
}

internal fun absoluteUrl(url: String): String = if (url.startsWith("//")) "https:$url" else url

internal fun JsonElement?.thumbnail(): String? =
    ((this as? JsonObject)?.get("thumbnail").find("thumbnails") as? JsonArray)
        ?.lastOrNull().str("url")?.let(::absoluteUrl)

internal fun JsonElement.continuationToken(): String? =
    findAll("continuationItemRenderer").lastOrNull()?.let { it.find("continuationCommand").str("token") }
        ?: find("nextContinuationData").str("continuation")

/** "1:02:03" or "12:34" in seconds. */
internal fun parseDuration(text: String): Long? {
    val parts = text.trim().split(':').map { it.toLongOrNull() ?: return null }
    if (parts.isEmpty() || parts.size > 3) return null
    return parts.fold(0L) { total, part -> total * 60 + part }
}

internal fun JsonElement.channels(): List<YtChannel> =
    (findAll("channelRenderer") + findAll("gridChannelRenderer")).mapNotNull { renderer ->
        val id = renderer.str("channelId") ?: return@mapNotNull null
        val name = (renderer as? JsonObject)?.get("title").text() ?: return@mapNotNull null
        YtChannel(id, name, renderer.thumbnail(), renderer.toString().contains("BADGE_STYLE_TYPE_VERIFIED"))
    }

internal fun JsonElement.videos(): List<YtVideo> {
    val out = LinkedHashMap<String, YtVideo>()
    for (key in listOf("playlistVideoRenderer", "videoRenderer", "gridVideoRenderer")) {
        for (renderer in findAll(key)) {
            val obj = renderer as? JsonObject ?: continue
            val id = obj.str("videoId") ?: continue
            if ((obj["isPlayable"] as? JsonPrimitive)?.booleanOrNull == false) continue
            val byline = obj["shortBylineText"] ?: obj["ownerText"] ?: obj["longBylineText"]
            out.putIfAbsent(
                id,
                YtVideo(
                    id = id,
                    title = obj["title"].text(),
                    channel = byline.text(),
                    channelId = byline.find("browseEndpoint").str("browseId"),
                    seconds = obj.str("lengthSeconds")?.toLongOrNull() ?: obj["lengthText"].text()?.let(::parseDuration),
                    watchedPercent = obj.find("thumbnailOverlayResumePlaybackRenderer").str("percentDurationWatched")?.toIntOrNull(),
                ),
            )
        }
    }
    for (renderer in findAll("lockupViewModel")) {
        val obj = renderer as? JsonObject ?: continue
        if (obj.str("contentType")?.contains("VIDEO") != true) continue
        val id = obj.str("contentId") ?: continue
        val meta = obj.find("lockupMetadataViewModel")
        val firstPart = (meta.find("metadataParts") as? JsonArray)?.firstOrNull()
        out.putIfAbsent(
            id,
            YtVideo(
                id = id,
                title = (meta as? JsonObject)?.get("title").text(),
                channel = (firstPart as? JsonObject)?.get("text").text(),
                channelId = obj.find("browseEndpoint").str("browseId")?.takeIf { YtInnerTube.CHANNEL_ID.matches(it) },
                seconds = (obj.find("thumbnailBadgeViewModel") as? JsonObject)?.get("text").text()?.let(::parseDuration),
                watchedPercent = obj.find("thumbnailOverlayProgressBarViewModel").str("startPercent")?.toIntOrNull(),
            ),
        )
    }
    return out.values.toList()
}

internal fun JsonElement.playlists(): List<YtPlaylistRef> {
    val out = LinkedHashMap<String, String>()
    for (key in listOf("gridPlaylistRenderer", "playlistRenderer")) {
        for (renderer in findAll(key)) {
            val id = renderer.str("playlistId") ?: continue
            val title = (renderer as? JsonObject)?.get("title").text() ?: continue
            out.putIfAbsent(id, title)
        }
    }
    for (renderer in findAll("lockupViewModel")) {
        val obj = renderer as? JsonObject ?: continue
        if (obj.str("contentType")?.contains("PLAYLIST") != true) continue
        val id = obj.str("contentId") ?: continue
        val title = (obj.find("lockupMetadataViewModel") as? JsonObject)?.get("title").text() ?: continue
        out.putIfAbsent(id, title)
    }
    // watch later and liked videos come separately; mixes are generated, not saved
    return out.filter { (id, _) -> id != "WL" && id != "LL" && !id.startsWith("RD") }
        .map { (id, title) -> YtPlaylistRef(id, title) }
}
