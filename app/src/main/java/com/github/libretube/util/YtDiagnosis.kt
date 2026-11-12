package com.github.libretube.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import com.github.libretube.api.RetrofitInstance
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.enums.SyncServerType
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.YtDesignDark
import com.github.libretube.ui.yt.account.YtAccount

/**
 * YT: the text of the diagnosis page. Only facts that help find a bug, in "key: value" lines the
 * owner can paste into a chat. Never tokens, cookies, user names or account ids: the account is
 * reported as yes/no, the sync server only by host name.
 */
object YtDiagnosis {

    /** The queue kept over a restart (YtQueueStore): size, place, when it was written, whether it waits. */
    private fun savedQueue(): String {
        val saved = YtQueueStore.saved ?: return "none"
        val waits = if (YtQueueStore.pending) ", waiting" else ""
        return "${saved.items.size} videos, at ${saved.currentIndex + 1}, saved ${YtCrashLog.formatTime(saved.savedAt)}$waits"
    }

    fun text(context: Context): String {
        val crashes = YtCrashLog.reports(context)
        val rows = buildList<Pair<String, String?>> {
            add("Time" to YtCrashLog.formatTime(System.currentTimeMillis()))
            addAll(YtCrashLog.buildLines())
            add("Dark now" to if (YtDesignDark.active) "yes" else "no")
            add("Streams" to "NewPipe (on device)")
            add("Sync" to sync())
            add("Network" to network(context))
            add("Last update" to runCatching { YtSelfUpdater.lastResult(context) }.getOrNull())
            add("Update check" to runCatching { YtUpdateNotifier.diagnosis(context) }.getOrNull())
            // only "registered" or not, never the push address
            add("Update push" to runCatching { YtPush.diagnosis(context) }.getOrNull())
            add("Update watch" to runCatching { YtUpdateWatch.diagnosis(context) }.getOrNull())
            add("New videos" to runCatching { newVideos(context) }.getOrNull())
            // only whether a key is set, never the key
            add("News summaries" to runCatching { com.github.libretube.ui.yt.news.YtNewsSummaryKey.diagnosis() }.getOrNull())
            add("YouTube account" to if (runCatching { YtAccount.signedIn }.getOrDefault(false)) "yes" else "no")
            add("Queue" to runCatching { PlayingQueue.size().toString() }.getOrNull())
            add("Saved queue" to runCatching { savedQueue() }.getOrNull())
            add("Sound" to sound())
            add("Frequency" to runCatching { com.github.libretube.ui.yt.FrequencyPulse.diagnosis() }.getOrNull())
            add("Synthwave" to runCatching { com.github.libretube.ui.yt.SynthwaveScene.diagnosis() }.getOrNull())
            add("Mini restore" to runCatching { com.github.libretube.ui.yt.mini.YtMiniRestore.traceText() }.getOrNull())
            add("Crashes stored" to crashes.size.toString())
            add("Last crash" to crashes.firstOrNull()?.let(YtCrashText::summary))
        }
        return YtCrashText.block("YT diagnosis", rows)
    }

    /**
     * The sound engine (Frequency): whether the UI listens, every condition for the analysis,
     * whether the frame loop runs, frames per second and the loudest values of the last 10 s it
     * ran. Main thread; the diagnosis page reads it again twice a second while it is open.
     */
    fun sound(): String =
        runCatching { com.github.libretube.ui.yt.sound.YtSoundEngine.diagnosis() }.getOrDefault("?")

    /**
     * LibreTube's new-video notifications: the switch, whether its worker is scheduled, and
     * whether Android shows its channel; apart from the update check above.
     */
    private fun newVideos(context: Context): String {
        val on = PreferenceHelper.getBoolean(PreferenceKeys.NOTIFICATION_ENABLED, true)
        val scheduled = runCatching {
            androidx.work.WorkManager.getInstance(context).getWorkInfosForUniqueWork("NotificationService")
                .get(300, java.util.concurrent.TimeUnit.MILLISECONDS)
                .any { !it.state.isFinished }
        }.getOrNull()
        val channel = when (YtNotifyChannels.isBlocked(context, YtNotifyChannels.VIDEOS)) {
            true -> "blocked in Android"
            false -> "shown"
            null -> "no channel"
        }
        val job = when (scheduled) {
            true -> "scheduled"
            false -> "not scheduled"
            null -> "?"
        }
        return "${if (on) "on" else "off"}, worker $job, channel $channel"
    }

    /** Which sync server is used, and its host; the full URL could carry a path with an id. */
    private fun sync(): String = runCatching {
        val type = PreferenceHelper.getString(PreferenceKeys.SYNC_SERVER_TYPE, "none")
        val url = when (type.lowercase()) {
            SyncServerType.PIPED.name.lowercase() -> RetrofitInstance.pipedAuthUrl
            SyncServerType.LIBRETUBE.name.lowercase() -> RetrofitInstance.libretubeSyncServerUrl
            else -> null
        }
        val host = url?.let { runCatching { java.net.URI(it).host }.getOrNull() ?: "?" }
        if (host == null) type else "$type @ $host"
    }.getOrDefault("?")

    private fun network(context: Context): String = runCatching {
        val cm = context.getSystemService<ConnectivityManager>() ?: return "?"
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "offline"
        val kind = listOfNotNull(
            "wifi".takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) },
            "mobile".takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) },
            "ethernet".takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) },
            "vpn".takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) },
        ).ifEmpty { listOf("other") }.joinToString("+")
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val metered = cm.isActiveNetworkMetered
        val saver = if (cm.restrictBackgroundStatus ==
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        ) ", data saver" else ""
        "$kind, ${if (metered) "metered" else "unmetered"}" +
            (if (validated) "" else ", no internet") + saver
    }.getOrDefault("?")
}
