package com.github.libretube.ui.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.core.net.toUri
import com.github.libretube.extensions.TAG
import com.github.libretube.helpers.IntentHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.ui.base.BaseActivity

class RouterActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // YT: only a web address is taken from shared text or a link; anything else just opens
        // the app (before, any app could end the app's process this way, or crash it)
        val uri = com.github.libretube.helpers.YtGuard.firstUrl(intent.getStringExtra(Intent.EXTRA_TEXT))
            ?: intent.data?.takeIf { com.github.libretube.helpers.YtGuard.isWebLink(it) }
        if (uri != null) {
            handleSendText(uri)
        } else {
            packageManager.getLaunchIntentForPackage(packageName)?.let { startActivity(it) }
            finish()
        }
    }

    private fun handleSendText(uri: Uri) {
        Log.i(TAG(), uri.toString())

        val intent = packageManager.getLaunchIntentForPackage(packageName)!!.let { intent ->
            IntentHelper.resolveType(intent, uri)
        }
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finishAndRemoveTask()
    }
}
