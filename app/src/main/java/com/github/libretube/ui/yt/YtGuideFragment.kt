package com.github.libretube.ui.yt

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.core.view.ViewCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.github.libretube.R
import com.github.libretube.ui.extensions.onSystemInsets
import com.github.libretube.util.UpdateChecker
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * YT: Settings → "Anleitung": what YT does differently and how to use it, from [YtGuide]. Plain
 * Material in the theme of the classic screens (the design overlay gives the cards their corners
 * and outline), sections that fold, a search field that filters in memory ([YtGuideRules]).
 * Entries with a target open that settings screen through the settings graph.
 */
class YtGuideFragment : Fragment() {
    /** Sections the user opened or closed by hand; the rest follows [YtGuideRules.expanded]. */
    private val chosen = mutableMapOf<String, Boolean>()
    private var groups: List<YtGuideRules.Group> = emptyList()
    private var query = ""
    private var content: LinearLayout? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.yt_guide_fragment, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        savedInstanceState?.getBundle(STATE_CHOSEN)?.let { saved ->
            for (key in saved.keySet()) chosen[key] = saved.getBoolean(key)
        }
        groups = YtGuide.resolve { getString(it) }
        content = view.findViewById(R.id.yt_guide_content)

        val scroll = view.findViewById<View>(R.id.yt_guide_scroll)
        val basePadding = scroll.paddingBottom
        // the last card stays above the navigation bar
        scroll.onSystemInsets { v, insets -> v.updatePadding(bottom = basePadding + insets.bottom) }

