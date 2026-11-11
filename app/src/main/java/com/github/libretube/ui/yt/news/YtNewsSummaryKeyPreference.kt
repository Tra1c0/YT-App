package com.github.libretube.ui.yt.news

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.preference.Preference
import com.github.libretube.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * YT: the settings row of the news summaries: enter, test and remove the user's own API key
 * ([YtNewsSummaryKey]). The summary line says whether the feature is on; the key itself is never
 * shown again once saved. The test is a free model lookup, no tokens.
 */
class YtNewsSummaryKeyPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {
    /** Tests outlive the dialog (a toast reports them); nothing here holds the screen. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        isPersistent = false
        refresh()
    }

    private fun refresh() {
        setSummary(if (YtNewsSummaryKey.present.value) R.string.yt_r28_newssum_summary_on else R.string.yt_r28_newssum_summary_off)
    }

    override fun onClick() {
        if (YtNewsSummaryKey.present.value) manage() else enter()
    }

    /** A key is set: test it, remove it or replace it. */
    private fun manage() {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.yt_r28_newssum_dialog_title)
            .setMessage(R.string.yt_r28_newssum_key_set)
            .setPositiveButton(R.string.yt_r28_newssum_test) { _, _ ->
                val key = YtNewsSummaryKey.get()
                if (key == null) {
                    refresh()
                    enter()
                } else {
                    test(key, save = false)
                }
            }
            .setNegativeButton(R.string.yt_r28_newssum_remove) { _, _ ->
                YtNewsSummaryKey.clear()
                YtNewsSummary.cancel()
                refresh()
                toast(R.string.yt_r28_newssum_removed)
            }
            .setNeutralButton(R.string.yt_r28_newssum_replace) { _, _ -> enter() }
            .show()
    }

    /** No key yet (or a new one): the input, then the test. */
    private fun enter() {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setHint(R.string.yt_r28_newssum_dialog_hint)
            isSingleLine = true
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val pad = (20 * context.resources.displayMetrics.density).toInt()
        val frame = FrameLayout(context).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.yt_r28_newssum_dialog_title)
            .setMessage(R.string.yt_r28_newssum_dialog_message)
            .setView(frame)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.yt_r28_newssum_save) { _, _ ->
                val key = input.text?.toString()?.trim().orEmpty()
                input.text?.clear()
                if (key.isNotEmpty()) test(key, save = true)
            }
            .show()
    }

    /** Tests [key]; a new one is saved unless it was rejected. */
    private fun test(key: String, save: Boolean) {
        toast(R.string.yt_r28_newssum_testing)
        scope.launch {
            val failure = withContext(Dispatchers.IO) { YtNewsSummaryApi.check(key) }
            val keep = failure != YtNewsSummaryFailure.INVALID_KEY
            if (save && keep) YtNewsSummaryKey.set(key)
            refresh()
            toast(
                when (failure) {
                    null -> R.string.yt_r28_newssum_test_ok
                    YtNewsSummaryFailure.INVALID_KEY ->
                        if (save) R.string.yt_r28_newssum_test_invalid else R.string.yt_r28_newssum_test_invalid_set
                    YtNewsSummaryFailure.OFFLINE ->
                        if (save) R.string.yt_r28_newssum_test_offline else R.string.yt_r28_newssum_test_offline_set
                    else -> if (save) R.string.yt_r28_newssum_test_busy else R.string.yt_r28_newssum_test_busy_set
                },
            )
        }
    }

    private fun toast(text: Int) {
        Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT).show()
    }
}
