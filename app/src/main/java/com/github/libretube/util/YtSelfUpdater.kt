package com.github.libretube.util

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import com.github.libretube.R
import com.github.libretube.obj.update.UpdateAsset
import com.github.libretube.receivers.YtUpdateReceiver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * YT: downloads the APK of a release into the cache, checks it (size, SHA-256 from GitHub, same
 * signing key as the installed app, same package) and hands it to a PackageInstaller session.
 *
 * The work runs in a scope tied to the process, not to the dialog, so closing the dialog keeps
 * the download going while the app stays alive. There is no foreground service: if Android
 * kills the process in the background, the download is lost and starts over next time.
 */
object YtSelfUpdater {
    sealed interface State {
        data object Idle : State
        data class Downloading(val bytes: Long, val total: Long) : State
        data object Verifying : State
        data object Installing : State
        /** Android wants the user to confirm; [confirm] opens its dialog again. */
        data class WaitingForUser(val confirm: Intent) : State
        data class Failed(val message: String) : State

        /**
         * Android ended the session without a clear verdict (see [YtUpdateRules.InstallVerdict.UNSURE]):
         * Play Protect may still install it. Shown neutral; [detail] is the system's message.
         */
        data class Unsure(val detail: String) : State
    }

    const val ACTION_INSTALL_STATUS = "app.tra1c0.yt.action.UPDATE_INSTALL_STATUS"

    private const val TAG = "YtSelfUpdater"
    private const val PREFS = "yt_self_updater"
    private const val KEY_COMMITTED_AT = "committed_at"
    private const val CHANNEL_ID = "yt_app_update"
    private const val NOTIFICATION_ID = 0x7955
    private const val RESTART_WINDOW_MS = 60 * 60 * 1000L

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    // A plain client: the shared one logs whole bodies in debug builds, which would buffer the APK.
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private class UpdateError(message: String) : Exception(message)

