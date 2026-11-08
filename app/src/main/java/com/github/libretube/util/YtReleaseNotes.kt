package com.github.libretube.util

/**
 * YT: the pure part of the "what is new" text in the update dialog. A release body is
 * `release-notes/next.md` (user-facing markdown) followed by [DETAILS_MARKER] and the technical
 * lines (build, commit, signer), as CI writes it. Bodies from before the marker existed carry
 * only the technical lines, so they count as "no notes". No Android types: JVM tests.
 */
object YtReleaseNotes {
    const val DETAILS_MARKER = "<!-- yt-details -->"

    /** At most this many builds are fetched and shown. */
    const val MAX_BUILDS = 10

    private val COMMIT = Regex("commit\\s+([0-9a-fA-F]{7,40})")
    private val FINGERPRINT = Regex("SHA-256\\s+((?:[0-9A-Fa-f]{2}:){31}[0-9A-Fa-f]{2})")
    private val BOLD = Regex("\\*\\*(.+?)\\*\\*")
    private val BULLET = Regex("^\\s*[-*•]\\s+(.*)$")
    private val HEADING = Regex("^\\s*#{1,6}(?:\\s+(.*))?$")

    /** The user-facing part of a release body; empty for an old body without the marker. */
    fun userPart(body: String?): String {
        if (body == null) return ""
        val at = body.indexOf(DETAILS_MARKER)
        if (at < 0) return ""
        return body.substring(0, at).trim()
    }

    /** The technical part below the marker; the whole body when there is no marker. */
    fun detailsPart(body: String?): String {
        if (body == null) return ""
        val at = body.indexOf(DETAILS_MARKER)
        return (if (at < 0) body else body.substring(at + DETAILS_MARKER.length)).trim()
    }

    /** The "Keine Notizen." CI writes for a push without notes counts as none here too. */
    fun hasNotes(userPart: String): Boolean =
        userPart.isNotBlank() && userPart.trim().trimEnd('.') != "Keine Notizen"

    /**
     * The builds whose notes the dialog shows: newest first, from [offered] down to the one after
     * [installed], at most [max]. An unnumbered installed build gets only the offered one.
     */
    fun buildsToFetch(installed: Int?, offered: Int, max: Int = MAX_BUILDS): List<Int> {
        if (max <= 0) return emptyList()
        val lowest = if (installed == null) offered else maxOf(installed + 1, offered - max + 1)
        if (lowest > offered) return emptyList()
        return (offered downTo lowest).toList()
    }

    data class BuildNotes(val build: Int, val text: String)

    /**
     * The notes to show: only builds with notes, each once, newest first, at most [max].
     * [bodies] maps a build to its whole release body (missing builds are simply absent).
     */
    fun merge(bodies: Map<Int, String?>, max: Int = MAX_BUILDS): List<BuildNotes> =
        bodies.entries
            .map { (build, body) -> BuildNotes(build, userPart(body)) }
            .filter { hasNotes(it.text) }
            .sortedByDescending { it.build }
            .take(max)

    /** What the details expander shows: the commit and the signer's fingerprint, when given. */
    data class Details(val commit: String?, val fingerprint: String?)

    fun details(body: String?): Details {
        val text = detailsPart(body)
        return Details(
            commit = COMMIT.find(text)?.groupValues?.get(1),
            fingerprint = FINGERPRINT.find(text)?.groupValues?.get(1)?.uppercase(),
        )
    }

    /** "C3:8D:…" against the installed app's signer hashes (lowercase hex without colons). */
    fun fingerprintMatches(fingerprint: String?, installedHashes: Set<String>): Boolean {
        if (fingerprint == null) return false
        val hex = fingerprint.replace(":", "").lowercase()
        return installedHashes.any { it.equals(hex, ignoreCase = true) }
    }

    /** A piece of a line; [bold] for text between `**`. */
    data class Span(val text: String, val bold: Boolean = false)

    sealed interface Block {
        /** "Build 106": one per build, above its notes. */
        data class BuildHeader(val build: Int) : Block

        /** A "## Neu" line. */
        data class Heading(val text: String) : Block

        /** A "- …" line. */
        data class Bullet(val spans: List<Span>) : Block

        /** Any other text line. */
        data class Text(val spans: List<Span>) : Block
    }

    /** Simple markdown: headings, bullets and bold; blank lines and HTML comments are dropped. */
    fun parse(markdown: String): List<Block> {
        val withoutComments = markdown.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        return withoutComments.lines().mapNotNull { raw ->
            val line = raw.trimEnd()
            if (line.isBlank()) return@mapNotNull null
            HEADING.find(line)?.let { m ->
                val text = m.groupValues[1].replace("**", "").trim()
                return@mapNotNull if (text.isEmpty()) null else Block.Heading(text)
            }
            BULLET.find(line)?.let { m ->
                val spans = spans(m.groupValues[1].trim())
                return@mapNotNull if (spans.isEmpty()) null else Block.Bullet(spans)
            }
            spans(line.trim()).takeIf { it.isNotEmpty() }?.let { Block.Text(it) }
        }
    }

    /** All builds as one list: a header per build, then its blocks. */
    fun blocks(notes: List<BuildNotes>): List<Block> =
        notes.flatMap { listOf(Block.BuildHeader(it.build)) + parse(it.text) }

    /** Splits [text] at `**bold**` pairs; an unpaired `**` stays as it is. */
    fun spans(text: String): List<Span> {
        val out = mutableListOf<Span>()
        var at = 0
        for (m in BOLD.findAll(text)) {
            if (m.range.first > at) out += Span(text.substring(at, m.range.first))
            out += Span(m.groupValues[1], bold = true)
            at = m.range.last + 1
        }
        if (at < text.length) out += Span(text.substring(at))
        return out.filter { it.text.isNotEmpty() }
    }
}
