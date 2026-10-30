package com.github.libretube.ui.yt.account

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.text.Html
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.github.libretube.LibreTubeApp
import com.github.libretube.R
import com.github.libretube.api.JsonHelper
import com.github.libretube.api.PlaylistsHelper
import com.github.libretube.api.SubscriptionHelper
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.db.obj.WatchHistoryItem
import com.github.libretube.db.obj.WatchPosition
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.obj.PipedImportPlaylist
import com.github.libretube.obj.YouTubeWatchHistoryFileItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import java.io.BufferedInputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/** What to bring over from YouTube. */
data class YtImportOptions(
    val subscriptions: Boolean = true,
    val playlists: Boolean = true,
    val watchLater: Boolean = true,
    val liked: Boolean = true,
    val history: Boolean = true,
)

/** Progress of a running import, or the result of the last one. */
data class YtImportState(
    val running: Boolean = false,
    val progress: String = "",
    val result: String? = null,
    val failed: Boolean = false,
)

/**
 * YT: brings subscriptions, playlists, watch later, liked videos and history into the app,
 * either straight from the signed-in YouTube account or from a Google Takeout export. Runs in
 * the background, so it goes on when the screen that started it closes; everything lands in
 * the app's own storage (or the chosen sync server).
 */
object YtImport {
    private const val MAX_HISTORY = 1000
    private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 160L * 1024 * 1024
    private const val MAX_ENTRIES = 20_000
    const val TAKEOUT_URL = "https://takeout.google.com/settings/takeout/custom/youtube"

    private val _state = MutableStateFlow(YtImportState())
    val state: StateFlow<YtImportState> = _state.asStateFlow()
    private var job: Job? = null

    private class Counter {
        var subscriptions = 0
        var playlists = 0
        var videos = 0
        var history = 0
    }

    private val context: Context get() = LibreTubeApp.instance

    fun fromAccount(options: YtImportOptions) = runImport { counter -> importAccount(options, counter) }

    fun fromTakeout(uri: Uri) = runImport { counter -> importTakeout(uri, counter) }

    private fun runImport(block: suspend (Counter) -> Unit) {
        if (job?.isActive == true) return
        job = YtAccount.scope.launch {
            val counter = Counter()
            _state.value = YtImportState(running = true, progress = context.getString(R.string.yt_import_running))
            try {
                block(counter)
                val summary = context.getString(
                    R.string.yt_import_done,
                    counter.subscriptions,
                    counter.playlists,
                    counter.videos,
                    counter.history,
                )
                _state.value = YtImportState(result = summary)
                context.toastFromMainDispatcher(summary, Toast.LENGTH_LONG)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = context.getString(R.string.yt_import_failed, e.message ?: e.javaClass.simpleName)
                _state.value = YtImportState(result = message, failed = true)
                context.toastFromMainDispatcher(message, Toast.LENGTH_LONG)
            }
        }
    }

    private fun progress(@StringRes text: Int, vararg args: Any) {
        _state.value = _state.value.copy(progress = context.getString(text, *args))
    }

    // --- from the account

