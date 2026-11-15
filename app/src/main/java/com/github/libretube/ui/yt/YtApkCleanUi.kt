package com.github.libretube.ui.yt

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import com.github.libretube.R
import com.github.libretube.util.YtApkClean
import com.github.libretube.util.YtApkCleanRules
import com.github.libretube.util.YtApkCleanRules.AppKind
import com.github.libretube.util.YtApkCleanRules.Outcome
import com.github.libretube.util.YtApkCleanRules.Part
import com.github.libretube.util.YtApkCleanRules.Verdict
import com.github.libretube.util.YtStorageRules
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.divider.MaterialDivider
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Date
import kotlin.coroutines.resume

/**
 * YT: the section "Alte YT-Installationsdateien" at the end of the storage page (YtStorageUi):
 * old YT APKs and artifact zips on the phone and old YT apps, with ticks, one delete button and
 * a summary line. Built from the same Material views and colour roles as the rest of the page.
 *
 * System screens (all-files access, the delete confirmation, uninstall dialogs, the installer)
 * return through the activity's result registry, registered per launch and dropped with the
 * dialog, so the list is searched again as soon as the user is back.
 *
 * [themed] is the dialog's themed context, [context] the one the page was opened from.
 */
class YtApkCleanSection(
    private val themed: Context,
    private val context: Context,
    private val scope: CoroutineScope,
) {
    val view: LinearLayout = LinearLayout(themed).apply { orientation = LinearLayout.VERTICAL }

    private val activity: ComponentActivity? = context.findActivity()
    private var scan: YtApkClean.Scan? = null
    private var scanning = false
    private var busy = false
    private var disposed = false
    private var summary: String? = null

    /** Keys of the ticked files ([YtApkClean.FileItem.key]) and apps ([appKey]). */
    private val ticked = mutableSetOf<String>()
    private val launchers = mutableListOf<ActivityResultLauncher<*>>()
    private var launchCount = 0

    private var selectedText: TextView? = null
    private var deleteButton: MaterialButton? = null

    fun refresh() {
        if (disposed || scanning) return
        scanning = true
        render()
        scope.launch {
            val result = try {
                YtApkClean.scan(context)
            } finally {
                scanning = false
            }
            if (disposed) return@launch
            val keep = YtApkClean.keepCurrent(context)
            ticked.clear()
            result.files.filter { YtApkCleanRules.preselected(it.verdict, keep) }.mapTo(ticked) { it.key }
            result.apps.filter { YtApkCleanRules.appPreselected(it.kind) }.mapTo(ticked) { appKey(it) }
            scan = result
            render()
        }
    }

    fun dispose() {
        disposed = true
        launchers.toList().forEach { it.unregister() }
        launchers.clear()
    }

    private fun appKey(app: YtApkClean.AppItem) = "package:" + app.packageName

    private fun render() {
        view.removeAllViews()
        selectedText = null
        deleteButton = null
        view.addView(
            MaterialDivider(themed),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(12) }
        )
        view.addView(
            text(context.getString(R.string.yt_apkclean_title), primary = true).apply {
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(12), 0, dp(4))
            }
        )
        val now = scan
        if (now == null) {
            view.addView(text(context.getString(R.string.yt_apkclean_searching), primary = false))
            return
        }
        if (!now.access) accessRow()

        if (now.files.isEmpty()) {
            view.addView(
                text(context.getString(R.string.yt_apkclean_none), primary = false).apply {
                    setPadding(0, dp(4), 0, dp(4))
                }
            )
        } else {
            now.files.forEach { view.addView(fileRow(it)) }
        }

        if (now.apps.isNotEmpty()) {
            view.addView(
                text(context.getString(R.string.yt_apkclean_apps_title), primary = true).apply {
                    setPadding(0, dp(8), 0, dp(2))
                }
            )
            now.apps.forEach { view.addView(appRow(it)) }
        }

        val selected = text("", primary = false).apply { setPadding(0, dp(8), 0, 0) }
        selectedText = selected
        view.addView(selected)
        val delete = MaterialButton(
            themed, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            setText(R.string.yt_apkclean_delete_all)
            setOnClickListener { confirmDelete() }
        }
        deleteButton = delete
        view.addView(delete)
        updateSelection()
        summary?.let { view.addView(text(it, primary = true).apply { setPadding(0, dp(4), 0, dp(4)) }) }
        if (scanning) view.addView(text(context.getString(R.string.yt_apkclean_searching), primary = false))

        val auto = MaterialSwitch(themed).apply {
            setText(R.string.yt_apkclean_auto)
            isChecked = YtApkClean.autoClean(context)
            setOnCheckedChangeListener { _, on -> YtApkClean.setAutoClean(context, on) }
        }
        view.addView(
            auto,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) }
        )
        view.addView(text(context.getString(R.string.yt_apkclean_auto_detail), primary = false))
        view.addView(
            MaterialCheckBox(themed).apply {
                setText(R.string.yt_apkclean_keep_current)
                isChecked = YtApkClean.keepCurrent(context)
                setOnCheckedChangeListener { _, on ->
                    YtApkClean.setKeepCurrent(context, on)
                    // the installed version's APK follows the switch
                    scan?.files?.filter { it.verdict == Verdict.CURRENT }?.forEach {
                        if (on) ticked -= it.key else ticked += it.key
                    }
                    view.post { render() }
                }
            }
        )
    }

    private fun accessRow() {
        val legacy = Build.VERSION.SDK_INT < Build.VERSION_CODES.R
        view.addView(
            text(
                context.getString(
                    if (legacy) R.string.yt_apkclean_access_line_legacy else R.string.yt_apkclean_access_line
                ),
                primary = false
            )
        )
        view.addView(
            MaterialButton(themed, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                setText(R.string.yt_apkclean_access_button)
                setOnClickListener { askAccess() }
            }
        )
    }

    private fun askAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            launch(ActivityResultContracts.RequestMultiplePermissions(), YtApkClean.legacyPermissions) { refresh() }
            return
        }
        val intent = YtApkClean.accessIntent(context) ?: return
        val launched = launch(ActivityResultContracts.StartActivityForResult(), intent) { refresh() }
        if (!launched) runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun fileRow(item: YtApkClean.FileItem): View {
        val version = item.build?.let { context.getString(R.string.yt_apkclean_build, it) }
            ?: item.versionName
            ?: context.getString(R.string.yt_apkclean_version_unknown)
        val date = item.modified.takeIf { it > 0 }?.let { DateFormat.getDateFormat(context).format(Date(it)) }
        val folder = if (item.inApp) {
            context.getString(R.string.yt_apkclean_app_cache)
        } else {
            item.folder ?: item.path?.substringBeforeLast('/')
        }
        val note = when (item.verdict) {
            Verdict.CURRENT -> context.getString(R.string.yt_apkclean_current)
            Verdict.NEWER -> context.getString(R.string.yt_apkclean_newer)
            Verdict.NEWER_FOREIGN -> context.getString(R.string.yt_apkclean_newer_foreign)
            Verdict.NEWER_ZIP -> context.getString(R.string.yt_apkclean_newer_zip)
            Verdict.OLD, Verdict.UNKNOWN -> null
        }
        val detail = listOfNotNull(version, YtStorageRules.formatSize(item.size), date, folder, note)
            .joinToString(" · ")
        val selectable = YtApkCleanRules.selectable(item.verdict)
        val action = if (item.verdict == Verdict.NEWER) {
            MaterialButton(themed, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                setText(R.string.yt_apkclean_install)
                setOnClickListener { install(item) }
            }
        } else {
            null
        }
        return checkRow(item.key, selectable, item.name, detail, action)
    }

    private fun appRow(app: YtApkClean.AppItem): View {
        val original = app.kind == AppKind.LIBRETUBE_ORIGINAL
        val title = if (original) context.getString(R.string.yt_apkclean_libretube) else app.label
        val version = app.build?.let { context.getString(R.string.yt_apkclean_build, it) } ?: app.versionName
        val note = if (original) context.getString(R.string.yt_apkclean_libretube_note) else null
        val detail = listOfNotNull(app.packageName, version, note).joinToString(" · ")
        return checkRow(appKey(app), selectable = true, title = title, detail = detail, action = null)
    }

    private fun checkRow(key: String, selectable: Boolean, title: String, detail: String, action: View?): View {
        val box = MaterialCheckBox(themed).apply {
            isEnabled = selectable && !busy
            isChecked = selectable && key in ticked
            setOnCheckedChangeListener { _, on ->
                if (on) ticked += key else ticked -= key
                updateSelection()
            }
        }
        val texts = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, primary = true).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f) })
            addView(text(detail, primary = false))
        }
        return LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2))
            addView(box)
            addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            action?.let { addView(it) }
        }
    }

    private fun tickedFiles(): List<YtApkClean.FileItem> =
        scan?.files.orEmpty().filter { YtApkCleanRules.selectable(it.verdict) && it.key in ticked }

    private fun tickedApps(): List<YtApkClean.AppItem> = scan?.apps.orEmpty().filter { appKey(it) in ticked }

    /** The "Ausgewählt" line and the delete button, without rebuilding the list. */
    private fun updateSelection() {
        val files = tickedFiles()
        val apps = tickedApps()
        val what = selectionText(files, apps)
        selectedText?.text = what?.let { context.getString(R.string.yt_apkclean_selected, it) }
            ?: context.getString(R.string.yt_apkclean_selected, "–")
        deleteButton?.isEnabled = !busy && what != null
    }

    private fun selectionText(files: List<YtApkClean.FileItem>, apps: List<YtApkClean.AppItem>): String? {
        if (files.isEmpty() && apps.isEmpty()) return null
        val bytes = YtApkCleanRules.selectedBytes(files.map { it.size to true })
        return buildList {
            if (files.isNotEmpty()) {
                add(context.resources.getQuantityString(R.plurals.yt_apkclean_files, files.size, files.size))
                add(YtStorageRules.formatSize(bytes))
            }
            if (apps.isNotEmpty()) {
                add(context.resources.getQuantityString(R.plurals.yt_apkclean_apps, apps.size, apps.size))
            }
        }.joinToString(" · ")
    }

    private fun confirmDelete() {
        if (busy) return
        val files = tickedFiles()
        val apps = tickedApps()
        val what = selectionText(files, apps) ?: return
        MaterialAlertDialogBuilder(themed)
            .setMessage(context.getString(R.string.yt_apkclean_confirm, what))
            .setPositiveButton(R.string.yt_storage_delete) { _, _ -> deleteAll(files, apps) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteAll(files: List<YtApkClean.FileItem>, apps: List<YtApkClean.AppItem>) {
        busy = true
        summary = null
        render()
        scope.launch {
            var outcome = Outcome()
            try {
                val deleted = YtApkClean.delete(context, files)
                outcome += deleted.outcome
                if (deleted.needConfirm.isNotEmpty()) {
                    // another app's files seen through MediaStore: one system confirmation for all
                    val sender = YtApkClean.deleteRequest(context, deleted.needConfirm)
                    val result = sender?.let {
                        await(ActivityResultContracts.StartIntentSenderForResult(), IntentSenderRequest.Builder(it).build())
                    }
                    outcome += if (result == null) {
                        Outcome(filesFailed = deleted.needConfirm.size)
                    } else {
                        YtApkClean.confirmedOutcome(context, deleted.needConfirm, result.resultCode == Activity.RESULT_OK)
                    }
                }
                // Android asks for every uninstall itself; one after the other
                for (app in apps) {
                    val result = await(ActivityResultContracts.StartActivityForResult(), YtApkClean.uninstallIntent(app.packageName))
                    if (result != null) delay(300)
                    outcome += if (result != null && !YtApkClean.isInstalled(context, app.packageName)) {
                        Outcome(appsRemoved = 1)
                    } else {
                        Outcome(appsKept = 1)
                    }
                }
            } finally {
                busy = false
            }
            if (disposed) return@launch
            val line = summaryText(outcome)
            summary = line
            Toast.makeText(context, line, Toast.LENGTH_SHORT).show()
            refresh()
        }
    }

    private fun summaryText(outcome: Outcome): String = YtApkCleanRules.summaryParts(outcome).joinToString(" · ") { (part, n) ->
        val count = n.toInt()
        val res = context.resources
        when (part) {
            Part.FILES -> res.getQuantityString(R.plurals.yt_apkclean_files_deleted, count, count)
            Part.FREED -> context.getString(R.string.yt_apkclean_freed, YtStorageRules.formatSize(n))
            Part.APPS -> res.getQuantityString(R.plurals.yt_apkclean_apps_removed, count, count)
            Part.FILES_FAILED -> res.getQuantityString(R.plurals.yt_apkclean_files_failed, count, count)
            Part.APPS_KEPT -> res.getQuantityString(R.plurals.yt_apkclean_apps_kept, count, count)
            Part.NOTHING -> context.getString(R.string.yt_apkclean_nothing)
        }
    }

    private fun install(item: YtApkClean.FileItem) {
        if (busy) return
        scope.launch {
            val intent = YtApkClean.installIntent(context, item)
            if (disposed) return@launch
            if (intent == null) {
                Toast.makeText(context, R.string.yt_apkclean_install_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val launched = launch(ActivityResultContracts.StartActivityForResult(), intent) { refresh() }
            if (!launched) {
                runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure {
                        Toast.makeText(context, R.string.yt_apkclean_install_failed, Toast.LENGTH_SHORT).show()
                    }
            }
        }
    }

    /**
     * Starts a system screen through the activity's result registry; the registration lives for
     * this one launch. False when there is no activity or the screen does not open.
     */
    private fun <I, O> launch(contract: ActivityResultContract<I, O>, input: I, onResult: (O) -> Unit): Boolean {
        val host = activity ?: return false
        var registered: ActivityResultLauncher<I>? = null
        val launcher = host.activityResultRegistry.register(
            "yt_apkclean_" + SystemClock.uptimeMillis() + "_" + launchCount++,
            contract,
        ) { output ->
            registered?.let {
                it.unregister()
                launchers -= it
            }
            if (!disposed) onResult(output)
        }
        registered = launcher
        launchers += launcher
        return runCatching { launcher.launch(input) }
            .onFailure {
                launcher.unregister()
                launchers -= launcher
            }
            .isSuccess
    }

    /** [launch] that waits for the result; null when the screen did not open. */
    private suspend fun <I, O> await(contract: ActivityResultContract<I, O>, input: I): O? =
        suspendCancellableCoroutine { cont ->
            val launched = launch(contract, input) { if (cont.isActive) cont.resume(it) }
            if (!launched && cont.isActive) cont.resume(null)
        }

    private fun text(value: String, primary: Boolean) = TextView(themed).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (primary) 16f else 14f)
        setTextColor(
            MaterialColors.getColor(
                themed,
                if (primary) {
                    com.google.android.material.R.attr.colorOnSurface
                } else {
                    com.google.android.material.R.attr.colorOnSurfaceVariant
                },
                currentTextColor
            )
        )
    }

    private fun dp(value: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), themed.resources.displayMetrics
    ).toInt()

    private companion object {
        tailrec fun Context.findActivity(): ComponentActivity? = when (this) {
            is ComponentActivity -> this
            is ContextWrapper -> baseContext.findActivity()
            else -> null
        }
    }
}
