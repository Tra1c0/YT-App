package com.github.libretube.helpers

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.work.ExistingPeriodicWorkPolicy
import com.github.libretube.R
import com.github.libretube.api.JsonHelper
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.obj.BackupFile
import com.github.libretube.obj.PreferenceItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Backup and restore the preferences
 */
object BackupHelper {
    /**
     * Write a [BackupFile] containing the database content as well as the preferences
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun createAdvancedBackup(context: Context, uri: Uri, backupFile: BackupFile) {
        try {
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                JsonHelper.json.encodeToStream(backupFile, outputStream)
            }
            context.toastFromMainDispatcher(R.string.backup_creation_success)
        } catch (e: Exception) {
            Log.e(TAG(), "Error while writing backup: $e")
            context.toastFromMainDispatcher(R.string.backup_creation_failed)
        }
    }

    /**
     * Restore data from a [BackupFile]
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun restoreAdvancedBackup(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        // YT: a broken or crafted file ends in a message, never in a crash
        try {
            restoreAdvancedBackupUnsafe(context, uri)
        } catch (e: Exception) {
            context.toastFromMainDispatcher(e.localizedMessage ?: context.getString(R.string.error))
        } catch (e: OutOfMemoryError) {
            context.toastFromMainDispatcher(context.getString(R.string.error))
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun restoreAdvancedBackupUnsafe(context: Context, uri: Uri) {
        val backupFile = context.contentResolver.openInputStream(uri)?.use {
            JsonHelper.json.decodeFromStream<BackupFile>(LimitedInputStream(it, MAX_BACKUP_BYTES))
        } ?: return

        Database.watchHistoryDao().insertAll(backupFile.watchHistory.orEmpty())
        Database.searchHistoryDao().insertAll(backupFile.searchHistory.orEmpty())
        Database.watchPositionDao().insertAll(backupFile.watchPositions.orEmpty())
        Database.localSubscriptionDao().insertAll(backupFile.subscriptions.orEmpty())
        Database.customInstanceDao().insertAll(backupFile.customInstances.orEmpty())
        Database.playlistBookmarkDao().insertAll(backupFile.playlistBookmarks.orEmpty())
        Database.subscriptionGroupsDao().insertAll(backupFile.groups.orEmpty())

        backupFile.localPlaylists?.forEach {
            // the playlist will be created with an id of 0, so that Room will auto generate a
            // new playlist id to avoid conflicts with existing local playlists
            val playlistId = Database.localPlaylistsDao().createPlaylist(it.playlist.copy(id = 0))
            it.videos.forEach { playlistItem ->
                playlistItem.playlistId = playlistId.toInt()
                Database.localPlaylistsDao().addPlaylistVideo(playlistItem.copy(id = 0))
            }
        }

        restorePreferences(context, backupFile.preferences)
    }

    /**
     * Restore the shared preferences from a backup file
     */
    private fun restorePreferences(context: Context, preferences: List<PreferenceItem>?) {
        if (preferences == null) return

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        // YT: what the app has now, to keep the types and the protected settings
        val current = prefs.all
        prefs.edit(commit = true) {
            // clear the previous settings, but keep what a backup must not bring in
            clear()
            for (key in PROTECTED_KEYS) {
                when (val value = current[key]) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                }
            }

            // decide for each preference which type it is and save it to the preferences
            preferences.forEach { (key, jsonValue) ->
                // YT: a backup never changes where the account's data and token go, nor the
                // app's own bookkeeping; a value of another type than the current one is skipped
                if (key == null || key in PROTECTED_KEYS || key.startsWith("yt_oidc")) return@forEach
                if (jsonValue.isString && jsonValue.content.length > MAX_PREF_STRING) return@forEach
                val value = if (jsonValue.isString) {
                    jsonValue.content
                } else {
                    jsonValue.booleanOrNull
                        ?: jsonValue.intOrNull
                        ?: jsonValue.longOrNull
                        ?: jsonValue.floatOrNull
                }
                val existing = current[key]
                if (existing != null && value != null && !sameType(existing, value, key)) return@forEach
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Float -> putFloat(key, value)
                    is Long -> putLong(key, value)
                    is Int -> {
                        // we only use integers for SponsorBlock colors and the start fragment
                        if (key == PreferenceKeys.START_FRAGMENT || "_color" in key.orEmpty()) {
                            putInt(key, value)
                        } else {
                            putLong(key, value.toLong())
                        }
                    }

                    is String -> {
                        if (
                            key == PreferenceKeys.HOME_TAB_CONTENT ||
                            key == PreferenceKeys.SELECTED_FEED_FILTERS
                        ) {
                            putStringSet(key, value.split(",").toSet())
                        } else {
                            putString(key, value)
                        }
                    }
                }
            }
        }

        // re-schedule the notification worker as some settings related to it might have changed
        NotificationHelper.enqueueWork(context, ExistingPeriodicWorkPolicy.UPDATE)
    }

    /** Settings a backup must not change: where the account's token goes, and bookkeeping. */
    private val PROTECTED_KEYS = setOf(
        PreferenceKeys.AUTH_INSTANCE,
        PreferenceKeys.LIBRETUBE_SYNC_SERVER_URL,
        PreferenceKeys.SYNC_SERVER_TYPE,
        PreferenceKeys.ERROR_LOG,
        PreferenceKeys.PREFERENCE_VERSION,
        PreferenceKeys.EXTERNAL_DOWNLOAD_PROVIDER,
        PreferenceKeys.WELCOME_ACTIVITY_FINISHED,
    )
    private const val MAX_PREF_STRING = 10_000
    private const val MAX_BACKUP_BYTES = 256L * 1024 * 1024

    private fun sameType(existing: Any, value: Any, key: String): Boolean = when (existing) {
        is Boolean -> value is Boolean
        is String -> value is String
        is Set<*> -> value is String
        is Int, is Long -> value is Int || value is Long
        is Float -> value is Float || value is Int || value is Long
        else -> true
    }

    /** Stops reading after [limit] bytes, so a huge file cannot fill the memory. */
    private class LimitedInputStream(input: java.io.InputStream, private val limit: Long) :
        java.io.FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int {
            val b = super.read()
            if (b >= 0 && ++count > limit) throw java.io.IOException("file too large")
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) {
                count += n
                if (count > limit) throw java.io.IOException("file too large")
            }
            return n
        }
    }
}
