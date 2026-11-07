package com.github.libretube.ui.yt

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.github.libretube.R
import com.github.libretube.constants.IntentData
import com.github.libretube.ui.activities.MainActivity
import com.github.libretube.util.YtStorage
import com.github.libretube.util.YtStorageRules
import com.github.libretube.util.YtStorageRules.Row
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * YT: the storage page (Settings → Storage), a Material dialog like the diagnosis page, so the
 * theme's own text colours on its surface keep their contrast. Sizes are measured off the main
 * thread (YtStorage) and again after every clear.
 */
object YtStorageUi {

    private val order = listOf(Row.IMAGES, Row.WEB, Row.UPDATE, Row.OTHER, Row.CRASH, Row.DOWNLOADS)

    fun show(context: Context) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val builder = MaterialAlertDialogBuilder(context)
        val themed = builder.context
        val content = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(themed, 24)
            setPadding(pad, dp(themed, 8), dp(themed, 12), 0)
        }
        val dialog = builder
            .setTitle(R.string.yt_storage_title)
            .setView(ScrollView(themed).apply { addView(content) })
            .setPositiveButton(R.string.yt_storage_clear_all, null)
            .setNegativeButton(R.string.yt_diag_close, null)
            .setOnDismissListener { scope.cancel() }
            .create()

        var report: YtStorage.Report? = null

        var busy = false

        fun clear(rows: Set<Row>, refresh: () -> Unit) {
            // a second tap while a clear runs would only measure twice
            if (busy) return
            busy = true
            val before = report
            scope.launch {
                val after = try {
                    YtStorage.clear(themed, rows)
                    YtStorage.scan(themed)
                } finally {
                    busy = false
                }
                if (before != null) {
                    val freed = rows.sumOf { before.usage(it).bytes - after.usage(it).bytes }
                        .coerceAtLeast(0)
                    Toast.makeText(
                        context,
                        context.getString(R.string.yt_storage_freed, YtStorageRules.formatSize(freed)),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                report = after
                refresh()
            }
        }

        lateinit var render: () -> Unit
        render = {
            content.removeAllViews()
            val now = report
            if (now == null) {
                content.addView(text(themed, context.getString(R.string.yt_storage_computing), primary = false))
            } else {
                val total = now.total?.let {
                    context.getString(R.string.yt_storage_total, YtStorageRules.formatSize(it))
                } ?: context.getString(R.string.yt_storage_total_unknown)
                content.addView(
                    text(themed, total, primary = true).apply {
                        setPadding(0, 0, 0, dp(themed, 8))
                    }
                )
                for (row in order) content.addView(rowView(themed, context, now, row) { action ->
                    when (action) {
                        Action.CLEAR -> if (row == Row.CRASH) {
                            confirm(themed, context.getString(R.string.yt_storage_crash_confirm)) {
                                clear(setOf(Row.CRASH), render)
                            }
                        } else {
                            clear(setOf(row), render)
                        }
                        Action.OPEN -> {
                            dialog.dismiss()
                            openDownloads(context)
                        }
                    }
                })
            }
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.isEnabled =
                now != null && YtStorageRules.clearAllRows.any { now.usage(it).clearableBytes > 0 }
        }

        dialog.setOnShowListener {
            // own listener: the dialog stays open and shows the new sizes after clearing
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
                val now = report ?: return@setOnClickListener
                val bytes = YtStorageRules.clearAllRows.sumOf { now.usage(it).clearableBytes }
                confirm(
                    themed,
                    context.getString(R.string.yt_storage_clear_all_confirm, YtStorageRules.formatSize(bytes))
                ) { clear(YtStorageRules.clearAllRows, render) }
            }
            render()
        }
        dialog.show()
        scope.launch {
            report = YtStorage.scan(themed)
            render()
        }
    }

    private enum class Action { CLEAR, OPEN }

    private fun rowView(
        themed: Context,
        context: Context,
        report: YtStorage.Report,
        row: Row,
        onAction: (Action) -> Unit,
    ): View {
        val usage = report.usage(row)
        val size = YtStorageRules.formatSize(usage.bytes)
        val title = context.getString(
            when (row) {
                Row.IMAGES -> R.string.yt_storage_images
                Row.WEB -> R.string.yt_storage_web
                Row.UPDATE -> R.string.yt_storage_update
                Row.OTHER -> R.string.yt_storage_other
                Row.CRASH -> R.string.yt_storage_crash
                Row.DOWNLOADS -> R.string.yt_storage_downloads
            }
        )
        val detail = when {
            row == Row.CRASH -> context.resources.getQuantityString(
                R.plurals.yt_storage_crash_detail, report.crashReports, report.crashReports, size
            )
            row == Row.UPDATE && usage.kept > 0 -> context.getString(R.string.yt_storage_update_held, size)
            else -> size
        }
        val texts = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(themed, title, primary = true))
            addView(text(themed, detail, primary = false))
        }
        val clearable = YtStorageRules.clearable(row)
        val button = MaterialButton(
            themed, null, androidx.appcompat.R.attr.borderlessButtonStyle
        ).apply {
            setText(if (clearable) R.string.yt_storage_clear else R.string.yt_storage_open)
            isEnabled = if (clearable) usage.clearableBytes > 0 else true
            setOnClickListener { onAction(if (clearable) Action.CLEAR else Action.OPEN) }
        }
        return LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(themed, 4), 0, dp(themed, 4))
            addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(button)
        }
    }

    /** [context] is the dialog's themed context, so the colours match the dialog surface. */
    private fun text(context: Context, value: String, primary: Boolean) = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (primary) 16f else 14f)
        setTextColor(
            MaterialColors.getColor(
                context,
                if (primary) {
                    com.google.android.material.R.attr.colorOnSurface
                } else {
                    com.google.android.material.R.attr.colorOnSurfaceVariant
                },
                currentTextColor
            )
        )
    }

    private fun confirm(context: Context, message: String, onYes: () -> Unit) {
        MaterialAlertDialogBuilder(context)
            .setMessage(message)
            .setPositiveButton(R.string.yt_storage_delete) { _, _ -> onYes() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openDownloads(context: Context) {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(IntentData.OPEN_DOWNLOADS, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { context.startActivity(intent) }
    }

    private fun dp(context: Context, value: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics
    ).toInt()
}
