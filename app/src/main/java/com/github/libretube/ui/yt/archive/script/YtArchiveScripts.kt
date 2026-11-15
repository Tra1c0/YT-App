package com.github.libretube.ui.yt.archive.script

import android.content.Context
import androidx.core.content.edit
import com.github.libretube.LibreTubeApp
import com.github.libretube.ui.yt.archive.YtArchiveParts
import com.github.libretube.ui.yt.news.YtNewsSummaryKey
import java.time.LocalDate

/**
 * YT: the script part in the app. It uses the provider, key and model chosen for the news
 * summaries (no setting of its own); without one it writes plain paragraphs and sends nothing.
 * The day's counts live in their own small preferences file.
 */
object YtArchiveScripts {
    private const val PREFS = "yt_archive_script"
    private const val LEDGER = "ledger"

    private object PrefsStore : YtScriptStore {
        private fun prefs() = LibreTubeApp.instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        override fun load(): YtScriptLedger = YtScriptLedger.parse(prefs().getString(LEDGER, null))

        override fun save(ledger: YtScriptLedger) = prefs().edit { putString(LEDGER, ledger.toJson()) }
    }

    val maker: YtScriptMaker by lazy {
        YtScriptMaker(
            textAi = { YtNewsSummaryKey.setup()?.let(YtScriptTextAis::of) },
            audioAi = { YtNewsSummaryKey.setup()?.let(YtScriptAudioAis::of) },
            store = PrefsStore,
            today = { LocalDate.now().toString() },
        )
    }

    /** At app start: the archive core uses this part from now on. */
    fun register() {
        YtArchiveParts.script = maker
    }

    /** One line for the diagnosis page (counts and the latest outcome; no key, no video id). */
    fun diagnosis(): String = runCatching { maker.diagnosis() }.getOrDefault("unavailable")
}
