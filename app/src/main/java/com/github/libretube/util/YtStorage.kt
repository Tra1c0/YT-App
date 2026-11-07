package com.github.libretube.util

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Process
import android.os.storage.StorageManager
import android.util.Log
import android.webkit.WebView
import com.github.libretube.helpers.DownloadHelper
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.util.YtStorageRules.Row
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * YT: measures and clears the app's caches for the storage page (Y/YtStorageUi). Every call runs
 * on [Dispatchers.IO] (the WebView cache is cleared on the main thread, as WebView requires).
 *
 * Deletes only inside cacheDir, externalCacheDir and filesDir/yt_crash. Databases, shared_prefs,
 * the encrypted account session, the WebView's cookies and storage (app_webview) and downloads
 * are never touched. There is no media cache: the player streams without a disk cache, so a
 * running playback holds no file here.
 */
object YtStorage {
    private const val TAG = "YtStorage"

    /** [bytes] in a row; [kept] of them stay on a clear (the APK of a running update). */
    data class Usage(val bytes: Long = 0, val files: Int = 0, val kept: Long = 0) {
        val clearableBytes get() = (bytes - kept).coerceAtLeast(0)
    }

    data class Report(
        /** All the app occupies (APK + data + caches), null when Android does not say. */
        val total: Long?,
        val rows: Map<Row, Usage>,
        val crashReports: Int,
        val updateHeld: Boolean,
    ) {
        fun usage(row: Row) = rows[row] ?: Usage()
    }

    suspend fun scan(context: Context): Report = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val held = YtSelfUpdater.holdsApk()
        val rows = mutableMapOf<Row, Usage>()
        for (row in Row.entries) rows[row] = Usage()

        cacheEntries(app).forEach { (entry, row) ->
            rows[row] = rows.getValue(row) + measure(entry)
        }
        if (held) {
            val apk = File(updateDir(app), YtStorageRules.UPDATE_APK)
            if (apk.isFile) rows[Row.UPDATE] = rows.getValue(Row.UPDATE).copy(kept = apk.length())
        }
        rows[Row.CRASH] = measure(crashDir(app))
        rows[Row.DOWNLOADS] = downloadDirs(app).fold(Usage()) { sum, dir -> sum + measure(dir) }
        val crashNames = crashDir(app).list()?.toList().orEmpty()

        Report(
            total = total(app),
            rows = rows,
            crashReports = YtStorageRules.crashCount(crashNames),
            updateHeld = held,
        )
    }

    /** Clears [rows]; downloads are skipped whatever is asked. */
    suspend fun clear(context: Context, rows: Set<Row>) {
        val app = context.applicationContext
        for (row in rows) {
            if (!YtStorageRules.clearable(row)) continue
            runCatching {
                when (row) {
                    Row.IMAGES -> clearImages(app)
                    Row.WEB -> clearWeb(context)
                    Row.UPDATE -> clearUpdate(app)
                    Row.OTHER -> clearOther(app)
                    Row.CRASH -> withContext(Dispatchers.IO) {
                        crashDir(app).listFiles()?.forEach { it.deleteRecursively() }
                    }
                    Row.DOWNLOADS -> Unit
                }
            }.onFailure { Log.w(TAG, "could not clear $row", it) }
        }
    }

    private suspend fun clearImages(context: Context) = withContext(Dispatchers.IO) {
        // through Coil, so its journal stays consistent while images keep loading
        val cache = runCatching { ImageHelper.loader().diskCache }.getOrNull()
        if (cache != null) {
            cache.clear()
        } else {
            File(context.cacheDir, YtStorageRules.IMAGE_DIR).listFiles()?.forEach { it.deleteRecursively() }
        }
    }

    /**
     * WebView's own call drops its HTTP cache for every WebView of the app, also one that is
     * open; cookies and web storage (the sign-in) are a different store and stay.
     */
    private suspend fun clearWeb(context: Context) = withContext(Dispatchers.Main) {
        val view = WebView(context)
        try {
            view.clearCache(true)
        } finally {
            view.destroy()
        }
    }

    private suspend fun clearUpdate(context: Context) = withContext(Dispatchers.IO) {
        val dir = updateDir(context)
        val names = dir.list()?.toList().orEmpty()
        YtStorageRules.updateFilesToDelete(names, YtSelfUpdater.holdsApk())
            .forEach { File(dir, it).deleteRecursively() }
    }

    private suspend fun clearOther(context: Context) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cacheEntries(context)
            .filter { (_, row) -> row == Row.OTHER }
            .forEach { (entry, _) -> deleteOld(entry, now) }
    }

    /** Deletes the files under [file] that are not recent, then the folders left empty. */
    private fun deleteOld(file: File, now: Long) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteOld(it, now) }
            if (file.list()?.isEmpty() == true) file.delete()
        } else if (!YtStorageRules.isRecent(file.lastModified(), now)) {
            file.delete()
        }
    }

    /**
     * The top-level entries of cacheDir with their row, and those of the external cache folder,
     * which all belong to "other cache".
     */
    private fun cacheEntries(context: Context): List<Pair<File, Row>> {
        val internal = context.cacheDir.listFiles()?.toList().orEmpty()
            .map { it to YtStorageRules.rowOf(it.name) }
        val external = runCatching { context.externalCacheDir?.listFiles()?.toList() }
            .getOrNull().orEmpty()
            .map { it to Row.OTHER }
        return internal + external
    }

    private fun updateDir(context: Context) = File(context.cacheDir, YtStorageRules.UPDATE_DIR)

    private fun crashDir(context: Context) = File(context.filesDir, YtStorageRules.CRASH_DIR)

    /** DownloadHelper's folders, without creating them. */
    private fun downloadDirs(context: Context): List<File> {
        val base = runCatching { context.getExternalFilesDir(null) }.getOrNull() ?: context.filesDir
        return listOf(
            DownloadHelper.VIDEO_DIR,
            DownloadHelper.AUDIO_DIR,
            DownloadHelper.SUBTITLE_DIR,
            DownloadHelper.THUMBNAIL_DIR,
            DownloadHelper.PLAYLIST_THUMBNAIL_DIR,
        ).map { File(base, it) }
    }

    private fun measure(file: File): Usage {
        if (!file.exists()) return Usage()
        var bytes = 0L
        var files = 0
        file.walkTopDown().forEach {
            if (it.isFile) {
                bytes += it.length()
                files++
            }
        }
        return Usage(bytes, files)
    }

    private operator fun Usage.plus(other: Usage) =
        Usage(bytes + other.bytes, files + other.files, kept + other.kept)

    /** Android's own count for this app (no permission needed for the own uid). */
    private fun total(context: Context): Long? = runCatching {
        val stats = context.getSystemService(StorageStatsManager::class.java)
            .queryStatsForUid(StorageManager.UUID_DEFAULT, Process.myUid())
        stats.appBytes + stats.dataBytes
    }.getOrNull()
}
