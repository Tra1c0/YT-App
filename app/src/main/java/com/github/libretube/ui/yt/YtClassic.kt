package com.github.libretube.ui.yt

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.TextView
import androidx.core.view.updatePadding
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import com.github.libretube.R
import com.github.libretube.helpers.YtDesign
import com.google.android.material.color.MaterialColors

/**
 * YT: the small decisions of the classic screens that a theme cannot make. The design overlays
 * (yt_classic.xml) set shapes, outlines, sheets, dialogs, buttons and menus; this makes the
 * decisions that need code: settings rows as cards of the design with their icon in a halo
 * (Clay, Glas, Pop, Formen, Frost, Kiesel), rows without icons where the design prints
 * (After, Editorial), numbered sections in Editorial, sheet options in capitals where the
 * design prints.
 */
object YtClassic {
    /** Designs that print: capitals, no icons, rules instead of cards. */
    private val YtDesign.prints: Boolean get() = this == YtDesign.EDITORIAL || this == YtDesign.AFTER

    /** Gives the settings of [fragment] the rows of the design. Call once the view exists. */
    fun stylePreferences(fragment: PreferenceFragmentCompat) {
        val design = YtDesign.current()
        if (design == YtDesign.STANDARD) return
        val screen = fragment.preferenceScreen ?: return
        val numbered = design == YtDesign.EDITORIAL
        val plain = design.prints
        fragment.setDivider(null)
        fragment.listView?.let { list ->
            list.clipToPadding = false
            val density = list.resources.displayMetrics.density
            list.updatePadding(top = (6 * density).toInt(), bottom = list.paddingBottom + (12 * density).toInt())
        }
        var number = 0
        fun style(group: PreferenceGroup) {
            for (i in 0 until group.preferenceCount) {
                val pref = group.getPreference(i)
                // hidden rows (options the design fixes) get no number, so Editorial counts on
                if (!pref.isVisible) continue
                when (pref) {
                    is PreferenceCategory -> {
                        pref.layoutResource = R.layout.yt_preference_category
                        style(pref)
                    }
                    is PreferenceGroup -> style(pref)
                    else -> {
                        if (!plain && pref.layoutResource == androidx.preference.R.layout.preference) {
                            pref.layoutResource = R.layout.yt_preference
                        }
                        if (plain) {
                            pref.icon = null
                            pref.isIconSpaceReserved = false
                        }
                        if (numbered) {
                            number++
                            val title = pref.title?.toString().orEmpty()
                            if (title.isNotEmpty() && !title.first().isDigit()) pref.title = "%02d   %s".format(number, title)
                        }
                    }
                }
            }
        }
        style(screen)
    }

    /**
     * The entries of a choice dialog with the current one, [selected], in bold and in the
     * design's primary color, so the radio button is not the only sign of what is in use.
     */
    fun markChoice(context: Context, entries: Array<CharSequence>, selected: Int): Array<CharSequence> {
        if (selected !in entries.indices) return entries
        val color = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, Color.BLACK)
        return Array(entries.size) { i ->
            if (i != selected) {
                entries[i]
            } else {
                SpannableString(entries[i]).apply {
                    setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(ForegroundColorSpan(color), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
    }

    /** Gives an option of a classic bottom sheet the manner of the design. */
    fun styleSheetItem(view: TextView) {
        val design = YtDesign.current()
        if (design == YtDesign.STANDARD) return
        if (design.prints) {
            view.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
            view.isAllCaps = true
            view.letterSpacing = 0.08f
            view.textSize = 13f
        } else {
            view.isAllCaps = false
            view.letterSpacing = 0f
            view.textSize = 16f
        }
    }
}
