package com.github.libretube.util

import java.util.Locale

/**
 * YT: the pure part of the storage page. Which cache folder belongs to which row, which files a
 * row may delete right now, and how sizes read. No Android types, so it is unit-tested.
 *
 * What the page never touches lives outside these rows: databases (subscriptions, playlists,
 * history, watch positions), SharedPreferences, the encrypted account session, the WebView's
 * cookies and storage (`app_webview` in the data folder), and downloads (shown only).
 */
object YtStorageRules {

    /** The rows of the storage page, in display order. */
    enum class Row { IMAGES, WEB, UPDATE, OTHER, CRASH, DOWNLOADS }

    /** Coil's disk cache folder in cacheDir (ImageHelper). */
    const val IMAGE_DIR = "coil"

    /** YtSelfUpdater's download folder in cacheDir. */
    const val UPDATE_DIR = "update"

    /** The APK YtSelfUpdater downloads, checks and installs. */
    const val UPDATE_APK = "update.apk"

    /** YtCrashLog's folder in filesDir, and the prefix of its reports. */
    const val CRASH_DIR = "yt_crash"
    const val CRASH_PREFIX = "crash-"

    /** The WebView's HTTP and code cache in cacheDir (name differs between WebView versions). */
    val WEB_DIRS = setOf("WebView", "org.chromium.android_webview")

    /**
     * Files in "other cache" younger than this may still be written or shared (a muxed export,
     * a shared thumbnail), so clearing leaves them.
     */
    const val RECENT_MS = 10 * 60 * 1000L

    /** The row a top-level entry of cacheDir belongs to. */
    fun rowOf(topLevelName: String): Row = when {
        topLevelName == IMAGE_DIR -> Row.IMAGES
        topLevelName == UPDATE_DIR -> Row.UPDATE
        topLevelName in WEB_DIRS -> Row.WEB
        else -> Row.OTHER
    }

    /** Rows that have a "clear" button; downloads are only shown. */
    fun clearable(row: Row): Boolean = row != Row.DOWNLOADS

    /** Rows that "clear everything unneeded" empties: not downloads, not the crash reports. */
    fun inClearAll(row: Row): Boolean = row != Row.DOWNLOADS && row != Row.CRASH

    val clearAllRows: Set<Row> get() = Row.entries.filter(::inClearAll).toSet()

    /**
     * The files of the update folder that may go. While the updater still needs [UPDATE_APK]
     * (download, check, install session or Android's installer reading it through the
     * FileProvider) that one stays; everything else there is a leftover.
     */
    fun updateFilesToDelete(names: List<String>, updaterHoldsApk: Boolean): List<String> =
        names.filter { !(updaterHoldsApk && it == UPDATE_APK) }

    /** Whether a file may still be in use (written recently, or a clock in the future). */
    fun isRecent(lastModified: Long, now: Long): Boolean = now - lastModified < RECENT_MS

    /** Stored crash reports among the names in the crash folder (the "new" marker is no report). */
    fun crashCount(names: List<String>): Int = names.count { it.startsWith(CRASH_PREFIX) }

    /**
     * Bytes as a short size, like Android's own (units of 1000): "512 B", "1,5 MB", "12 MB",
     * "3,2 GB". One decimal below 10 units, none above.
     */
    fun formatSize(bytes: Long, locale: Locale = Locale.getDefault()): String {
        if (bytes < 1000) return "${bytes.coerceAtLeast(0)} B"
        val units = arrayOf("kB", "MB", "GB", "TB")
        var value = bytes / 1000.0
        var unit = 0
        // 999.95 kB would print as "1000 kB"; move up a unit before that
        while (value >= 999.5 && unit < units.lastIndex) {
            value /= 1000.0
            unit++
        }
        val number = if (value < 9.95) {
            String.format(locale, "%.1f", value)
        } else {
            String.format(locale, "%.0f", value)
        }
        return "$number ${units[unit]}"
    }
}
