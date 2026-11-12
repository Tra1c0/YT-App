package com.github.libretube.ui.preferences

import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.SwitchPreferenceCompat
import androidx.work.ExistingPeriodicWorkPolicy
import com.github.libretube.R
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.helpers.NotificationHelper
import com.github.libretube.ui.base.BasePreferenceFragment

class NotificationSettings : BasePreferenceFragment() {
    // YT: the group "App-Updates" (YT builds, push), independent of the new videos below
    private val ytUpdateNotify = YtUpdateNotifyPrefs(this)

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.notification_settings, rootKey)

        val notificationsEnabled =
            findPreference<SwitchPreferenceCompat>(PreferenceKeys.NOTIFICATION_ENABLED)
        val checkingFrequency = findPreference<ListPreference>(PreferenceKeys.CHECKING_FREQUENCY)
        val requiredNetwork = findPreference<ListPreference>(PreferenceKeys.REQUIRED_NETWORK)

        notificationsEnabled?.setOnPreferenceChangeListener { _, _ ->
            updateNotificationPrefs()
            true
        }

        checkingFrequency?.setOnPreferenceChangeListener { _, _ ->
            updateNotificationPrefs()
            true
        }

        requiredNetwork?.setOnPreferenceChangeListener { _, _ ->
            updateNotificationPrefs()
            true
        }

        ytUpdateNotify.bind()
    }

    private fun updateNotificationPrefs() {
        // YT: the change listener runs before the new value is stored, and enqueueWork reads the
        // stored one: switching "new videos" off kept the worker until the next app start (and
        // switching it on cancelled it). Run it once the value is written.
        val context = requireContext().applicationContext
        val run = Runnable {
            // replace the previous queued work request
            NotificationHelper
                .enqueueWork(
                    context = context,
                    existingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.UPDATE
                )
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post(run)
    }
}
