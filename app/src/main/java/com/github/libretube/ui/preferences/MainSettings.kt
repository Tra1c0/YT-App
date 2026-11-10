package com.github.libretube.ui.preferences

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import com.github.libretube.BuildConfig
import com.github.libretube.R
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.ui.base.BasePreferenceFragment
import com.github.libretube.ui.dialogs.ErrorDialog
import com.github.libretube.util.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainSettings : BasePreferenceFragment() {

    override fun onResume() {
        super.onResume()
        // device-gated one-time surprise: this is the settings home (root list), no-op unless armed
        com.github.libretube.helpers.YtDeviceEgg.onSettingsHome(listView, requireActivity())
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings, rootKey)

        val update = findPreference<Preference>("update")
        update?.summary = "v${BuildConfig.VERSION_NAME}"

        // check app update manually
        update?.setOnPreferenceClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                UpdateChecker(requireContext()).checkUpdate(true)
            }

            true
        }

        val crashlog = findPreference<Preference>("crashlog")
        crashlog?.isVisible = PreferenceHelper.getErrorLog().isNotEmpty() && BuildConfig.DEBUG
        crashlog?.setOnPreferenceClickListener {
            ErrorDialog().show(childFragmentManager, null)
            crashlog.isVisible = false
            true
        }

        // YT: the diagnosis page (text to copy)
        findPreference<Preference>("yt_diagnosis")?.setOnPreferenceClickListener {
            com.github.libretube.ui.yt.YtDiagnosisUi.showDiagnosis(requireContext())
            true
        }

        // YT: the storage page (cache sizes, clear)
        findPreference<Preference>("yt_storage")?.setOnPreferenceClickListener {
            com.github.libretube.ui.yt.YtStorageUi.show(requireContext())
            true
        }
        
        listOf(
            "general" to R.id.action_global_generalSettings,
            "account" to R.id.action_global_instanceSettings,
            "appearance" to R.id.action_global_appearanceSettings,
            "sponsorblock" to R.id.action_global_sponsorBlockSettings,
            "player" to R.id.action_global_playerSettings,
            "yt_interaction" to R.id.action_global_ytInteractionSettings,
            "yt_guide" to R.id.action_global_ytGuide,
            "audio_video" to R.id.action_global_audioVideoSettings,
            "notifications" to R.id.action_global_notificationSettings,
            "backup_restore" to R.id.action_global_backupRestoreSettings
        ).forEach { (preferenceKey, actionId) ->
            findPreference<Preference>(preferenceKey)?.setOnPreferenceClickListener { _ ->
                findNavController().navigate(actionId)
                true
            }
        }
    }
}
