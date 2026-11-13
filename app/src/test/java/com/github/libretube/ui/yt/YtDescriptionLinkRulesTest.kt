package com.github.libretube.ui.yt

import com.github.libretube.ui.yt.YtDescriptionLinkRules.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** YT: sorting description links by type and finding ads, sponsors and affiliate links. */
class YtDescriptionLinkRulesTest {
    private fun overview(text: String, duration: Long = 0) = YtDescriptionLinkRules.overview(text, duration)

    // ------------------------------------------------------------------------------------------
    // Types

    @Test
    fun plainChaptersBecomeTimestamps() {
        val o = overview(
            """
            In this video we build a desk.

            0:00 Intro
            1:23 - Wood
            (12:05) Gluing
            1:02:03 Outro
            """.trimIndent(),
        )
        assertEquals(listOf(0L, 83L, 725L, 3723L), o.timestamps.map { it.seconds })
        assertEquals(listOf("Intro", "Wood", "Gluing", "Outro"), o.timestamps.map { it.label })
    }

    @Test
    fun timeAtTheEndOfALineCounts() {
        val o = overview("Intro - 0:00\nDer Test – 4:12")
        assertEquals(listOf(0L, 252L), o.timestamps.map { it.seconds })
        assertEquals("Der Test", o.timestamps[1].label)
    }

    @Test
    fun clockTimesAndDatesAreNoTimestamps() {
        val o = overview(
            "Livestream heute um 20:15 Uhr im Kanal\nPremiere at 12:30 pm\nVersion 1.2.3 released 2024\nScore was 3:1",
            duration = 600,
        )
        assertTrue(o.timestamps.isEmpty())
    }

    @Test
    fun timesPastTheEndAreDropped() {
        val o = overview("0:00 Start\n9:59 End\n45:00 Not in this video", duration = 600)
        assertEquals(listOf(0L, 599L), o.timestamps.map { it.seconds })
    }

    @Test
    fun invalidSecondsAreNoTime() {
        assertNull(YtDescriptionLinkRules.timeOf("1:75"))
        assertNull(YtDescriptionLinkRules.timeOf("1:60:00"))
        assertEquals(5L, YtDescriptionLinkRules.timeOf("0:05"))
        assertEquals(7384L, YtDescriptionLinkRules.timeOf("2:03:04"))
        assertEquals(6000L, YtDescriptionLinkRules.timeOf("100:00"))
    }

    @Test
    fun htmlTimestampsChannelsVideosAndExternal() {
        val html = "Hello!<br><br>" +
            "<a href=\"https://www.youtube.com/watch?v=abcdefghijk&amp;t=83s\">1:23</a> First part<br>" +
            "<a href=\"https://www.youtube.com/watch?v=abcdefghijk&amp;t=600s\">10:00</a> Second part<br>" +
            "<br>With <a href=\"https://www.youtube.com/@SomeFriend\">@SomeFriend</a><br>" +
            "Old channel: <a href=\"https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv\">https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv</a><br>" +
            "Part 1: <a href=\"https://youtu.be/zzzzzzzzzzz\">https://youtu.be/zzzzzzzzzzz</a><br>" +
            "Playlist: <a href=\"https://www.youtube.com/playlist?list=PL12345\">https://www.youtube.com/playlist?list=PL12345</a><br>" +
            "Blog: <a href=\"https://example.org/post\">https://example.org/post</a><br>" +
            "<a href=\"https://www.youtube.com/hashtag/diy\">#diy</a>"
        val o = overview(html)
        assertEquals(listOf(83L, 600L), o.timestamps.map { it.seconds })
        assertEquals(listOf("First part", "Second part"), o.timestamps.map { it.label })

        assertEquals(2, o.channels.size)
        assertEquals("@SomeFriend", o.channels[0].label)
        assertEquals("@SomeFriend", o.channels[0].channel?.name)
        assertEquals("UCabcdefghijklmnopqrstuv", o.channels[1].channel?.id)
        assertEquals("Old channel", o.channels[1].label)

        assertEquals(listOf(Kind.VIDEO, Kind.PLAYLIST), o.videos.map { it.kind })
        assertEquals("zzzzzzzzzzz", o.videos[0].videoId)
        assertEquals("Part 1", o.videos[0].label)
        assertEquals("PL12345", o.videos[1].playlistId)

        assertEquals(1, o.external.size)
        assertEquals("Blog", o.external[0].label)
        assertEquals("example.org/post", o.external[0].detail)
        assertTrue(o.sponsored.isEmpty())
    }

