package com.github.libretube.ui.yt.archive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtArchiveRetryRulesTest {
    private val day = YtArchiveRetryRules.MIN_GAP_MS

    @Test fun completeNeedsThumbnailAndTranscriptOrLanguage() {
        assertTrue(YtArchiveRetryRules.complete(true, true, null))
        assertTrue(YtArchiveRetryRules.complete(true, false, "de"))
        assertFalse(YtArchiveRetryRules.complete(false, true, "de"))
        assertFalse(YtArchiveRetryRules.complete(true, false, null))
    }

    @Test fun dueAfterADayAndAtMostThreeTimes() {
        assertFalse(YtArchiveRetryRules.due(false, 0, null, 10 * day))
        assertFalse(YtArchiveRetryRules.due(true, 0, 100, 100 + day - 1))
        assertTrue(YtArchiveRetryRules.due(true, 0, 100, 100 + day))
        assertTrue(YtArchiveRetryRules.due(true, 2, 100, 100 + day))
        assertFalse(YtArchiveRetryRules.due(true, 3, 100, 100 + 9 * day))
    }
}
