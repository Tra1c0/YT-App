package com.github.libretube.ui.yt

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.util.TypedValue
import android.widget.ScrollView
import android.widget.TextView
import com.github.libretube.R
import com.github.libretube.helpers.ClipboardHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.util.YtCrashLog
import com.github.libretube.util.YtDiagnosis
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * YT: the crash dialog on the next start and the diagnosis page in the settings. Both are plain
 * Material dialogs on the theme's own surface, so the theme's text colours keep their contrast.
 */
object YtDiagnosisUi {

    /**
     * Shows the crash dialog once after a crash. Clears the upstream error log too, so its own
     * error dialog does not show the same crash a second time.
     */
    fun showCrashIfNew(context: Context) {
        if (!YtCrashLog.hasNew(context)) return
        val report = YtCrashLog.reports(context).firstOrNull()
        if (report == null) {
            YtCrashLog.markSeen(context)
            return
        }
        PreferenceHelper.saveErrorLog("")
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.yt_crash_title)
            .setMessage(R.string.yt_crash_message)
            .setPositiveButton(R.string.yt_crash_copy) { _, _ -> copy(context, report) }
            .setNeutralButton(R.string.yt_diag_share) { _, _ -> share(context, report) }
            .setNegativeButton(R.string.yt_diag_close, null)
            // any way out counts as seen, so the dialog never comes back for this crash
            .setOnDismissListener { YtCrashLog.markSeen(context) }
            .show()
    }

    /** The diagnosis page: one text block, copy and share, and the stored crash reports. */
    fun showDiagnosis(context: Context) {
        val text = YtDiagnosis.text(context)
        val crashes = YtCrashLog.allText(context)
        val builder = MaterialAlertDialogBuilder(context)
        val scroll = textView(builder.context, text)
        val view = scroll.getChildAt(0) as TextView
        // the sound line is live: read again twice a second while the page is open
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val soundRow = "Sound: "
        val refresh = object : Runnable {
            override fun run() {
                val now = view.text.toString()
                val at = now.indexOf(soundRow)
                if (at >= 0) {
                    val end = now.indexOf('\n', at).let { if (it < 0) now.length else it }
                    view.text = now.substring(0, at) + soundRow + YtDiagnosis.sound() + now.substring(end)
                }
                handler.postDelayed(this, 500L)
            }
        }
        view.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) {
                handler.post(refresh)
            }

            override fun onViewDetachedFromWindow(v: android.view.View) {
                handler.removeCallbacks(refresh)
            }
        })
        builder.setTitle(R.string.yt_diag_title)
            .setView(scroll)
            .setPositiveButton(R.string.yt_diag_copy) { _, _ -> copy(context, view.text.toString()) }
            .setNegativeButton(R.string.yt_diag_close, null)
        if (crashes.isNotEmpty()) {
            builder.setNeutralButton(R.string.yt_diag_crashes) { _, _ -> showCrashes(context, crashes) }
        } else {
            builder.setNeutralButton(R.string.yt_diag_share) { _, _ -> share(context, view.text.toString()) }
        }
        builder.show()
    }

    private fun showCrashes(context: Context, crashes: String) {
        val builder = MaterialAlertDialogBuilder(context)
        builder.setTitle(R.string.yt_diag_crashes)
            .setView(textView(builder.context, crashes))
            .setPositiveButton(R.string.yt_crash_copy) { _, _ -> copy(context, crashes) }
            .setNeutralButton(R.string.yt_diag_share) { _, _ -> share(context, crashes) }
            .setNegativeButton(R.string.yt_diag_close, null)
            .show()
    }

    /** [context] is the dialog's themed context, so colorOnSurface matches the dialog surface. */
    private fun textView(context: Context, text: String): ScrollView {
        val pad = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 24f, context.resources.displayMetrics
        ).toInt()
        val view = TextView(context).apply {
            this.text = text
            typeface = Typeface.MONOSPACE
            setTextColor(
                MaterialColors.getColor(
                    context, com.google.android.material.R.attr.colorOnSurface, currentTextColor
                )
            )
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextIsSelectable(true)
            setPadding(pad, pad / 2, pad, 0)
        }
        return ScrollView(context).apply { addView(view) }
    }

    private fun copy(context: Context, text: String) {
        ClipboardHelper.save(context, text = text, notify = true)
    }

    private fun share(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        runCatching {
            context.startActivity(Intent.createChooser(send, null))
        }
    }
}
