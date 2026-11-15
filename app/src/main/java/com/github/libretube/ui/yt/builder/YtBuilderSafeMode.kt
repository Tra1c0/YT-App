package com.github.libretube.ui.yt.builder

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.github.libretube.R
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.YtDesign
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/**
 * YT: a broken theme never locks the user out. If the app crashes while "Eigenes" is shown (or
 * inside the theme builder), the crash log (YtCrashLog) leaves a marker; at the next start the
 * design falls back to the one used before "Eigenes", and the main screen says so with ways to
 * repair (open the editor) or reset the theme. The same happens when the active theme cannot be
 * read at start. Pure decisions are in [decide], for the tests.
 */
object YtBuilderSafeMode {
    private const val MARKER = "crashed"
    const val PREF_PREVIOUS = "yt_custom_previous"
    const val PREF_DISABLED = "yt_custom_disabled"
    const val REASON_CRASH = "crash"
    const val REASON_LOAD = "load"

    enum class Verdict { NOTHING, LOAD, FALL_BACK_CRASH }

    /** What the start does: [custom] the design is "Eigenes", [crashed] the marker exists. */
    fun decide(custom: Boolean, crashed: Boolean): Verdict = when {
        !custom -> Verdict.NOTHING
        crashed -> Verdict.FALL_BACK_CRASH
        else -> Verdict.LOAD
    }

    /** Whether a crash belongs to the theme: "Eigenes" was shown, or the builder's code threw. */
    fun blames(customActive: Boolean, stack: String): Boolean = customActive || stack.contains(".ui.yt.builder.")

    private fun marker(context: Context) = File(YtBuilderStore.root(context), MARKER)

    /** From the crash handler (synchronous, never throws). */
    fun onCrash(context: Context, error: Throwable) {
        runCatching {
            val custom = YtDesign.current() == YtDesign.CUSTOM
            if (!blames(custom, error.stackTraceToString())) return
            YtBuilderStore.root(context).mkdirs()
            marker(context).writeText(System.currentTimeMillis().toString())
        }
    }

    /** App start, after PreferenceHelper is ready. */
    fun onAppStart(context: Context) {
        runCatching {
            val design = YtDesign.current()
            if (design != YtDesign.CUSTOM && design != YtDesign.STANDARD) PreferenceHelper.putString(PREF_PREVIOUS, design.key)
            val crashed = marker(context).exists()
            when (decide(design == YtDesign.CUSTOM, crashed)) {
                Verdict.NOTHING -> if (crashed) marker(context).delete()
                Verdict.FALL_BACK_CRASH -> fallBack(context, REASON_CRASH)
                Verdict.LOAD -> if (!YtBuilderStore.loadActiveAtStart(context)) fallBack(context, REASON_LOAD)
            }
        }.onFailure {
            // even the fallback failed: the classic look is always safe
            runCatching { PreferenceHelper.putString(YtDesign.PREF_KEY, YtDesign.STANDARD.key) }
        }
    }

    private fun fallBack(context: Context, reason: String) {
        marker(context).delete()
        val previous = PreferenceHelper.getString(PREF_PREVIOUS, YtDesign.YOUTUBE.key)
            .takeIf { key -> YtDesign.entries.any { it.key == key && it != YtDesign.CUSTOM } } ?: YtDesign.YOUTUBE.key
        PreferenceHelper.putString(YtDesign.PREF_KEY, previous)
        PreferenceHelper.putString(PREF_DISABLED, reason)
    }

    /** The main screen: tells once that the theme was turned off, with repair and reset. */
    fun showNotice(activity: Activity) {
        val reason = runCatching { PreferenceHelper.getString(PREF_DISABLED, "") }.getOrDefault("")
        if (reason.isEmpty()) return
        PreferenceHelper.putString(PREF_DISABLED, "")
        val message = if (reason == REASON_LOAD) R.string.yt_builder_safe_load else R.string.yt_builder_safe_crash
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.yt_builder_safe_title)
            .setMessage(message)
            .setPositiveButton(R.string.yt_builder_safe_repair) { _, _ ->
                activity.startActivity(Intent(activity, YtThemeEditorActivity::class.java).putExtra(YtThemeEditorActivity.EXTRA_SAFE, true))
            }
            .setNeutralButton(R.string.yt_builder_safe_reset) { _, _ -> reset(activity) }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    /** Replaces the active theme by a fresh one from the design it started from (keeps a version). */
    private fun reset(context: Context) {
        Thread {
            runCatching {
                val id = YtBuilderStore.activeId() ?: return@runCatching
                val old = (YtBuilderStore.load(context, id) as? YtThemeCodec.Read.Ok)?.doc
                old?.let { YtBuilderStore.snapshot(context, it) }
                val base = YtDesign.entries.firstOrNull { it.key == old?.base } ?: YtDesign.YOUTUBE
                val fresh = YtBuilderActive.create(base, old?.name ?: context.getString(R.string.yt_builder_default_name)).copy(id = id)
                YtBuilderStore.save(context, fresh)
            }
        }.start()
    }
}
