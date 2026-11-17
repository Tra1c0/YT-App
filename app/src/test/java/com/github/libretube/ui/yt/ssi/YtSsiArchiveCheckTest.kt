package com.github.libretube.ui.yt.ssi

import com.github.libretube.ui.yt.archive.YtArchiveMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YtSsiArchiveCheckTest {
    private val sig = YtSsiArchiveCheck.Signature(3, 100, 5)
    private val gap = YtSsiArchiveCheck.MIN_GAP_MS

    @Test fun dueWhenNeverDone() = assertTrue(YtSsiArchiveCheck.due(1000, null, null, sig))

    @Test fun notDueWithinGapAndUnchanged() = assertFalse(YtSsiArchiveCheck.due(1000 + gap - 1, 1000, sig, sig))

    @Test fun dueAfterGap() = assertTrue(YtSsiArchiveCheck.due(1000 + gap, 1000, sig, sig))

    @Test fun dueWhenArchiveChanged() = assertTrue(YtSsiArchiveCheck.due(1001, 1000, sig, sig.copy(count = 4)))

    @Test fun signatureSeesNewEntriesAndText() {
        val a = YtArchiveMeta(videoId = "aaaaaaaaaaa", savedAt = 10, tierAt = 10)
        val before = YtSsiArchiveCheck.signature(listOf(a))
        assertEquals(before, YtSsiArchiveCheck.signature(listOf(a)))
        assertNotEquals(before, YtSsiArchiveCheck.signature(listOf(a, a.copy(videoId = "bbbbbbbbbbb", savedAt = 20))))
        assertNotEquals(before, YtSsiArchiveCheck.signature(listOf(a.copy(textBytes = 5))))
    }
}
