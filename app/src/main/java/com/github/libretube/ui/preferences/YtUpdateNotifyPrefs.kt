package com.github.libretube.ui.preferences

import android.Manifest
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.github.libretube.R
import com.github.libretube.util.YtUpdateNotifier
import com.github.libretube.util.YtUpdateNotifier.Block
import com.github.libretube.util.YtUpdateNotifyRules

/**
 * YT: the rows "Bei neuen Versionen benachrichtigen", the check interval and, while Android does
 * not let YT notify, "Benachrichtigungen erlauben" (main settings, next to "Nach Update suchen").
 *
 * The permission is asked only when the user taps that row or switches the notifications on:
 * the first time with Android's dialog, after that (Android stops showing it) via the system
 * page. Create it while the fragment is constructed (it registers a result callback), then
 * call [bind] in onCreatePreferences.
 */
class YtUpdateNotifyPrefs(private val fragment: PreferenceFragmentCompat) {
    private val askPermission = fragment.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh() }

    init {
        // back from Android's settings or its dialog: show what is true now
        fragment.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        })
    }

    fun bind() {
        val switch = fragment.findPreference<SwitchPreferenceCompat>(YtUpdateNotifyRules.KEY_ENABLED)
        val interval = fragment.findPreference<ListPreference>(YtUpdateNotifyRules.KEY_INTERVAL)
        val allow = fragment.findPreference<Preference>(YtUpdateNotifyRules.KEY_ALLOW)

        switch?.setOnPreferenceChangeListener { _, value ->
            val on = value as? Boolean ?: return@setOnPreferenceChangeListener true
            YtUpdateNotifier.schedule(fragment.requireContext(), on = on)
            // switched on by the user: that is the moment to ask
            if (on && YtUpdateNotifier.block(fragment.requireContext()) != Block.NONE) requestOrOpen()
            fragment.view?.post { refresh() }
            true
        }
        interval?.setOnPreferenceChangeListener { _, value ->
            val hours = YtUpdateNotifyRules.intervalHours(value?.toString())
            YtUpdateNotifier.schedule(fragment.requireContext(), hours = hours)
            true
        }
        allow?.setOnPreferenceClickListener {
            requestOrOpen()
            true
        }
        refresh()
    }

    private fun requestOrOpen() {
        val context = fragment.context ?: return
        when (YtUpdateNotifier.block(context)) {
            Block.NONE -> refresh()
            Block.PERMISSION -> {
                val askable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    (!YtUpdateNotifier.askedForPermission(context) ||
                        fragment.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))
                if (askable) {
                    YtUpdateNotifier.markAskedForPermission(context)
                    runCatching { askPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
                } else {
                    // Android shows its dialog at most twice; after that only its settings page helps
                    open(channel = false)
                }
            }
            Block.APP_OFF -> open(channel = false)
            Block.CHANNEL_OFF -> open(channel = true)
        }
    }

    private fun open(channel: Boolean) {
        val context = fragment.context ?: return
        runCatching { fragment.startActivity(YtUpdateNotifier.settingsIntent(context, channel)) }
            .onFailure {
                if (channel) runCatching { fragment.startActivity(YtUpdateNotifier.settingsIntent(context, false)) }
            }
    }

    /** The switch's summary and the "allow" row follow whether YT may notify. */
    fun refresh() {
        val context = fragment.context ?: return
        val switch = fragment.findPreference<SwitchPreferenceCompat>(YtUpdateNotifyRules.KEY_ENABLED) ?: return
        val allow = fragment.findPreference<Preference>(YtUpdateNotifyRules.KEY_ALLOW)
        val block = YtUpdateNotifier.block(context)
        val on = switch.isChecked
        switch.setSummary(
            if (on && block != Block.NONE) R.string.yt_r27_updnotify_summary_blocked
            else R.string.yt_r27_updnotify_summary
        )
        allow?.isVisible = on && block != Block.NONE
        allow?.setSummary(
            when (block) {
                Block.CHANNEL_OFF -> R.string.yt_r27_updnotify_allow_channel
                Block.APP_OFF -> R.string.yt_r27_updnotify_allow_app
                else -> R.string.yt_r27_updnotify_allow_permission
            }
        )
    }
}
