package com.github.libretube.util

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.FragmentActivity
import androidx.core.net.toUri
import com.github.libretube.BuildConfig
import com.github.libretube.R
import com.github.libretube.api.RetrofitInstance
import com.github.libretube.constants.IntentData.appUpdateChangelog
import com.github.libretube.constants.IntentData.appUpdateURL
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.ui.dialogs.UpdateAvailableDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.util.Locale

class UpdateChecker(private val context: Context) {
    suspend fun checkUpdate(isManualCheck: Boolean = false) {
        // Private releases require the user's GitHub session. The browser (or GitHub app)
        // already has it; the APK does not need a GitHub credential of its own.
        if (isManualCheck) {
            withContext(Dispatchers.Main) {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, RELEASES_URL.toUri())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: android.content.ActivityNotFoundException) {
                    context.toastFromMainDispatcher(R.string.yt_update_open_failed)
                }
            }
            return
        }

        try {
            val response = RetrofitInstance.externalApi.getLatestRelease()
            if (YtBuildVersion.isNewer(BuildConfig.VERSION_NAME, response.tagName)) {
                withContext(Dispatchers.Main) {
                    showUpdateAvailableDialog(response.body, response.htmlUrl)
                }
                Log.i(TAG(), response.toString())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // An unauthenticated request to the private repository returns 404. Leave the
            // optional automatic check quiet; manual checking always opens the release page.
            Log.d(TAG(), "Automatic update check unavailable: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        const val RELEASES_URL = "https://github.com/Tra1c0/YT-App/releases/latest"
    }

    private fun showUpdateAvailableDialog(
        changelog: String,
        url: String
    ) {
        val dialog = UpdateAvailableDialog()
        val args =
            Bundle().apply {
                putString(appUpdateChangelog, sanitizeChangelog(changelog))
                putString(appUpdateURL, url)
            }
        dialog.arguments = args
        val fragmentManager = (context as? FragmentActivity)?.supportFragmentManager
        fragmentManager?.let {
            dialog.show(it, UpdateAvailableDialog::class.java.simpleName)
        }
    }

    private fun sanitizeChangelog(changelog: String): String {
        return changelog.substringBeforeLast("**Full Changelog**")
            .replace(Regex("in https://github\\.com/\\S+"), "")
            .lines().joinToString("\n") { line ->
                if (line.startsWith("##")) line.uppercase(Locale.ROOT) + " :" else line
            }
            .replace("## ", "")
            .replace(">", "")
            .replace("*", "•")
            .lines()
            .joinToString("\n") { it.trim() }
    }
}
