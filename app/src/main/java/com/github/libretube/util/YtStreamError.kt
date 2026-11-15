package com.github.libretube.util

import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.exceptions.UnsupportedContentInCountryException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException
import java.io.IOException

/**
 * YT: why the streams of a video could not be loaded, told apart from the exception the
 * extractor threw (or anything wrapping it). YouTube answers several cases with the playability
 * status LOGIN_REQUIRED; NewPipeExtractor's checkPlayabilityStatus turns the known reasons into
 * their own exceptions and the rest into a ContentNotAvailableException
 * `Got error LOGIN_REQUIRED: "Please sign in"` (or "YouTube probably temporarily blocked
 * anonymous watch access …"). No Android here, so the rules are unit-tested.
 */
object YtStreamError {
    enum class Kind {
        AGE_RESTRICTED,
        MEMBERS_ONLY,
        PRIVATE,
        BOT_CHECK,

        /** LOGIN_REQUIRED without a known reason: an anonymous-access block, often temporary. */
        SIGN_IN_REQUIRED,
        GEO,
        PAID,
        TERMINATED,
        UNAVAILABLE,
        NETWORK,
        OTHER,
    }

    /** How deep the cause chain is followed (it may loop). */
    private const val MAX_DEPTH = 12

    /** The kind of the first exception in [error]'s cause chain that tells one. */
    fun classify(error: Throwable?): Kind {
        var current = error
        val seen = HashSet<Throwable>()
        var depth = 0
        while (current != null && depth < MAX_DEPTH && seen.add(current)) {
            val kind = byClass(current) ?: byMessage(current.message)
            if (kind != null) return kind
            current = current.cause
            depth++
        }
        return Kind.OTHER
    }

    private fun byClass(e: Throwable): Kind? = when (e) {
        is AgeRestrictedContentException -> Kind.AGE_RESTRICTED
        is PrivateContentException -> Kind.PRIVATE
        is SignInConfirmNotBotException, is ReCaptchaException -> Kind.BOT_CHECK
        is YoutubeMusicPremiumContentException -> Kind.PAID
        // the extractor throws members-only videos as paid ones
        is PaidContentException -> if (isMembers(e.message)) Kind.MEMBERS_ONLY else Kind.PAID
        is GeographicRestrictionException, is UnsupportedContentInCountryException -> Kind.GEO
        is AccountTerminatedException -> Kind.TERMINATED
        is ContentNotAvailableException -> byMessage(e.message) ?: Kind.UNAVAILABLE
        is IOException -> Kind.NETWORK
        else -> null
    }

    /** The reasons as their messages name them, also when another exception carries the text. */
    internal fun byMessage(message: String?): Kind? {
        val m = message?.lowercase() ?: return null
        return when {
            "inappropriate for some users" in m || "age-restricted" in m || "age restricted" in m -> Kind.AGE_RESTRICTED
            "a bot" in m -> Kind.BOT_CHECK
            isMembers(m) -> Kind.MEMBERS_ONLY
            "video is private" in m -> Kind.PRIVATE
            "login_required" in m || "anonymous watch access" in m -> Kind.SIGN_IN_REQUIRED
            else -> null
        }
    }

    private fun isMembers(message: String?): Boolean =
        message?.lowercase()?.let { "members of the channel" in it || "members-only" in it || "members only" in it } == true

    /**
     * True if trying again later may help (a block, the network, an unknown failure). A sign-in
     * block is mostly a video made private (YouTube answers those with LOGIN_REQUIRED "Please
     * sign in" too), so it is not one of them.
     */
    fun retryMayHelp(kind: Kind): Boolean = when (kind) {
        Kind.BOT_CHECK, Kind.NETWORK, Kind.OTHER, Kind.UNAVAILABLE -> true
        else -> false
    }

    /**
     * True if the video is gone for everyone (private, deleted, its channel closed), so the local
     * feed drops it. Not for what depends on this phone or this moment (network, bot check,
     * country, age, membership, payment): the video still exists there. A video that comes back
     * returns with the next real feed refresh.
     */
    fun removesFromFeed(kind: Kind): Boolean = when (kind) {
        Kind.PRIVATE, Kind.SIGN_IN_REQUIRED, Kind.UNAVAILABLE, Kind.TERMINATED -> true
        else -> false
    }

    /**
     * The indices of [failedId]'s copies after [currentIndex] in a queue of [ids], highest first
     * (so they can be removed one by one). The current item itself stays while its page shows.
     */
    fun upcomingCopies(ids: List<String?>, currentIndex: Int, failedId: String): List<Int> =
        ids.indices.filter { it > currentIndex && ids[it] == failedId }.sortedDescending()

    /**
     * The video "Nächstes Video" offers after [failedId] failed: the queue's next one, unless
     * that is the failed video itself (the queue had not moved on to it).
     */
    fun nextAfter(failedId: String, queueNext: String?): String? = queueNext?.takeIf { it != failedId }
}
