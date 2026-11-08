package com.github.libretube.util

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.github.libretube.BuildConfig
import com.github.libretube.R
import com.github.libretube.api.RetrofitInstance
import com.github.libretube.constants.IntentData.appUpdateChangelog
import com.github.libretube.constants.IntentData.appUpdateURL
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.obj.update.UpdateAsset
import com.github.libretube.ui.dialogs.UpdateAvailableDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.util.Locale

class UpdateChecker(private val context: Context) {
    suspend fun checkUpdate(isManualCheck: Boolean = false) {
        // YT: builds are numbered ("32.1-yt.88", release "YT Build 88"); only a newer build is an
        // update. Comparing all digits for inequality offered the latest release (an older build)
        // to every test build of a branch.
        val currentBuild = YtUpdateRules.currentBuild(BuildConfig.VERSION_NAME)

        try {
            val response = RetrofitInstance.externalApi.getLatestRelease()
            val update = YtUpdateRules.buildNumber(response.name)
                ?: YtUpdateRules.buildNumber(response.tagName)

            if (YtUpdateRules.isNewer(currentBuild, update)) {
                // the APK of that build, downloaded and installed in the app; null -> release page
                val apk = YtUpdateRules.pickApk(response.assets, update)
                // YT: the notes of every build since the installed one (the user may skip some)
                val notes = fetchNotes(currentBuild, update ?: return, response.body)
                withContext(Dispatchers.Main) {
                    showUpdateAvailableDialog(response.body, response.htmlUrl, apk, notes, update)
                }
                Log.i(TAG(), response.toString())
            } else if (isManualCheck) {
                context.toastFromMainDispatcher(R.string.app_uptodate)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * YT: the release bodies of the builds after [installed] up to [offered] (newest first, at
     * most 10), merged by [YtReleaseNotes]. The offered build's body is already here; a build
     * that is missing or cannot be fetched is skipped, and the whole fetch gives up after 8 s.
     */
    private suspend fun fetchNotes(
        installed: Int?,
        offered: Int,
        offeredBody: String
    ): List<YtReleaseNotes.BuildNotes> {
        val builds = YtReleaseNotes.buildsToFetch(installed, offered)
        val bodies = withTimeoutOrNull(8_000) {
            coroutineScope {
                builds.map { build ->
                    async {
                        build to if (build == offered) {
                            offeredBody
                        } else {
                            runCatching {
                                RetrofitInstance.externalApi.getReleaseByTag("build-$build").body
                            }.onFailure { Log.i(TAG(), "notes of build $build: $it") }.getOrNull()
                        }
                    }
                }.awaitAll().toMap()
            }
        } ?: mapOf(offered to offeredBody)
        return YtReleaseNotes.merge(bodies)
    }

    private fun showUpdateAvailableDialog(
        changelog: String,
        url: String,
        apk: UpdateAsset?,
        notes: List<YtReleaseNotes.BuildNotes>,
        offered: Int? = notes.firstOrNull()?.build
    ) {
        val dialog = UpdateAvailableDialog()
        val args =
            Bundle().apply {
                putString(appUpdateChangelog, sanitizeChangelog(changelog))
                putString(appUpdateURL, url)
                putParcelable(UpdateAvailableDialog.APP_UPDATE_ASSET, apk)
                // YT: the notes per build, and the offered build's technical lines (Details)
                putIntArray(UpdateAvailableDialog.NOTES_BUILDS, notes.map { it.build }.toIntArray())
                putStringArrayList(UpdateAvailableDialog.NOTES_TEXTS, ArrayList(notes.map { it.text }))
                putString(UpdateAvailableDialog.NOTES_DETAILS, YtReleaseNotes.detailsPart(changelog))
                offered?.let { putInt(UpdateAvailableDialog.NOTES_OFFERED, it) }
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