    private suspend fun importAccount(options: YtImportOptions, counter: Counter) {
        val cookies = YtAccount.session() ?: throw IOException(context.getString(R.string.yt_account_failed))
        val api = YtInnerTube(cookies) { YtAccount.saveSession(it) }
        runCatching { api.prepare() }

        if (options.subscriptions) {
            val channels = LinkedHashMap<String, YtChannel>()
            api.pages("FEchannels", 60) { page ->
                page.channels().forEach { channels.putIfAbsent(it.id, it) }
                progress(R.string.yt_import_step_subscriptions, channels.size)
            }
            if (channels.isEmpty()) runCatching { api.guideChannels() }.getOrDefault(emptyList()).forEach { channels.putIfAbsent(it.id, it) }
            val existing = SubscriptionHelper.getSubscriptionChannelIds().toSet()
            for (channel in channels.values) {
                if (channel.id in existing) continue
                SubscriptionHelper.subscribe(channel.id, channel.name, channel.avatar, channel.verified)
                counter.subscriptions++
            }
        }

        if (options.playlists) {
            var refs = collectPlaylists(api, "FEplaylist_aggregation")
            if (refs.isEmpty()) refs = collectPlaylists(api, "FElibrary")
            for (ref in refs) copyPlaylist(api, "VL" + ref.id, ref.title, counter)
        }
        if (options.watchLater) copyPlaylist(api, "VLWL", context.getString(R.string.yt_import_watch_later_name), counter)
        if (options.liked) copyPlaylist(api, "VLLL", context.getString(R.string.yt_import_liked_name), counter)

        if (options.history) {
            val videos = LinkedHashMap<String, YtVideo>()
            api.pages("FEhistory", 40, stop = { videos.size >= MAX_HISTORY }) { page ->
                page.videos().forEach { videos.putIfAbsent(it.id, it) }
                progress(R.string.yt_import_step_history, videos.size)
            }
            val newestFirst = videos.values.take(MAX_HISTORY)
            // the history lists the last inserted first, so the newest goes in last
            Database.watchHistoryDao().insertAll(
                newestFirst.reversed().map { video ->
                    WatchHistoryItem(
                        videoId = video.id,
                        title = video.title,
                        uploader = video.channel,
                        uploaderUrl = video.channelId,
                        thumbnailUrl = thumbnail(video.id),
                        duration = video.seconds,
                    )
                },
            )
            Database.watchPositionDao().insertAll(
                newestFirst.mapNotNull { video ->
                    val percent = video.watchedPercent ?: return@mapNotNull null
                    val seconds = video.seconds ?: return@mapNotNull null
                    WatchPosition(video.id, seconds * 1000L * percent.coerceIn(0, 100) / 100)
                },
            )
            counter.history += newestFirst.size
        }
    }

    private fun collectPlaylists(api: YtInnerTube, browseId: String): List<YtPlaylistRef> {
        val refs = LinkedHashMap<String, YtPlaylistRef>()
        runCatching {
            api.pages(browseId, 10) { page -> page.playlists().forEach { refs.putIfAbsent(it.id, it) } }
        }
        return refs.values.toList()
    }

    private suspend fun copyPlaylist(api: YtInnerTube, browseId: String, name: String, counter: Counter) {
        val videos = LinkedHashMap<String, YtVideo>()
        runCatching {
            api.pages(browseId, 60) { page ->
                page.videos().forEach { videos.putIfAbsent(it.id, it) }
                progress(R.string.yt_import_step_playlist, name, videos.size)
            }
        }
        if (videos.isEmpty()) return
        // a second import fills the same playlist again instead of making a copy
        val playlistId = PlaylistsHelper.getPlaylists().firstOrNull { it.name == name }?.id
            ?: PlaylistsHelper.createPlaylist(name)
            ?: return
        for (chunk in videos.values.chunked(50)) {
            PlaylistsHelper.addToPlaylist(playlistId, *chunk.map { it.toStreamItem() }.toTypedArray())
        }
        counter.playlists++
        counter.videos += videos.size
    }

    private fun YtVideo.toStreamItem() = StreamItem(
        url = "/watch?v=$id",
        type = StreamItem.TYPE_STREAM,
        title = title,
        thumbnail = thumbnail(id),
        uploaderName = channel,
        uploaderUrl = channelId?.let { "/channel/$it" },
        duration = seconds,
    )

    private fun thumbnail(videoId: String) = "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"

    // --- from a Google Takeout export