    @Test
    fun htmlLinesMatchTheRenderedText() {
        // raw line breaks are spaces in HTML; <br> breaks the line
        val parsed = YtDescriptionLinkRules.parse("one\ntwo<br>three &amp; four<br><br>five")
        assertEquals(listOf("one two", "three & four", "", "five"), parsed.lines)
    }

    @Test
    fun youtubeAddressVariants() {
        fun kind(url: String) = YtDescriptionLinkRules.classify(url)?.kind
        assertEquals(Kind.VIDEO, kind("https://m.youtube.com/watch?v=abc"))
        assertEquals(Kind.VIDEO, kind("https://www.youtube.com/shorts/abc"))
        assertEquals(Kind.VIDEO, kind("https://youtube.com/live/abc"))
        assertEquals(Kind.VIDEO, kind("https://music.youtube.com/watch?v=abc&list=RD"))
        assertEquals(Kind.PLAYLIST, kind("https://youtube.com/playlist?list=PLx"))
        assertEquals(Kind.CHANNEL, kind("https://youtube.com/@handle/videos"))
        assertEquals(Kind.CHANNEL, kind("https://www.youtube.com/c/CustomName"))
        assertEquals(Kind.CHANNEL, kind("https://www.youtube.com/user/OldName"))
        assertEquals(Kind.CHANNEL, kind("https://www.youtube.com/LinusTechTips"))
        assertEquals(Kind.EXTERNAL, kind("https://www.youtube.com/premium"))
        assertNull(kind("https://www.youtube.com/hashtag/tag"))
        assertNull(kind("https://www.youtube.com/results?search_query=x"))
        assertNull(kind("mailto:me@example.org"))
        assertEquals(Kind.VIDEO, kind("/watch?v=abc"))
        // YouTube's redirect wrapper is unwrapped
        val redirect = YtDescriptionLinkRules.classify("https://www.youtube.com/redirect?event=video_description&q=https%3A%2F%2Fexample.org%2Fx")
        assertEquals(Kind.EXTERNAL, redirect?.kind)
        assertEquals("https://example.org/x", redirect?.url)
        // shorts play as a normal watch link, with the start time kept
        val short = YtDescriptionLinkRules.classify("https://youtube.com/shorts/abc?t=1m5s")
        assertEquals("https://www.youtube.com/watch?v=abc&t=65", short?.url)
    }

    @Test
    fun tParameterForms() {
        assertEquals(90L, YtDescriptionLinkRules.tSeconds("90"))
        assertEquals(90L, YtDescriptionLinkRules.tSeconds("90s"))
        assertEquals(3723L, YtDescriptionLinkRules.tSeconds("1h2m3s"))
        assertNull(YtDescriptionLinkRules.tSeconds("abc"))
    }

    @Test
    fun plainLinksHandlesAndEmails() {
        val o = overview(
            """
            Kamera-Test von @TechFreund und @Zweitkanal.
            Schreibt mir: kontakt@example.de
            Twitter: @mein_twitter
            Website: www.example.de/blog.
            Shop: example.shop/tasse
            Video: https://youtu.be/abcdefghijk
            """.trimIndent(),
        )
        assertEquals(listOf("@TechFreund", "@Zweitkanal"), o.channels.map { it.label })
        assertEquals(listOf("https://www.example.de/blog", "https://example.shop/tasse"), o.external.map { it.url })
        assertEquals(listOf("Website", "Shop"), o.external.map { it.label })
        assertEquals(1, o.videos.size)
        assertTrue(o.sponsored.isEmpty())
    }

    @Test
    fun sameLinkTwiceShowsOnce() {
        val o = overview("https://example.org/a\nhttps://example.org/a/\nhttps://example.org/b")
        assertEquals(2, o.external.size)
    }

