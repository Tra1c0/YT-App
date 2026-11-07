package com.github.libretube.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.github.libretube.util.YtSelfUpdater

/** YT: result of the self-update install session, and "the new version is in place". */
class YtUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            YtSelfUpdater.ACTION_INSTALL_STATUS -> YtSelfUpdater.onInstallStatus(context, intent)
            Intent.ACTION_MY_PACKAGE_REPLACED -> YtSelfUpdater.onPackageReplaced(context)
        }
    }
}
