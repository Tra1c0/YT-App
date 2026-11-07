package com.github.libretube.util

import android.content.Context
import android.os.Build
import com.github.libretube.BuildConfig
import com.github.libretube.helpers.YtDesign
import com.github.libretube.helpers.YtDesignDark
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * YT: the local crash report. Called from the upstream [ExceptionHandler] while the process is
 * dying, so everything is written synchronously to plain files in app-private storage (no
 * SharedPreferences apply, no coroutines). Nothing is ever sent anywhere; the user copies or
 * shares the text by hand. The newest [YtCrashText.MAX_REPORTS] crashes are kept.
 */
object YtCrashLog {
    private const val DIR = "yt_crash"

    // exists while the newest crash has not been seen in the start dialog
    private const val NEW_MARKER = "new"

    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xx", Locale.US)

    private fun dir(context: Context) = File(context.filesDir, DIR)

    fun formatTime(millis: Long): String = timeFormat.format(
        ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), java.time.ZoneId.systemDefault())
    )

    /** The header both the crash report and the diagnosis page start with. */
    fun buildLines(): List<Pair<String, String>> = listOf(
        "App" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, build " +
            "${BuildConfig.VERSION_CODE % 100000}${if (BuildConfig.DEBUG) ", debug" else ""})",
        "Android" to "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
        "Device" to "${Build.MANUFACTURER} ${Build.MODEL}",
        // the design pref is read directly; a crash before PreferenceHelper is ready still reports
        "Design" to runCatching { "${YtDesign.current().key}, dark ${YtDesignDark.choice()}" }
            .getOrDefault("?"),
    )

    /** Stores one crash. Never throws: a failing crash log must not hide the real crash. */
    fun record(context: Context, thread: Thread, error: Throwable) {
        runCatching {
            val now = System.currentTimeMillis()
            val header = listOf("Time" to formatTime(now)) + buildLines()
            val text = YtCrashText.report(header, thread.name, error.stackTraceToString())
            val dir = dir(context).apply { mkdirs() }
            File(dir, YtCrashText.fileName(now)).writeText(text)
            File(dir, NEW_MARKER).writeText(now.toString())
            val names = dir.list()?.filter { it.startsWith("crash-") }.orEmpty()
            YtCrashText.toDelete(names).forEach { File(dir, it).delete() }
        }
    }

    /** Stored reports, newest first. */
    fun reports(context: Context): List<String> = runCatching {
        dir(context).listFiles { f -> f.name.startsWith("crash-") }.orEmpty()
            .sortedByDescending { it.name }
            .map { it.readText() }
    }.getOrDefault(emptyList())

    /** Whether a crash happened that the start dialog has not shown yet. */
    fun hasNew(context: Context): Boolean = File(dir(context), NEW_MARKER).exists()

    fun markSeen(context: Context) {
        runCatching { File(dir(context), NEW_MARKER).delete() }
    }

    /** All stored reports as one text, newest first. */
    fun allText(context: Context): String = reports(context).joinToString("\n\n")
}