    @Test
    fun emptyDescription() {
        assertTrue(overview("").isEmpty)
        assertTrue(overview("Just words, no links at all.").isEmpty)
    }

    // ------------------------------------------------------------------------------------------
    // Ads, sponsors, affiliates (English)

    @Test
    fun sponsoredByHeadingCoversItsBlock() {
        val o = overview(
            """
            This video is sponsored by NordVPN.
            Get 70% off: https://nordvpn.com/linus
            Use code LINUS at checkout.

            My Twitter: https://twitter.com/me
            Merch: https://store.example.com
            """.trimIndent(),
        )
        assertEquals(listOf("https://nordvpn.com/linus"), o.sponsored.map { it.url })
        assertEquals(listOf("https://twitter.com/me", "https://store.example.com"), o.external.map { it.url })
        assertEquals(setOf(0, 1, 2), o.sponsorLines)
    }

    @Test
    fun useCodeOnTheLineBelowMarksTheLinkAbove() {
        val o = overview("Try Brilliant: https://brilliant.org/me\nUse code ME20 for 20% off\n\nhttps://github.com/me/project")
        assertEquals(1, o.sponsored.size)
        assertEquals(1, o.external.size)
        assertTrue(1 in o.sponsorLines)
    }

    @Test
    fun affiliateAddressesWithoutWords() {
        assertTrue(YtDescriptionLinkRules.isAffiliateUrl("https://amzn.to/3abcd"))
        assertTrue(YtDescriptionLinkRules.isAffiliateUrl("https://www.amazon.de/dp/B000?tag=kanal-21"))
        assertTrue(YtDescriptionLinkRules.isAffiliateUrl("https://www.amazon.com/shop/creator"))
        assertTrue(YtDescriptionLinkRules.isAffiliateUrl("https://geni.us/xyz"))
        assertTrue(YtDescriptionLinkRules.isAffiliateUrl("https://shop.example.com/p?aff_id=12"))
        assertTrue(YtDescriptionLinkRules.isAffiliateUrl("https://shop.example.com/p?utm_medium=affiliate"))
        assertTrue(YtDescriptionLinkRules.isAffiliateUrl("https://kit.co/creator/desk"))
        assertFalse(YtDescriptionLinkRules.isAffiliateUrl("https://www.amazon.de/dp/B000"))
        assertFalse(YtDescriptionLinkRules.isAffiliateUrl("https://bit.ly/3abc"))
        assertFalse(YtDescriptionLinkRules.isAffiliateUrl("https://example.org/?utm_source=youtube"))
        assertFalse(YtDescriptionLinkRules.isAffiliateUrl("https://github.com/me/repo"))
        val o = overview("Camera: https://amzn.to/3abcd\nTripod: https://www.amazon.de/dp/B000")
        assertEquals(listOf("Camera"), o.sponsored.map { it.label })
        assertEquals(listOf("Tripod"), o.external.map { it.label })
    }

    @Test
    fun shortLinkOnlyWithSponsorWords() {
        val plain = overview("Slides: https://bit.ly/talk-slides")
        assertTrue(plain.sponsored.isEmpty())
        val ad = overview("Sponsor: Squarespace – https://bit.ly/sqsp-me")
        assertEquals(1, ad.sponsored.size)
    }

    @Test
    fun codeInOtherSensesIsNoOffer() {
        val o = overview(
            """
            Source code: https://github.com/me/project
            The code is on GitLab: https://gitlab.com/me/x
            QR code generator: https://qr.example.org
            Code: https://codeberg.org/me/repo
            We use code from https://example.org/lib under the MIT licence
            The HTML code is here: https://example.org/html
            Morse code explained: https://example.org/morse
            """.trimIndent(),
        )
        assertTrue(o.sponsored.isEmpty())
        assertEquals(7, o.external.size)
    }

