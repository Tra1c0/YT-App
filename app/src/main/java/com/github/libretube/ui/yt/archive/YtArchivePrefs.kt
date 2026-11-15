package com.github.libretube.ui.yt.archive

/**
 * YT: the keys of the archive settings (default shared preferences, screen "Archiv",
 * `R/xml/yt_r31_archive_settings.xml`) and their defaults. The risk estimator reads
 * [KEY_AI_RISK]. Free of Android.
 */
object YtArchivePrefs {
    /** Master switch; off by default (the archive downloads in the background). */
    const val KEY_ENABLED = "yt_archive_enabled"
    const val DEFAULT_ENABLED = false

    /** The capacity in MB, one of [YtArchiveRules.CAPACITY_STEPS_MB] (stored as an int). */
    const val KEY_CAPACITY_MB = "yt_archive_capacity_mb"

    /** What tier 3 keeps: [TOP_VIDEO] (144p with sound) or [TOP_AUDIO] (sound only, no tier 3). */
    const val KEY_TOP = "yt_archive_top"
    const val TOP_VIDEO = "video"
    const val TOP_AUDIO = "audio"
    const val DEFAULT_TOP = TOP_VIDEO

    /** The notification "… könnte verschwinden – gesichert". */
    const val KEY_NOTIFY_RISK = "yt_archive_notify_risk"
    const val DEFAULT_NOTIFY_RISK = true

    /**
     * Make the script with the AI by itself: for a vanished video (from the transcript) and for a
     * high-risk video once its audio is saved (audio analysis). Off: only the button per entry.
     */
    const val KEY_AUTO_SCRIPT = "yt_archive_auto_script"
    const val DEFAULT_AUTO_SCRIPT = false

    /** The risk estimator may ask the AI (read by the risk part). */
    const val KEY_AI_RISK = "yt_archive_ai_risk"
    const val DEFAULT_AI_RISK = false

    // rows of the settings screen that are no settings
    const val KEY_USAGE = "yt_archive_usage"
    const val KEY_VANISHED = "yt_archive_vanished"
    const val KEY_SAVED = "yt_archive_saved"

    fun top(value: String?): YtArchiveTier = if (value == TOP_AUDIO) YtArchiveTier.AUDIO else YtArchiveTier.VIDEO
}
