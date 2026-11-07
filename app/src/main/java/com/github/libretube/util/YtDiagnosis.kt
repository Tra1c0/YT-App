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
            add("YouTube account" to if (runCatching { YtAccount.signedIn }.getOrDefault(false)) "yes" else "no")
            add("Queue" to runCatching { PlayingQueue.size().toString() }.getOrNull())
            add("Mini restore" to runCatching { com.github.libretube.ui.yt.mini.YtMiniRestore.traceText() }.getOrNull())
            add("Crashes stored" to crashes.size.toString())
            add("Last crash" to crashes.firstOrNull()?.let(YtCrashText::summary))
        }
        return YtCrashText.block("YT diagnosis", rows)
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
