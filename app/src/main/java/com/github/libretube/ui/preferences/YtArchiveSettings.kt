package com.github.libretube.ui.preferences

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import com.github.libretube.R
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.base.BasePreferenceFragment
import com.github.libretube.ui.yt.archive.YtArchive
import com.github.libretube.ui.yt.archive.YtArchiveMeta
import com.github.libretube.ui.yt.archive.YtArchivePrefs
import com.github.libretube.ui.yt.archive.YtArchiveThrottle
import com.github.libretube.ui.yt.archive.YtArchiveTier
import com.github.libretube.ui.yt.archive.YtArchiveUi
import com.github.libretube.util.YtCrashLog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * YT: "Archiv" (round 31), the archive of vanished videos: the master switch (off by default),
 * the capacity slider, what tier 3 keeps, the high-risk notification, automatic scripts, the AI
 * risk estimate, what is in use, and the two lists "Verschwundene Videos" and "Gesichert". An
 * entry opens a menu: play, read the script or transcript, make the script, export as zip, delete.
 */
class YtArchiveSettings : BasePreferenceFragment() {
    private var exportId: String? = null

    private val exporter = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val id = exportId ?: return@registerForActivityResult
        exportId = null
        if (uri == null) return@registerForActivityResult
        val context = context ?: return@registerForActivityResult
        lifecycleScope.launch { YtArchiveUi.export(context, id, uri) }
    }

    private val askPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.yt_r31_archive_settings, rootKey)

        findPreference<SwitchPreferenceCompat>(YtArchivePrefs.KEY_ENABLED)?.setOnPreferenceChangeListener { _, value ->
            val on = value as? Boolean ?: return@setOnPreferenceChangeListener true
            YtArchive.schedule(requireContext(), on = on)
            if (on && YtArchive.notifyRisk()) askForNotifications()
            true
        }
        findPreference<SwitchPreferenceCompat>(YtArchivePrefs.KEY_NOTIFY_RISK)?.setOnPreferenceChangeListener { _, value ->
            if (value == true && YtArchive.enabled()) askForNotifications()
            true
        }
        // the capacity text names what fits at the highest tier
        findPreference<ListPreference>(YtArchivePrefs.KEY_TOP)?.setOnPreferenceChangeListener { _, _ ->
            view?.post { findPreference<Preference>(YtArchivePrefs.KEY_CAPACITY_MB)?.let { it.summary = it.summary } ; refresh() }
            true
        }
        findPreference<Preference>(YtArchivePrefs.KEY_CAPACITY_MB)?.setOnPreferenceChangeListener { _, _ ->
            view?.post { refresh() }
            true
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val context = context ?: return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        runCatching { askPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }

    /** Usage line and both lists from the archive on disk. */
    private fun refresh() {
        val context = context ?: return
        lifecycleScope.launch {
            val metas = withContext(Dispatchers.IO) { runCatching { YtArchive.store.all() }.getOrDefault(emptyList()) }
            val state = YtArchive.throttle(context)
            val used = metas.sumOf { it.totalBytes }
            findPreference<Preference>(YtArchivePrefs.KEY_USAGE)?.summary = buildString {
                append(
                    getString(
                        R.string.yt_r31_archive_usage_summary,
                        YtArchiveUi.size(used),
                        YtArchiveUi.size(YtArchive.budget()),
                        metas.count { it.tier == YtArchiveTier.TEXT },
                        metas.count { it.tier == YtArchiveTier.AUDIO },
                        metas.count { it.tier == YtArchiveTier.VIDEO },
                    )
                )
                if (YtArchiveThrottle.paused(state, System.currentTimeMillis())) {
                    append('\n')
                    append(getString(R.string.yt_r31_archive_paused, YtCrashLog.formatTime(state.backoffUntil)))
                }
            }
            fill(YtArchivePrefs.KEY_VANISHED, metas.filter { it.vanished }.sortedByDescending { it.vanishedAt })
            fill(YtArchivePrefs.KEY_SAVED, metas.filter { !it.vanished }.sortedByDescending { it.savedAt })
        }
    }

    private fun fill(key: String, metas: List<YtArchiveMeta>) {
        val category = findPreference<PreferenceCategory>(key) ?: return
        val context = category.context
        category.removeAll()
        val design = YtDesign.current()
        // rows in the manner of the design, as YtClassic does for the rows of the XML
        val prints = design == YtDesign.EDITORIAL || design == YtDesign.AFTER
        val layout = if (design != YtDesign.STANDARD && !prints) R.layout.yt_preference else null
        fun row(build: Preference.() -> Unit) = Preference(context).apply {
            isIconSpaceReserved = false
            isPersistent = false
            layout?.let { layoutResource = it }
            build()
        }.also { category.addPreference(it) }
        if (metas.isEmpty()) {
            row {
                title = getString(R.string.yt_r31_archive_none)
                isSelectable = false
            }
            return
        }
        for (meta in metas.take(MAX_ROWS)) {
            row {
                title = meta.title.ifBlank { meta.videoId }
                summary = YtArchiveUi.summary(context, meta)
                setOnPreferenceClickListener {
                    openEntry(meta.videoId)
                    true
                }
            }
        }
        if (metas.size > MAX_ROWS) {
            row {
                title = getString(R.string.yt_r31_archive_more, metas.size - MAX_ROWS)
                isSelectable = false
            }
        }
    }

    /** The menu of one entry (read again: a run may have changed it). */
    private fun openEntry(videoId: String) {
        val context = context ?: return
        val meta = YtArchive.store[videoId] ?: return refresh()
        val hasScript = YtArchiveUi.hasScript(meta)
        val actions = buildList<Pair<Int, () -> Unit>> {
            if (meta.tier.level >= YtArchiveTier.AUDIO.level) add(R.string.yt_r31_archive_play to { YtArchiveUi.play(context, videoId) })
            add((if (hasScript) R.string.yt_r31_archive_read_script else R.string.yt_r31_archive_read_transcript) to {
                lifecycleScope.launch { YtArchiveUi.showText(context, meta) }
                Unit
            })
            if (!hasScript) add(R.string.yt_r31_archive_make_script to { makeScript(meta) })
            add(R.string.yt_r31_archive_export to {
                exportId = videoId
                runCatching { exporter.launch(YtArchiveUi.exportName(meta)) }
                    .onFailure { Toast.makeText(context, R.string.yt_r31_archive_export_failed, Toast.LENGTH_SHORT).show() }
                Unit
            })
            add(R.string.yt_r31_archive_delete to { confirmDelete(meta) })
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(meta.title.ifBlank { meta.videoId })
            .setItems(actions.map { getString(it.first) }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun makeScript(meta: YtArchiveMeta) {
        val context = context ?: return
        Toast.makeText(context, R.string.yt_r31_archive_script_working, Toast.LENGTH_SHORT).show()
        // the app's scope: a script takes a while and should not end with the screen
        YtArchiveScope.launch {
            val ok = YtArchive.makeScript(meta)
            withContext(Dispatchers.Main) {
                Toast.makeText(context.applicationContext, if (ok) R.string.yt_r31_archive_script_done else R.string.yt_r31_archive_script_failed, Toast.LENGTH_LONG).show()
                if (isAdded) refresh()
            }
        }
    }

    private fun confirmDelete(meta: YtArchiveMeta) {
        val context = context ?: return
        MaterialAlertDialogBuilder(context)
            .setMessage(getString(R.string.yt_r31_archive_delete_confirm, meta.title))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.yt_r31_archive_delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { YtArchive.store.delete(meta.videoId) }
                    refresh()
                }
            }
            .show()
    }

    private companion object {
        /** Rows per list; a preference screen with thousands of rows gets slow. */
        const val MAX_ROWS = 150

        val YtArchiveScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    }
}
