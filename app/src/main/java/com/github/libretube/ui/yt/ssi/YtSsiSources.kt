package com.github.libretube.ui.yt.ssi

/** SSI-3: why two videos count as one origin. */
enum class YtSsiSameOrigin {
    /** Their passages say nearly the same words (or meaning vectors almost alike). */
    NEAR_DUPLICATE,

    /** Their descriptions cite the same source (a link that is not a channel's own profile). */
    SAME_LINK,

    /** One's description links the other video. */
    LINKS_VIDEO,

    /** Same title and length from another channel: a re-upload. */
    REUPLOAD,
}

/**
 * SSI-3: videos that repeat one origin and count once. [videos] earliest upload first (the
 * likely origin first); [reasons] why they were put together (empty for a video on its own).
 */
data class YtSsiOrigin(val videos: List<String>, val reasons: Set<YtSsiSameOrigin>) {
    val key: String get() = videos.first()
    val size: Int get() = videos.size
}

/**
 * YT (SSI-3): source independence, pure. Several videos saying the same thing are no more
 * independent evidence than one when they repeat the same origin. Videos are joined
 * (union-find) when their hit passages are near-duplicates (meaning vectors ≥ [VECTOR_SAME], or
 * word shingles ≥ [SHINGLE_SAME] without vectors), when their descriptions cite the same link
 * (profile, shop and donation links do not count, nor a link two videos of one channel share),
 * when one's description links the other, or when one is a re-upload (same title, length within
 * [REUPLOAD_SECONDS]). Joined is never "proven the same", it is "count once".
 */
object YtSsiSources {
    const val VECTOR_SAME = 0.93f
    const val SHINGLE_SAME = 0.5
    const val SHINGLE = 3

    /** Fewer shingles than this prove nothing (a short passage). */
    const val MIN_SHINGLES = 6
    const val REUPLOAD_SECONDS = 3L

    /** One hit passage of a video. */
    data class Item(
        val videoId: String,
        val channelId: String?,
        val channelName: String?,
        val title: String,
        val description: String?,
        val uploaded: Long?,
        val durationSec: Long?,
        val text: String,
        val vector: FloatArray? = null,
    )

