package com.github.libretube.ui.dialogs

import android.app.Dialog
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.core.os.BundleCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.libretube.R
import com.github.libretube.constants.IntentData.appUpdateChangelog
import com.github.libretube.constants.IntentData.appUpdateURL
import com.github.libretube.databinding.DialogYtUpdateBinding
import com.github.libretube.obj.update.UpdateAsset
import com.github.libretube.util.YtSelfUpdater
import com.github.libretube.util.YtSelfUpdater.State
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * YT: shows the changelog; Download fetches the APK in the app (thin progress bar, percent, MB,
 * cancel), checks it and installs it. Without an APK in the release it opens the release page.
 * "In background" closes the dialog while the download goes on in the app process.
 */
class UpdateAvailableDialog : DialogFragment() {
    private var changelog: String? = null
    private var releaseUrl: String? = null
    private var asset: UpdateAsset? = null

    /** The user was sent to the "install unknown apps" page and continues when back. */
    private var awaitingPermission = false

    private lateinit var binding: DialogYtUpdateBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.run {
            changelog = getString(appUpdateChangelog)
            releaseUrl = getString(appUpdateURL)
            asset = BundleCompat.getParcelable(this, APP_UPDATE_ASSET, UpdateAsset::class.java)
        }
        awaitingPermission = savedInstanceState?.getBoolean(AWAITING_PERMISSION) ?: false
        if (savedInstanceState == null) YtSelfUpdater.clearFailure()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                YtSelfUpdater.state.collect { render(it) }
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        binding = DialogYtUpdateBinding.inflate(layoutInflater)
        binding.changelog.text = changelog
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.update_available)
            .setView(binding.root)
            // listeners are set in onStart so that a tap does not close the dialog
            .setPositiveButton(R.string.download, null)
            .setNegativeButton(R.string.tooltip_dismiss, null)
            .setNeutralButton(R.string.yt_update_in_background, null)
            .setCancelable(false)
            .create()
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? AlertDialog ?: return
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { onPositive() }
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener {
            if (YtSelfUpdater.state.value is State.Downloading) YtSelfUpdater.cancel() else dismiss()
        }
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener { dismiss() }
        render(YtSelfUpdater.state.value)
    }

    override fun onResume() {
        super.onResume()
        // back from the system settings: go on by itself once the permission is there
        if (awaitingPermission && YtSelfUpdater.canInstall(requireContext())) {
            awaitingPermission = false
            asset?.let { YtSelfUpdater.start(requireContext(), it) }
        }
        render(YtSelfUpdater.state.value)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(AWAITING_PERMISSION, awaitingPermission)
    }

    private fun onPositive() {
        val state = YtSelfUpdater.state.value
        if (state is State.WaitingForUser) {
            runCatching { startActivity(state.confirm) }
            return
        }
        val asset = asset
        if (asset == null) {
            // no APK attached to the release: the release page, as before
            releaseUrl?.let { startActivity(Intent(Intent.ACTION_VIEW, it.toUri())) }
            dismiss()
            return
        }
        val context = requireContext()
        if (!YtSelfUpdater.canInstall(context)) {
            awaitingPermission = true
            render(state)
            runCatching { startActivity(YtSelfUpdater.permissionIntent(context)) }
            return
        }
        awaitingPermission = false
        YtSelfUpdater.start(context, asset)
    }

    private fun render(state: State) {
        val dialog = dialog as? AlertDialog ?: return
        if (!::binding.isInitialized) return
        val positive = dialog.getButton(DialogInterface.BUTTON_POSITIVE) ?: return
        val negative = dialog.getButton(DialogInterface.BUTTON_NEGATIVE)
        val neutral = dialog.getButton(DialogInterface.BUTTON_NEUTRAL)
        val status = binding.updateStatus
        val progress = binding.updateProgress
        status.setTextColor(
            MaterialColors.getColor(status, com.google.android.material.R.attr.colorOnSurfaceVariant)
        )

        fun buttons(positiveText: Int?, negativeText: Int?, background: Boolean) {
            positive.show(positiveText)
            negative.show(negativeText)
            neutral.visibility = if (background) View.VISIBLE else View.GONE
        }

        fun indeterminate(text: Int) {
            progress.visibility = View.VISIBLE
            progress.isIndeterminate = true
            status.visibility = View.VISIBLE
            status.setText(text)
        }

        when (state) {
            State.Idle -> {
                progress.visibility = View.GONE
                status.visibility = if (awaitingPermission) View.VISIBLE else View.GONE
                if (awaitingPermission) status.setText(R.string.yt_update_allow_hint)
                buttons(
                    if (awaitingPermission) R.string.yt_update_allow else R.string.download,
                    R.string.tooltip_dismiss,
                    background = false
                )
            }
            is State.Downloading -> {
                progress.visibility = View.VISIBLE
                status.visibility = View.VISIBLE
                if (state.total > 0) {
                    val percent = (state.bytes * 100 / state.total).toInt().coerceIn(0, 100)
                    progress.isIndeterminate = false
                    progress.setProgressCompat(percent, true)
                    status.text = getString(
                        R.string.yt_update_progress, mb(state.bytes), mb(state.total), percent
                    )
                } else if (state.bytes > 0) {
                    progress.isIndeterminate = true
                    status.text = getString(R.string.yt_update_progress_unknown, mb(state.bytes))
                } else {
                    progress.isIndeterminate = true
                    status.setText(R.string.yt_update_connecting)
                }
                buttons(null, R.string.cancel, background = true)
            }
            State.Verifying -> {
                indeterminate(R.string.yt_update_verifying)
                buttons(null, null, background = true)
            }
            State.Installing -> {
                indeterminate(R.string.yt_update_installing)
                buttons(null, null, background = true)
            }
            is State.WaitingForUser -> {
                progress.visibility = View.GONE
                status.visibility = View.VISIBLE
                status.setText(R.string.yt_update_confirm)
                buttons(R.string.yt_update_install, null, background = true)
            }
            is State.Unsure -> {
                // neutral: Play Protect often reports a failure and then installs it anyway
                progress.visibility = View.GONE
                status.visibility = View.VISIBLE
                status.text = getString(R.string.yt_update_unsure, state.detail)
                buttons(R.string.yt_update_retry, R.string.tooltip_dismiss, background = false)
            }
            is State.Failed -> {
                progress.visibility = View.GONE
                status.visibility = View.VISIBLE
                status.text = state.message
                status.setTextColor(
                    MaterialColors.getColor(status, androidx.appcompat.R.attr.colorError)
                )
                buttons(R.string.yt_update_retry, R.string.tooltip_dismiss, background = false)
            }
        }
    }

    private fun Button.show(text: Int?) {
        visibility = if (text == null) View.GONE else View.VISIBLE
        if (text != null) setText(text)
    }

    private fun mb(bytes: Long) = String.format(Locale.getDefault(), "%.1f", bytes / 1_048_576.0)

    companion object {
        const val APP_UPDATE_ASSET = "updateAsset"
        private const val AWAITING_PERMISSION = "awaitingPermission"
    }
}
