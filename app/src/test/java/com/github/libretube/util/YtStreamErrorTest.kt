package com.github.libretube.util

import com.github.libretube.util.YtStreamError.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class YtStreamErrorTest {

    @Test
    fun `the user's message is a sign-in block`() {
        // exactly what the toast showed for the failing video
        val e = ContentNotAvailableException("Got error LOGIN_REQUIRED: \"Please sign in\"")
        assertEquals(Kind.SIGN_IN_REQUIRED, YtStreamError.classify(e))
    }

    @Test
    fun `the anonymous access block is a sign-in block`() {
        val e = ContentNotAvailableException(
            "YouTube probably temporarily blocked anonymous watch access with this IP , got error LOGIN_REQUIRED: \"Sign in\""
        )
        assertEquals(Kind.SIGN_IN_REQUIRED, YtStreamError.classify(e))
    }

    @Test
    fun `the extractor's own exceptions`() {
        assertEquals(Kind.AGE_RESTRICTED, YtStreamError.classify(AgeRestrictedContentException("This age-restricted video cannot be watched anonymously")))
        assertEquals(Kind.PRIVATE, YtStreamError.classify(PrivateContentException("This video is private")))
        assertEquals(Kind.BOT_CHECK, YtStreamError.classify(SignInConfirmNotBotException("YouTube probably blocked: Sign in to confirm you're not a bot")))
        assertEquals(Kind.BOT_CHECK, YtStreamError.classify(ReCaptchaException("reCaptcha Challenge requested", "https://www.youtube.com")))
        assertEquals(Kind.MEMBERS_ONLY, YtStreamError.classify(PaidContentException("This video is only available for members of the channel of this video")))
        assertEquals(Kind.PAID, YtStreamError.classify(PaidContentException("This video is a paid video")))
        assertEquals(Kind.PAID, YtStreamError.classify(YoutubeMusicPremiumContentException()))
        assertEquals(Kind.GEO, YtStreamError.classify(GeographicRestrictionException("This video is not available in client's country.")))
        assertEquals(Kind.TERMINATED, YtStreamError.classify(AccountTerminatedException("This account has been terminated")))
        assertEquals(Kind.UNAVAILABLE, YtStreamError.classify(ContentNotAvailableException("Got error UNPLAYABLE: \"Video unavailable\"")))
    }

    @Test
    fun `a wrapped exception is found in the cause chain`() {
        val inner = AgeRestrictedContentException("This age-restricted video cannot be watched anonymously")
        val outer = RuntimeException("load failed", ExtractionException("wrapped", inner))
        assertEquals(Kind.AGE_RESTRICTED, YtStreamError.classify(outer))
    }

    @Test
    fun `a wrapping exception that only carries the text`() {
        val e = IllegalStateException("java.lang.Exception: Got error LOGIN_REQUIRED: \"Please sign in\"")
        assertEquals(Kind.SIGN_IN_REQUIRED, YtStreamError.classify(e))
    }

    @Test
    fun `network errors`() {
        assertEquals(Kind.NETWORK, YtStreamError.classify(UnknownHostException("www.youtube.com")))
        assertEquals(Kind.NETWORK, YtStreamError.classify(RuntimeException(SocketTimeoutException("timeout"))))
    }

    @Test
    fun `anything else is other, also a looping chain`() {
        assertEquals(Kind.OTHER, YtStreamError.classify(null))
        assertEquals(Kind.OTHER, YtStreamError.classify(IllegalArgumentException("bad")))
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertEquals(Kind.OTHER, YtStreamError.classify(b))
    }

    @Test
    fun `messages alone`() {
        assertNull(YtStreamError.byMessage(null))
        assertNull(YtStreamError.byMessage("Video unavailable"))
        assertEquals(Kind.BOT_CHECK, YtStreamError.byMessage("Sign in to confirm you’re not a bot"))
    }

    @Test
    fun `retry and next`() {
        assertFalse(YtStreamError.retryMayHelp(Kind.SIGN_IN_REQUIRED))
        assertTrue(YtStreamError.retryMayHelp(Kind.NETWORK))
        assertFalse(YtStreamError.retryMayHelp(Kind.PRIVATE))
        assertEquals("b", YtStreamError.nextAfter("a", "b"))
        assertNull(YtStreamError.nextAfter("a", "a"))
        assertNull(YtStreamError.nextAfter("a", null))
    }

    @Test
    fun `only videos gone for everyone leave the feed`() {
        val removes = Kind.entries.filter { YtStreamError.removesFromFeed(it) }.toSet()
        assertEquals(setOf(Kind.PRIVATE, Kind.SIGN_IN_REQUIRED, Kind.UNAVAILABLE, Kind.TERMINATED), removes)
        // the user's video: private now, answered with "Please sign in"
        val kind = YtStreamError.classify(ContentNotAvailableException("Got error LOGIN_REQUIRED: \"Please sign in\""))
        assertTrue(YtStreamError.removesFromFeed(kind))
        assertFalse(YtStreamError.removesFromFeed(Kind.NETWORK))
        assertFalse(YtStreamError.removesFromFeed(Kind.AGE_RESTRICTED))
    }

    @Test
    fun `upcoming copies of the failed video, not the current one`() {
        val ids = listOf("a", "x", "b", "x", null, "x")
        assertEquals(listOf(5, 3), YtStreamError.upcomingCopies(ids, 1, "x"))
        assertEquals(listOf(5, 3, 1), YtStreamError.upcomingCopies(ids, -1, "x"))
        assertEquals(emptyList<Int>(), YtStreamError.upcomingCopies(ids, 5, "x"))
    }
}
