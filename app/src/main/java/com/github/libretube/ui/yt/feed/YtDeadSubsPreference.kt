package com.github.libretube.ui.yt.feed

import android.content.Context
import android.content.DialogInterface
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import com.github.libretube.R
import com.github.libretube.api.obj.Subscription
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.ContextHelper
import com.github.libretube.helpers.YtDeadSubs
import com.github.libretube.ui.yt.ytAgeText
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * YT: "Tote Abos finden" next to "Pausierte Kanäle": checks the subscriptions (YtDeadSubs, with a
 * progress dialog that can be cancelled) and lists the channels without an upload for over a
 * year with the age of their last upload. The ticked ones are unsubscribed, with an undo.
 * Stores nothing itself.
 */
class YtDeadSubsPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {
    init {
        isPersistent = false
    }

    override fun onClick() {
        val activity = ContextHelper.tryUnwrapActivity<AppCompatActivity>(context) ?: return
        val bar = LinearProgressIndicator(activity).apply { isIndeterminate = true }
        val pad = (24 * activity.resources.displayMetrics.density).toInt()
        val frame = FrameLayout(activity).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(bar)
        }
        val progress = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(R.string.yt_dead_subs_loading)
            .setView(frame)
            .setNegativeButton(R.string.cancel, null)
            .show()
        val job = activity.lifecycleScope.launch {
            try {
                val subs = withContext(Dispatchers.IO) { YtDeadSubs.subscriptions() }
                if (subs.isEmpty()) {
                    progress.dismiss()
                    message(activity, activity.getString(R.string.yt_dead_subs_no_subs))
                    return@launch
                }
                val result = YtDeadSubs.scan(subs) { done, total ->
                    if (bar.isIndeterminate) {
                        // switching needs the bar hidden, else it ignores the new mode
                        bar.visibility = View.INVISIBLE
                        bar.isIndeterminate = false
                        bar.visibility = View.VISIBLE
                    }
                    bar.max = total
                    bar.setProgressCompat(done, true)
                    progress.setMessage(activity.resources.getQuantityString(R.plurals.yt_dead_subs_progress, total, done, total))
                }
                progress.dismiss()
                val byId = subs.associateBy { it.url.toID() }
                val dead = result.dead.mapNotNull { entry -> byId[entry.id]?.let { it to entry } }
                val unknown = if (result.unknown == 0) null else
                    activity.resources.getQuantityString(R.plurals.yt_dead_subs_unknown, result.unknown, result.unknown)
                if (dead.isEmpty()) {
                    // "all uploaded" only when every channel could be checked
                    val none = activity.getString(if (unknown == null) R.string.yt_dead_subs_none else R.string.yt_dead_subs_none_checked)
                    message(activity, listOfNotNull(none, unknown).joinToString("\n\n"))
                } else {
                    pick(activity, dead, unknown)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                progress.dismiss()
                message(activity, activity.getString(R.string.server_error))
            }
        }
        progress.setOnDismissListener { job.cancel() }
    }

    private fun message(activity: AppCompatActivity, text: String) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** The list of dead channels; the ticked ones are unsubscribed. */
    private fun pick(activity: AppCompatActivity, dead: List<Pair<Subscription, YtDeadSub>>, unknown: String?) {
        val labels = dead.map<Pair<Subscription, YtDeadSub>, CharSequence> { (sub, entry) ->
            sub.name.ifBlank { entry.id } + " · " + label(activity, entry)
        }.toTypedArray()
        val checked = BooleanArray(dead.size)
        val resources = activity.resources
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(resources.getQuantityString(R.plurals.yt_dead_subs_found, dead.size, dead.size))
            .setMultiChoiceItems(labels, checked, null)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.yt_dead_subs_select_all, null)
            .setPositiveButton(R.string.unsubscribe) { _, _ ->
                val chosen = dead.filterIndexed { i, _ -> checked[i] }.map { it.first }
                if (chosen.isNotEmpty()) unsubscribe(activity, chosen)
            }
            .show()
        val positive = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
        val neutral = dialog.getButton(DialogInterface.BUTTON_NEUTRAL)
        fun update() {
            val count = checked.count { it }
            positive.isEnabled = count > 0
            positive.text = if (count == 0) activity.getString(R.string.unsubscribe)
            else activity.getString(R.string.yt_dead_subs_unsubscribe_count, activity.getString(R.string.unsubscribe), count)
            neutral.setText(if (count == checked.size) R.string.yt_dead_subs_select_none else R.string.yt_dead_subs_select_all)
        }
        dialog.listView.setOnItemClickListener { _, _, position, _ ->
            checked[position] = dialog.listView.isItemChecked(position)
            update()
        }
        // the neutral button ticks all (or none) and keeps the dialog open
        neutral.setOnClickListener {
            val all = checked.any { !it }
            checked.indices.forEach { i ->
                checked[i] = all
                dialog.listView.setItemChecked(i, all)
            }
            update()
        }
        update()
        if (unknown != null) {
            // a multi-choice list has no room for a message
            Toast.makeText(activity, unknown, Toast.LENGTH_LONG).show()
        }
    }

    private fun label(context: Context, entry: YtDeadSub): String = when (entry.state) {
        YtSubState.GONE -> context.getString(R.string.yt_dead_subs_gone)
        YtSubState.NONE -> context.getString(R.string.yt_dead_subs_no_videos)
        YtSubState.UPLOADS -> ytAgeText(context, entry.last, ago = true)
            ?.let { context.getString(R.string.yt_dead_subs_last, it) }
            ?: context.getString(R.string.yt_dead_subs_no_videos)
    }

    private fun unsubscribe(activity: AppCompatActivity, chosen: List<Subscription>) {
        // the work outlives the settings screen: leaving it must not stop it half way
        appScope.launch {
            val gone = YtDeadSubs.unsubscribe(chosen)
            withContext(Dispatchers.Main) {
                if (activity.isFinishing || activity.isDestroyed) return@withContext
                val text = activity.resources.getQuantityString(R.plurals.yt_dead_subs_done, gone.size, gone.size)
                val root = activity.findViewById<View>(android.R.id.content) ?: return@withContext
                Snackbar.make(root, text, Snackbar.LENGTH_LONG)
                    .apply { if (gone.isNotEmpty()) setAction(R.string.undo) { appScope.launch { YtDeadSubs.resubscribe(gone) } } }
                    .show()
            }
        }
    }

    private companion object {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
