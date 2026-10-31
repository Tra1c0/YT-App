package com.github.libretube.ui.preferences

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.core.net.toUri
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.github.libretube.R
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.helpers.LocaleHelper
import com.github.libretube.ui.base.BasePreferenceFragment
import com.github.libretube.ui.yt.YtPipVisualizer
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class PlayerSettings : BasePreferenceFragment() {
    private var waveSwitch: SwitchPreferenceCompat? = null

    /** The user switched the sound wave on; it waits for the permission to show over other apps. */
    private var pendingWave = false

    /** The system settings were opened for that permission; coming back checks it. */
    private var awaitingReturn = false

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.player_settings, rootKey)

        val defaultSubtitle = findPreference<ListPreference>(PreferenceKeys.DEFAULT_SUBTITLE)
        defaultSubtitle?.let { setupSubtitlePref(it) }

        val captionSettings = findPreference<Preference>(PreferenceKeys.CAPTION_SETTINGS)
        captionSettings?.setOnPreferenceClickListener {
            try {
                val captionSettingsIntent = Intent(Settings.ACTION_CAPTIONING_SETTINGS)
                startActivity(captionSettingsIntent)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(activity, R.string.error, Toast.LENGTH_SHORT).show()
            }
            true
        }

        val alternativePipControls =
            findPreference<SwitchPreferenceCompat>(PreferenceKeys.ALTERNATIVE_PIP_CONTROLS)

        // YT: the sound wave below the picture-in-picture window needs to show over other apps.
        // The switch turns on only once that permission exists; until then it explains the way
        // there, Android 15's lock on apps installed from an APK file included.
        pendingWave = savedInstanceState?.getBoolean(STATE_PENDING_WAVE) == true
        awaitingReturn = savedInstanceState?.getBoolean(STATE_AWAITING_RETURN) == true
        waveSwitch = findPreference<SwitchPreferenceCompat>(YtPipVisualizer.PREF_KEY)?.apply {
            setOnPreferenceChangeListener { _, on ->
                if (on != true || YtPipVisualizer.allowed(requireContext())) {
                    pendingWave = false
                    return@setOnPreferenceChangeListener true
                }
                pendingWave = true
                askForOverlay()
                false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val switch = waveSwitch ?: return
        val allowed = YtPipVisualizer.allowed(requireContext())
        val wanted = pendingWave || switch.isChecked
        // back from the system settings: on if the permission is there now, else the hint again
        val returning = awaitingReturn
        awaitingReturn = false
        when {
            wanted && allowed -> {
                pendingWave = false
                switch.isChecked = true
                switch.setSummary(R.string.yt_pip_visualizer_summary)
            }
            wanted -> {
                // the switch says what is: off, and why
                pendingWave = true
                switch.isChecked = false
                switch.setSummary(R.string.yt_pip_visualizer_missing_summary)
                if (returning) askForOverlay()
            }
            else -> switch.setSummary(R.string.yt_pip_visualizer_summary)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_PENDING_WAVE, pendingWave)
        outState.putBoolean(STATE_AWAITING_RETURN, awaitingReturn)
    }

    /**
     * The way to the permission to show over other apps. Android 15 and newer lock it for apps
     * installed from an APK file (as YT is) until "Allow restricted settings" is chosen in the
     * app's info; the dialog then names those steps and leads to both places.
     */
    private fun askForOverlay() {
        val context = requireContext()
        val restricted = YtPipVisualizer.mayBeRestricted()
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.yt_pip_visualizer_permission_title)
            .setMessage(
                if (restricted) R.string.yt_pip_visualizer_steps_restricted else R.string.yt_pip_visualizer_steps,
            )
            .setPositiveButton(R.string.yt_pip_visualizer_open_overlay) { _, _ ->
                openSystemSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            }
            .apply {
                if (restricted) {
                    setNeutralButton(R.string.yt_pip_visualizer_open_app_info) { _, _ ->
                        openSystemSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    }
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                pendingWave = false
                waveSwitch?.setSummary(R.string.yt_pip_visualizer_summary)
            }
            .show()
    }

    private fun openSystemSettings(action: String) {
        val context = requireContext()
        val uri = "package:${context.packageName}".toUri()
        val opened = runCatching { startActivity(Intent(action, uri)) }.isSuccess ||
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)) }.isSuccess
        if (opened) {
            awaitingReturn = true
        } else {
            Toast.makeText(context, R.string.error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupSubtitlePref(preference: ListPreference) {
        val locales = LocaleHelper.getAvailableLocales()
        val localeNames = locales.map { it.name }
            .toMutableList()
        localeNames.add(0, requireContext().getString(R.string.none))

        val localeCodes = locales.map { it.code }
            .toMutableList()
        localeCodes.add(0, "")

        preference.entries = localeNames.toTypedArray()
        preference.entryValues = localeCodes.toTypedArray()
        preference.summaryProvider =
            Preference.SummaryProvider<ListPreference> {
                it.entry
            }
    }

    private companion object {
        const val STATE_PENDING_WAVE = "yt_pending_wave"
        const val STATE_AWAITING_RETURN = "yt_awaiting_return"
    }
}