    /** The origins of [items] (one per independent origin), in the order their first video appears. */
    fun cluster(items: List<Item>): List<YtSsiOrigin> {
        val videos = LinkedHashMap<String, MutableList<Item>>()
        for (item in items) videos.getOrPut(item.videoId) { ArrayList() } += item
        val ids = videos.keys.toList()
        val parent = IntArray(ids.size) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }
        val reasons = HashMap<Int, MutableSet<YtSsiSameOrigin>>()
        fun join(a: Int, b: Int, why: YtSsiSameOrigin) {
            val ra = find(a)
            val rb = find(b)
            val all = (reasons.remove(ra).orEmpty() + reasons.remove(rb).orEmpty() + why).toMutableSet()
            if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb)
            reasons[find(a)] = all
        }
        val shingles = ids.map { id -> videos.getValue(id).map { shingles(it.text) } }
        val links = ids.map { id -> videos.getValue(id).first().description?.let(::links).orEmpty() }
        val index = ids.withIndex().associate { (i, id) -> id to i }
        for (a in ids.indices) {
            val first = videos.getValue(ids[a]).first()
            // a description linking another video of the set
            for (link in links[a]) {
                val other = link.removePrefix("yt:").takeIf { link.startsWith("yt:") }?.let { index[it] } ?: continue
                if (other != a) join(a, other, YtSsiSameOrigin.LINKS_VIDEO)
            }
            for (b in a + 1 until ids.size) {
                val second = videos.getValue(ids[b]).first()
                if (nearDuplicate(videos.getValue(ids[a]), shingles[a], videos.getValue(ids[b]), shingles[b])) join(a, b, YtSsiSameOrigin.NEAR_DUPLICATE)
                if (!sameChannel(first, second) && (links[a] intersect links[b]).any { !it.startsWith("yt:") || index[it.removePrefix("yt:")] == null }) {
                    join(a, b, YtSsiSameOrigin.SAME_LINK)
                }
                if (reupload(first, second)) join(a, b, YtSsiSameOrigin.REUPLOAD)
            }
        }
        val groups = LinkedHashMap<Int, MutableList<String>>()
        for (i in ids.indices) groups.getOrPut(find(i)) { ArrayList() } += ids[i]
        return groups.map { (root, members) ->
            val order = members.withIndex().sortedWith(
                compareBy<IndexedValue<String>> { videos.getValue(it.value).first().uploaded ?: Long.MAX_VALUE }.thenBy { it.index },
            ).map { it.value }
            YtSsiOrigin(order, if (members.size > 1) reasons[root].orEmpty() else emptySet())
        }
    }

    /** Video id → its origin's key, for every video of [origins]. */
    fun byVideo(origins: List<YtSsiOrigin>): Map<String, YtSsiOrigin> = buildMap { for (o in origins) for (v in o.videos) put(v, o) }

    private fun sameChannel(a: Item, b: Item): Boolean {
        if (a.channelId != null && b.channelId != null) return a.channelId == b.channelId
        val na = a.channelName?.let(YtSsiText::fold)
        val nb = b.channelName?.let(YtSsiText::fold)
        return na != null && na.isNotEmpty() && na == nb
    }

    private fun nearDuplicate(a: List<Item>, sa: List<Set<String>>, b: List<Item>, sb: List<Set<String>>): Boolean {
        for ((i, x) in a.withIndex()) {
            for ((j, y) in b.withIndex()) {
                val vx = x.vector
                val vy = y.vector
                if (vx != null && vy != null && vx.size == vy.size && YtSsiVectors.cosine(vx, vy) >= VECTOR_SAME) return true
                if (jaccard(sa[i], sb[j]) >= SHINGLE_SAME) return true
            }
        }
        return false
    }

    /** The folded word [SHINGLE]-grams of [text]; empty when too short to compare. */
    fun shingles(text: String): Set<String> {
        val words = YtSsiText.words(text)
        if (words.size < SHINGLE) return emptySet()
        val out = HashSet<String>()
        for (i in 0..words.size - SHINGLE) out += words.subList(i, i + SHINGLE).joinToString(" ")
        return if (out.size < MIN_SHINGLES) emptySet() else out
    }

    fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val common = a.count { it in b }
        return common.toDouble() / (a.size + b.size - common)
    }

    /** A re-upload: the same folded title (or almost the same words) and the same length, other channel. */
    fun reupload(a: Item, b: Item): Boolean {
        if (sameChannel(a, b)) return false
        val da = a.durationSec ?: return false
        val db = b.durationSec ?: return false
        if (kotlin.math.abs(da - db) > REUPLOAD_SECONDS) return false
        val ta = YtSsiText.words(a.title).toSet()
        val tb = YtSsiText.words(b.title).toSet()
        if (ta.isEmpty() || tb.isEmpty()) return false
        return YtSsiText.fold(a.title) == YtSsiText.fold(b.title) || jaccard(ta, tb) >= 0.8
    }

    private val URL = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"')\]]+""")

    /** Hosts whose links say who made a video, not where its information comes from. */
    private val GENERIC = listOf(
        "patreon.com", "paypal.com", "paypal.me", "instagram.com", "twitter.com", "x.com", "facebook.com", "fb.com", "tiktok.com",
        "discord.gg", "discord.com", "twitch.tv", "linktr.ee", "amzn.to", "amazon.", "spotify.com", "podcasts.apple.com",
        "t.me", "threads.net", "bsky.app", "steadyhq.com", "ko-fi.com", "buymeacoffee.com", "teespring.com", "spreadshirt.",
        "tipeee.com", "gofundme.com", "linkedin.com", "snapchat.com", "reddit.com/r/", "whatsapp.com", "telegram.me",
        "music.apple.com", "soundcloud.com", "shop.", "merch", "nordvpn", "squarespace.com", "skillshare.com", "audible.",
    )

    /**
     * The links in [description], normalised: host without "www."/"m.", no query (a YouTube
     * video link becomes "yt:<id>"), no trailing slash; channel, playlist and profile links left out.
     */
    fun links(description: String): Set<String> {
        val out = LinkedHashSet<String>()
        for (match in URL.findAll(description)) {
            val raw = match.value.trimEnd('.', ',', ';', ':', '!', '?')
            val bare = raw.replaceFirst(Regex("(?i)^https?://"), "")
            val host = bare.substringBefore('/').substringBefore('?').lowercase().removePrefix("www.").removePrefix("m.")
            val path = bare.substringAfter('/', "").substringBefore('#')
            youtubeId(host, path)?.let {
                out += "yt:$it"
                continue
            }
            if (host.endsWith("youtube.com") || host == "youtu.be") continue // channel, playlist, handle
            val full = (host + "/" + path.substringBefore('?')).trimEnd('/').lowercase()
            if (GENERIC.any { full.contains(it) }) continue
            if (host.isEmpty() || !host.contains('.')) continue
            out += full
        }
        return out
    }

    private fun youtubeId(host: String, path: String): String? {
        val id = when {
            host == "youtu.be" -> path.substringBefore('?').substringBefore('/')
            host.endsWith("youtube.com") && path.startsWith("watch") -> Regex("""(?:^|[?&])v=([\w-]{11})""").find(path.substringAfter('?', ""))?.groupValues?.get(1)
            host.endsWith("youtube.com") && (path.startsWith("shorts/") || path.startsWith("live/")) -> path.substringAfter('/').substringBefore('?').substringBefore('/')
            else -> null
        }
        return id?.takeIf { Regex("""[\w-]{11}""").matches(it) }
    }
}
