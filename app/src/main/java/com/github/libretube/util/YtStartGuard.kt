package com.github.libretube.util

import android.util.Log

/**
 * YT: keeps one failing YT part from crashing the app at every start. Each YT call in the app's
 * and the main activity's onCreate runs through [ytStart]; a failure is logged and remembered
 * (in memory, for the diagnosis line "Start") and the start goes on.
 */
object YtStartGuard {
    private const val TAG = "YtStartGuard"

    /** What was remembered of one failed part. */
    data class Failure(val part: String, val type: String, val message: String?)

    /** Pure record-keeping: the newest [max] failures, a bounded list. */
    class Record(private val max: Int = MAX) {
        private val items = ArrayList<Failure>()

        @Synchronized
        fun add(part: String, error: Throwable) {
            items.add(Failure(part, error.javaClass.simpleName.ifEmpty { error.javaClass.name }, error.message?.take(120)))
            while (items.size > max) items.removeAt(0)
        }

        @Synchronized
        fun failures(): List<Failure> = items.toList()

        /** "ok", or one entry per failed part: "part (Type: message)". */
        @Synchronized
        fun describe(): String =
            if (items.isEmpty()) "ok" else items.joinToString("; ") { f ->
                "${f.part} (${f.type}${f.message?.let { ": $it" }.orEmpty()})"
            }
    }

    const val MAX = 20

    val record = Record()

    fun failed(part: String, error: Throwable) {
        runCatching { Log.e(TAG, "start part failed: $part", error) }
        record.add(part, error)
    }

    fun diagnosis(): String = record.describe()
}

/** Runs [block] (one YT part of the start); a Throwable is recorded, never thrown. */
inline fun ytStart(part: String, block: () -> Unit) {
    try {
        block()
    } catch (t: Throwable) {
        YtStartGuard.failed(part, t)
    }
}
