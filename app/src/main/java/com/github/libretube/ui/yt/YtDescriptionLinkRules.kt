package com.github.libretube.ui.yt

import java.net.URI
import java.net.URLDecoder

/**
 * YT: pure rules (no Android) that read a video description and sort its links by what a tap
 * does: timestamps (seek), channels (open the channel), videos and playlists (play / open) and
 * external links (browser). Ad, sponsor and affiliate links are found by their address
 * (affiliate hosts and parameters) and by the words around them ("sponsored by", "use code X",
 * "Werbung", "Anzeige", "*Affiliate-Link", ...); they leave their type group and go into a group
 * of their own, and [Overview.sponsorLines] names the lines of the text that carry them, so the
 * description itself can fold them away.
 *
 * The description comes either as HTML (NewPipe's attributed description: `<a href>` and `<br>`)
 * or as plain text. The HTML is read the way the page's TextView renders it (`<br>` breaks a
 * line, raw line breaks are spaces), so the line numbers match the rendered text.
 */
object YtDescriptionLinkRules {
    enum class Kind { TIMESTAMP, CHANNEL, VIDEO, PLAYLIST, EXTERNAL }

    /** A channel to open: [id] is a "UC…" id; otherwise [name] is a handle ("@x") or a custom name. */
    data class Channel(val id: String?, val name: String?)

    data class Link(
        val kind: Kind,
        /** What the row shows: the words around the link, the link's own words or a short address. */
        val label: String,
        /** The full address (null for a plain-text timestamp). */
        val url: String?,
        /** Short address for a second line ("amzn.to/3abc"), null when the label already is it. */
        val detail: String? = null,
        val seconds: Long? = null,
        val channel: Channel? = null,
        val videoId: String? = null,
        val playlistId: String? = null,
        val sponsor: Boolean = false,
        /** Line of [Overview.lines] the link stands on. */
        val line: Int,
    )

    data class Overview(
        val timestamps: List<Link>,
        val channels: List<Link>,
        val videos: List<Link>,
        val external: List<Link>,
        val sponsored: List<Link>,
        /** Lines that belong to ads (the link lines and their "Sponsored by"/"use code" lines). */
        val sponsorLines: Set<Int>,
        /** The description as rendered, one entry per line. */
        val lines: List<String>,
    ) {
        val isEmpty: Boolean
            get() = timestamps.isEmpty() && channels.isEmpty() && videos.isEmpty() &&
                external.isEmpty() && sponsored.isEmpty()
    }

    /** A link as found in the text, before it is sorted. */
    internal data class RawLink(val url: String?, val text: String, val line: Int, val anchored: Boolean)

    internal data class Parsed(val lines: List<String>, val links: List<RawLink>)

    /** Same test the pages use to choose the HTML renderer. */
    fun isHtml(description: String) = description.contains("<") && description.contains(">")

