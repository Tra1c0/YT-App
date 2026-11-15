package com.github.libretube.ui.yt.ssi

import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.github.libretube.R
import com.github.libretube.ui.base.BasePreferenceFragment
import com.github.libretube.ui.yt.archive.YtArchiveUi
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * YT: "SSI-Suche" (round 33): the meaning model (download with its size, or delete), what is in
 * the index, background indexing, the Deep budget, the AI switch, "Jetzt indexieren", rebuild
 * and clear.
 */
class YtSsiSettings : BasePreferenceFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.yt_r33_ssi_settings, rootKey)
        findPreference<SwitchPreferenceCompat>(YtSsiPrefs.KEY_BACKGROUND)?.setOnPreferenceChangeListener { _, value ->
            YtSsi.schedule(requireContext(), on = value == true)
            true
        }
        findPreference<Preference>(KEY_MODEL)?.setOnPreferenceClickListener {
            model()
            true
        }
        findPreference<Preference>(KEY_NOW)?.setOnPreferenceClickListener {
            YtSsi.runNow(requireContext())
            Toast.makeText(requireContext(), R.string.yt_r33_ssi_index_started, Toast.LENGTH_SHORT).show()
            true
        }
        findPreference<Preference>(KEY_REBUILD)?.setOnPreferenceClickListener {
            confirm(R.string.yt_r33_ssi_rebuild) {
                YtSsi.db.clear()
                YtSsi.dropVectors()
                YtSsi.runNow(requireContext())
            }
            true
        }
        findPreference<Preference>(KEY_CLEAR)?.setOnPreferenceClickListener {
            confirm(R.string.yt_r33_ssi_clear_index) {
                YtSsi.db.clear()
                YtSsi.dropVectors()
                YtSsiSearch.clear()
            }
            true
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                YtSsiModels.state.collect { showModel(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        YtSsiModels.refresh(requireContext())
        refreshIndex()
    }

    private fun confirm(title: Int, action: suspend () -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(R.string.yt_r33_ssi_clear_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { action() }
                    refreshIndex()
                }
            }
            .show()
    }

    private fun model() {
        val context = requireContext()
        val spec = YtSsiModels.spec
        when (YtSsiModels.state.value) {
            is YtSsiModelState.Downloading -> MaterialAlertDialogBuilder(context)
                .setTitle(R.string.yt_r33_ssi_model)
                .setPositiveButton(R.string.yt_r33_ssi_model_cancel) { _, _ -> YtSsiModels.cancelDownload() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            YtSsiModelState.Ready -> MaterialAlertDialogBuilder(context)
                .setTitle(R.string.yt_r33_ssi_model)
                .setMessage(getString(R.string.yt_r33_ssi_model_ready, spec.name, YtArchiveUi.size(YtSsiModels.bytes(context))))
                .setPositiveButton(R.string.yt_r33_ssi_model_delete) { _, _ ->
                    lifecycleScope.launch {
                        YtSsiModels.delete(context)
                        YtSsi.dropVectors()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            YtSsiModelState.Unsupported -> Unit
            else -> MaterialAlertDialogBuilder(context)
                .setTitle(R.string.yt_r33_ssi_model_download_title)
                .setMessage(getString(R.string.yt_r33_ssi_model_download_text, spec.name, spec.licence, spec.approxMb))
                .setPositiveButton(R.string.yt_r33_ssi_model_download) { _, _ -> YtSsiModels.download(context) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun showModel(state: YtSsiModelState) {
        val context = context ?: return
        val spec = YtSsiModels.spec
        findPreference<Preference>(KEY_MODEL)?.summary = when (state) {
            YtSsiModelState.Missing -> getString(R.string.yt_r33_ssi_model_missing, spec.name, spec.approxMb)
            is YtSsiModelState.Downloading -> getString(
                R.string.yt_r33_ssi_model_downloading,
                YtArchiveUi.size(state.done) + (state.total?.let { " / " + YtArchiveUi.size(it) } ?: ""),
            )
            YtSsiModelState.Ready -> getString(R.string.yt_r33_ssi_model_ready, spec.name, YtArchiveUi.size(YtSsiModels.bytes(context)))
            YtSsiModelState.Unsupported -> getString(R.string.yt_r33_ssi_model_unsupported)
            is YtSsiModelState.Failed -> getString(R.string.yt_r33_ssi_model_failed, state.reason)
        }
        if (state == YtSsiModelState.Ready) refreshIndex()
    }

    private fun refreshIndex() {
        val context = context ?: return
        lifecycleScope.launch {
            val stats = withContext(Dispatchers.IO) { runCatching { YtSsi.db.stats(context, YtSsiModels.spec.version) }.getOrNull() } ?: return@launch
            findPreference<Preference>(KEY_INDEX)?.summary =
                getString(R.string.yt_r33_ssi_index_summary, stats.videos, stats.segments, stats.vectors, YtArchiveUi.size(stats.bytes))
        }
    }

    private companion object {
        const val KEY_MODEL = "yt_ssi_model"
        const val KEY_INDEX = "yt_ssi_index"
        const val KEY_NOW = "yt_ssi_index_now"
        const val KEY_REBUILD = "yt_ssi_rebuild"
        const val KEY_CLEAR = "yt_ssi_clear"
    }
}
