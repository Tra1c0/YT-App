package com.github.libretube.ui.yt.news

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import com.github.libretube.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * YT: the settings rows of the news summaries, in the News section: the provider ("Anbieter",
 * off by default), then for the chosen one its key (enter, free test, save; test, replace,
 * remove), an optional model and, for the compatible one, its address. Everything is stored in
 * [YtNewsSummaryKey]; a key is never shown again once saved.
 */

/** A row that redraws itself from [YtNewsSummaryKey] when any summary setting changes. */
abstract class YtNewsSummaryRow(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {
    init {
        isPersistent = false
    }

    override fun onAttached() {
        super.onAttached()
        refresh()
    }

    abstract fun refresh()

    /** Redraws every summary row of the section (visibility follows the provider). */
    protected fun refreshAll() {
        val group: PreferenceGroup = parent ?: return refresh()
        for (i in 0 until group.preferenceCount) (group.getPreference(i) as? YtNewsSummaryRow)?.refresh()
    }

    protected fun toast(text: CharSequence) {
        Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT).show()
    }

    protected fun toast(text: Int) = toast(context.getString(text))

    /** A one-line text field in a padded frame, for the dialogs. */
    protected fun field(type: Int, hint: CharSequence, value: String? = null): Pair<FrameLayout, EditText> {
        val input = EditText(context).apply {
            inputType = type
            this.hint = hint
            isSingleLine = true
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            value?.let { setText(it) }
        }
        val pad = (20 * context.resources.displayMetrics.density).toInt()
        val frame = FrameLayout(context).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        return frame to input
    }
}

/** The names and hints of the providers in the settings. */
object YtNewsProviderLabels {
    /** The order of the choice; null is "Aus". */
    val CHOICES: List<YtNewsProviderKind?> = listOf(
        null,
        YtNewsProviderKind.OPENAI,
        YtNewsProviderKind.ANTHROPIC,
        YtNewsProviderKind.GEMINI,
        YtNewsProviderKind.MISTRAL,
        YtNewsProviderKind.COMPATIBLE,
    )

    fun name(kind: YtNewsProviderKind?): Int = when (kind) {
        null -> R.string.yt_r28_newssum_provider_off
        YtNewsProviderKind.ANTHROPIC -> R.string.yt_r28_newssum_provider_anthropic
        YtNewsProviderKind.OPENAI -> R.string.yt_r28_newssum_provider_openai
        YtNewsProviderKind.GEMINI -> R.string.yt_r28_newssum_provider_gemini
        YtNewsProviderKind.MISTRAL -> R.string.yt_r28_newssum_provider_mistral
        YtNewsProviderKind.COMPATIBLE -> R.string.yt_r28_newssum_provider_compatible
    }

    /** "OpenAI API key"; the compatible provider's key has no brand. */
    fun keyName(context: Context, kind: YtNewsProviderKind): String = when (kind) {
        YtNewsProviderKind.ANTHROPIC -> context.getString(R.string.yt_r28_newssum_key_named, "Anthropic")
        YtNewsProviderKind.OPENAI -> context.getString(R.string.yt_r28_newssum_key_named, "OpenAI")
        YtNewsProviderKind.GEMINI -> context.getString(R.string.yt_r28_newssum_key_named, "Gemini")
        YtNewsProviderKind.MISTRAL -> context.getString(R.string.yt_r28_newssum_key_named, "Mistral")
        YtNewsProviderKind.COMPATIBLE -> context.getString(R.string.yt_r28_newssum_dialog_hint)
    }

    /** Where the key comes from. */
    fun keyHint(kind: YtNewsProviderKind): Int = when (kind) {
        YtNewsProviderKind.ANTHROPIC -> R.string.yt_r28_newssum_hint_anthropic
        YtNewsProviderKind.OPENAI -> R.string.yt_r28_newssum_hint_openai
        YtNewsProviderKind.GEMINI -> R.string.yt_r28_newssum_hint_gemini
        YtNewsProviderKind.MISTRAL -> R.string.yt_r28_newssum_hint_mistral
        YtNewsProviderKind.COMPATIBLE -> R.string.yt_r28_newssum_hint_compatible
    }
}

