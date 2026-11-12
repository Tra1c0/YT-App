package com.github.libretube.ui.preferences

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.getSystemService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.github.libretube.R
import com.github.libretube.util.YtNotifyChannels
import com.github.libretube.util.YtPush
import com.github.libretube.util.YtPushRules
import com.github.libretube.util.YtPushRules.Status
import com.github.libretube.util.YtUpdateNotifier
import com.github.libretube.util.YtUpdateNotifier.Block
import com.github.libretube.util.YtUpdateNotifyRules
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * YT: the group "App-Updates" of the notification settings: "Bei neuen Versionen
 * benachrichtigen", the check interval, while Android does not let YT notify "Benachrichtigungen
 * erlauben", the instant push (switch, status, "copy the address") and the row that opens the
 * Android channel "YT-Updates". Also the row that opens LibreTube's channel "Neue Videos" in the
 * other group.
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

    // the push service writes the address in the background: the rows follow it while shown
    private val pushListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        fragment.view?.post { refresh() }
    }

    init {
        // back from Android's settings, its dialog or the distributor: show what is true now
        fragment.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    fragment.context?.let { YtPush.prefs(it).registerOnSharedPreferenceChangeListener(pushListener) }
                    refresh()
                }
                Lifecycle.Event.ON_PAUSE -> fragment.context?.let {
                    YtPush.prefs(it).unregisterOnSharedPreferenceChangeListener(pushListener)
                }
                else -> Unit
            }
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

        fragment.findPreference<SwitchPreferenceCompat>(YtPushRules.KEY_ENABLED)?.setOnPreferenceChangeListener { _, value ->
            val on = value as? Boolean ?: return@setOnPreferenceChangeListener true
            val activity = fragment.activity ?: return@setOnPreferenceChangeListener true
            if (on) {
                if (YtPush.distributors(activity).isEmpty()) {
                    showNoDistributor()
                } else {
                    YtPush.enable(activity) { fragment.view?.post { refresh() } }
                }
            } else {
                YtPush.disable(activity)
            }
            fragment.view?.post { refresh() }
            true
        }
        fragment.findPreference<Preference>(YtPushRules.KEY_STATUS)?.setOnPreferenceClickListener {
            val activity = fragment.activity ?: return@setOnPreferenceClickListener true
            when (YtPush.status(activity)) {
                Status.NO_DISTRIBUTOR -> showNoDistributor()
                Status.PENDING, Status.FAILED -> YtPush.enable(activity) { fragment.view?.post { refresh() } }
                else -> Unit
            }
            true
        }
        fragment.findPreference<Preference>(YtPushRules.KEY_COPY)?.setOnPreferenceClickListener {
            copyAddress()
            true
        }

        fragment.findPreference<Preference>(KEY_CHANNEL_UPDATES)?.setOnPreferenceClickListener {
            openChannel(YtNotifyChannels.UPDATES)
            true
        }
        fragment.findPreference<Preference>(KEY_CHANNEL_VIDEOS)?.setOnPreferenceClickListener {
            openChannel(YtNotifyChannels.VIDEOS)
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

    /** Android's page of one channel; the app's page when the channel page cannot open. */
    private fun openChannel(channel: String) {
        val context = fragment.context ?: return
        // the channel exists once created; LibreTube creates "Neue Videos" at every start
        if (channel == YtNotifyChannels.UPDATES) runCatching { YtNotifyChannels.ensureUpdates(context) }
        runCatching { fragment.startActivity(YtNotifyChannels.channelPage(context, channel)) }
            .onFailure { runCatching { fragment.startActivity(YtNotifyChannels.appPage(context)) } }
    }

    /** No UnifiedPush distributor: what it is and where to get ntfy. */
    private fun showNoDistributor() {
        val context = fragment.context ?: return
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.yt_r30_push_none_title)
            .setMessage(R.string.yt_r30_push_none_message)
            .setPositiveButton(R.string.yt_r30_push_none_get) { _, _ ->
                runCatching {
                    fragment.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(YtPush.NTFY_URL)))
                }.onFailure {
                    Toast.makeText(context, YtPush.NTFY_URL, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Copies the push address, marked as sensitive (Android 13+ then does not show it in the
     * clipboard preview), and explains the one GitHub step it is for.
     */
    private fun copyAddress() {
        val context = fragment.context ?: return
        val endpoint = YtPush.endpoint(context) ?: return refresh()
        val clip = ClipData.newPlainText(context.getString(R.string.yt_r30_push_copy_title), endpoint)
        clip.description.extras = PersistableBundle().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            } else {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        val copied = runCatching { context.getSystemService<ClipboardManager>()?.setPrimaryClip(clip) }.isSuccess
        if (!copied) return
        YtPush.markCopied(context)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.yt_r30_push_copied_title)
            .setMessage(R.string.yt_r30_push_copied_message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
        refresh()
    }

    /** The switch's summary, the "allow" row and the push rows follow what is true now. */
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
        refreshPush(context)
    }

    private fun refreshPush(context: android.content.Context) {
        val push = fragment.findPreference<SwitchPreferenceCompat>(YtPushRules.KEY_ENABLED) ?: return
        val status = fragment.findPreference<Preference>(YtPushRules.KEY_STATUS)
        val copy = fragment.findPreference<Preference>(YtPushRules.KEY_COPY)
        val state = YtPush.status(context, push.isChecked)
        val changed = YtPush.endpointChanged(context)
        status?.isVisible = state != Status.OFF
        status?.summary = when (state) {
            Status.OFF -> null
            Status.NO_DISTRIBUTOR -> context.getString(R.string.yt_r30_push_status_none)
            Status.PENDING -> context.getString(R.string.yt_r30_push_status_found, YtPush.distributorName(context) ?: "?")
            Status.FAILED -> context.getString(R.string.yt_r30_push_status_failed)
            Status.REGISTERED -> context.getString(
                if (changed) R.string.yt_r30_push_status_changed else R.string.yt_r30_push_status_registered,
                YtPush.distributorName(context) ?: "?"
            )
        }
        copy?.isVisible = state == Status.REGISTERED
    }

    private companion object {
        const val KEY_CHANNEL_UPDATES = "yt_notify_channel_updates"
        const val KEY_CHANNEL_VIDEOS = "yt_notify_channel_videos"
    }
}
