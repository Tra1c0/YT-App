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
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.decodeToSequence
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
    private const val MAX_CSV_BYTES = 8L * 1024 * 1024
    private const val MAX_HTML_BYTES = 24L * 1024 * 1024
    private const val MAX_UNPACKED = 1024L * 1024 * 1024
    private const val MAX_ENTRIES = 20_000
    private const val MAX_HISTORY_SCAN = 20_000
    private const val MAX_SUBSCRIPTIONS = 5_000
    private const val MAX_PLAYLISTS = 200
    private const val MAX_PLAYLIST_VIDEOS = 5_000
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

    /** Stops a running import (e.g. on sign-out). */
    fun cancel() {
        job?.cancel()
        job = null
        _state.value = YtImportState()
    }

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
            } catch (e: OutOfMemoryError) {
                val message = context.getString(R.string.yt_import_failed, context.getString(R.string.yt_import_too_large))
                _state.value = YtImportState(result = message, failed = true)
                context.toastFromMainDispatcher(message, Toast.LENGTH_LONG)
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
        val epoch = YtAccount.epoch
        val cookies = YtAccount.session() ?: throw IOException(context.getString(R.string.yt_account_failed))
        // renewed cookies are kept only while the same sign-in lasts
        val api = YtInnerTube(cookies) { if (YtAccount.epoch == epoch) YtAccount.saveSession(it) }
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
                if (counter.subscriptions >= MAX_SUBSCRIPTIONS) break
                if (channel.id in existing) continue
                SubscriptionHelper.subscribe(channel.id, channel.name, channel.avatar, channel.verified)
                counter.subscriptions++
            }
        }

        if (options.playlists) {
            var refs = collectPlaylists(api, "FEplaylist_aggregation")
            if (refs.isEmpty()) refs = collectPlaylists(api, "FElibrary")
            for (ref in refs.take(MAX_PLAYLISTS)) copyPlaylist(api, "VL" + ref.id, ref.title, counter)
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

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun importTakeout(uri: Uri, counter: Counter) {
        val subscriptions = LinkedHashMap<String, String>()
        val playlists = mutableListOf<PipedImportPlaylist>()
        val history = LinkedHashMap<String, WatchHistoryItem>()

        // each file is read and parsed on its own, never the whole export at once
        readTakeout(uri) { path, input ->
            val name = path.substringAfterLast('/')
            val lower = name.lowercase()
            when {
                lower.endsWith(".csv") -> {
                    val bytes = readLimited(input, MAX_CSV_BYTES) ?: return@readTakeout
                    val rows = String(bytes, Charsets.UTF_8).removePrefix("﻿")
                        .lineSequence().map { csvCells(it) }.filter { it.isNotEmpty() }.toList()
                    val channelRows = rows.filter { YtInnerTube.CHANNEL_ID.matches(it[0]) }
                    val videoIds = rows.asSequence().map { it[0] }.filter { VIDEO_ID.matches(it) }
                        .distinct().take(MAX_PLAYLIST_VIDEOS).toList()
                    when {
                        channelRows.isNotEmpty() -> {
                            for (row in channelRows) {
                                if (subscriptions.size >= MAX_SUBSCRIPTIONS) break
                                subscriptions.putIfAbsent(row[0], row.getOrNull(2)?.takeIf { it.isNotBlank() }?.take(200) ?: row[0])
                            }
                        }
                        videoIds.isNotEmpty() && playlists.size < MAX_PLAYLISTS -> playlists.add(
                            PipedImportPlaylist(
                                name = (PLAYLIST_FILE.find(name)?.groupValues?.get(1) ?: name.removeSuffix(".csv")).take(200),
                                videos = videoIds,
                            ),
                        )
                    }
                }
                lower.endsWith(".json") -> {
                    if (history.size >= MAX_HISTORY) return@readTakeout
                    // read as a stream: only the newest entries, whatever the size of the file
                    runCatching {
                        JsonHelper.json.decodeToSequence(input, YouTubeWatchHistoryFileItem.serializer(), DecodeSequenceMode.ARRAY_WRAPPED)
                            .take(MAX_HISTORY_SCAN)
                            .forEach { item ->
                                if (history.size >= MAX_HISTORY) return@runCatching
                                val id = WATCH_LINK.find(item.titleUrl)?.groupValues?.get(1) ?: return@forEach
                                history.putIfAbsent(
                                    id,
                                    WatchHistoryItem(
                                        videoId = id,
                                        title = cleanTitle(item.title).take(300),
                                        uploader = item.subtitles.firstOrNull()?.name?.take(200),
                                        uploaderUrl = item.subtitles.firstOrNull()?.url?.takeLast(24),
                                        thumbnailUrl = thumbnail(id),
                                    ),
                                )
                            }
                    }
                }
                lower.endsWith(".html") && HISTORY_FILE.containsMatchIn(lower) -> {
                    if (history.size >= MAX_HISTORY) return@readTakeout
                    // the newest entries come first; the beginning of a huge file is enough
                    val text = String(readPrefix(input, MAX_HTML_BYTES), Charsets.UTF_8)
                    for (match in HTML_ENTRY.findAll(text)) {
                        if (history.size >= MAX_HISTORY) break
                        val id = match.groupValues[1]
                        history.putIfAbsent(
                            id,
                            WatchHistoryItem(
                                videoId = id,
                                title = unescape(match.groupValues[2]).take(300),
                                uploader = match.groupValues[4].takeIf { it.isNotEmpty() }?.let(::unescape)?.take(200),
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

    /**
     * Hands each relevant file of a Takeout ZIP (or the single file that was picked) to [consume]
     * as a stream. A crafted ZIP cannot fill the memory or keep the phone busy: the number of
     * entries and everything unpacked, skipped files included, have limits.
     */
    private fun readTakeout(uri: Uri, consume: (path: String, input: java.io.InputStream) -> Unit) {
        val name = DocumentFile.fromSingleUri(context, uri)?.name.orEmpty()
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException(context.getString(R.string.yt_import_nothing_found))
        BufferedInputStream(input).use { stream ->
            stream.mark(4)
            val magic = ByteArray(2)
            val read = stream.read(magic)
            stream.reset()
            when {
                read == 2 && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte() -> ZipInputStream(stream).use { zip ->
                    var unpacked = 0L
                    var entries = 0
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (++entries > MAX_ENTRIES) throw IOException(context.getString(R.string.yt_import_too_large))
                        val counting = CountingInput(zip)
                        if (!entry.isDirectory && relevant(entry.name)) consume(entry.name, counting)
                        // what the parser left of the entry is counted too, not skipped unseen
                        counting.drain(MAX_UNPACKED - unpacked)
                        unpacked += counting.count
                        if (unpacked > MAX_UNPACKED) throw IOException(context.getString(R.string.yt_import_too_large))
                        entry = zip.nextEntry
                    }
                }
                read == 2 && magic[0] == 0x1F.toByte() && magic[1] == 0x8B.toByte() ->
                    throw IOException(context.getString(R.string.yt_import_zip_only))
                else -> consume(name, CountingInput(stream))
            }
        }
    }

    /** Counts what is read and never closes the stream below (the ZIP goes on after a file). */
    private class CountingInput(input: java.io.InputStream) : java.io.FilterInputStream(input) {
        var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }

        override fun close() {}

        /** Reads the rest of the entry, at most up to [limit] more bytes. */
        fun drain(limit: Long) {
            val buffer = ByteArray(64 * 1024)
            val stop = count + limit
            while (count <= stop) {
                if (read(buffer) < 0) return
            }
        }
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

    /** The first [limit] bytes of a stream. */
    private fun readPrefix(input: java.io.InputStream, limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (out.size() < limit) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - out.size()).toInt())
            if (n < 0) break
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
