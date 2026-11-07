package com.github.libretube.util

import com.github.libretube.util.YtStorageRules.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class YtStorageRulesTest {

    @Test
    fun cacheFoldersMapToRows() {
        assertEquals(Row.IMAGES, YtStorageRules.rowOf("coil"))
        assertEquals(Row.UPDATE, YtStorageRules.rowOf("update"))
        assertEquals(Row.WEB, YtStorageRules.rowOf("WebView"))
        assertEquals(Row.WEB, YtStorageRules.rowOf("org.chromium.android_webview"))
        assertEquals(Row.OTHER, YtStorageRules.rowOf("share_image"))
        assertEquals(Row.OTHER, YtStorageRules.rowOf("mux123.mp4"))
        // names are exact: a look-alike is not the image cache
        assertEquals(Row.OTHER, YtStorageRules.rowOf("coil2"))
    }

    @Test
    fun clearAllLeavesDownloadsAndCrashReports() {
        assertEquals(setOf(Row.IMAGES, Row.WEB, Row.UPDATE, Row.OTHER), YtStorageRules.clearAllRows)
        assertFalse(YtStorageRules.clearable(Row.DOWNLOADS))
        assertTrue(YtStorageRules.clearable(Row.CRASH))
        assertFalse(YtStorageRules.inClearAll(Row.CRASH))
    }

    @Test
    fun runningUpdateKeepsItsApk() {
        val names = listOf("update.apk", "old.apk", "leftover.tmp")
        assertEquals(listOf("old.apk", "leftover.tmp"), YtStorageRules.updateFilesToDelete(names, true))
        assertEquals(names, YtStorageRules.updateFilesToDelete(names, false))
        assertEquals(emptyList<String>(), YtStorageRules.updateFilesToDelete(listOf("update.apk"), true))
    }

    @Test
    fun recentFilesStay() {
        val now = 100_000_000L
        assertTrue(YtStorageRules.isRecent(now - 60_000, now))
        assertFalse(YtStorageRules.isRecent(now - YtStorageRules.RECENT_MS, now))
        // a clock in the future counts as in use
        assertTrue(YtStorageRules.isRecent(now + 60_000, now))
    }

    @Test
    fun crashCountIgnoresMarker() {
        assertEquals(2, YtStorageRules.crashCount(listOf("crash-1.txt", "new", "crash-2.txt")))
        assertEquals(0, YtStorageRules.crashCount(emptyList()))
    }

    @Test
    fun sizes() {
        val de = Locale.GERMANY
        assertEquals("0 B", YtStorageRules.formatSize(0, de))
        assertEquals("0 B", YtStorageRules.formatSize(-5, de))
        assertEquals("999 B", YtStorageRules.formatSize(999, de))
        assertEquals("1,0 kB", YtStorageRules.formatSize(1000, de))
        assertEquals("1,5 MB", YtStorageRules.formatSize(1_500_000, de))
        assertEquals("12 MB", YtStorageRules.formatSize(12_300_000, de))
        assertEquals("1,0 MB", YtStorageRules.formatSize(999_999, de))
        assertEquals("3,2 GB", YtStorageRules.formatSize(3_200_000_000, de))
        assertEquals("1.5 MB", YtStorageRules.formatSize(1_500_000, Locale.US))
    }
}
