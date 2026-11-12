package com.github.libretube.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * YT: the pure decisions of the instant push (UnifiedPush, [YtPush]). A push is only a trigger:
 * whatever it says, the app asks GitHub itself before it tells the user anything. No Android
 * types, so they run in JVM tests.
 *
 * The CI sends `{"event":"started","build":133}` when a build of main begins and
 * `{"event":"published","build":133}` once its release is out. Plain text ("published 133")
 * is accepted too; everything else is ignored.
 */
object YtPushRules {
    /** Switch "instant push for new builds" (default off). */
    const val KEY_ENABLED = "yt_update_push"

    /** The status row and the "copy the address" row of the push. */
    const val KEY_STATUS = "yt_update_push_status"
    const val KEY_COPY = "yt_update_push_copy"

    const val DEFAULT_ENABLED = false

    /** A push body longer than this is not from the CI. */
    const val MAX_BODY_CHARS = 512

    /** At least this long between two checks a push asks for. */
    const val MIN_GAP_MS = 30_000L

    /** And at most this many push-triggered checks per hour (GitHub allows 60 requests an hour). */
    const val MAX_PER_HOUR = 10
    const val HOUR_MS = 3_600_000L

    /** A build number from a push outside this range is no build number. */
    private val BUILDS = 1..9_999_999

    sealed interface Event {
        /** A build of main has started; [build] is its number when the push said it. */
        data class Started(val build: Int?) : Event

        /** A build's release is published. */
        data class Published(val build: Int?) : Event

        /** Not a message of the CI: nothing happens. */
        data object Ignored : Event
    }

    private val json = Json { isLenient = false }
    private val plain = Regex("^(started|published)(?:[\\s:=#,-]+(\\d{1,7}))?$")

    /** What the push [body] asks for; anything unknown or too long is [Event.Ignored]. */
    fun parse(body: String?): Event {
        val text = body?.trim().orEmpty()
        if (text.isEmpty() || text.length > MAX_BODY_CHARS) return Event.Ignored
        if (text.startsWith("{")) {
            val obj = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                ?: return Event.Ignored
            val event = (obj["event"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.lowercase()
            val build = (obj["build"] as? JsonPrimitive)?.let { p ->
                p.intOrNull ?: p.content.trim().toIntOrNull()
            }?.takeIf { it in BUILDS }
            return event(event, build)
        }
        val match = plain.matchEntire(text.lowercase()) ?: return Event.Ignored
        return event(match.groupValues[1], match.groupValues[2].toIntOrNull()?.takeIf { it in BUILDS })
    }

    private fun event(name: String?, build: Int?): Event = when (name) {
        "started" -> Event.Started(build)
        "published" -> Event.Published(build)
        else -> Event.Ignored
    }

    /**
     * Whether a push may start a check now: the last one ([lastAt]) at least [MIN_GAP_MS] ago,
     * and fewer than [MAX_PER_HOUR] since [windowStart] (the window restarts after an hour).
     * A clock that went back counts as a fresh start.
     */
    fun mayCheck(lastAt: Long, windowStart: Long, countInWindow: Int, now: Long): Boolean {
        if (lastAt in 1..now && now - lastAt < MIN_GAP_MS) return false
        val windowRuns = windowStart in 1..now && now - windowStart < HOUR_MS
        return !windowRuns || countInWindow < MAX_PER_HOUR
    }

    /** The rate window after a check at [now]: (window start, count in it). */
    fun nextWindow(windowStart: Long, countInWindow: Int, now: Long): Pair<Long, Int> {
        val windowRuns = windowStart in 1..now && now - windowStart < HOUR_MS
        return if (windowRuns) windowStart to countInWindow + 1 else now to 1
    }

    /**
     * Whether a push about [build] is still news: a build number the push did not give is
     * allowed (the check decides), else only one newer than the [installed] and the one
     * notified last ([lastNotified]).
     */
    fun isNews(build: Int?, installed: Int?, lastNotified: Int?): Boolean {
        if (build == null) return true
        if (!YtUpdateRules.isNewer(installed, build)) return false
        return lastNotified == null || build > lastNotified
    }

    /** How often a check after "published" is tried again when GitHub does not show the build yet. */
    const val PUBLISHED_RETRIES = 3

    /**
     * After a check that found no newer build: try again only when the push named a [expected]
     * build that is newer than the [installed] one (GitHub may answer from a cache for a minute)
     * and [attempt] retries are not used up.
     */
    fun retryPublished(expected: Int?, installed: Int?, attempt: Int): Boolean =
        expected != null && YtUpdateRules.isNewer(installed, expected) && attempt < PUBLISHED_RETRIES

    /** What the settings show about the push. */
    enum class Status {
        /** The switch is off. */
        OFF,

        /** No UnifiedPush distributor (such as ntfy) is installed. */
        NO_DISTRIBUTOR,

        /** A distributor is there, the registration is not confirmed (yet). */
        PENDING,

        /** Registered: the address can be copied. */
        REGISTERED,

        /** The distributor refused or failed the registration. */
        FAILED,
    }

    fun status(enabled: Boolean, hasDistributor: Boolean, hasEndpoint: Boolean, failed: Boolean): Status = when {
        !enabled -> Status.OFF
        hasEndpoint && hasDistributor -> Status.REGISTERED
        !hasDistributor -> Status.NO_DISTRIBUTOR
        failed -> Status.FAILED
        else -> Status.PENDING
    }

    /**
     * Whether a new address from the distributor replaces the stored one: only a lasting one
     * (a temporary fallback address goes away again; the CI secret should not follow it).
     * True also tells that the CI secret must be updated when an address was stored before.
     */
    fun takesEndpoint(stored: String?, new: String, temporary: Boolean): Boolean =
        !temporary && new.isNotBlank() && new != stored
}