    private suspend fun importTakeout(uri: Uri, counter: Counter) {
        val files = readTakeout(uri)
        val subscriptions = LinkedHashMap<String, String>()
        val playlists = mutableListOf<PipedImportPlaylist>()
        val history = LinkedHashMap<String, WatchHistoryItem>()

        for ((path, bytes) in files) {
            val name = path.substringAfterLast('/')
            val lower = name.lowercase()
            val text = String(bytes, Charsets.UTF_8).removePrefix("﻿")
            when {
                lower.endsWith(".csv") -> {
                    val rows = text.lineSequence().map { csvCells(it) }.filter { it.isNotEmpty() }.toList()
                    val channelRows = rows.filter { YtInnerTube.CHANNEL_ID.matches(it[0]) }
                    val videoIds = rows.map { it[0] }.filter { VIDEO_ID.matches(it) }.distinct()
                    when {
                        channelRows.isNotEmpty() -> channelRows.forEach { row ->
                            subscriptions.putIfAbsent(row[0], row.getOrNull(2)?.takeIf { it.isNotBlank() } ?: row[0])
                        }
                        videoIds.isNotEmpty() -> playlists.add(
                            PipedImportPlaylist(
                                name = PLAYLIST_FILE.find(name)?.groupValues?.get(1) ?: name.removeSuffix(".csv"),
                                videos = videoIds,
                            ),
                        )
                    }
                }
                lower.endsWith(".json") && text.trimStart().startsWith("[") && text.contains("titleUrl") -> {
                    val items = runCatching { JsonHelper.json.decodeFromString<List<YouTubeWatchHistoryFileItem>>(text) }
                        .getOrDefault(emptyList())
                    items.forEach { item ->
                        val id = WATCH_LINK.find(item.titleUrl)?.groupValues?.get(1) ?: return@forEach
                        history.putIfAbsent(
                            id,
                            WatchHistoryItem(
                                videoId = id,
                                title = cleanTitle(item.title),
                                uploader = item.subtitles.firstOrNull()?.name,
                                uploaderUrl = item.subtitles.firstOrNull()?.url?.takeLast(24),
                                thumbnailUrl = thumbnail(id),
                            ),
                        )
                    }
                }
                lower.endsWith(".html") && HISTORY_FILE.containsMatchIn(lower) -> {
                    HTML_ENTRY.findAll(text).forEach { match ->
                        val id = match.groupValues[1]
                        history.putIfAbsent(
                            id,
                            WatchHistoryItem(
                                videoId = id,
                                title = unescape(match.groupValues[2]),
                                uploader = match.groupValues[4].takeIf { it.isNotEmpty() }?.let(::unescape),
                                uploaderUrl = match.groupValues[3].takeIf { it.isNotEmpty() },
                                thumbnailUrl = thumbnail(id),
                            ),
                        )
                    }
                }
            }
        }

        if (subscriptions.isEmpty() && playlists.isEmpty() && history.isEmpty()) {
            throw IOException(context.getString(R.string.yt_import_nothing_found))
        }

        val existing = SubscriptionHelper.getSubscriptionChannelIds().toSet()
        for ((id, name) in subscriptions) {
            if (id in existing) continue
            SubscriptionHelper.subscribe(id, name, null, false)
            counter.subscriptions++
            if (counter.subscriptions % 10 == 0) progress(R.string.yt_import_step_subscriptions, counter.subscriptions)
        }
        for (playlist in playlists) {
            progress(R.string.yt_import_step_playlist, playlist.name.orEmpty(), playlist.videos.size)
            PlaylistsHelper.importPlaylists(listOf(playlist))
            counter.playlists++
            counter.videos += playlist.videos.size
        }
        if (history.isNotEmpty()) {
            val newestFirst = history.values.take(MAX_HISTORY)
            progress(R.string.yt_import_step_history, newestFirst.size)
            Database.watchHistoryDao().insertAll(newestFirst.reversed())
            counter.history += newestFirst.size
        }
    }