/** "Anbieter": off, or one of the providers. Choosing one sends nothing yet. */
class YtNewsSummaryProviderPreference(context: Context, attrs: AttributeSet?) : YtNewsSummaryRow(context, attrs) {
    override fun refresh() {
        val kind = YtNewsSummaryKey.provider()
        summary = if (kind == null) {
            context.getString(R.string.yt_r28_newssum_summary_off)
        } else {
            context.getString(R.string.yt_r28_newssum_provider_is, context.getString(YtNewsProviderLabels.name(kind)))
        }
    }

    override fun onClick() {
        val choices = YtNewsProviderLabels.CHOICES
        val current = choices.indexOf(YtNewsSummaryKey.provider()).coerceAtLeast(0)
        val names = choices.map { context.getString(YtNewsProviderLabels.name(it)) }.toTypedArray()
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.yt_r28_newssum_provider)
            .setSingleChoiceItems(names, current) { dialog, which ->
                YtNewsSummary.cancel()
                YtNewsSummaryKey.setProvider(choices[which])
                refreshAll()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}

/** The chosen provider's key: enter, test and remove it; the key itself is never shown again. */
class YtNewsSummaryKeyPreference(context: Context, attrs: AttributeSet?) : YtNewsSummaryRow(context, attrs) {
    /** Tests outlive the dialog (a toast reports them); nothing here holds the screen. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun refresh() {
        val kind = YtNewsSummaryKey.provider()
        isVisible = kind != null
        if (kind == null) return
        title = YtNewsProviderLabels.keyName(context, kind)
        summary = if (YtNewsSummaryKey.hasKey(kind)) {
            context.getString(R.string.yt_r28_newssum_summary_on)
        } else {
            context.getString(R.string.yt_r28_newssum_key_unset, context.getString(YtNewsProviderLabels.keyHint(kind)))
        }
    }

    override fun onClick() {
        val kind = YtNewsSummaryKey.provider() ?: return
        if (YtNewsSummaryKey.hasKey(kind)) manage(kind) else enter(kind)
    }

    /** A key is set: test it, remove it or replace it. */
    private fun manage(kind: YtNewsProviderKind) {
        MaterialAlertDialogBuilder(context)
            .setTitle(YtNewsProviderLabels.keyName(context, kind))
            .setMessage(R.string.yt_r28_newssum_key_set)
            .setPositiveButton(R.string.yt_r28_newssum_test) { _, _ ->
                val key = YtNewsSummaryKey.get(kind)
                if (key == null) {
                    refreshAll()
                    enter(kind)
                } else {
                    test(kind, key, save = false)
                }
            }
            .setNegativeButton(R.string.yt_r28_newssum_remove) { _, _ ->
                YtNewsSummaryKey.clear(kind)
                YtNewsSummary.cancel()
                refreshAll()
                toast(R.string.yt_r28_newssum_removed)
            }
            .setNeutralButton(R.string.yt_r28_newssum_replace) { _, _ -> enter(kind) }
            .show()
    }

