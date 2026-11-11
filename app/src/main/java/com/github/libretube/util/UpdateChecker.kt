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
import com.github.libretube.obj.update.UpdateInfo
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
        try {
            val newer = newerRelease()
            if (newer != null) {
                val (response, update) = newer
                // the APK of that build, downloaded and installed in the app; null -> release page
                val apk = YtUpdateRules.pickApk(response.assets, update)
                // YT: the notes of every build since the installed one (the user may skip some)
                val notes = fetchNotes(installedBuild(), update, response.body)
                withContext(Dispatchers.Main) {
                    showUpdateAvailableDialog(response.body, response.htmlUrl, apk, notes, update)
                }
                Log.i(TAG(), response.toString())
            } else if (isManualCheck) {
                context.toastFromMainDispatcher(R.string.app_uptodate)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // YT: asked for (button, guide, update notification): say that it did not work
            if (isManualCheck) context.toastFromMainDispatcher(R.string.yt_r27_updnotify_check_failed)
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
            val tag = UpdateAvailableDialog::class.java.simpleName
            // YT: one dialog at a time (the start check and a tap on the update notification)
            runCatching { it.executePendingTransactions() }
            if (it.findFragmentByTag(tag) != null) return
            dialog.show(it, tag)
            // YT: seen in the app: no notification for this build any more
            offered?.let { build -> YtUpdateNotifier.markSeen(context, build) }
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

    companion object {
        /**
         * YT: builds are numbered ("32.1-yt.88", release "YT Build 88"); the installed one, or
         * null for an unnumbered local build.
         */
        fun installedBuild(): Int? = YtUpdateRules.currentBuild(BuildConfig.VERSION_NAME)

        /**
         * YT: the latest release with its build number when that build is newer than the installed
         * one, else null. Only a newer build is an update: comparing all digits for inequality
         * offered the latest release (an older build) to every test build of a branch. Throws when
         * the request fails. Shared by the dialog check and the background check
         * ([com.github.libretube.workers.YtUpdateCheckWorker]).
         */
        suspend fun newerRelease(): Pair<UpdateInfo, Int>? {
            val response = RetrofitInstance.externalApi.getLatestRelease()
            val update = YtUpdateRules.buildNumber(response.name)
                ?: YtUpdateRules.buildNumber(response.tagName)
                ?: return null
            return if (YtUpdateRules.isNewer(installedBuild(), update)) response to update else null
        }
    }
}