    fun isBusy(state: State = _state.value) = state is State.Downloading ||
        state is State.Verifying || state is State.Installing || state is State.WaitingForUser

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** The system page where the user allows YT to install apps. */
    fun permissionIntent(context: Context) =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())

    /** A failure shown once stays until the next attempt; a fresh dialog starts clean. */
    fun clearFailure() {
        val now = _state.value
        if (now is State.Failed || now is State.Unsure) _state.value = State.Idle
    }

    @Synchronized
    fun start(context: Context, asset: UpdateAsset) {
        if (job?.isActive == true || isBusy()) return
        val app = context.applicationContext
        _state.value = State.Downloading(0, asset.size)
        job = scope.launch {
            val file = File(updateDir(app), "update.apk")
            try {
                val sha256 = download(asset, file)
                _state.value = State.Verifying
                verify(app, asset, file, sha256)
                _state.value = State.Installing
                install(app, file)
            } catch (e: CancellationException) {
                file.delete()
                _state.value = State.Idle
                throw e
            } catch (e: UpdateError) {
                file.delete()
                _state.value = State.Failed(e.message.orEmpty())
            } catch (e: Exception) {
                file.delete()
                // a cancelled call ends the read with an IOException; that is no failure
                if (!isActive) {
                    _state.value = State.Idle
                    return@launch
                }
                Log.w(TAG, "update failed", e)
                _state.value = State.Failed(
                    app.getString(R.string.yt_update_error_download, e.message ?: e.javaClass.simpleName)
                )
            }
        }
    }

    /** Stops a running download; a committed install session is up to Android from then on. */
    @Synchronized
    fun cancel() {
        if (_state.value !is State.Downloading) return
        job?.cancel()
        job = null
        _state.value = State.Idle
    }

    private fun updateDir(context: Context) = File(context.cacheDir, "update")

    /** Streams the APK into [file] and returns its SHA-256 in hex. */
    private suspend fun download(asset: UpdateAsset, file: File): String {
        file.parentFile?.mkdirs()
        file.delete()
        val request = Request.Builder()
            .url(asset.downloadUrl)
            .header("Accept", "application/octet-stream")
            .build()
        val call = client.newCall(request)
        // a blocking read does not see the cancellation, so cancel the call itself
        currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        call.execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body
            val total = body.contentLength().takeIf { it > 0 } ?: asset.size
            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L
            var reported = 0L
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        read += n
                        if (read - reported >= 128 * 1024) {
                            reported = read
                            _state.value = State.Downloading(read, total)
                        }
                    }
                }
            }
            _state.value = State.Downloading(read, total)
            return YtUpdateRules.hex(digest.digest())
        }
    }

    private fun verify(context: Context, asset: UpdateAsset, file: File, sha256: String) {
        if (asset.size > 0 && file.length() != asset.size) {
            throw UpdateError(context.getString(R.string.yt_update_error_size))
        }
        if (!YtUpdateRules.digestMatches(YtUpdateRules.expectedSha256(asset.digest), sha256)) {
            throw UpdateError(context.getString(R.string.yt_update_error_checksum))
        }
        val pm = context.packageManager
        val archive = archiveInfo(pm, file)
        if (archive == null || archive.packageName != context.packageName) {
            throw UpdateError(context.getString(R.string.yt_update_error_package))
        }
        val installed = installedInfo(pm, context.packageName)
        if (!YtUpdateRules.sameSigners(signerHashes(installed), signerHashes(archive))) {
            throw UpdateError(context.getString(R.string.yt_update_error_signature))
        }
    }

    private val signatureFlags =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, file: File): PackageInfo? =
        pm.getPackageArchiveInfo(file.path, signatureFlags)

    @Suppress("DEPRECATION")
    private fun installedInfo(pm: PackageManager, packageName: String): PackageInfo =
        pm.getPackageInfo(packageName, signatureFlags)

    /** SHA-256 of each certificate the package is currently signed with. */
    @SuppressLint("PackageManagerGetSignatures")
    private fun signerHashes(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        val sha = MessageDigest.getInstance("SHA-256")
        return signatures.orEmpty().map { YtUpdateRules.hex(sha.digest(it.toByteArray())) }.toSet()
    }

    private fun install(context: Context, file: File) {
        if (!canInstall(context)) {
            throw UpdateError(context.getString(R.string.yt_update_error_no_permission))
        }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android may then update without asking (an app updating itself, 12 and newer)
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { output ->
                    file.inputStream().use { it.copyTo(output, 64 * 1024) }
                    session.fsync(output)
                }
                val statusIntent = Intent(context, YtUpdateReceiver::class.java)
                    .setAction(ACTION_INSTALL_STATUS)
                    .setPackage(context.packageName)
                // mutable: the installer adds the status extras to this explicit intent
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(context, sessionId, statusIntent, flags)
                prefs(context).edit(commit = true) { putLong(KEY_COMMITTED_AT, System.currentTimeMillis()) }
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            prefs(context).edit { remove(KEY_COMMITTED_AT) }
            throw UpdateError(
                context.getString(R.string.yt_update_error_install, e.message ?: e.javaClass.simpleName)
            )
        }
    }

    /** Result of the install session (see [YtUpdateReceiver]). */
    fun onInstallStatus(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
        Log.i(TAG, "install status $status: $message")
        when (YtUpdateRules.installVerdict(status)) {
            YtUpdateRules.InstallVerdict.CONFIRM -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm == null) {
                    fail(context, context.getString(R.string.yt_update_error_install, "no confirmation"))
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                _state.value = State.WaitingForUser(confirm)
                // from the background Android would drop the start; a notification works there
                val started = isForeground() && runCatching { context.startActivity(confirm) }.isSuccess
                if (!started) {
                    notify(context, context.getString(R.string.yt_update_ready_title), confirm)
                }
            }
            YtUpdateRules.InstallVerdict.DONE -> {
                // an update of YT itself ends the process before this; anything else lands here
                _state.value = State.Idle
                updateDir(context).deleteRecursively()
            }
            YtUpdateRules.InstallVerdict.ABORTED ->
                fail(context, context.getString(R.string.yt_update_error_aborted))
            YtUpdateRules.InstallVerdict.FAILED ->
                fail(context, context.getString(R.string.yt_update_error_install, message))
            // Play Protect may still install it: keep the restart marker so the new version
            // opens again, and say nothing about failing
            YtUpdateRules.InstallVerdict.UNSURE -> _state.value = State.Unsure(message)
        }
    }

    private fun fail(context: Context, message: String) {
        prefs(context).edit { remove(KEY_COMMITTED_AT) }
        updateDir(context).deleteRecursively()
        _state.value = State.Failed(message)
    }

    /**
     * The new version is installed (ACTION_MY_PACKAGE_REPLACED, in the new process). Opens YT
     * again when the update came from here and Android lets a receiver start an activity;
     * otherwise a notification takes the user back with one tap.
     */
    fun onPackageReplaced(context: Context) {
        updateDir(context).deleteRecursively()
        val prefs = prefs(context)
        val committedAt = prefs.getLong(KEY_COMMITTED_AT, 0)
        prefs.edit { remove(KEY_COMMITTED_AT) }
        // an install by adb, a store or a file manager is not ours to answer
        if (committedAt == 0L || System.currentTimeMillis() - committedAt > RESTART_WINDOW_MS) return

        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (mayStartFromBackground(context) && runCatching { context.startActivity(launch) }.isSuccess) {
            return
        }
        notify(context, context.getString(R.string.yt_update_done_title), launch)
    }

    /**
     * Before Android 10 any receiver may start an activity. From 10 on only exempt apps may; the
     * one exemption YT can hold is "display over other apps", and Android 15 narrowed that to
     * apps with an overlay window on screen, which a freshly updated process never has.
     */
    private fun mayStartFromBackground(context: Context): Boolean = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> true
        Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM -> Settings.canDrawOverlays(context)
        else -> false
    }

    private fun isForeground(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    @SuppressLint("MissingPermission")
    private fun notify(context: Context, title: String, target: Intent) {
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.yt_update_channel_name))
                .setDescription(context.getString(R.string.yt_update_channel_description))
                .build()
        )
        if (!manager.areNotificationsEnabled()) return
        val pending = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            target,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_lockscreen)
            .setContentTitle(title)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
