package com.github.libretube.ui.yt.ssi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * YT: which checksum a model file from Hugging Face must have, read from the hub's file listing
 * (`/api/models/<repo>/tree/<rev>/<dir>`) instead of guessed from response headers. The download
 * redirects to a storage server whose own ETag can be a 64-digit storage hash that is not the
 * file's SHA-256, which made every check fail ("checksum mismatch", build 138). The listing names
 * the SHA-256 of a large (LFS) file directly; a small file only has its git blob id (SHA-1 of
 * "blob <size>\0" + content). Free of Android.
 */
object YtSsiHfChecksum {
    private val json = Json { ignoreUnknownKeys = true }
    private val SHA256 = Regex("^[0-9a-f]{64}$")
    private val SHA1 = Regex("^[0-9a-f]{40}$")

    /** A file of a model repo: `https://huggingface.co/<owner>/<name>/resolve/<rev>/<path>`. */
    data class Ref(val repo: String, val rev: String, val path: String) {
        val dir: String get() = path.substringBeforeLast('/', "")
        val treeUrl: String
            get() = "https://huggingface.co/api/models/$repo/tree/$rev" + (if (dir.isEmpty()) "" else "/$dir")
    }

    /** What the downloaded bytes must hash to: a SHA-256, or a git blob SHA-1 for [size] bytes. */
    sealed interface Expected {
        data class Sha256(val hex: String) : Expected
        data class GitBlob(val sha1: String, val size: Long) : Expected
    }

    fun ref(url: String): Ref? {
        val m = Regex("^https://huggingface\\.co/([^/]+/[^/]+)/resolve/([^/]+)/(.+)$").find(url) ?: return null
        return Ref(m.groupValues[1], m.groupValues[2], m.groupValues[3])
    }

    /** The checksum of [ref] in a tree listing (JSON array of entries), or null when not found. */
    fun expected(listing: String, ref: Ref): Expected? {
        val entries = runCatching { json.parseToJsonElement(listing) as? JsonArray }.getOrNull() ?: return null
        val entry = entries.filterIsInstance<JsonObject>()
            .firstOrNull { it["path"]?.jsonPrimitive?.contentOrNull == ref.path } ?: return null
        val lfs = entry["lfs"] as? JsonObject
        val lfsOid = lfs?.get("oid")?.jsonPrimitive?.contentOrNull?.lowercase()?.removePrefix("sha256:")
        if (lfsOid != null && SHA256.matches(lfsOid)) return Expected.Sha256(lfsOid)
        val oid = entry["oid"]?.jsonPrimitive?.contentOrNull?.lowercase()
        val size = entry["size"]?.jsonPrimitive?.longOrNull
        if (oid != null && SHA1.matches(oid) && size != null && size >= 0) return Expected.GitBlob(oid, size)
        return null
    }

    /** The header that starts a git blob's SHA-1 for [size] bytes. */
    fun gitBlobHeader(size: Long): ByteArray = "blob $size\u0000".toByteArray(Charsets.US_ASCII)

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
