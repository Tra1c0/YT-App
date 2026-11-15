package com.github.libretube.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import com.github.libretube.BuildConfig
import com.github.libretube.util.YtApkCleanRules.AppKind
import com.github.libretube.util.YtApkCleanRules.Kind
import com.github.libretube.util.YtApkCleanRules.Outcome
import com.github.libretube.util.YtApkCleanRules.Verdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * YT: finds old YT installation files (APKs and zipped CI artifacts in Download, Documents, the
 * folders browsers and messengers save to, and YT's own update folder) and old YT apps, and
 * removes them for the storage page (Y/YtApkCleanUi). A file counts as YT by its parsed package
 * and signing key, never by its name alone; rules in [YtApkCleanRules].
 *
 * Access: other apps' files in shared storage are readable only with "All files access"
 * (Android 11+, asked for only when the user taps) or the storage permission (Android 8–10).
 * Without either, MediaStore still lists the files YT itself created.
 */
object YtApkClean {
    private const val TAG = "YtApkClean"
    private const val PREFS = "yt_apk_clean"
    private const val KEY_AUTO = "auto_clean"
    private const val KEY_KEEP_CURRENT = "keep_current"
    private const val KEY_LAST_CODE = "last_version_code"

    /** Folders below the shared storage root that downloads land in. Case does not matter there. */
    private val SHARED_DIRS = listOf("Download", "Downloads", "Documents", "UCDownloads", "Telegram", "Bluetooth")
    private const val MAX_DEPTH = 4
    private const val MAX_VISITED = 20_000

    /** The copy of a newer local APK handed to Android's installer (FileProvider path update/). */
    private const val LOCAL_APK = "local.apk"
    private const val PEEK_DIR = "yt_apkpeek"

    data class FileItem(
        /** Lower-case path, or the content URI when there is no path. */
        val key: String,
        val name: String,
        val path: String?,
        val uri: Uri?,
        val size: Long,
        val modified: Long,
        /** "Download/Telegram"; null when outside shared storage. */
        val folder: String?,
        /** In YT's own update folder. */
        val inApp: Boolean,
        val kind: Kind,
        val packageName: String?,
        val build: Int?,
        val versionName: String?,
        val verdict: Verdict,
    )

    data class AppItem(
        val packageName: String,
        val label: String,
        val versionName: String?,
        val build: Int?,
        val kind: AppKind,
    )

    data class Scan(
        val files: List<FileItem>,
        val apps: List<AppItem>,
        val access: Boolean,
        val installedCode: Long,
    )

    /** A delete: what went, what failed, and the files only Android's own confirmation can remove. */
    data class Deleted(
        val outcome: Outcome,
        val needConfirm: List<FileItem>,
    )

    // ---------------------------------------------------------------- settings

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun autoClean(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO, true)

    fun setAutoClean(context: Context, on: Boolean) = prefs(context).edit { putBoolean(KEY_AUTO, on) }

    fun keepCurrent(context: Context): Boolean = prefs(context).getBoolean(KEY_KEEP_CURRENT, false)

    fun setKeepCurrent(context: Context, on: Boolean) =
        prefs(context).edit { putBoolean(KEY_KEEP_CURRENT, on) }

    // ---------------------------------------------------------------- access

    /** Whether YT may read and delete other apps' files in shared storage. */
    fun hasAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    /** The runtime permissions of Android 8–10 (declared with maxSdkVersion 29). */
    val legacyPermissions = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
    )

    /** Android 11+: the system page with YT's own "All files access" switch (or the list of apps). */
    fun accessIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val own = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            "package:${context.packageName}".toUri()
        )
        if (own.resolveActivity(context.packageManager) != null) return own
        val list = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        return list.takeIf { it.resolveActivity(context.packageManager) != null }
    }

    // ---------------------------------------------------------------- scan

    suspend fun scan(context: Context): Scan = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        Scan(
            files = runCatching { scanFiles(app) }.onFailure { Log.w(TAG, "file scan", it) }.getOrDefault(emptyList()),
            apps = runCatching { scanApps(app) }.onFailure { Log.w(TAG, "app scan", it) }.getOrDefault(emptyList()),
            access = hasAccess(app),
            installedCode = installedCode(),
        )
    }

    private fun installedCode(): Long = BuildConfig.VERSION_CODE.toLong()

    /** A found file before it is parsed. */
    private data class Raw(
        val name: String,
        val path: String?,
        val uri: Uri?,
        val size: Long,
        val modified: Long,
        val folder: String?,
        val inApp: Boolean = false,
    )

    private fun scanFiles(context: Context): List<FileItem> {
        val found = LinkedHashMap<String, Raw>()
        // YT's own update folder; the APK of an update in progress is not touched
        val updateDir = File(context.cacheDir, YtStorageRules.UPDATE_DIR)
        val held = YtSelfUpdater.holdsApk()
        updateDir.listFiles()?.forEach { file ->
            if (!file.isFile || !file.name.lowercase(Locale.ROOT).endsWith(".apk")) return@forEach
            if (held && file.name == YtStorageRules.UPDATE_APK) return@forEach
            found[keyOf(file.path)] = Raw(file.name, file.path, null, file.length(), file.lastModified(), null, inApp = true)
        }
        @Suppress("DEPRECATION")
        val root = Environment.getExternalStorageDirectory()
        val walked = mutableSetOf<String>()
        var visited = 0
        fun walk(dir: File, depth: Int) {
            if (depth > MAX_DEPTH || visited > MAX_VISITED) return
            val entries = dir.listFiles() ?: return
            for (entry in entries) {
                visited++
                if (entry.name.startsWith(".")) continue
                if (entry.isDirectory) {
                    walk(entry, depth + 1)
                } else if (candidate(entry.name)) {
                    found.getOrPut(keyOf(entry.path)) {
                        Raw(
                            entry.name, entry.path, null, entry.length(), entry.lastModified(),
                            YtApkCleanRules.folderOf(entry.path, root.path)
                        )
                    }
                }
            }
        }
        for (name in SHARED_DIRS) {
            val dir = File(root, name)
            // shared storage ignores case: "Download" and "download" are one folder
            if (!dir.isDirectory || !walked.add(runCatching { dir.canonicalPath }.getOrDefault(dir.path).lowercase(Locale.ROOT))) continue
            walk(dir, 1)
        }
        // what MediaStore lists: without any access at least the files YT created itself
        for (raw in mediaStoreFiles(context, root.path)) {
            val key = raw.path?.let(::keyOf) ?: raw.uri.toString()
            val known = found[key]
            found[key] = known?.copy(uri = raw.uri) ?: raw
        }
        val installedCode = installedCode()
        val ownSigners = YtSelfUpdater.installedSignerHashes(context)
        return found.mapNotNull { (key, raw) ->
            runCatching { identify(context, key, raw, installedCode, ownSigners) }
                .onFailure { Log.w(TAG, "could not read ${raw.name}", it) }
                .getOrNull()
        }.sortedWith(compareBy<FileItem> { it.verdict.ordinal }.thenByDescending { it.modified })
    }

    private fun keyOf(path: String) = path.lowercase(Locale.ROOT)

    private fun candidate(name: String) =
        YtApkCleanRules.isApkCandidate(name) || YtApkCleanRules.isZipCandidate(name)

    private fun mediaStoreFiles(context: Context, rootPath: String): List<Raw> {
        val collections = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL))
                add(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL))
            } else {
                add(MediaStore.Files.getContentUri("external"))
            }
        }
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            @Suppress("DEPRECATION")
            add(MediaStore.MediaColumns.DATA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(MediaStore.MediaColumns.RELATIVE_PATH)
        }.toTypedArray()
        val selection = "${MediaStore.MediaColumns.MIME_TYPE} = ? OR " +
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val args = arrayOf(YtApkCleanRules.APK_MIME, "%.apk", "%.zip")
        val result = LinkedHashMap<Long, Raw>()
        for (collection in collections) {
            runCatching {
                context.contentResolver.query(collection, projection, selection, args, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        if (id in result) continue
                        val name = c.getString(1) ?: continue
                        if (!candidate(name)) continue
                        val path = c.string(MediaStore.MediaColumns.DATA)
                        val relative = c.string("relative_path")?.trim('/')
                        result[id] = Raw(
                            name = name,
                            path = path,
                            uri = ContentUris.withAppendedId(collection, id),
                            size = c.getLong(2),
                            modified = c.getLong(3) * 1000,
                            folder = relative ?: path?.let { YtApkCleanRules.folderOf(it, rootPath) },
                        )
                    }
                }
            }.onFailure { Log.w(TAG, "MediaStore query $collection", it) }
        }
        return result.values.toList()
    }

    private fun Cursor.string(column: String): String? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }

    private fun identify(
        context: Context,
        key: String,
        raw: Raw,
        installedCode: Long,
        ownSigners: Set<String>,
    ): FileItem? {
        val lower = raw.name.lowercase(Locale.ROOT)
        val readable = raw.path?.let(::File)?.takeIf { it.canRead() }
        return if (lower.endsWith(".zip")) {
            val names = readable?.let(::zipNames) ?: raw.uri?.let { zipNames(context, it, raw.size) }
            val entry = names?.let(YtApkCleanRules::zipApkEntry) ?: return null
            val build = YtApkCleanRules.buildFromName(entry)
            val verdict = YtApkCleanRules.verdict(
                Kind.ZIP, samePackage = true, sameSigner = false,
                fileCode = build?.let(YtApkCleanRules::versionCodeOf), installedCode = installedCode
            )
            FileItem(key, raw.name, raw.path, raw.uri, raw.size, raw.modified, raw.folder, raw.inApp, Kind.ZIP, null, build, null, verdict)
        } else {
            val parsed = if (readable != null) {
                parse(context, readable, ownSigners)
            } else {
                raw.uri?.let { peek(context, it, raw.size, ownSigners) }
            } ?: return null
            val (info, sameSigner) = parsed
            val code = PackageInfoCompat.getLongVersionCode(info)
            val verdict = YtApkCleanRules.verdict(
                Kind.APK, samePackage = info.packageName == context.packageName, sameSigner = sameSigner,
                fileCode = code, installedCode = installedCode
            )
            FileItem(
                key, raw.name, raw.path, raw.uri, raw.size, raw.modified, raw.folder, raw.inApp, Kind.APK,
                info.packageName, YtApkCleanRules.buildOf(code), info.versionName, verdict
            )
        }
    }

    private val signatureFlags =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    /**
     * The package of an APK file when it is one of the owner's, and whether it carries YT's key.
     * The cheap read of the manifest comes first; only an own package gets its signature checked.
     */
    private fun parse(context: Context, file: File, ownSigners: Set<String>): Pair<PackageInfo, Boolean>? {
        val pm = context.packageManager
        val plain = pm.getPackageArchiveInfo(file.path, 0) ?: return null
        if (!YtApkCleanRules.isOwnFamily(plain.packageName, context.packageName)) return null
        val signed = pm.getPackageArchiveInfo(file.path, signatureFlags)
        val same = signed != null && YtUpdateRules.sameSigners(ownSigners, signerHashes(signed))
        return (signed ?: plain) to same
    }

    /** An APK behind a content URI only (no readable path): parsed from a short-lived copy. */
    private fun peek(context: Context, uri: Uri, size: Long, ownSigners: Set<String>): Pair<PackageInfo, Boolean>? {
        if (size > YtApkCleanRules.MAX_PEEK_BYTES) return null
        val dir = File(context.cacheDir, PEEK_DIR).apply { mkdirs() }
        val copy = File(dir, "peek.apk")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                copy.outputStream().use { input.copyTo(it, 64 * 1024) }
            } ?: return null
            parse(context, copy, ownSigners)
        } finally {
            copy.delete()
            dir.delete()
        }
    }

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

    /** The central directory of a zip file, read from its end; nothing is extracted. */
    private fun zipNames(file: File): List<String>? = RandomAccessFile(file, "r").use { raf ->
        val length = raf.length()
        val tail = ByteArray(minOf(length, YtApkCleanRules.ZIP_TAIL_BYTES.toLong()).toInt())
        raf.seek(length - tail.size)
        raf.readFully(tail)
        YtApkCleanRules.zipEntryNames(tail, length)
    }

    private fun zipNames(context: Context, uri: Uri, size: Long): List<String>? =
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                val length = channel.size().takeIf { it > 0 } ?: size
                val tail = ByteBuffer.allocate(minOf(length, YtApkCleanRules.ZIP_TAIL_BYTES.toLong()).toInt())
                var at = length - tail.capacity()
                while (tail.hasRemaining()) {
                    val n = channel.read(tail, at)
                    if (n <= 0) break
                    at += n
                }
                if (tail.hasRemaining()) null else YtApkCleanRules.zipEntryNames(tail.array(), length)
            }
        }

    /**
     * Installed apps besides YT: the named packages and every app that opens YouTube links are
     * visible through the manifest's <queries>. Old YT builds carry YT's key or the owner's
     * package namespace; LibreTube's own packages are listed apart.
     */
    private fun scanApps(context: Context): List<AppItem> {
        val pm = context.packageManager
        val own = context.packageName
        val names = linkedSetOf(
            YtApkCleanRules.LIBRETUBE,
            "${YtApkCleanRules.LIBRETUBE}.debug",
            "$own.debug",
        )
        @Suppress("DEPRECATION")
        val handlers = pm.queryIntentActivities(
            Intent(Intent.ACTION_VIEW, "https://www.youtube.com/watch?v=jNQXAC9IVRw".toUri()),
            PackageManager.MATCH_ALL
        )
        handlers.mapTo(names) { it.activityInfo.packageName }
        return names.mapNotNull { name ->
            if (name == own) return@mapNotNull null
            val info = runCatching { pm.getPackageInfo(name, 0) }.getOrNull() ?: return@mapNotNull null
            val same = pm.checkSignatures(own, name) == PackageManager.SIGNATURE_MATCH
            val kind = YtApkCleanRules.classifyApp(name, own, same)
            if (kind == AppKind.NONE) return@mapNotNull null
            val code = PackageInfoCompat.getLongVersionCode(info)
            AppItem(
                packageName = name,
                label = info.applicationInfo?.loadLabel(pm)?.toString() ?: name,
                versionName = info.versionName,
                build = YtApkCleanRules.buildOf(code),
                kind = kind,
            )
        }.sortedBy { it.kind.ordinal }
    }

    fun isInstalled(context: Context, packageName: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess

    /** Android's own uninstall dialog for one package (needs REQUEST_DELETE_PACKAGES). */
    fun uninstallIntent(packageName: String): Intent =
        Intent(Intent.ACTION_DELETE, Uri.fromParts("package", packageName, null))

    // ---------------------------------------------------------------- delete

    /**
     * Deletes [items] without asking: by path where YT may, else through MediaStore (its own
     * files). What only Android's confirmation can remove (Android 11+, another app's file seen
     * through MediaStore) comes back in [Deleted.needConfirm]; with [quiet] it counts as failed.
     */
    suspend fun delete(context: Context, items: List<FileItem>, quiet: Boolean = false): Deleted =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            var deleted = 0
            var bytes = 0L
            var failed = 0
            val confirm = mutableListOf<FileItem>()
            val scanned = mutableListOf<String>()
            for (item in items) {
                if (item.inApp && item.name == YtStorageRules.UPDATE_APK && YtSelfUpdater.holdsApk()) {
                    // an update started since the scan; its APK stays
                    failed++
                    continue
                }
                var gone = item.path?.let { File(it).delete() } ?: false
                if (gone) {
                    // the file is gone; drop its MediaStore row too (or let the scanner see it)
                    if (item.uri != null) {
                        runCatching { resolver.delete(item.uri, null, null) }
                    } else if (!item.inApp) {
                        scanned += item.path!!
                    }
                } else if (item.uri != null) {
                    // RecoverableSecurityException for another app's file: that needs a prompt
                    gone = runCatching { resolver.delete(item.uri, null, null) > 0 }.getOrDefault(false)
                }
                when {
                    gone -> {
                        deleted++
                        bytes += item.size
                    }
                    !quiet && item.uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> confirm += item
                    else -> failed++
                }
            }
            if (scanned.isNotEmpty()) {
                runCatching { MediaScannerConnection.scanFile(context, scanned.toTypedArray(), null, null) }
            }
            Deleted(Outcome(filesDeleted = deleted, bytesFreed = bytes, filesFailed = failed), confirm)
        }

    /** Android 11+: one system confirmation that deletes all of [items] (MediaStore URIs). */
    fun deleteRequest(context: Context, items: List<FileItem>): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val uris = items.mapNotNull { it.uri }.ifEmpty { return null }
        return runCatching { MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender }
            .onFailure { Log.w(TAG, "createDeleteRequest", it) }
            .getOrNull()
    }

    /** After the confirmation: what of [items] is really gone now. */
    suspend fun confirmedOutcome(context: Context, items: List<FileItem>, accepted: Boolean): Outcome =
        withContext(Dispatchers.IO) {
            var deleted = 0
            var bytes = 0L
            for (item in items) {
                val stillThere = item.uri?.let { uri ->
                    runCatching {
                        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                            ?.use { it.count > 0 }
                    }.getOrNull()
                } ?: !accepted
                if (!stillThere) {
                    deleted++
                    bytes += item.size
                }
            }
            Outcome(filesDeleted = deleted, bytesFreed = bytes, filesFailed = items.size - deleted)
        }

    // ---------------------------------------------------------------- install

    /**
     * A newer YT APK found on the phone, handed to Android's installer the way the updater's
     * last resort does: copied into the update folder and opened through the FileProvider. The
     * copy is checked again (package and key). Null when that fails.
     */
    suspend fun installIntent(context: Context, item: FileItem): Intent? = withContext(Dispatchers.IO) {
        if (item.verdict != Verdict.NEWER || item.kind != Kind.APK) return@withContext null
        val app = context.applicationContext
        if (YtSelfUpdater.holdsApk()) return@withContext null
        val dir = File(app.cacheDir, YtStorageRules.UPDATE_DIR).apply { mkdirs() }
        val copy = File(dir, LOCAL_APK)
        val ok = runCatching {
            val source = item.path?.let(::File)?.takeIf { it.canRead() }
            if (source != null) {
                source.inputStream().use { input -> copy.outputStream().use { input.copyTo(it, 64 * 1024) } }
            } else {
                val uri = item.uri ?: return@runCatching false
                app.contentResolver.openInputStream(uri)?.use { input ->
                    copy.outputStream().use { input.copyTo(it, 64 * 1024) }
                } ?: return@runCatching false
            }
            val parsed = parse(app, copy, YtSelfUpdater.installedSignerHashes(app))
            parsed != null && parsed.first.packageName == app.packageName && parsed.second
        }.getOrDefault(false)
        if (!ok) {
            copy.delete()
            return@withContext null
        }
        val uri = runCatching { FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", copy) }
            .getOrNull() ?: return@withContext null
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, YtApkCleanRules.APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .takeIf { it.resolveActivity(app.packageManager) != null }
    }

    // ---------------------------------------------------------------- automation

    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * On the first start of a new build (after an in-app update, or any other install): quietly
     * deletes the old YT APKs YT may delete without asking. Never prompts, never uninstalls.
     */
    fun onAppStart(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        val code = installedCode()
        if (prefs(app).getLong(KEY_LAST_CODE, -1L) == code) return
        scope.launch {
            // after the start's own work
            delay(5_000)
            if (autoClean(app)) {
                runCatching { quietClean(app) }.onFailure { Log.w(TAG, "quiet clean-up", it) }
            }
            prefs(app).edit { putLong(KEY_LAST_CODE, code) }
        }
    }

    private suspend fun quietClean(context: Context): Outcome {
        val keep = keepCurrent(context)
        val files = withContext(Dispatchers.IO) { scanFiles(context) }
            .filter { YtApkCleanRules.autoDelete(it.verdict, keep) }
        if (files.isEmpty()) return Outcome()
        val outcome = delete(context, files, quiet = true).outcome
        Log.i(TAG, "quiet clean-up: $outcome")
        return outcome
    }
}
