package com.github.libretube.ui.yt.archive.risk

import android.content.Context
import com.github.libretube.LibreTubeApp
import com.github.libretube.R
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.ui.yt.archive.YtArchiveCandidate
import com.github.libretube.ui.yt.archive.YtArchiveParts
import com.github.libretube.ui.yt.archive.YtArchiveRiskSource
import com.github.libretube.ui.yt.archive.YtRiskScore
import com.github.libretube.ui.yt.news.YtNewsSummaryKey
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * YT: the archive's risk estimator in the app ([YtArchiveRiskSource], registered at app start by
 * [install]). It wraps [YtRiskEngine] with its file (filesDir/yt_archive_risk.json, read on first
 * use, written a moment after a change), the AI switch (pref [PREF_AI], made by the archive
 * settings) together with the news-summary provider ([YtNewsSummaryKey.setup]), and the reason
 * strings (yt_r31_archiverisk_strings.xml).
 */
object YtArchiveRisk : YtArchiveRiskSource {
    /** Whether the AI check runs (default off); only with a usable news-summary provider. */
    const val PREF_AI = "yt_archive_ai_risk"

    private const val FILE = "yt_archive_risk.json"
    private const val SAVE_DELAY_MS = 1500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fileLock = Any()

    private val saveLock = Any()
    private var saving: Job? = null
    private var dirty = false

    private val engine: YtRiskEngine by lazy {
        val text = runCatching { file().takeIf { it.isFile }?.readText() }.getOrNull()
        YtRiskEngine.decode(text)
    }

    /** Makes this the archive's risk source; cheap (nothing is read before the first use). */
    fun install() {
        YtArchiveParts.risk = this
    }

    private fun context(): Context = LibreTubeApp.instance

    private fun file() = File(context().filesDir, FILE)

    override suspend fun score(candidates: List<YtArchiveCandidate>): List<YtRiskScore> = try {
        withContext(Dispatchers.IO) {
            val context = context()
            val german = context.resources.configuration.locales[0].language == "de"
            val scores = engine.score(candidates, words(context), ask(), german)
            save()
            scores
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        candidates.map { YtRiskScore(it.videoId, 0.0, false, null) }
    }

    override fun observe(candidate: YtArchiveCandidate, vanished: Boolean) {
        runCatching { if (engine.observe(candidate, vanished)) saveSoon() }
    }

    /** For the diagnosis line, e.g. "channels learned 12, labels 3 vanished / 140 stayed, AI today 1/4". */
    fun describe(): String = runCatching { engine.describe() }.getOrElse { "unavailable" }

    /** The AI request of the user's news-summary provider, or null when the check is off or not set up. */
    private fun ask(): YtRiskAsk? {
        if (!PreferenceHelper.getBoolean(PREF_AI, false)) return null
        val setup = runCatching { YtNewsSummaryKey.setup() }.getOrNull() ?: return null
        return YtRiskAiClient(setup)
    }

    private fun words(context: Context) = object : YtRiskWords {
        private val res = context.resources
        override fun channel(gone: Int, total: Int) = res.getQuantityString(R.plurals.yt_archive_risk_channel, total, gone, total)
        override fun topic(words: List<String>) = res.getString(R.string.yt_archive_risk_topic, words.joinToString(", "))
        override fun keywords(words: List<String>) = res.getString(R.string.yt_archive_risk_keywords, words.joinToString(", "))
        override fun ai(reason: String?, percent: Int) =
            if (reason != null) res.getString(R.string.yt_archive_risk_ai, reason) else res.getString(R.string.yt_archive_risk_ai_percent, percent)
    }

    /** Saves a moment after the last of a run of changes (the core labels many videos at once). */
    private fun saveSoon() {
        synchronized(saveLock) {
            dirty = true
            if (saving == null) saving = launchSave()
        }
    }

    private fun launchSave(): Job =
        scope.launch {
            while (true) {
                delay(SAVE_DELAY_MS)
                synchronized(saveLock) {
                    if (!dirty) {
                        saving = null
                        return@launch
                    }
                    dirty = false
                }
                save()
            }
        }

    /** Writes the file whole through a temporary one, so a crash never leaves half of it. */
    private fun save() {
        runCatching {
            val text = engine.encode()
            synchronized(fileLock) {
                val target = file()
                val tmp = File(target.parentFile, "$FILE.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(target)) {
                    target.delete()
                    tmp.renameTo(target)
                }
            }
        }
    }
}
