package com.github.libretube.util

import com.github.libretube.util.YtReleaseNotes.Block
import com.github.libretube.util.YtReleaseNotes.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtReleaseNotesTest {
    private val fp = "C3:8D:B9:09:4F:B4:67:51:FE:C3:45:FB:DB:66:49:2F:28:5A:D1:B5:CA:02:7D:DC:51:08:8B:49:55:B8:5A:86"
    private val details = "YT build 107 from commit 9328dd6abc1234567890abcdef1234567890abcd.\n" +
        "Signed with: personal key (SHA-256 $fp)."
    private val body = "## Neu\n- Neues Fenster\n\n${YtReleaseNotes.DETAILS_MARKER}\n$details\n"
    private val oldBody = "YT build 106 from commit 9328dd6.\nSigned with: personal key (SHA-256 $fp).\n"

    @Test
    fun splitsAtTheMarker() {
        assertEquals("## Neu\n- Neues Fenster", YtReleaseNotes.userPart(body))
        assertEquals(details, YtReleaseNotes.detailsPart(body))
    }

    @Test
    fun oldBodiesHaveNoNotes() {
        assertEquals("", YtReleaseNotes.userPart(oldBody))
        assertEquals(oldBody.trim(), YtReleaseNotes.detailsPart(oldBody))
        assertEquals("", YtReleaseNotes.userPart(null))
    }

    @Test
    fun keineNotizenCountsAsNone() {
        assertFalse(YtReleaseNotes.hasNotes("Keine Notizen."))
        assertFalse(YtReleaseNotes.hasNotes("  "))
        assertTrue(YtReleaseNotes.hasNotes("- x"))
    }

    @Test
    fun buildsNewestFirstAtMostTen() {
        assertEquals(listOf(107, 106), YtReleaseNotes.buildsToFetch(105, 107))
        assertEquals((130 downTo 121).toList(), YtReleaseNotes.buildsToFetch(100, 130))
        assertEquals(listOf(107), YtReleaseNotes.buildsToFetch(null, 107))
        assertEquals(emptyList<Int>(), YtReleaseNotes.buildsToFetch(107, 107))
    }

    @Test
    fun mergeSkipsMissingAndEmptyAndSorts() {
        val bodies = mapOf(
            105 to body.replace("Neues Fenster", "A"),
            107 to body,
            106 to oldBody,
            104 to null,
        )
        val merged = YtReleaseNotes.merge(bodies)
        assertEquals(listOf(107, 105), merged.map { it.build })
        assertEquals("## Neu\n- A", merged[1].text)
        val many = (1..15).associateWith { body }
        assertEquals((15 downTo 6).toList(), YtReleaseNotes.merge(many).map { it.build })
    }

    @Test
    fun parsesHeadingsBulletsBold() {
        val md = "## Neu\n- Ein **fettes** Ding\n* zwei\n\nText <!-- c -->\n##   \n- **"
        assertEquals(
            listOf(
                Block.Heading("Neu"),
                Block.Bullet(listOf(Span("Ein "), Span("fettes", true), Span(" Ding"))),
                Block.Bullet(listOf(Span("zwei"))),
                Block.Text(listOf(Span("Text"))),
                Block.Bullet(listOf(Span("**"))),
            ),
            YtReleaseNotes.parse(md),
        )
    }

    @Test
    fun blocksPutAHeaderPerBuild() {
        val blocks = YtReleaseNotes.blocks(
            listOf(YtReleaseNotes.BuildNotes(107, "- a"), YtReleaseNotes.BuildNotes(106, "- b"))
        )
        assertEquals(
            listOf(
                Block.BuildHeader(107), Block.Bullet(listOf(Span("a"))),
                Block.BuildHeader(106), Block.Bullet(listOf(Span("b"))),
            ),
            blocks,
        )
    }

    @Test
    fun detailsAndSigner() {
        val d = YtReleaseNotes.details(body)
        assertEquals("9328dd6abc1234567890abcdef1234567890abcd", d.commit)
        assertEquals(fp, d.fingerprint)
        val installed = setOf(fp.replace(":", "").lowercase())
        assertTrue(YtReleaseNotes.fingerprintMatches(d.fingerprint, installed))
        assertFalse(YtReleaseNotes.fingerprintMatches(d.fingerprint, setOf("00")))
        assertFalse(YtReleaseNotes.fingerprintMatches(null, installed))
        val debug = YtReleaseNotes.details("x\n${YtReleaseNotes.DETAILS_MARKER}\nSigned with: throwaway debug key (y).")
        assertNull(debug.fingerprint)
        assertNull(debug.commit)
    }
}
