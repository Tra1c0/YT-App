package com.github.libretube.ui.yt.archive

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.NestedScrollView
import com.github.libretube.R
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.ui.activities.YtArchivePlayerActivity
import com.github.libretube.ui.fragments.DownloadTab
import com.github.libretube.util.YtStreamError
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.NumberFormat

/**
 * YT: the archive's small UI pieces shared by the settings screen and the watch-page badge:
 * sizes and "what fits" texts, an entry's summary, the reading screen (script with speakers, else
 * the transcript), playing a kept copy and the zip export. Views and Material dialogs only (the
 * dialogs take the theme's colours, so no ink question, Pitfall P1).
 */
object YtArchiveUi {
    /** "5 MB", "250 MB", "1 GB", "7,5 GB" (binary units, as the slider's steps are). */
    fun size(bytes: Long): String {
        val mb = bytes.toDouble() / YtArchiveRules.MB
        val number = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }
        return when {
            mb >= 1024 -> "${number.format(mb / 1024)} GB"
            mb >= 10 -> "${mb.toLong()} MB"
            else -> "${number.format(mb)} MB"
        }
    }

    private fun count(value: Long): String = NumberFormat.getIntegerInstance().format(value)

    /** "≈ Text für 5.120 Videos · ≈ 61 Videos mit Ton oder 24 in 144p" (or "· nur Text"). */
    fun fits(context: Context, budget: Long, top: YtArchiveTier): String {
        val fits = YtArchiveRules.fits(budget)
        val text = context.getString(R.string.yt_r31_archive_fits_text, count(fits.text))
        val more = when {
            fits.textOnly -> context.getString(R.string.yt_r31_archive_text_only)
            top == YtArchiveTier.VIDEO -> context.getString(R.string.yt_r31_archive_fits_media, count(fits.audio), count(fits.video))
            else -> context.getString(R.string.yt_r31_archive_fits_audio, count(fits.audio))
        }
        return "$text · $more"
    }

    fun tierLabel(context: Context, tier: YtArchiveTier): String = context.getString(
        when (tier) {
            YtArchiveTier.TEXT -> R.string.yt_r31_archive_tier_text
            YtArchiveTier.AUDIO -> R.string.yt_r31_archive_tier_audio
            YtArchiveTier.VIDEO -> R.string.yt_r31_archive_tier_video
        }
    )

    private fun ago(time: Long): String =
        DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

    /** "Kanal · Ton · 4,2 MB · verschwunden vor 2 Tagen" */
    fun summary(context: Context, meta: YtArchiveMeta): String = listOfNotNull(
        meta.channelName?.takeIf { it.isNotBlank() },
        tierLabel(context, meta.tier),
        size(meta.totalBytes),
        meta.vanishedAt?.let { context.getString(R.string.yt_r31_archive_vanished_on, ago(it)) }
            ?: context.getString(R.string.yt_r31_archive_saved_on, ago(meta.savedAt)),
    ).joinToString(" · ")

    /** The entry has a script made by the script part. */
    fun hasScript(meta: YtArchiveMeta): Boolean = YtArchive.store.files(meta.videoId)?.script?.isFile == true

    private fun labels(context: Context) = YtArchiveText.Labels(
        chapters = context.getString(R.string.yt_r31_archive_chapters),
        transcript = context.getString(R.string.yt_r31_archive_transcript),
        script = context.getString(R.string.yt_r31_archive_script),
        none = context.getString(R.string.yt_r31_archive_no_text),
        speaker = { context.getString(R.string.yt_r31_archive_speaker, it) },
    )

    /** The reading text of [meta] (disk; call off the main thread). */
    fun readingText(context: Context, meta: YtArchiveMeta): String {
        val files = YtArchive.store.files(meta.videoId) ?: return meta.title
        return YtArchiveText.render(meta, YtArchiveText.read(files.transcript), YtArchiveText.readScript(files.script), labels(context))
    }

    /** The reading screen: a dialog with the whole text, selectable. */
    suspend fun showText(context: Context, meta: YtArchiveMeta) {
        val text = withContext(Dispatchers.IO) { readingText(context, meta) }
        val pad = (16 * context.resources.displayMetrics.density).toInt()
        val view = TextView(context).apply {
            setText(text)
            setTextIsSelectable(true)
            setPadding(pad * 3 / 2, pad / 2, pad * 3 / 2, pad)
            textSize = 15f
            setLineSpacing(0f, 1.15f)
        }
        val scroll = NestedScrollView(context).apply {
            addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(if (hasScript(meta)) R.string.yt_r31_archive_read_script else R.string.yt_r31_archive_read_transcript))
            .setView(scroll)
            .setPositiveButton(R.string.okay, null)
            .show()
    }

    /** Plays the archived audio/video of [videoId] in the archive's own player. */
    fun play(context: Context, videoId: String) {
        context.startActivity(
            Intent(context, YtArchivePlayerActivity::class.java)
                .putExtra(YtArchivePlayerActivity.EXTRA_VIDEO_ID, videoId)
        )
    }

    /**
     * Plays the kept copy from the watch page: a LibreTube download first (the better quality,
     * in LibreTube's own offline player), else the archive's audio/video. False when there is
     * nothing to play (text only).
     */
    fun playKept(context: Context, videoId: String, kept: YtArchive.Kept): Boolean {
        if (kept.download) {
            val audioOnly = !kept.downloadHasVideo
            NavigationHelper.navigateVideo(
                context,
                PlayerData(videoId = videoId, isOffline = true, downloadTab = if (audioOnly) DownloadTab.AUDIO else DownloadTab.VIDEO),
                audioOnlyPlayerRequested = audioOnly,
            )
            return true
        }
        val meta = kept.meta ?: return false
        if (meta.tier.level < YtArchiveTier.AUDIO.level) return false
        play(context, videoId)
        return true
    }

    /** "privatisiert • alte Version abspielen" and its kin, by kind and what is kept; null without a badge. */
    fun badgeRes(kind: YtStreamError.Kind, kept: YtArchive.Kept?): Int? {
        if (kept == null || !YtStreamError.removesFromFeed(kind)) return null
        val deleted = kind == YtStreamError.Kind.UNAVAILABLE || kind == YtStreamError.Kind.TERMINATED
        return when {
            kept.playable -> if (deleted) R.string.yt_r31_archive_badge_deleted_play else R.string.yt_r31_archive_badge_private_play
            kept.meta != null -> if (deleted) R.string.yt_r31_archive_badge_deleted_read else R.string.yt_r31_archive_badge_private_read
            else -> null
        }
    }

    /** Writes the zip of [videoId] to the document [uri] the user picked. */
    suspend fun export(context: Context, videoId: String, uri: Uri) {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val meta = YtArchive.store[videoId] ?: return@runCatching false
                val text = readingText(context, meta)
                context.contentResolver.openOutputStream(uri)?.use { YtArchive.store.exportZip(videoId, it, text) } == true
            }.getOrDefault(false)
        }
        Toast.makeText(context, if (ok) R.string.yt_r31_archive_exported else R.string.yt_r31_archive_export_failed, Toast.LENGTH_SHORT).show()
    }

    /** A file name for the export: the title, safe for any file system. */
    fun exportName(meta: YtArchiveMeta): String {
        val safe = meta.title.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), " ").trim().take(60).ifEmpty { meta.videoId }
        return "$safe (${meta.videoId}).zip"
    }
}
