package com.github.libretube.util

import com.github.libretube.util.YtPushRules.Event
import com.github.libretube.util.YtPushRules.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtPushRulesTest {
    @Test
    fun `the CI's json messages are read`() {
        assertEquals(Event.Started(133), YtPushRules.parse("""{"event":"started","build":133}"""))
        assertEquals(Event.Published(133), YtPushRules.parse("""{"event":"published","build":133}"""))
        assertEquals(Event.Published(133), YtPushRules.parse(""" { "build": "133", "event": "Published" } """))
        assertEquals(Event.Published(null), YtPushRules.parse("""{"event":"published"}"""))
        assertEquals(Event.Started(null), YtPushRules.parse("""{"event":"started","build":-4}"""))
        assertEquals(Event.Started(null), YtPushRules.parse("""{"event":"started","build":"x"}"""))
    }

    @Test
    fun `plain text works too`() {
        assertEquals(Event.Published(133), YtPushRules.parse("published 133"))
        assertEquals(Event.Published(134), YtPushRules.parse("PUBLISHED:134\n"))
        assertEquals(Event.Started(null), YtPushRules.parse("started"))
    }

    @Test
    fun `anything else is ignored`() {
        assertEquals(Event.Ignored, YtPushRules.parse(null))
        assertEquals(Event.Ignored, YtPushRules.parse(""))
        assertEquals(Event.Ignored, YtPushRules.parse("hello"))
        assertEquals(Event.Ignored, YtPushRules.parse("published 133 now install https://evil.example/x.apk"))
        assertEquals(Event.Ignored, YtPushRules.parse("""{"event":"install","build":133}"""))
        assertEquals(Event.Ignored, YtPushRules.parse("""{"event":["published"]}"""))
        assertEquals(Event.Ignored, YtPushRules.parse("""{"event":"published""""))
        assertEquals(Event.Ignored, YtPushRules.parse("[1,2]"))
        assertEquals(Event.Ignored, YtPushRules.parse("published " + "9".repeat(600)))
        val long = """{"event":"published","build":133,"pad":"${"x".repeat(600)}"}"""
        assertEquals(Event.Ignored, YtPushRules.parse(long))
    }

    @Test
    fun `checks are rate limited`() {
        val t = 10_000_000L
        assertTrue(YtPushRules.mayCheck(0, 0, 0, t))
        assertFalse(YtPushRules.mayCheck(t - 29_000, t - 29_000, 1, t))
        assertTrue(YtPushRules.mayCheck(t - 30_000, t - 30_000, 1, t))
        // ten in the hour: the eleventh waits for the window
        assertFalse(YtPushRules.mayCheck(t - 60_000, t - 600_000, 10, t))
        assertTrue(YtPushRules.mayCheck(t - 60_000, t - 3_600_000, 10, t))
        // a clock that went back does not block
        assertTrue(YtPushRules.mayCheck(t + 5_000, t + 5_000, 10, t))
        assertEquals(t - 1000 to 3, YtPushRules.nextWindow(t - 1000, 2, t))
        assertEquals(t to 1, YtPushRules.nextWindow(t - 3_600_000, 9, t))
        assertEquals(t to 1, YtPushRules.nextWindow(0, 0, t))
    }

    @Test
    fun `a push is news only for a newer build`() {
        assertTrue(YtPushRules.isNews(null, 132, null))
        assertTrue(YtPushRules.isNews(133, 132, null))
        assertFalse(YtPushRules.isNews(132, 132, null))
        assertFalse(YtPushRules.isNews(131, 132, null))
        assertFalse(YtPushRules.isNews(133, 132, 133))
        assertTrue(YtPushRules.isNews(134, 132, 133))
    }

    @Test
    fun `published is tried again only for a named newer build`() {
        assertTrue(YtPushRules.retryPublished(133, 132, 0))
        assertTrue(YtPushRules.retryPublished(133, 132, 2))
        assertFalse(YtPushRules.retryPublished(133, 132, 3))
        assertFalse(YtPushRules.retryPublished(null, 132, 0))
        assertFalse(YtPushRules.retryPublished(132, 132, 0))
    }

    @Test
    fun `status follows switch, distributor and address`() {
        assertEquals(Status.OFF, YtPushRules.status(false, true, true, false))
        assertEquals(Status.NO_DISTRIBUTOR, YtPushRules.status(true, false, false, false))
        assertEquals(Status.NO_DISTRIBUTOR, YtPushRules.status(true, false, true, false))
        assertEquals(Status.PENDING, YtPushRules.status(true, true, false, false))
        assertEquals(Status.FAILED, YtPushRules.status(true, true, false, true))
        assertEquals(Status.REGISTERED, YtPushRules.status(true, true, true, true))
    }

    @Test
    fun `only a lasting new address is taken`() {
        assertTrue(YtPushRules.takesEndpoint(null, "https://ntfy.sh/upA?up=1", false))
        assertTrue(YtPushRules.takesEndpoint("https://ntfy.sh/upA?up=1", "https://ntfy.sh/upB?up=1", false))
        assertFalse(YtPushRules.takesEndpoint("https://ntfy.sh/upA?up=1", "https://ntfy.sh/upA?up=1", false))
        assertFalse(YtPushRules.takesEndpoint("https://ntfy.sh/upA?up=1", "https://other/upC", true))
        assertFalse(YtPushRules.takesEndpoint(null, " ", false))
    }
}