    /** The relevant files of a Takeout ZIP, or the single file that was picked. */
    private fun readTakeout(uri: Uri): List<Pair<String, ByteArray>> {
        val name = DocumentFile.fromSingleUri(context, uri)?.name.orEmpty()
        val files = mutableListOf<Pair<String, ByteArray>>()
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException(context.getString(R.string.yt_import_nothing_found))
        BufferedInputStream(input).use { stream ->
            stream.mark(4)
            val magic = ByteArray(2)
            val read = stream.read(magic)
            stream.reset()
            when {
                read == 2 && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte() -> ZipInputStream(stream).use { zip ->
                    // a crafted ZIP must not fill the memory: each file, all files together and the
                    // number of entries have limits, whatever sizes the ZIP claims
                    var total = 0L
                    var entries = 0
                    var entry = zip.nextEntry
                    while (entry != null && entries < MAX_ENTRIES) {
                        entries++
                        val path = entry.name
                        if (!entry.isDirectory && relevant(path) && entry.size <= MAX_ENTRY_BYTES) {
                            val bytes = readLimited(zip, minOf(MAX_ENTRY_BYTES, MAX_TOTAL_BYTES - total))
                            if (bytes != null) {
                                total += bytes.size
                                files.add(path to bytes)
                            }
                        }
                        entry = zip.nextEntry
                    }
                }
                read == 2 && magic[0] == 0x1F.toByte() && magic[1] == 0x8B.toByte() ->
                    throw IOException(context.getString(R.string.yt_import_zip_only))
                else -> readLimited(stream, MAX_ENTRY_BYTES)?.let { files.add(name to it) }
                    ?: throw IOException(context.getString(R.string.yt_import_nothing_found))
            }
        }
        return files
    }

    /** Reads at most [limit] bytes; null if there is more, so oversized files are skipped. */
    private fun readLimited(input: java.io.InputStream, limit: Long): ByteArray? {
        if (limit <= 0) return null
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var count = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            count += n
            if (count > limit) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun relevant(path: String): Boolean {
        val lower = path.lowercase()
        if (!(lower.endsWith(".csv") || lower.endsWith(".json") || lower.endsWith(".html"))) return false
        // comments, chats and searches also link videos, but are no history
        return SKIPPED_FILES.none { lower.substringAfterLast('/').contains(it) }
    }

    private fun csvCells(line: String): List<String> {
        if (line.isBlank()) return emptyList()
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val char = line[i]
            when {
                char == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> {
                    cell.append('"')
                    i++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    cells += cell.toString().trim()
                    cell.clear()
                }
                else -> cell.append(char)
            }
            i++
        }
        cells += cell.toString().trim()
        return cells
    }

    private fun cleanTitle(title: String): String = title
        .removePrefix("Watched ")
        .removeSuffix(" angesehen")
        .trim()
        .removeSurrounding("„", "“")

    @Suppress("DEPRECATION")
    private fun unescape(text: String): String = Html.fromHtml(text).toString()

    private val VIDEO_ID = Regex("^[\\w-]{11}$")
    private val WATCH_LINK = Regex("watch\\?v=([\\w-]{11})")
    private val PLAYLIST_FILE = Regex("^(.*)-[\\p{L}]+\\.csv$", RegexOption.IGNORE_CASE)
    private val HISTORY_FILE = Regex("watch|wiedergabe|verlauf|history|historial|visionnage|cronologia")
    private val SKIPPED_FILES = listOf("comment", "kommentar", "chat", "search", "such")
    private val HTML_ENTRY = Regex(
        "<a href=\"https://www\\.youtube\\.com/watch\\?v=([\\w-]{11})\">([^<]*)</a>" +
            "(?:<br>\\s*<a href=\"https://www\\.youtube\\.com/channel/(UC[\\w-]{22})\">([^<]*)</a>)?",
    )
}

/** YT: Google Takeout in the browser (with every sign-in method of Google) and the file picker. */
object YtTakeout {
    fun openInBrowser(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, YtImport.TAKEOUT_URL.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** Lets the user pick the Takeout ZIP (or one of its files) and imports it. */
    fun pick(activity: ComponentActivity) {
        var launcher: ActivityResultLauncher<Array<String>>? = null
        val registered = activity.activityResultRegistry.register(
            "yt_takeout_" + SystemClock.uptimeMillis(),
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            launcher?.unregister()
            if (uri != null) YtImport.fromTakeout(uri)
        }
        launcher = registered
        registered.launch(arrayOf("*/*"))
    }
}