    @Test
    fun codeOffers() {
        assertTrue(YtDescriptionLinkRules.hasCode("Use code LINUS for 10% off"))
        assertTrue(YtDescriptionLinkRules.hasCode("with code \"SAVE20\""))
        assertTrue(YtDescriptionLinkRules.hasCode("Mit dem Code MAX10 spart ihr"))
        assertTrue(YtDescriptionLinkRules.hasCode("Rabatt mit Code: KANAL15"))
        assertFalse(YtDescriptionLinkRules.hasCode("Source code: github"))
        assertFalse(YtDescriptionLinkRules.hasCode("use code wisely"))
        assertFalse(YtDescriptionLinkRules.hasCode("Code HTML"))
        assertFalse(YtDescriptionLinkRules.hasCode("I write code with VS Code"))
    }

    @Test
    fun supportPagesAreNoAds() {
        val o = overview(
            """
            Support me on Patreon: https://patreon.com/me
            Become a sponsor on GitHub: https://github.com/sponsors/me
            Thanks to our Patreon sponsors! https://example.org/supporters
            Kanalmitglied werden: https://www.youtube.com/channel/UCxyz/join
            """.trimIndent(),
        )
        assertTrue(o.sponsored.isEmpty())
    }

    @Test
    fun noSponsorStatementsAreNoAds() {
        val o = overview(
            "This video is not sponsored. Gear: https://example.org/gear\n" +
                "Dieses Video enthält keine Werbung: https://example.org/info",
        )
        assertTrue(o.sponsored.isEmpty())
    }

    @Test
    fun hashAdAndPaidPromotion() {
        assertEquals(1, overview("#ad https://brand.example.com/x").sponsored.size)
        assertEquals(1, overview("Paid promotion: https://brand.example.com/x").sponsored.size)
        assertEquals(1, overview("Try it free: https://app.example.com/free-trial (free trial)").sponsored.size)
    }

    @Test
    fun proseWithSponsorDoesNotSpreadOverTheParagraph() {
        val longProse = "Today we look at the new phone, and as always thanks to everyone who made this possible, " +
            "including today's sponsor who also sent the case we tested over the last two weeks"
        val o = overview("$longProse\nMy website: https://example.org")
        assertTrue(o.sponsored.isEmpty())
    }

    // ------------------------------------------------------------------------------------------
    // German

    @Test
    fun germanWerbungHeadingAndCode() {
        val o = overview(
            """
            Werbung:
            Holt euch HelloFresh mit 60 % Rabatt: https://hellofresh.de/kanal
            Gutscheincode: KANAL60

            Mein Instagram: https://instagram.com/me
            """.trimIndent(),
        )
        assertEquals(listOf("https://hellofresh.de/kanal"), o.sponsored.map { it.url })
        assertEquals(listOf("https://instagram.com/me"), o.external.map { it.url })
        assertEquals(setOf(0, 1, 2), o.sponsorLines)
    }

    @Test
    fun germanAnzeigeOnlyAsAMark() {
        assertEquals(1, overview("[Anzeige] Das Tablet: https://shop.example.de/tab").sponsored.size)
        assertEquals(1, overview("Anzeige | https://shop.example.de/tab").sponsored.size)
        assertEquals(1, overview("*Anzeige: https://shop.example.de/tab").sponsored.size)
        // "Anzeige" also means a phone's display
        assertTrue(overview("Die Anzeige des Handys im Test: https://example.de/test").sponsored.isEmpty())
        assertTrue(overview("Wie die Werbung früher aussah: https://example.de/history").sponsored.isEmpty())
    }

    @Test
    fun germanStarDisclosure() {
        val o = overview(
            """
            Mein Equipment:
            Kamera*: https://www.example.de/kamera
            Mikrofon: https://www.example.de/mikro
            * Twitter: https://twitter.com/me

            Die mit * markierten Links sind Affiliate-Links. Wenn ihr darüber kauft, erhalte ich eine Provision.
            """.trimIndent(),
        )
        assertEquals(listOf("https://www.example.de/kamera"), o.sponsored.map { it.url })
        assertEquals(2, o.external.size)
    }

