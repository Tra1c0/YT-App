package com.github.libretube.ui.preferences

import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.github.libretube.R
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.YtAppearanceOptions
import com.github.libretube.helpers.YtDesign
import com.github.libretube.helpers.YtThemeRefresh
import com.github.libretube.ui.adapters.IconsSheetAdapter
import com.github.libretube.ui.base.BasePreferenceFragment
import com.github.libretube.ui.dialogs.NavBarOptionsDialog
import com.github.libretube.ui.sheets.IconsBottomSheet
import com.github.libretube.ui.yt.YtMotionPrefs
import com.google.android.material.color.DynamicColors

class AppearanceSettings : BasePreferenceFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.appearance_settings, rootKey)

        // YT: a design fixes theme mode, accent and pure theme, so those rows only show where
        // they do something (the standard design); the screen is rebuilt after a change anyway
        val design = YtDesign.current()
        listOf(PreferenceKeys.THEME_MODE, PreferenceKeys.PURE_THEME, PreferenceKeys.ACCENT_COLOR)
            .forEach { key ->
                findPreference<Preference>(key)?.isVisible = YtAppearanceOptions.applies(key, design.key)
            }

        // YT: the design row names the current design and says what it fixes, since the
        // theme rows are gone for it
        findPreference<ListPreference>(YtDesign.PREF_KEY)?.let { pref ->
            pref.summaryProvider = Preference.SummaryProvider<ListPreference> { p ->
                val entry = p.entry ?: getString(R.string.yt_design_standard)
                val selected = YtDesign.entries.firstOrNull { it.key == p.value } ?: YtDesign.STANDARD
                when {
                    selected == YtDesign.STANDARD -> entry
                    selected.light -> getString(R.string.yt_design_fixed_light, entry)
                    else -> getString(R.string.yt_design_fixed_dark, entry)
                }
            }
            pref.applyLookOnChange()
        }

        // YT: these are read when an activity is created; recreating the open activities is
        // enough, no restart of the app (see YtThemeRefresh)
        findPreference<SwitchPreferenceCompat>(YtMotionPrefs.KEY)?.applyLookOnChange()
        findPreference<ListPreference>(PreferenceKeys.THEME_MODE)?.applyLookOnChange()
        findPreference<SwitchPreferenceCompat>(PreferenceKeys.PURE_THEME)?.applyLookOnChange()

        val accentColor = findPreference<ListPreference>(PreferenceKeys.ACCENT_COLOR)
        updateAccentColorValues(accentColor!!)
        accentColor.applyLookOnChange()

        val changeIcon = findPreference<Preference>(PreferenceKeys.APP_ICON)
        val iconPref = PreferenceHelper.getString(
            PreferenceKeys.APP_ICON,
            IconsSheetAdapter.Companion.AppIcon.Default.activityAlias
        )
        IconsSheetAdapter.availableIcons.firstOrNull { it.activityAlias == iconPref }?.let {
            changeIcon?.summary = getString(it.nameResource)
        }
        changeIcon?.setOnPreferenceClickListener {
            IconsBottomSheet().show(childFragmentManager)
            true
        }

        val navBarOptions = findPreference<Preference>(PreferenceKeys.NAVBAR_ITEMS)
        navBarOptions?.setOnPreferenceClickListener {
            NavBarOptionsDialog().show(childFragmentManager, null)
            true
        }
    }

    /**
     * YT: once the new value is saved, mark the open activities stale and rebuild this one, so
     * the change shows at once. The listener runs before the value is stored, hence the post.
     * Picking the value that is already set changes nothing and rebuilds nothing.
     */
    private fun Preference.applyLookOnChange() {
        setOnPreferenceChangeListener { pref, newValue ->
            val old: Any? = when (pref) {
                is ListPreference -> pref.value
                is SwitchPreferenceCompat -> pref.isChecked
                else -> null
            }
            if (YtAppearanceOptions.isNoChange(old, newValue)) return@setOnPreferenceChangeListener false
            YtThemeRefresh.bump()
            view?.post { activity?.let { ActivityCompat.recreate(it) } }
            true
        }
    }

    /**
     * Remove material you from accent color option if not available
     */
    private fun updateAccentColorValues(pref: ListPreference) {
        // YT: by value, not by position, so the list may change its order
        val keep = YtAppearanceOptions.accentValues(
            pref.entryValues.map { it.toString() },
            DynamicColors.isDynamicColorAvailable()
        ).toSet()
        val indices = pref.entryValues.indices.filter { pref.entryValues[it].toString() in keep }
        pref.entries = indices.map { pref.entries[it] }.toTypedArray()
        pref.entryValues = indices.map { pref.entryValues[it] }.toTypedArray()
    }
}
