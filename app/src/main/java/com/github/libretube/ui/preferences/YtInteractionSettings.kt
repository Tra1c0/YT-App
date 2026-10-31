package com.github.libretube.ui.preferences

import android.os.Bundle
import androidx.preference.PreferenceCategory
import com.github.libretube.R
import com.github.libretube.ui.base.BasePreferenceFragment

/**
 * YT: "Gesten & Interaktion": the custom haptics and one section per feature package of round
 * 12, each from its own XML, so the packages never edit the same file. The settings are read
 * per gesture, so none of them needs a restart.
 */
class YtInteractionSettings : BasePreferenceFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.yt_interaction_settings, rootKey)
        for (section in SECTIONS) addPreferencesFromResource(section)

        // a section whose package has no settings yet stays out of sight
        val screen = preferenceScreen ?: return
        for (i in 0 until screen.preferenceCount) {
            val category = screen.getPreference(i) as? PreferenceCategory ?: continue
            if ((0 until category.preferenceCount).none { category.getPreference(it).isVisible }) {
                category.isVisible = false
            }
        }
    }

    companion object {
        /** One section per package: mini player, minimizing, player gestures, home, comments. */
        private val SECTIONS = intArrayOf(
            R.xml.yt_r12_a_prefs,
            R.xml.yt_r12_b_prefs,
            R.xml.yt_r12_c_prefs,
            R.xml.yt_r12_d_prefs,
            R.xml.yt_r12_e_prefs,
        )
    }
}