    /**
     * Sorts the links of [description]. [durationSeconds] (0 = unknown) drops times past the end
     * of the video, which are clock times or dates rather than chapters.
     */
    fun overview(description: String, durationSeconds: Long = 0): Overview {
        val parsed = parse(description)
        val lines = parsed.lines
        val cues = lines.map { cueOf(it) }
        val starDisclosure = lines.any { it.contains('*') && STAR_DISCLOSURE.containsMatchIn(it) }
        val lineHasLink = BooleanArray(lines.size)
        parsed.links.forEach { if (it.url != null) lineHasLink[it.line] = true }
        val paragraph = paragraphs(lines)

        val timestamps = mutableListOf<Link>()
        val channels = mutableListOf<Link>()
        val videos = mutableListOf<Link>()
        val external = mutableListOf<Link>()
        val sponsored = mutableListOf<Link>()
        val sponsorLines = sortedSetOf<Int>()
        val seen = HashSet<String>()

        // timestamps: links whose words are a time (YouTube links them), plain times at the
        // start or end of a line
        val timeLines = HashSet<Int>()
        for (raw in parsed.links) {
            if (!raw.anchored) continue
            val seconds = timeOf(raw.text.trim()) ?: continue
            if (durationSeconds > 0 && seconds > durationSeconds) continue
            if (raw.line in timeLines) continue
            timeLines += raw.line
            timestamps += Link(Kind.TIMESTAMP, timeLabel(lines[raw.line]), raw.url, seconds = seconds, line = raw.line)
        }
        if (!isHtml(description)) {
            lines.forEachIndexed { index, line ->
                if (index in timeLines) return@forEachIndexed
                val seconds = edgeTime(line) ?: return@forEachIndexed
                if (durationSeconds > 0 && seconds > durationSeconds) return@forEachIndexed
                timeLines += index
                timestamps += Link(Kind.TIMESTAMP, timeLabel(line), null, seconds = seconds, line = index)
            }
        }
        timestamps.sortBy { it.line }

        for (raw in parsed.links) {
            val url = raw.url ?: continue
            if (raw.anchored && timeOf(raw.text.trim()) != null) continue
            val target = classify(url) ?: continue
            val key = target.key
            if (!seen.add(key)) continue
            val sponsor = isSponsor(raw.line, url, raw.text, lines, cues, paragraph, lineHasLink, starDisclosure, sponsorLines)
            val link = Link(
                kind = target.kind,
                label = labelFor(raw, lines[raw.line], target),
                url = target.url,
                detail = shortAddress(target.url),
                channel = target.channel,
                videoId = target.videoId,
                playlistId = target.playlistId,
                sponsor = sponsor,
                line = raw.line,
            ).let { if (it.kind == Kind.CHANNEL) it.copy(detail = it.channel?.name ?: it.detail) else it }
                .let { if (it.detail == it.label) it.copy(detail = null) else it }
            when {
                sponsor -> sponsored += link
                link.kind == Kind.CHANNEL -> channels += link
                link.kind == Kind.VIDEO || link.kind == Kind.PLAYLIST -> videos += link
                else -> external += link
            }
        }
        return Overview(timestamps, channels, videos, external, sponsored, sponsorLines, lines)
    }

    // -----------------------------------------------------------------------------------------
    // Reading the text

    internal fun parse(description: String): Parsed =
        if (isHtml(description)) parseHtml(description) else parsePlain(description)

    private fun parsePlain(description: String): Parsed {
        val lines = description.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val links = mutableListOf<RawLink>()
        lines.forEachIndexed { index, line ->
            val taken = mutableListOf<IntRange>()
            for (match in URL_IN_TEXT.findAll(line)) {
                val text = trimUrlEnd(match.value)
                if (text.isEmpty() || taken.any { it.first <= match.range.first && match.range.first <= it.last }) continue
                // an e-mail address is no web link
                if (match.range.first > 0 && line[match.range.first - 1] == '@') continue
                taken += match.range.first until match.range.first + text.length
                val url = if (text.startsWith("http://", true) || text.startsWith("https://", true)) text else "https://$text"
                links += RawLink(url, text, index, anchored = false)
            }
            // a channel named by its handle, unless the line is about another network
            if (!OTHER_NETWORK.containsMatchIn(line)) {
                for (match in HANDLE.findAll(line)) {
                    if (taken.any { match.range.first in it }) continue
                    val handle = match.groupValues[1].trimEnd('.', '-', '_')
                    if (handle.length < 3) continue
                    links += RawLink("https://www.youtube.com/@$handle", "@$handle", index, anchored = false)
                }
            }
        }
        return Parsed(lines, links.sortedBy { it.line })
    }

