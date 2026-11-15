package com.github.libretube.ui.yt.archive

import java.io.File

/*
 * YT: the shared contract of the archive of vanished videos (round 31). Three parts build on it:
 * the archive core (storage, tiers, budget, background saving, settings, watch-page badge), the
 * risk estimator (small local learning model + optional AI check + notification) and the script
 * part (dialogue script from the transcript, audio analysis with speakers). Each part registers its
 * implementation in [YtArchiveParts]; without one the defaults below keep the archive working.
 * Free of Android.
 */

/** How much of a video is kept. Higher tiers include the lower ones. */
enum class YtArchiveTier(val level: Int) {
    /** Title, description, thumbnail, chapters, transcript (captions as text with times). */
    TEXT(1),

    /** Plus storyboard stills and low-bitrate audio. */
    AUDIO(2),

    /** Plus a 144p video with audio. */
    VIDEO(3),
}

/** A video the archive may keep, as known from the feed. Times in epoch millis, length in seconds. */
data class YtArchiveCandidate(
    val videoId: String,
    val channelId: String?,
    val channelName: String?,
    val title: String,
    val description: String?,
    val uploaded: Long?,
    val durationSec: Long?,
)

/**
 * The estimated chance [risk] (0..1) that [videoId] vanishes within the archive window; [high] when it
 * crosses the alert line; [reason] one short user-visible sentence (German or English by locale),
 * e.g. "Kanal hat 3 von 40 Videos gelöscht · Thema: Rechtsstreit".
 */
data class YtRiskScore(
    val videoId: String,
    val risk: Double,
    val high: Boolean,
    val reason: String?,
)

/** The risk estimator, as the archive core sees it. */
interface YtArchiveRiskSource {
    /** Scores candidates (may call the AI in batches when the user enabled it). Never throws. */
    suspend fun score(candidates: List<YtArchiveCandidate>): List<YtRiskScore>

    /** A learning label: [candidate] was checked and is still there ([vanished] false) or gone (true). */
    fun observe(candidate: YtArchiveCandidate, vanished: Boolean)
}

/** Where one archived video lives on disk; the core owns the folder, the others write their files into it. */
class YtArchiveFiles(val dir: File) {
    val meta get() = File(dir, "meta.json")
    val thumbnail get() = File(dir, "thumb.jpg")

    /** Captions as JSON lines {"start":ms,"end":ms,"text":"…"}, gzip. */
    val transcript get() = File(dir, "transcript.jsonl.gz")
    val storyboard get() = File(dir, "storyboard")
    val audio get() = File(dir, "audio")
    val video get() = File(dir, "video")

    /** The readable script with speakers (see [YtArchiveScript]), JSON. */
    val script get() = File(dir, "script.json")
}

/** The script part, as the archive core sees it. */
interface YtArchiveScriptSource {
    /** Makes [YtArchiveFiles.script] from the transcript (text AI); false when it could not. Never throws. */
    suspend fun dialogue(candidate: YtArchiveCandidate, files: YtArchiveFiles): Boolean

    /** Analyses the stored audio with speaker separation where the provider takes audio, else falls back to [dialogue]. Never throws. */
    suspend fun analyseAudio(candidate: YtArchiveCandidate, files: YtArchiveFiles): Boolean
}

/** The registered parts; the defaults do nothing (no risk known, no script). */
object YtArchiveParts {
    @Volatile
    var risk: YtArchiveRiskSource = object : YtArchiveRiskSource {
        override suspend fun score(candidates: List<YtArchiveCandidate>) =
            candidates.map { YtRiskScore(it.videoId, 0.0, false, null) }

        override fun observe(candidate: YtArchiveCandidate, vanished: Boolean) = Unit
    }

    @Volatile
    var script: YtArchiveScriptSource = object : YtArchiveScriptSource {
        override suspend fun dialogue(candidate: YtArchiveCandidate, files: YtArchiveFiles) = false
        override suspend fun analyseAudio(candidate: YtArchiveCandidate, files: YtArchiveFiles) = false
    }
}
