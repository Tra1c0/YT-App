package com.github.libretube.ui.yt.archive

import kotlinx.serialization.Serializable

/**
 * YT: what the archive knows about one kept video, stored as `meta.json` in its folder
 * ([YtArchiveFiles.meta]). The candidate is copied in, so an entry stays readable after the video
 * left the feed (a gone video is deleted from the feed DB). Sizes are bytes per part: the text part
 * (meta, thumbnail, transcript, script), the audio part (audio + storyboard stills) and the video
 * part. Times in epoch millis. Free of Android.
 */
@Serializable
data class YtArchiveMeta(
    val version: Int = 1,
    val videoId: String,
    val channelId: String? = null,
    val channelName: String? = null,
    val title: String = "",
    val description: String? = null,
    val uploaded: Long? = null,
    val durationSec: Long? = null,
    /** [YtArchiveTier.level] of what is on disk (1..3). */
    val tierLevel: Int = YtArchiveTier.TEXT.level,
    /** When the entry was first saved and when its tier last changed. */
    val savedAt: Long = 0,
    val tierAt: Long = 0,
    val textBytes: Long = 0,
    val audioBytes: Long = 0,
    val videoBytes: Long = 0,
    /** MIME types of the stored streams (the files have no extension). */
    val audioMime: String? = null,
    val videoMime: String? = null,
    val videoHeight: Int? = null,
    /** Kept no matter the budget: the video vanished, or the user saved it by hand. */
    val vanishedAt: Long? = null,
    /** The [com.github.libretube.util.YtStreamError.Kind] name that told it vanished. */
    val vanishedKind: String? = null,
    val pinned: Boolean = false,
    /** The vanish label went to the risk estimator ([YtArchiveRiskSource.observe] true). */
    val vanishLabelled: Boolean = false,
    /** The entry left the archive window still there and that label went out (observe false). */
    val windowLabelled: Boolean = false,
    /** The last light check whether the video is still there. */
    val checkedAt: Long? = null,
    val risk: Double? = null,
    val riskReason: String? = null,
    val high: Boolean = false,
    /** The "could vanish" notification was posted for this video. */
    val notified: Boolean = false,
    val chapters: List<YtArchiveChapter> = emptyList(),
    val transcriptLanguage: String? = null,
    val transcriptAuto: Boolean? = null,
    /** Failed audio/video saves in a row and the last one ([YtArchiveRules.retryDue]). */
    val mediaFailures: Int = 0,
    val mediaFailedAt: Long? = null,
    /** Script state: null none, [SCRIPT_DONE], [SCRIPT_FAILED]; set by the core after asking the script part. */
    val scriptState: String? = null,
    val scriptAt: Long? = null,
    /** The text save did not finish (no thumbnail or transcript); retried by [YtArchiveRetryRules]. */
    val textIncomplete: Boolean = false,
    val textRetries: Int = 0,
    val textRetryAt: Long? = null,
) {
    val tier: YtArchiveTier get() = YtArchiveTier.entries.firstOrNull { it.level == tierLevel } ?: YtArchiveTier.TEXT
    val vanished: Boolean get() = vanishedAt != null

    /** Never downgraded or dropped automatically. */
    val protected: Boolean get() = vanished || pinned
    val totalBytes: Long get() = textBytes + audioBytes + videoBytes

    fun candidate() = YtArchiveCandidate(videoId, channelId, channelName, title, description, uploaded, durationSec)

    companion object {
        const val SCRIPT_DONE = "done"
        const val SCRIPT_FAILED = "failed"

        fun of(candidate: YtArchiveCandidate, now: Long) = YtArchiveMeta(
            videoId = candidate.videoId,
            channelId = candidate.channelId,
            channelName = candidate.channelName,
            title = candidate.title,
            description = candidate.description,
            uploaded = candidate.uploaded,
            durationSec = candidate.durationSec,
            savedAt = now,
            tierAt = now,
        )
    }
}

/** A chapter of a kept video: start in milliseconds (the script part reads it so). */
@Serializable
data class YtArchiveChapter(val start: Long, val title: String)

/** The kept storyboard (`storyboard/index.json`): sheets `0.jpg`, `1.jpg`, … of [perX] × [perY] frames. */
@Serializable
data class YtArchiveBoardIndex(
    val frameWidth: Int,
    val frameHeight: Int,
    val perX: Int,
    val perY: Int,
    val durationPerFrameMs: Long,
    val total: Int,
    val sheets: Int,
)
