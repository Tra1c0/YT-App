package com.github.libretube.util

/**
 * YT: the pure decisions of the background update check ([com.github.libretube.workers.YtUpdateCheckWorker]):
 * the check interval, whether a found build gets a notification, and the notification's text
 * from the release notes. No Android types, so they run in JVM tests.
 */
object YtUpdateNotifyRules {
    /** Switch "notify about new versions" (default on). */
    const val KEY_ENABLED = "yt_update_notify"

    /** Interval in hours as a string ("3", "6", "12", "24"). */
    const val KEY_INTERVAL = "yt_update_notify_interval"

    /** The row that asks for the notification permission; shown only while it is missing. */
    const val KEY_ALLOW = "yt_update_notify_allow"

    const val DEFAULT_ENABLED = true
    const val DEFAULT_HOURS = 6
    val CHOICES_HOURS = listOf(3, 6, 12, 24)

    /** WorkManager does not run periodic work more often than this. */
    const val MIN_INTERVAL_MINUTES = 15L

    /** Lines of the notes in the collapsed and in the expanded notification. */
    const val SHORT_LINES = 2
    const val LONG_LINES = 5

    /** The interval setting as hours; anything not offered falls back to the default. */
    fun intervalHours(value: String?): Int =
        value?.trim()?.toIntOrNull()?.takeIf { it in CHOICES_HOURS } ?: DEFAULT_HOURS

    /** The repeat interval for WorkManager, never below its minimum. */
    fun intervalMinutes(hours: Int): Long = maxOf(hours * 60L, MIN_INTERVAL_MINUTES)

    /**
     * Whether the found build [offered] gets a notification: only a build newer than the
     * [installed] one (the rule of the in-app check, [YtUpdateRules.isNewer]) and newer than the
     * one notified last ([lastNotified]), so each build is told once and an older or pulled
     * release never comes back.
     */
    fun shouldNotify(installed: Int?, offered: Int?, lastNotified: Int?): Boolean {
        if (offered == null || !YtUpdateRules.isNewer(installed, offered)) return false
        return lastNotified == null || offered > lastNotified
    }

    /** A notification about [notified] is out of date once that build (or a newer one) is installed. */
    fun isOutdated(installed: Int?, notified: Int?): Boolean =
        notified != null && installed != null && notified <= installed

    /**
     * The periodic work is enqueued again with the new interval ("update") only when it differs
     * from the one it was scheduled with; otherwise the existing schedule is kept ("keep").
     */
    fun mustReplace(scheduledHours: Int?, wantedHours: Int): Boolean =
        scheduledHours != null && scheduledHours != wantedHours

    /**
     * The notification text of a release body: [short] for the collapsed view (the first lines
     * joined), [long] for the expanded one (up to [longLines] lines as bullets, "…" when more
     * follow). Null when the body has no user notes (old body, "Keine Notizen."): the caller then
     * says "new version available".
     */
    data class Text(val short: String, val long: String)

    fun notificationText(
        body: String?,
        shortLines: Int = SHORT_LINES,
        longLines: Int = LONG_LINES,
    ): Text? {
        val user = YtReleaseNotes.userPart(body)
        if (!YtReleaseNotes.hasNotes(user)) return null
        val lines = noteLines(user)
        if (lines.isEmpty()) return null
        val short = lines.take(shortLines.coerceAtLeast(1)).joinToString(" · ")
        val shown = lines.take(longLines.coerceAtLeast(1))
        val long = buildString {
            append(shown.joinToString("\n") { "• $it" })
            if (lines.size > shown.size) append("\n…")
        }
        return Text(short, long)
    }

    /**
     * The note lines of the user part: bullets first; without any bullet, the plain text lines.
     * Headings ("Neu", "Behoben") are left out, bold marks become plain text.
     */
    fun noteLines(userPart: String): List<String> {
        val blocks = YtReleaseNotes.parse(userPart)
        fun text(spans: List<YtReleaseNotes.Span>) =
            spans.joinToString("") { it.text }.replace(Regex("\\s+"), " ").trim()
        val bullets = blocks.filterIsInstance<YtReleaseNotes.Block.Bullet>()
            .map { text(it.spans) }.filter { it.isNotEmpty() }
        if (bullets.isNotEmpty()) return bullets
        return blocks.filterIsInstance<YtReleaseNotes.Block.Text>()
            .map { text(it.spans) }.filter { it.isNotEmpty() }
    }

    /** What a run found, as the diagnosis page shows it. */
    sealed interface Outcome {
        /** No newer build. */
        data object None : Outcome

        /** A newer build; [notice] says what happened with its notification. */
        data class Found(val build: Int, val notice: Notice) : Outcome

        /** The request failed (no network, rate limit, …). */
        data class Error(val reason: String) : Outcome
    }

    enum class Notice { POSTED, ALREADY, BLOCKED }

    fun outcomeText(outcome: Outcome): String = when (outcome) {
        Outcome.None -> "none"
        is Outcome.Found -> "build ${outcome.build} (" + when (outcome.notice) {
            Notice.POSTED -> "notified"
            Notice.ALREADY -> "already notified"
            Notice.BLOCKED -> "notifications blocked"
        } + ")"
        is Outcome.Error -> "error: ${outcome.reason}"
    }

    /**
     * When the next run is due, roughly: one interval after the last run; before the first run
     * the time it was scheduled (periodic work runs its first period at once); null when neither
     * is known. WorkManager may run it later (constraints, Doze), so the page shows it with "≈".
     */
    fun nextRunEstimate(lastRunAt: Long?, scheduledAt: Long?, intervalMinutes: Long): Long? {
        lastRunAt?.takeIf { it > 0 }?.let { return it + intervalMinutes * 60_000L }
        return scheduledAt?.takeIf { it > 0 }
    }
}