    private fun parseHtml(description: String): Parsed {
        val lines = mutableListOf<String>()
        val links = mutableListOf<RawLink>()
        val current = StringBuilder()
        var href: String? = null
        var anchorText: StringBuilder? = null
        var anchorLine = 0
        var i = 0
        fun text(raw: String) {
            // raw line breaks are white space in HTML
            val decoded = decodeEntities(raw.replace('\n', ' ').replace('\r', ' '))
            current.append(decoded)
            anchorText?.append(decoded)
        }
        while (i < description.length) {
            val open = description.indexOf('<', i)
            if (open < 0) {
                text(description.substring(i))
                break
            }
            if (open > i) text(description.substring(i, open))
            val close = description.indexOf('>', open)
            if (close < 0) {
                text(description.substring(open))
                break
            }
            val tag = description.substring(open + 1, close).trim()
            val name = tag.trimStart('/').takeWhile { it.isLetterOrDigit() }.lowercase()
            val closing = tag.startsWith("/")
            when {
                name == "br" -> {
                    lines += current.toString()
                    current.clear()
                }
                name == "p" && closing -> {
                    lines += current.toString()
                    current.clear()
                }
                name == "a" && !closing -> {
                    href = HREF.find(tag)?.let { m -> decodeEntities(m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }.orEmpty()) }
                    anchorText = StringBuilder()
                    anchorLine = lines.size
                }
                name == "a" && closing -> {
                    val words = anchorText?.toString().orEmpty()
                    if (anchorText != null) links += RawLink(href?.trim(), words, anchorLine, anchored = true)
                    href = null
                    anchorText = null
                }
            }
            i = close + 1
        }
        lines += current.toString()
        return Parsed(lines.map { normalizeSpace(it) }, links)
    }

    private fun decodeEntities(text: String): String {
        if (!text.contains('&')) return text
        return ENTITY.replace(text) { match ->
            val body = match.groupValues[1]
            when {
                body.startsWith("#x", true) -> body.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                body.startsWith("#") -> body.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                else -> NAMED_ENTITIES[body.lowercase()]
            } ?: match.value
        }
    }

    /** Collapses runs of white space (also no-break spaces) the way the rendered text shows them. */
    fun normalizeSpace(text: String): String = text.replace(SPACES, " ").trim()

    private fun trimUrlEnd(url: String): String {
        var end = url.length
        while (end > 0) {
            val c = url[end - 1]
            val drop = when (c) {
                '.', ',', ';', ':', '!', '?', '\'', '"', '»', '”', '“', '*' -> true
                ')' -> url.count { it == '(' } < url.substring(0, end).count { it == ')' }
                ']' -> !url.contains('[')
                else -> false
            }
            if (!drop) break
            end--
        }
        return url.substring(0, end)
    }

    // -----------------------------------------------------------------------------------------
    // Times

    /** Seconds of "1:02:03" / "12:34" / "0:05", or null when [text] is no time. */
    fun timeOf(text: String): Long? {
        val match = TIME_EXACT.matchEntire(text) ?: return null
        return seconds(match.groupValues[1], match.groupValues[2], match.groupValues[3])
    }

    private fun seconds(hours: String, minutes: String, secs: String): Long? {
        val h = hours.toLongOrNull() ?: 0
        val m = minutes.toLongOrNull() ?: return null
        val s = secs.toLongOrNull() ?: return null
        if (s >= 60) return null
        if (hours.isNotEmpty() && m >= 60) return null
        return h * 3600 + m * 60 + s
    }

    /** A time at the very start of a line (after a bullet) or at its very end. */
    private fun edgeTime(line: String): Long? {
        val start = TIME_AT_START.find(line)
        if (start != null) return seconds(start.groupValues[1], start.groupValues[2], start.groupValues[3])
        val end = TIME_AT_END.find(line) ?: return null
        return seconds(end.groupValues[1], end.groupValues[2], end.groupValues[3])
    }

    /** The chapter title of a timestamp line: the line without its times and separators. */
    fun timeLabel(line: String): String =
        normalizeSpace(TIME_ANY.replace(line, " ")).trim(*LABEL_TRIM)

    // -----------------------------------------------------------------------------------------
    // Addresses

    internal data class Target(
        val kind: Kind,
        val url: String,
        val key: String,
        val channel: Channel? = null,
        val videoId: String? = null,
        val playlistId: String? = null,
    )

    private class Address(val host: String, val segments: List<String>, val query: Map<String, String>)

    private fun address(url: String): Address? {
        val withScheme = if (url.contains("://")) url else "https://$url"
        val uri = runCatching { URI(withScheme.replace(" ", "%20")) }.getOrNull()
        val host: String
        val path: String
        val rawQuery: String
        if (uri?.host != null) {
            host = uri.host
            path = uri.rawPath.orEmpty()
            rawQuery = uri.rawQuery.orEmpty()
        } else {
            // unusual characters: read the parts by hand
            val match = LOOSE_URL.matchEntire(withScheme) ?: return null
            host = match.groupValues[1]
            path = match.groupValues[2]
            rawQuery = match.groupValues[3]
        }
        val query = rawQuery.split('&').filter { it.isNotEmpty() }.associate { pair ->
            val key = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            decode(key).lowercase() to decode(value)
        }
        return Address(host.lowercase().removePrefix("www."), path.split('/').filter { it.isNotEmpty() }.map { decode(it) }, query)
    }

    private fun decode(text: String) = runCatching { URLDecoder.decode(text, "UTF-8") }.getOrDefault(text)

    private fun isYouTube(host: String) =
        host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be" || host == "youtube-nocookie.com"

    /** What a tap on [url] opens; null for links that lead nowhere useful (hashtags, searches). */
    internal fun classify(url: String): Target? {
        if (url.startsWith("/") && !url.startsWith("//")) return classify("https://www.youtube.com$url")
        val address = address(url) ?: return null
        if (isYouTube(address.host) && address.segments.firstOrNull() == "redirect") {
            val target = address.query["q"] ?: return null
            return classify(target)
        }
        if (!isYouTube(address.host)) {
            if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) return null
            return Target(Kind.EXTERNAL, url, "x:" + url.trimEnd('/').lowercase())
        }
        val first = address.segments.firstOrNull()
        val second = address.segments.getOrNull(1)
        if (address.host == "youtu.be") {
            val id = first ?: return null
            return video(id, address)
        }
        return when {
            first == null -> Target(Kind.EXTERNAL, url, "x:" + url.trimEnd('/').lowercase())
            first == "watch" -> address.query["v"]?.takeIf { it.isNotBlank() }?.let { video(it, address) }
            first in VIDEO_PATHS && second != null -> video(second, address)
            first == "playlist" -> address.query["list"]?.takeIf { it.isNotBlank() }?.let {
                Target(Kind.PLAYLIST, "https://www.youtube.com/playlist?list=$it", "p:$it", playlistId = it)
            }
            first.startsWith("@") && first.length > 1 ->
                channel(Channel(null, first), "https://www.youtube.com/$first")
            first == "channel" && second != null ->
                channel(Channel(second, null), "https://www.youtube.com/channel/$second")
            (first == "c" || first == "user") && second != null ->
                channel(Channel(null, second), "https://www.youtube.com/$first/$second")
            first in NOWHERE -> null
            address.segments.size == 1 && first !in RESERVED ->
                channel(Channel(null, first), "https://www.youtube.com/$first")
            else -> Target(Kind.EXTERNAL, url, "x:" + url.trimEnd('/').lowercase())
        }
    }

    private fun video(id: String, address: Address): Target {
        val t = address.query["t"]?.let { tSeconds(it) }
        val playUrl = "https://www.youtube.com/watch?v=$id" + (t?.let { "&t=$it" } ?: "")
        return Target(Kind.VIDEO, playUrl, "v:$id", videoId = id)
    }

    private fun channel(channel: Channel, url: String) =
        Target(Kind.CHANNEL, url, "c:" + (channel.id ?: channel.name.orEmpty()).lowercase(), channel = channel)

    /** "90", "90s", "1m30s", "1h2m3s" in seconds. */
    internal fun tSeconds(value: String): Long? {
        value.toLongOrNull()?.let { return it }
        val match = T_PARAM.matchEntire(value) ?: return null
        val (h, m, s) = match.destructured
        if (h.isEmpty() && m.isEmpty() && s.isEmpty()) return null
        return (h.toLongOrNull() ?: 0) * 3600 + (m.toLongOrNull() ?: 0) * 60 + (s.toLongOrNull() ?: 0)
    }

    /** "amzn.to/3abc" for a row's second line; long paths are cut. */
    fun shortAddress(url: String): String {
        val noScheme = url.substringAfter("://").removePrefix("www.").trimEnd('/')
        return if (noScheme.length <= 42) noScheme else noScheme.take(41) + "…"
    }

    private fun looksLikeAddress(text: String) =
        text.contains("://") || text.startsWith("www.", true) || BARE_DOMAIN.matches(text.trim().trimEnd('/', '…', '.'))

    private fun labelFor(raw: RawLink, line: String, target: Target): String {
        val words = normalizeSpace(raw.text).trimEnd('…')
        if (target.kind == Kind.CHANNEL && (words.startsWith("@") || (words.isNotEmpty() && !looksLikeAddress(words)))) {
            return words
        }
        if (raw.anchored && words.isNotEmpty() && !looksLikeAddress(words)) return words
        // the words around the link: "Instagram: https://…" -> "Instagram"
        val around = normalizeSpace(line.replace(raw.text, " ")).trim(*LABEL_TRIM)
        if (around.isNotEmpty() && around.length <= 80 && !looksLikeAddress(around)) return around
        return target.channel?.name ?: shortAddress(target.url)
    }

    // -----------------------------------------------------------------------------------------
    // Ads, sponsors, affiliates

    internal enum class Cue {
        /** A sponsor, ad or affiliate statement ("Sponsored by", "Werbung", "*Affiliate-Link"). */
        STATEMENT,

        /** A discount offer ("use code MAX10", "20 % Rabatt", "promo code"). */
        OFFER,
    }

    /** Whether [line] says ads, a sponsor, an affiliate or a discount offer. */
    internal fun cueOf(line: String): Cue? {
        if (line.isBlank()) return null
        if (NEGATED.containsMatchIn(line)) return null
        if (STATEMENT_CUES.any { it.containsMatchIn(line) }) return Cue.STATEMENT
        if (!SUPPORT.containsMatchIn(line) && SPONSOR_WORD_CUES.any { it.containsMatchIn(line) }) return Cue.STATEMENT
        if (OFFER_CUES.any { it.containsMatchIn(line) } || hasCode(line)) return Cue.OFFER
        return null
    }

    /**
     * "use code MAX10", "mit dem Code LINUS", "Code: SPAREN20": a word "code" after an offer verb
     * (or "promo"/"Rabatt") followed by a token in capitals or digits. "Source code", "QR code",
     * "Code: https://github.com/…" are no offers.
     */
    internal fun hasCode(line: String): Boolean {
        for (match in CODE_WORD.findAll(line)) {
            val after = line.substring(match.range.last + 1)
            val token = CODE_TOKEN.find(after)?.groupValues?.get(1) ?: continue
            if (!token.any { it.isUpperCase() || it.isDigit() } || token.any { it.isLowerCase() }) continue
            if (token.length < 3 || token in NOT_CODES) continue
            val before = line.substring(0, match.range.first).takeLast(40)
            if (CODE_VERB.containsMatchIn(before) || OFFER_WORDS.containsMatchIn(line)) return true
        }
        return false
    }

    /** Affiliate addresses: short links of shops' partner programmes and partner parameters. */
    fun isAffiliateUrl(url: String): Boolean {
        val address = address(url) ?: return false
        val host = address.host
        if (AFFILIATE_HOSTS.any { host == it || host.endsWith(".$it") }) return true
        val amazon = AMAZON.matches(host)
        if (amazon && (address.query.containsKey("tag") || address.segments.firstOrNull() == "shop")) return true
        if (address.query.keys.any { it in AFFILIATE_PARAMS }) return true
        if (address.query.any { (key, value) -> key.startsWith("utm_") && AFFILIATE_UTM.containsMatchIn(value) }) return true
        return false
    }

    /** Blank lines split the text into paragraphs; the result holds each line's paragraph number. */
    private fun paragraphs(lines: List<String>): IntArray {
        val result = IntArray(lines.size)
        var number = 0
        lines.forEachIndexed { index, line ->
            if (line.isBlank()) number++
            result[index] = number
        }
        return result
    }

    private fun isSponsor(
        line: Int,
        url: String,
        words: String,
        lines: List<String>,
        cues: List<Cue?>,
        paragraph: IntArray,
        lineHasLink: BooleanArray,
        starDisclosure: Boolean,
        sponsorLines: MutableSet<Int>,
    ): Boolean {
        val text = lines[line]
        var hit = false
        if (isAffiliateUrl(url)) hit = true
        if (cues[line] != null) hit = true
        // links marked with a star, when the description explains the star as an ad/affiliate mark
        if (starDisclosure && hasStar(text.replace(words, " "))) hit = true
        // "use code X" on the line below the link
        val below = line + 1
        if (below < lines.size && paragraph[below] == paragraph[line] && cues[below] == Cue.OFFER && !lineHasLink[below]) {
            hit = true
            sponsorLines += below
        }
        // a heading above ("Sponsored by:", "Werbung:", "Meine Ausrüstung (Affiliate-Links):")
        // covers the link lines below it in the same paragraph
        var above = line - 1
        while (above >= 0 && line - above <= HEADING_REACH && paragraph[above] == paragraph[line]) {
            val cue = cues[above]
            val starNote = starDisclosure && lines[above].contains('*')
            if (cue != null && !lineHasLink[above] && !starNote && looksLikeHeading(lines[above])) {
                hit = true
                for (l in above until line) sponsorLines += l
                break
            }
            above--
        }
        if (hit) sponsorLines += line
        return hit
    }

    /** A star that marks a link ("Kamera*:", "link *"), not a bullet at the start of the line. */
    private fun hasStar(text: String) = text.replaceFirst(LEADING_BULLET, "").contains('*')

    /** A short line or one that ends with a colon introduces the lines below it; prose does not. */
    private fun looksLikeHeading(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.length <= 80 || trimmed.endsWith(":")
    }

    /**
     * The character ranges of [rendered] (the description as the TextView shows it) to fold
     * away: whole lines that match the sponsor lines of [overview], merged where they touch.
     * Lines are matched by their text, in order, so a renderer that differs a little in its
     * spacing still finds them; a line it cannot find stays visible.
     */
    fun foldRanges(rendered: String, overview: Overview): List<IntRange> {
        if (overview.sponsorLines.isEmpty()) return emptyList()
        // start and end offsets of every rendered line
        val starts = mutableListOf(0)
        rendered.forEachIndexed { index, c -> if (c == '\n') starts += index + 1 }
        val renderedLines = starts.mapIndexed { i, start ->
            val end = if (i + 1 < starts.size) starts[i + 1] - 1 else rendered.length
            start until end
        }
        val keys = renderedLines.map { normalizeSpace(rendered.substring(it.first, it.last + 1)) }
        val hits = sortedSetOf<Int>()
        var from = 0
        for (index in overview.sponsorLines.sorted()) {
            val want = normalizeSpace(overview.lines.getOrNull(index) ?: continue)
            if (want.isEmpty()) continue
            val found = (from until keys.size).firstOrNull { keys[it] == want } ?: continue
            hits += found
            from = found + 1
        }
        val result = mutableListOf<IntRange>()
        var runStart = -1
        var runEnd = -1
        for (i in hits) {
            if (runStart >= 0 && i == runEnd + 1) {
                runEnd = i
            } else {
                if (runStart >= 0) result += renderedLines[runStart].first..renderedLines[runEnd].last
                runStart = i
                runEnd = i
            }
        }
        if (runStart >= 0) result += renderedLines[runStart].first..renderedLines[runEnd].last
        return result.filter { it.last >= it.first }
    }

    // -----------------------------------------------------------------------------------------

    private const val HEADING_REACH = 12
    private val LEADING_BULLET = Regex("""^\s*\*\s+""")
    private val LABEL_TRIM = charArrayOf(' ', '-', '–', '—', ':', '|', '•', '·', '(', ')', '[', ']', '>', '»', '*', ',', '/', '=', '.', ';')
    private val SPACES = Regex("[\\s\\u00A0\\u2007\\u202F]+")
    private val ENTITY = Regex("&(#?[A-Za-z0-9]{1,8});")
    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "#39" to "'", "ndash" to "–", "mdash" to "—", "hellip" to "…",
    )
    private val HREF = Regex("""href\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)

    private val TIME_EXACT = Regex("""(?:(\d{1,2}):)?(\d{1,3}):(\d{2})""")
    private const val TIME_CORE = """(?:(\d{1,2}):)?(\d{1,3}):(\d{2})"""
    private val TIME_AT_START = Regex("""^\s*(?:[-–•·*>▶►]\s*)?[(\[]?$TIME_CORE(?![\d:])[)\]]?(?!\s*(?:uhr|am\b|pm\b|h\b|Uhr))""", RegexOption.IGNORE_CASE)
    private val TIME_AT_END = Regex("""(?<![\d:.,/])[(\[]?$TIME_CORE[)\]]?\s*$""")
    private val TIME_ANY = Regex("""[(\[]?(?<![\d:])(?:\d{1,2}:)?\d{1,3}:\d{2}(?![\d:])[)\]]?""")

    private val URL_IN_TEXT = Regex(
        """(?:https?://[^\s<>"]+)|(?:www\.[^\s<>"]+)|(?<![\w@./-])(?:[a-z0-9-]+\.)+(?:com|de|to|ly|me|io|gg|tv|net|org|co|link|shop|store|eu|at|ch|fm|app|us|uk|page|so|xyz|info|ws|it|fr|nl)/[^\s<>"]*""",
        RegexOption.IGNORE_CASE,
    )
    private val BARE_DOMAIN = Regex("""(?:[a-z0-9-]+\.)+[a-z]{2,}(?:/\S*)?""", RegexOption.IGNORE_CASE)
    private val LOOSE_URL = Regex("""(?i)[a-z][a-z0-9+.-]*://([^/?#\s]+)([^?#]*)\??([^#]*).*""")
    private val HANDLE = Regex("""(?<![\w@./:+-])@([A-Za-z0-9][A-Za-z0-9._-]{1,29})(?![\w@])""")
    private val OTHER_NETWORK = Regex(
        """\b(twitter|instagram|insta|tiktok|x\.com|threads|twitch|mastodon|bluesky|bsky|telegram|discord|snapchat|facebook|ig|tt|kick|reddit|e-?mail|mail)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val T_PARAM = Regex("""(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s?)?""")

    private val VIDEO_PATHS = setOf("shorts", "live", "embed", "v", "e")
    private val NOWHERE = setOf("hashtag", "results", "feed", "signin", "logout", "attribution_link")
    private val RESERVED = setOf(
        "about", "premium", "gaming", "kids", "account", "upload", "music", "t", "howyoutubeworks",
        "creators", "ads", "new", "help", "trends", "jobs", "yt", "watch", "playlist", "redirect",
        "channel", "c", "user", "shorts", "live", "embed", "s", "store", "movies", "podcasts",
    )

    private val NEGATED = Regex(
        """\b(keine|kein|ohne|nicht|not|no|non|unsponsored|unsponsert)[- ]+(werbung|sponsor\w*|gesponsert|affiliate\w*|anzeige|bezahlt\w*|paid)""",
        RegexOption.IGNORE_CASE,
    )
    /** Cues that only say "sponsor": on a line about Patreon or channel membership they are support, not ads. */
    private val SPONSOR_WORD_CUES = listOf(
        Regex("""\b(today'?s|this video'?s|our|video|episode|main|thanks? (to )?(our )?)\s+sponsors?\b""", RegexOption.IGNORE_CASE),
        Regex("""^\W*sponsors?\s*[:(\-–]""", RegexOption.IGNORE_CASE),
        Regex("""\b(unser|unsere[nm]?|unseres|heutige[nmr]?|dieses videos?)\s+sponsors?\b""", RegexOption.IGNORE_CASE),
    )
    private val SUPPORT = Regex(
        """\b(patreon|github sponsors?|ko-?fi|steady(hq)?|buy ?me ?a ?coffee|kanalmitglied\w*|channel member\w*|membership|become a sponsor|sponsor (me|us|this project|on github)|unterstütz\w*|support (me|us|the channel))\b""",
        RegexOption.IGNORE_CASE,
    )
    private val STATEMENT_CUES = listOf(
        // English
        Regex("""\bsponsored\b""", RegexOption.IGNORE_CASE),
        Regex("""\bfor sponsoring\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(paid promotion|paid partnership|ad|advert|advertisement|promo)\s*[:)\]|]""", RegexOption.IGNORE_CASE),
        Regex("""^\W*(#ad|\[ad]|\(ad\))(\W|$)""", RegexOption.IGNORE_CASE),
        Regex("""\baffiliate\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(amazon associate|qualifying purchases|earn (a small )?commission|(small )?commission (at no|for me))""", RegexOption.IGNORE_CASE),
        // German
        Regex("""\bgesponsert\b""", RegexOption.IGNORE_CASE),
        Regex("""(^\W*|[#*(\[|]\s*)Werbung\b|\bWerbung\s*([:)\]|*]|$)""", RegexOption.IGNORE_CASE),
        Regex("""\b(enthält|beinhaltet|contains|includes)\s+(\w+\s+)?(werbung|paid promotion|ads|advertising|produktplatzierung)\b""", RegexOption.IGNORE_CASE),
        Regex("""(^\W*|[#*(\[]\s*)Anzeige\b|\bAnzeige\s*[:)\]|*]""", RegexOption.IGNORE_CASE),
        Regex("""\b(werbe-?links?|werbepartner\w*|partner-?links?|provisions-?links?|empfehlungs-?links?|affiliate-?links?|produktplatzierung|bezahlte partnerschaft|unbezahlte werbung|dauerwerbesendung)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(amazon-?partner|erhalte ich (eine|eine kleine) provision|provision erhalten)\b""", RegexOption.IGNORE_CASE),
    )
    private val OFFER_CUES = listOf(
        Regex("""\b(promo|discount|coupon|voucher|rabatt|gutschein)[- ]?codes?\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(rabattcode|gutscheincode|rabatt-code|gutschein-code)\b""", RegexOption.IGNORE_CASE),
        Regex("""\d+\s?%\s*(off|discount|rabatt|günstiger|sparen|reduziert)""", RegexOption.IGNORE_CASE),
        Regex("""\b(save|spare|get|bekomm\w*|erhalte\w*|sichere? dir)\s+(\w+\s+){0,3}\d+\s?(%|€|\$|euro|dollar)""", RegexOption.IGNORE_CASE),
        Regex("""\b(free trial|kostenlos(e|en)? (testen|probemonat|testmonat)|gratis testen|first month free|ersten monat gratis)\b""", RegexOption.IGNORE_CASE),
    )
    private val CODE_WORD = Regex("""\bcodes?\b""", RegexOption.IGNORE_CASE)
    private val CODE_TOKEN = Regex("""^\s*[:=]?\s*["'„“‚‘«»]?\s*([A-Za-z0-9_-]{2,24})""")
    private val CODE_VERB = Regex(
        """\b(use|using|enter|apply|with|redeem|mit|nutze|nutzt|benutze|benutzt|verwende|verwendet|gib|gebt|dem|den|promo|rabatt|gutschein|discount|coupon)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val OFFER_WORDS = Regex("""(%|€|\$|\boff\b|\bdiscount\b|\brabatt|\bspar|\bsave\b|\bfree\b|\bgratis\b|\bgünstiger\b)""", RegexOption.IGNORE_CASE)
    private val NOT_CODES = setOf("HTML", "CSS", "API", "SDK", "QR", "PDF", "JSON", "XML", "GPL", "MIT", "BSD", "AGPL", "VS", "README")
    private val STAR_DISCLOSURE = Regex(
        """(affiliate|werbe|partner|provision|amazon|anzeige|werbung|commission|sponsor)""",
        RegexOption.IGNORE_CASE,
    )

    private val AFFILIATE_HOSTS = listOf(
        "amzn.to", "amzn.eu", "amzn.com", "a.co", "geni.us", "go.skimresources.com", "shareasale.com",
        "click.linksynergy.com", "awin1.com", "prf.hn", "rstyle.me", "shopstyle.it", "howl.me", "tidd.ly",
        "liketk.it", "kit.co", "pxf.io", "sjv.io", "tkqlhce.com", "anrdoezrs.net", "jdoqocy.com",
        "dpbolvw.net", "kqzyfj.com", "s.click.aliexpress.com", "ebay.us", "go.nordvpn.net",
        "linkby.com", "partner.bol.com", "ad.admitad.com", "clk.tradedoubler.com",
    )
    private val AMAZON = Regex("""(?:.+\.)?amazon\.(?:com|de|co\.uk|fr|it|es|ca|nl|se|pl|at|com\.au|co\.jp|in)""")
    private val AFFILIATE_PARAMS = setOf(
        "aff", "aff_id", "affid", "affiliate", "affiliate_id", "affiliateid", "aff_sub", "irclickid",
        "irgwc", "ref_partner", "partnerid", "partner_id", "coupon", "couponcode", "promo", "promocode",
        "discount", "discountcode", "voucher", "gutschein", "rabattcode",
    )
    private val AFFILIATE_UTM = Regex("""affiliate|sponsor|influencer|partnership|paid""", RegexOption.IGNORE_CASE)
}
