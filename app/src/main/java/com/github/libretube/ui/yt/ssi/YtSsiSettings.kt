package com.github.libretube.ui.yt.ssi

import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.github.libretube.R
import com.github.libretube.ui.base.BasePreferenceFragment
import com.github.libretube.ui.yt.archive.YtArchiveUi
import com.github.libretube.util.YtCrashLog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * YT: "SSI-Suche" (round 33): the meaning model (download with its size, or delete), what is in
 * the index, background indexing, the Deep budget, the AI switch, "Jetzt indexieren", rebuild
 * and clear. Round 35 (SSI-2): the AI re-rank switch, learning from feedback with its reset,
 * and the benchmark on the user's own examples (import, run, format help).
 */
class YtSsiSettings : BasePreferenceFragment() {
    private val importExamples = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val context = context ?: return@registerForActivityResult
        lifecycleScope.launch {
            val result = runCatching { YtSsiBenchRunner.import(context, uri) }
            result.onSuccess { set ->
                Toast.makeText(context, getString(R.string.yt_r35_ssi_bench_imported, set.examples.size, set.skipped.size), Toast.LENGTH_LONG).show()
                if (set.skipped.isNotEmpty()) {
                    MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.yt_r35_ssi_bench_import)
                        .setMessage(getString(R.string.yt_r35_ssi_bench_skipped, set.skipped.take(12).joinToString("\n")))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }.onFailure { e ->
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.yt_r35_ssi_bench_import)
                    .setMessage(getString(R.string.yt_r35_ssi_bench_import_failed, e.message ?: e.javaClass.simpleName))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            refreshBench()
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.yt_r33_ssi_settings, rootKey)
        findPreference<Preference>(KEY_LEARN_RESET)?.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.yt_r35_ssi_learn_reset)
                .setMessage(R.string.yt_r35_ssi_learn_reset_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val context = requireContext()
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { YtSsi.resetLearning(context) }
                        refreshLearning()
                    }
                }
                .show()
            true
        }
        findPreference<Preference>(KEY_BENCH_IMPORT)?.setOnPreferenceClickListener {
            runCatching { importExamples.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
                .onFailure { Toast.makeText(requireContext(), R.string.yt_r35_ssi_bench_no_picker, Toast.LENGTH_SHORT).show() }
            true
        }
        findPreference<Preference>(KEY_BENCH_RUN)?.setOnPreferenceClickListener {
            runBench()
            true
        }
        findPreference<Preference>(KEY_BENCH_FORMAT)?.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.yt_r35_ssi_bench_format)
                .setMessage(R.string.yt_r35_ssi_bench_format_text)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            true
        }
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
        refreshLearning()
        refreshBench()
    }

    private fun refreshLearning() {
        val context = context ?: return
        lifecycleScope.launch {
            val state = withContext(Dispatchers.IO) { YtSsi.learnState(context) }
            findPreference<Preference>(KEY_LEARN_RESET)?.summary = if (state.events == 0) {
                getString(R.string.yt_r35_ssi_learn_none)
            } else {
                val now = System.currentTimeMillis()
                val s = YtSsiLearning.decay(state, now)
                val moved = s.kinds.entries.joinToString("; ") { (kind, m) ->
                    getString(R.string.yt_r35_ssi_learn_kind, kindName(kind), f2(m.lexical), f2(m.semantic), f2(m.title))
                }
                getString(R.string.yt_r35_ssi_learn_state, state.events, moved.ifEmpty { "–" }, "%+.3f".format(Locale.getDefault(), s.cutOffset))
            }
        }
    }

    private fun kindName(kind: String): String = getString(
        when (kind) {
            YtSsiIntentKind.EXACT_QUOTE.name -> R.string.yt_r35_ssi_kind_quote
            YtSsiIntentKind.FIND_VIDEO.name -> R.string.yt_r35_ssi_kind_video
            YtSsiIntentKind.EXPLANATION.name, YtSsiIntentKind.QUESTION.name -> R.string.yt_r35_ssi_kind_question
            else -> R.string.yt_r35_ssi_kind_statement
        },
    )

    private fun f2(v: Double) = "%.2f".format(Locale.getDefault(), v)

    private fun refreshBench() {
        val context = context ?: return
        lifecycleScope.launch {
            val (set, last) = withContext(Dispatchers.IO) { YtSsiBenchRunner.examples(context) to YtSsiBenchRunner.last(context) }
            findPreference<Preference>(KEY_BENCH_IMPORT)?.summary = if (set == null) {
                getString(R.string.yt_r35_ssi_bench_import_summary)
            } else {
                getString(R.string.yt_r35_ssi_bench_examples, set.examples.size, set.quotes, set.questions)
            }
            findPreference<Preference>(KEY_BENCH_RUN)?.apply {
                isEnabled = set != null
                summary = when {
                    set == null -> getString(R.string.yt_r35_ssi_bench_run_none)
                    last == null -> getString(R.string.yt_r35_ssi_bench_run_summary)
                    else -> getString(R.string.yt_r35_ssi_bench_last, YtCrashLog.formatTime(last.current.at), f2(last.current.recall3), f2(last.baseline.recall3))
                }
            }
        }
    }

    private fun runBench() {
        val context = requireContext()
        val dialog: AlertDialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.yt_r35_ssi_bench_run)
            .setMessage(R.string.yt_r35_ssi_bench_running)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        var finished = false
        val job = lifecycleScope.launch {
            val outcome = runCatching {
                YtSsiBenchRunner.run(context) { p ->
                    lifecycleScope.launch {
                        if (finished) return@launch
                        dialog.setMessage(
                            if (p.fetching > 0) {
                                context.getString(R.string.yt_r35_ssi_bench_fetching, p.fetching)
                            } else {
                                context.getString(R.string.yt_r35_ssi_bench_progress, p.done, p.total)
                            },
                        )
                    }
                }
            }
            finished = true
            dialog.dismiss()
            outcome.onSuccess { result ->
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.yt_r35_ssi_bench_result)
                    .setMessage(report(result))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) return@onFailure
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.yt_r35_ssi_bench_run)
                    .setMessage(getString(R.string.yt_r35_ssi_bench_failed, e.message ?: e.javaClass.simpleName))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            refreshBench()
        }
        dialog.setOnDismissListener { if (!finished) job.cancel() }
    }

    /** The result as the dialog shows it: SSI-2, the SSI-1 way, and the change since the last run. */
    private fun report(outcome: YtSsiBenchRunner.Outcome): String {
        val run = outcome.run
        val m = run.current
        val set = YtSsiBenchRunner.examples(requireContext())
        return buildString {
            if (set != null) append(getString(R.string.yt_r35_ssi_bench_head, set.examples.size, set.quotes, set.questions, set.negatives)).append("\n")
            if (run.fetched > 0 || run.missing > 0) append(getString(R.string.yt_r35_ssi_bench_videos, run.fetched, run.missing)).append("\n")
            if (outcome.blocked) append(getString(R.string.yt_r35_ssi_bench_blocked)).append("\n")
            append("\n").append(getString(R.string.yt_r35_ssi_bench_current)).append("\n")
            metrics(this, m)
            append("\n").append(getString(R.string.yt_r35_ssi_bench_baseline)).append("\n")
            metrics(this, run.baseline)
            append("\n")
            val before = outcome.previous?.current
            when {
                before == null -> append(getString(R.string.yt_r35_ssi_bench_first))
                !YtSsiBenchmark.comparable(before, m) -> append(getString(R.string.yt_r35_ssi_bench_other_examples))
                else -> {
                    append(getString(R.string.yt_r35_ssi_bench_since, YtCrashLog.formatTime(before.at))).append("\n")
                    for ((label, a, b) in listOf(
                        Triple("Recall@1", before.recall1, m.recall1),
                        Triple("Recall@3", before.recall3, m.recall3),
                        Triple("Recall@5", before.recall5, m.recall5),
                        Triple("nDCG@5", before.ndcg5, m.ndcg5),
                    )) {
                        append(getString(R.string.yt_r35_ssi_bench_delta, label, f2(a), f2(b), "%+.2f".format(Locale.getDefault(), b - a))).append("\n")
                    }
                    append(getString(R.string.yt_r35_ssi_bench_delta, "Start P50", ms(before.deviationP50Ms), ms(m.deviationP50Ms), "")).append("\n")
                    append(getString(R.string.yt_r35_ssi_bench_delta, "Start P95", ms(before.deviationP95Ms), ms(m.deviationP95Ms), ""))
                }
            }
        }
    }

    private fun metrics(out: StringBuilder, m: YtSsiBenchmark.Metrics) {
        out.append(getString(R.string.yt_r35_ssi_bench_recall, f2(m.recall1), f2(m.recall3), f2(m.recall5))).append("\n")
        if (m.recall3Quotes != null || m.recall3Questions != null) {
            out.append(getString(R.string.yt_r35_ssi_bench_recall_kinds, m.recall3Quotes?.let(::f2) ?: "–", m.recall3Questions?.let(::f2) ?: "–")).append("\n")
        }
        out.append(getString(R.string.yt_r35_ssi_bench_ndcg, f2(m.ndcg5))).append("\n")
        out.append(getString(R.string.yt_r35_ssi_bench_deviation, ms(m.deviationP50Ms), ms(m.deviationP95Ms))).append("\n")
        out.append(getString(R.string.yt_r35_ssi_bench_nomatch, f2(m.noMatch))).append("\n")
        m.emptyRight?.let { out.append(getString(R.string.yt_r35_ssi_bench_empty_right, f2(it))).append("\n") }
        out.append(getString(R.string.yt_r35_ssi_bench_latency, m.latencyP50Ms, m.latencyP95Ms)).append("\n")
        if (m.aiUsed > 0) out.append(getString(R.string.yt_r35_ssi_bench_ai, m.aiUsed, m.examples, ms(m.aiP50Ms))).append("\n")
    }

    private fun ms(v: Long?): String = v?.let { if (it >= 10_000) "%.1f s".format(Locale.getDefault(), it / 1000.0) else "$it ms" } ?: "–"

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
        const val KEY_LEARN_RESET = "yt_ssi_learn_reset"
        const val KEY_BENCH_IMPORT = "yt_ssi_bench_import"
        const val KEY_BENCH_RUN = "yt_ssi_bench_run"
        const val KEY_BENCH_FORMAT = "yt_ssi_bench_format"
    }
}