        val input = view.findViewById<TextInputEditText>(R.id.yt_guide_search_input)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val now = s?.toString().orEmpty()
                if (now == query) return
                query = now
                render()
            }
        })
        input.setOnEditorActionListener { v, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            v.clearFocus()
            v.context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(v.windowToken, 0)
            true
        }
        // a restored search text arrives after this through the watcher and renders again
        query = input.text?.toString().orEmpty()
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBundle(STATE_CHOSEN, Bundle().apply { chosen.forEach { (k, v) -> putBoolean(k, v) } })
    }

    override fun onDestroyView() {
        content = null
        super.onDestroyView()
    }

    private fun render() {
        val list = content ?: return
        val context = list.context
        val ink = Ink.of(context)
        list.removeAllViews()
        val shown = YtGuideRules.filter(groups, query)
        if (shown.isEmpty()) {
            list.addView(
                text(context, getString(R.string.yt_guide_empty, query.trim()), com.google.android.material.R.attr.textAppearanceBodyLarge, ink.secondary).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(context, 24), dp(context, 32), dp(context, 24), 0)
                }
            )
            return
        }
        for (group in shown) {
            val index = groups.indexOfFirst { it.key == group.key }
            list.addView(sectionView(context, ink, group, YtGuideRules.expanded(group.key, index, query, chosen)))
        }
    }

    private fun sectionView(context: Context, ink: Ink, group: YtGuideRules.Group, open: Boolean): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.yt_pref_row)
            // the header's ripple keeps to the card's corners
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                val side = dp(context, 12)
                setMargins(side, dp(context, 4), side, dp(context, 4))
            }
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), 0, dp(context, 12), dp(context, 8))
            visibility = if (open) View.VISIBLE else View.GONE
        }
        group.note?.let { note ->
            body.addView(
                text(context, note, com.google.android.material.R.attr.textAppearanceBodySmall, ink.secondary).apply {
                    setPadding(0, 0, 0, dp(context, 4))
                }
            )
        }
        for (entry in group.entries) body.addView(entryView(context, ink, entry))

        val chevron = ImageView(context).apply {
            setImageResource(R.drawable.ic_arrow_down)
            setColorFilter(ink.secondary)
            rotation = if (open) 180f else 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val title = text(context, group.title, com.google.android.material.R.attr.textAppearanceTitleMedium, ink.primary).apply {
            isAllCaps = themeBoolean(context, R.attr.ytUppercase)
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(context, 56)
            setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8))
            setBackgroundResource(selectableBackground(context))
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(chevron, LinearLayout.LayoutParams(dp(context, 24), dp(context, 24)))
            isClickable = true
            isFocusable = true
            ViewCompat.setStateDescription(this, getString(if (open) R.string.yt_guide_expanded else R.string.yt_guide_collapsed))
        }
        header.setOnClickListener {
            val nowOpen = body.visibility != View.VISIBLE
            // while searching every hit is open; a tap there folds only for the moment
            if (YtGuideRules.terms(query).isEmpty()) chosen[group.key] = nowOpen
            (content?.parent as? ViewGroup)?.let { scene ->
                if (YtMotionPrefs.enabled) TransitionManager.beginDelayedTransition(scene, AutoTransition().setDuration(200))
            }
            body.visibility = if (nowOpen) View.VISIBLE else View.GONE
            chevron.rotation = if (nowOpen) 180f else 0f
            ViewCompat.setStateDescription(header, getString(if (nowOpen) R.string.yt_guide_expanded else R.string.yt_guide_collapsed))
        }
        card.addView(header)
        card.addView(body)
        return card
    }

    private fun entryView(context: Context, ink: Ink, entry: YtGuideRules.Entry): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(context, 8), 0, dp(context, 8))
        }
        box.addView(text(context, entry.title, com.google.android.material.R.attr.textAppearanceTitleSmall, ink.primary))
        box.addView(
            text(context, entry.body, com.google.android.material.R.attr.textAppearanceBodyMedium, ink.secondary).apply {
                setPadding(0, dp(context, 2), 0, 0)
            }
        )
        entry.where?.let { where ->
            box.addView(
                text(context, where, com.google.android.material.R.attr.textAppearanceLabelMedium, ink.secondary).apply {
                    setPadding(0, dp(context, 6), 0, 0)
                }
            )
        }
        YtGuide.item(entry.key)?.target?.let { target ->
            box.addView(
                MaterialButton(context, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                    text = getString(
                        when {
                            target == YtGuideTarget.UPDATE -> R.string.update_summary
                            target.isScreen -> R.string.yt_guide_open_setting
                            else -> R.string.yt_guide_open
                        }
                    )
                    setOnClickListener { open(target) }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    // the button's own inset lines its text up with the text above
                    marginStart = -dp(context, 12)
                }
            )
        }
        return box
    }

    private fun open(target: YtGuideTarget) {
        val context = context ?: return
        val action = when (target) {
            YtGuideTarget.INTERACTION -> R.id.action_global_ytInteractionSettings
            YtGuideTarget.PLAYER -> R.id.action_global_playerSettings
            YtGuideTarget.AUDIO_VIDEO -> R.id.action_global_audioVideoSettings
            YtGuideTarget.SPONSORBLOCK -> R.id.action_global_sponsorBlockSettings
            YtGuideTarget.GENERAL -> R.id.action_global_generalSettings
            YtGuideTarget.DIAGNOSIS -> {
                YtDiagnosisUi.showDiagnosis(context)
                return
            }
            YtGuideTarget.STORAGE -> {
                YtStorageUi.show(context)
                return
            }
            YtGuideTarget.UPDATE -> {
                val app = context.applicationContext
                lifecycleScope.launch(Dispatchers.IO) { UpdateChecker(app).checkUpdate(true) }
                return
            }
        }
        runCatching { findNavController().navigate(action) }
    }

    /** The two text colours of the cards, made readable on the card as it shows over the page. */
    private class Ink(val primary: Int, val secondary: Int) {
        companion object {
            fun of(context: Context): Ink {
                val page = MaterialColors.getColor(context, android.R.attr.colorBackground, 0xFFFFFFFF.toInt())
                val card = YtContrast.over(
                    MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceContainerLow, page),
                    page
                )
                val onSurface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, 0xFF000000.toInt())
                val variant = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, onSurface)
                return Ink(YtContrast.readable(onSurface, card), YtContrast.readable(variant, card))
            }
        }
    }

    private fun text(context: Context, value: String, @AttrRes appearance: Int, color: Int) = TextView(context).apply {
        val style = TypedValue()
        if (context.theme.resolveAttribute(appearance, style, true) && style.resourceId != 0) {
            setTextAppearance(style.resourceId)
        }
        // Google Sans needs this off (see the design overlays); follow the theme explicitly
        includeFontPadding = themeBoolean(context, android.R.attr.includeFontPadding, default = true)
        setTextColor(color)
        text = value
    }

    private fun themeBoolean(context: Context, @AttrRes attr: Int, default: Boolean = false): Boolean {
        val value = TypedValue()
        if (!context.theme.resolveAttribute(attr, value, true)) return default
        return if (value.type == TypedValue.TYPE_INT_BOOLEAN) value.data != 0 else default
    }

    private fun selectableBackground(context: Context): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        return value.resourceId
    }

    private fun dp(context: Context, value: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics
    ).toInt()

    companion object {
        private const val STATE_CHOSEN = "yt_guide_chosen"
    }
}
