package com.github.libretube.ui.yt.archive

/**
 * YT: retry of a text save that did not finish (an entry without thumbnail or transcript):
 * at most [MAX_RETRIES] more tries, at least a day apart. Pure.
 */
object YtArchiveRetryRules {
    const val MAX_RETRIES = 3
    const val MIN_GAP_MS = 24L * 60 * 60 * 1000

    /** The text part is whole: the thumbnail is there, and a transcript or a known caption language. */
    fun complete(thumbnail: Boolean, transcript: Boolean, transcriptLanguage: String?): Boolean =
        thumbnail && (transcript || transcriptLanguage != null)

    fun due(incomplete: Boolean, retries: Int, lastAt: Long?, now: Long): Boolean =
        incomplete && retries < MAX_RETRIES && now - (lastAt ?: 0L) >= MIN_GAP_MS
}
