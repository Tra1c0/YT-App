package com.github.libretube.util

/**
 * YT: the pure text part of the local crash report and the diagnosis page. No Android types, so
 * the formatting, scrubbing and truncation can be tested on the JVM. The owner pastes these texts
 * into a chat, so they stay compact and never carry tokens, cookies or account names.
 */
object YtCrashText {
    /** Upper bound for one stored report; a deep RxJava/coroutine trace is rarely useful beyond. */
    const val MAX_REPORT_CHARS = 8000

    /** How many crashes are kept on disk. */
    const val MAX_REPORTS = 3

    /** Separates the header lines from the stack trace inside a stored report. */
    private const val TRACE_MARKER = "--- trace ---"

    private const val SUMMARY_CHARS = 160

    // key=value pairs and headers whose value may be a secret (query strings in exception messages,
    // cookie headers from a failed request); only the value is replaced so the key stays readable
    private val secretPair = Regex(
        "(?i)\\b(token|access_token|refresh_token|auth|authorization|cookie|set-cookie|sid|" +
            "sapisid|hsid|ssid|apisid|key|api_key|password|passwd|session|login_info)" +
            "(\\s*[=:]\\s*)([^\\s&;,\"']+)"
    )
    private val bearer = Regex("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]+")

    // API keys of the news summaries, wherever they appear in a message: "sk-ant-…", other
    // "sk-…" keys (also "sk-proj-…") and Google keys ("AIza…"); the header forms are below
    private val apiKey = Regex("\\bsk-ant-[A-Za-z0-9_-]+")
    private val skKey = Regex("\\bsk-(?!ant-)[A-Za-z0-9_-]{16,}")
    private val googleKey = Regex("\\bAIza[A-Za-z0-9_-]{20,}")
    private val keyHeader = Regex("(?i)\\b(x-goog-api-key|x-api-key)(\\s*[=:]\\s*)([^\\s&;,\"']+)")

    /** Replaces values that look like secrets with "***". */
    fun scrub(text: String): String = text
        .replace(bearer, "Bearer ***")
        .replace(apiKey, "sk-ant-***")
        .replace(skKey, "sk-***")
        .replace(googleKey, "AIza***")
        .replace(keyHeader) { "${it.groupValues[1]}${it.groupValues[2]}***" }
        .replace(secretPair) { "${it.groupValues[1]}${it.groupValues[2]}***" }

    /**
     * Cuts [text] to at most [max] chars. Keeps the start (the exception and the first frames)
     * and the end (the deepest "Caused by", usually the real reason), dropping the middle.
     */
    fun truncate(text: String, max: Int = MAX_REPORT_CHARS): String {
        if (text.length <= max) return text
        val note = "\n… [${text.length - max} chars cut] …\n"
        val room = (max - note.length).coerceAtLeast(0)
        val head = room * 2 / 3
        val tail = room - head
        return text.take(head) + note + text.takeLast(tail)
    }

    /**
     * One stored crash report: header lines (time, build, device, design, thread) and the stack
     * trace with all causes. The whole text is scrubbed and truncated.
     */
    fun report(header: List<Pair<String, String>>, thread: String, trace: String): String {
        val lines = header.map { (k, v) -> "$k: $v" } + "Thread: $thread"
        val text = lines.joinToString("\n") + "\n" + TRACE_MARKER + "\n" + trace.trimEnd()
        return truncate(scrub(text))
    }

    /** The value of a header line of a stored report, or null. */
    fun field(report: String, key: String): String? = report
        .substringBefore(TRACE_MARKER)
        .lineSequence()
        .firstOrNull { it.startsWith("$key: ") }
        ?.substringAfter(": ")

    /** The first line of the stack trace ("java.lang.X: message"). */
    fun exceptionLine(report: String): String = report
        .substringAfter(TRACE_MARKER, "")
        .lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        .orEmpty()

    /** One line for the diagnosis page: "time, thread: exception", at most a few words long. */
    fun summary(report: String): String {
        val time = field(report, "Time").orEmpty()
        val thread = field(report, "Thread").orEmpty()
        val line = "$time [$thread] ${exceptionLine(report)}".trim()
        return if (line.length <= SUMMARY_CHARS) line else line.take(SUMMARY_CHARS - 1) + "…"
    }

    /**
     * File names to delete so only the newest [max] remain. Names start with the crash time in
     * millis, zero-padded, so their plain sort order is their time order.
     */
    fun toDelete(names: List<String>, max: Int = MAX_REPORTS): List<String> =
        names.sortedDescending().drop(max)

    /** The file name for a crash at [timeMillis]. */
    fun fileName(timeMillis: Long): String = "crash-" + timeMillis.toString().padStart(15, '0') + ".txt"

    /** The diagnosis block: "key: value" lines under a title, empty values shown as "-". */
    fun block(title: String, rows: List<Pair<String, String?>>): String =
        (listOf(title) + rows.map { (k, v) -> "$k: ${v?.ifBlank { null } ?: "-"}" })
            .joinToString("\n")
            .let(::scrub)
}