    @Test
    fun germanAffiliateHeadingCoversGearList() {
        val o = overview(
            """
            Mein Setup (Affiliate-Links):
            Kamera: https://shop.example.de/a
            Objektiv: https://shop.example.de/b
            Licht: https://shop.example.de/c

            Zweitkanal: https://www.youtube.com/@zweitkanal
            """.trimIndent(),
        )
        assertEquals(3, o.sponsored.size)
        assertEquals(listOf("Zweitkanal"), o.channels.map { it.label })
        assertEquals(setOf(0, 1, 2, 3), o.sponsorLines)
    }

    @Test
    fun germanSponsorWords() {
        assertNotNull(YtDescriptionLinkRules.cueOf("Dieses Video wird gesponsert von Raid"))
        assertNotNull(YtDescriptionLinkRules.cueOf("Danke an unseren Sponsor"))
        assertNotNull(YtDescriptionLinkRules.cueOf("Unser heutiger Sponsor: X"))
        assertNotNull(YtDescriptionLinkRules.cueOf("#Werbung"))
        assertNotNull(YtDescriptionLinkRules.cueOf("Das Video enthält Werbung"))
        assertNotNull(YtDescriptionLinkRules.cueOf("Spart 20 % mit dem Rabattcode"))
        assertNotNull(YtDescriptionLinkRules.cueOf("Jetzt kostenlos testen"))
        assertNull(YtDescriptionLinkRules.cueOf("Unterstützt mich als Kanalmitglied, ihr Sponsoren!"))
        assertNull(YtDescriptionLinkRules.cueOf("Dieses Video ist nicht gesponsert"))
        assertNull(YtDescriptionLinkRules.cueOf("Mehr Infos zum Thema Datenschutz"))
        assertNull(YtDescriptionLinkRules.cueOf(""))
    }

    @Test
    fun htmlSponsorBlock() {
        val html = "Thanks to <a href=\"https://www.youtube.com/redirect?q=https%3A%2F%2Fsponsor.example.com%2Fme\">Sponsor</a> for sponsoring this video!<br>" +
            "<br>Camera: <a href=\"https://amzn.to/3x\">https://amzn.to/3x</a><br>" +
            "Friend: <a href=\"https://www.youtube.com/@friend\">@friend</a>"
        val o = overview(html)
        assertEquals(listOf("https://sponsor.example.com/me", "https://amzn.to/3x"), o.sponsored.map { it.url })
        assertEquals(1, o.channels.size)
        assertEquals(setOf(0, 2), o.sponsorLines)
    }

    // ------------------------------------------------------------------------------------------
    // Folding the text

    @Test
    fun foldRangesCoverWholeSponsorLines() {
        val text = "Intro text\nSponsored by X:\nhttps://x.example.com/me\nUse code ME for 10% off\n\nhttps://github.com/me"
        val o = overview(text)
        val ranges = YtDescriptionLinkRules.foldRanges(text, o)
        assertEquals(1, ranges.size)
        assertEquals("Sponsored by X:\nhttps://x.example.com/me\nUse code ME for 10% off", text.substring(ranges[0].first, ranges[0].last + 1))
    }

    @Test
    fun foldRangesFindLinesDespiteSpacing() {
        val html = "Hi<br>Camera:  <a href=\"https://amzn.to/1\">https://amzn.to/1</a><br>end"
        val o = overview(html)
        // the page renders "</a>" with a space after it and keeps the double space
        val rendered = "Hi\nCamera:  https://amzn.to/1 \nend"
        val ranges = YtDescriptionLinkRules.foldRanges(rendered, o)
        assertEquals(listOf("Camera:  https://amzn.to/1 "), ranges.map { rendered.substring(it.first, it.last + 1) })
    }

    @Test
    fun foldRangesSkipLinesThatAreNotThere() {
        val o = overview("Camera: https://amzn.to/1")
        assertTrue(YtDescriptionLinkRules.foldRanges("something else entirely", o).isEmpty())
        assertTrue(YtDescriptionLinkRules.foldRanges("abc", overview("abc")).isEmpty())
    }

    @Test
    fun separateSponsorBlocksFoldSeparately() {
        val text = "#ad https://a.example.com\nNormal: https://b.example.com\nCamera: https://amzn.to/2"
        val ranges = YtDescriptionLinkRules.foldRanges(text, overview(text))
        assertEquals(2, ranges.size)
    }
}