    /** No key yet (or a new one): the input, then the test. */
    private fun enter(kind: YtNewsProviderKind) {
        val name = YtNewsProviderLabels.keyName(context, kind)
        val (frame, input) = field(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            name,
        )
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.yt_r28_newssum_dialog_title)
            .setMessage(context.getString(R.string.yt_r28_newssum_dialog_message, name, context.getString(YtNewsProviderLabels.keyHint(kind))))
            .setView(frame)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.yt_r28_newssum_save) { _, _ ->
                val key = input.text?.toString()?.trim().orEmpty()
                input.text?.clear()
                val other = YtNewsProviders.mismatch(kind, key)
                when {
                    key.isEmpty() -> Unit
                    other != null -> toast(context.getString(R.string.yt_r28_newssum_wrong_key, context.getString(YtNewsProviderLabels.name(other))))
                    else -> test(kind, key, save = true)
                }
            }
            .show()
    }

    /** Tests [key] with a free request; a new one is saved unless it was rejected. */
    private fun test(kind: YtNewsProviderKind, key: String, save: Boolean) {
        val address = YtNewsSummaryKey.address()
        if (kind == YtNewsProviderKind.COMPATIBLE && (address == null || YtNewsHttp.baseUrl(address) == null)) {
            if (save) YtNewsSummaryKey.set(kind, key)
            refreshAll()
            toast(R.string.yt_r28_newssum_address_first)
            return
        }
        val setup = YtNewsProviderSetup(kind, key, YtNewsProviders.model(kind, YtNewsSummaryKey.model(kind)).orEmpty(), address)
        toast(R.string.yt_r28_newssum_testing)
        scope.launch {
            val failure = withContext(Dispatchers.IO) { YtNewsProviders.of(kind).check(setup) }
            val keep = failure != YtNewsSummaryFailure.INVALID_KEY
            if (save && keep) YtNewsSummaryKey.set(kind, key)
            refreshAll()
            toast(
                when (failure) {
                    null -> R.string.yt_r28_newssum_test_ok
                    YtNewsSummaryFailure.INVALID_KEY ->
                        if (save) R.string.yt_r28_newssum_test_invalid else R.string.yt_r28_newssum_test_invalid_set
                    YtNewsSummaryFailure.OFFLINE ->
                        if (save) R.string.yt_r28_newssum_test_offline else R.string.yt_r28_newssum_test_offline_set
                    YtNewsSummaryFailure.MODEL_UNKNOWN ->
                        if (save) R.string.yt_r28_newssum_test_model else R.string.yt_r28_newssum_model_unknown
                    else -> if (save) R.string.yt_r28_newssum_test_busy else R.string.yt_r28_newssum_test_busy_set
                },
            )
        }
    }
}

/** "Modell": the chosen provider's model; empty means its default. */
class YtNewsSummaryModelPreference(context: Context, attrs: AttributeSet?) : YtNewsSummaryRow(context, attrs) {
    override fun refresh() {
        val kind = YtNewsSummaryKey.provider()
        isVisible = kind != null
        if (kind == null) return
        val own = YtNewsSummaryKey.model(kind)
        val default = YtNewsProviders.of(kind).defaultModel
        summary = when {
            own != null -> own
            default != null -> context.getString(R.string.yt_r28_newssum_model_default, default)
            else -> context.getString(R.string.yt_r28_newssum_model_needed)
        }
    }

    override fun onClick() {
        val kind = YtNewsSummaryKey.provider() ?: return
        val default = YtNewsProviders.of(kind).defaultModel
        val hint = if (default != null) {
            context.getString(R.string.yt_r28_newssum_model_hint, default)
        } else {
            context.getString(R.string.yt_r28_newssum_model_hint_own)
        }
        val (frame, input) = field(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            hint,
            YtNewsSummaryKey.model(kind),
        )
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.yt_r28_newssum_model)
            .setView(frame)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.yt_r28_newssum_ok) { _, _ ->
                YtNewsSummary.cancel()
                YtNewsSummaryKey.setModel(kind, input.text?.toString())
                refreshAll()
            }
            .show()
    }
}

/** "Adresse": the base address of the compatible provider (HTTPS, or HTTP in the own network). */
class YtNewsSummaryAddressPreference(context: Context, attrs: AttributeSet?) : YtNewsSummaryRow(context, attrs) {
    override fun refresh() {
        isVisible = YtNewsSummaryKey.provider() == YtNewsProviderKind.COMPATIBLE
        summary = YtNewsSummaryKey.address() ?: context.getString(R.string.yt_r28_newssum_address_unset)
    }

    override fun onClick() {
        val (frame, input) = field(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            context.getString(R.string.yt_r28_newssum_address_hint),
            YtNewsSummaryKey.address(),
        )
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.yt_r28_newssum_address)
            .setMessage(R.string.yt_r28_newssum_address_message)
            .setView(frame)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.yt_r28_newssum_ok) { _, _ ->
                val address = input.text?.toString()?.trim().orEmpty()
                if (address.isNotEmpty() && YtNewsHttp.baseUrl(address) == null) {
                    toast(R.string.yt_r28_newssum_address_invalid)
                } else {
                    YtNewsSummary.cancel()
                    YtNewsSummaryKey.setAddress(address)
                    refreshAll()
                }
            }
            .show()
    }
}
