package com.github.libretube.ui.yt.archive

import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * YT: the archive on disk: one folder per video under [root] (`filesDir/yt_archive/<videoId>/`,
 * layout [YtArchiveFiles]) with its `meta.json` ([YtArchiveMeta]). The index of all entries is
 * read once, lazily, on first use and then kept in memory; every change goes through here, so the
 * index stays true. Writes of meta.json are atomic (tmp + rename). Thread-safe (one lock), plain
 * java.io, so it runs in JVM tests.
 */
class YtArchiveStore(val root: File) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = false
    }

    private val lock = Any()
    private var index: LinkedHashMap<String, YtArchiveMeta>? = null

    private fun loaded(): LinkedHashMap<String, YtArchiveMeta> {
        index?.let { return it }
        val map = LinkedHashMap<String, YtArchiveMeta>()
        root.listFiles()?.filter { it.isDirectory && YtArchiveRules.validId(it.name) }?.forEach { dir ->
            val meta = read(File(dir, META))
            if (meta != null && meta.videoId == dir.name) map[meta.videoId] = meta
            // a folder without a readable meta.json is a save that did not finish: it goes
            else dir.deleteRecursively()
        }
        index = map
        return map
    }

    private fun read(file: File): YtArchiveMeta? =
        runCatching { json.decodeFromString(YtArchiveMeta.serializer(), file.readText()) }.getOrNull()

    fun all(): List<YtArchiveMeta> = synchronized(lock) { loaded().values.toList() }

    operator fun get(videoId: String): YtArchiveMeta? = synchronized(lock) { loaded()[videoId] }

    fun contains(videoId: String): Boolean = get(videoId) != null

    fun usedBytes(): Long = synchronized(lock) { loaded().values.sumOf { it.totalBytes } }

    /** The folder of [videoId] (created on demand by the writers); null for an id that is not a video id. */
    fun files(videoId: String): YtArchiveFiles? =
        if (YtArchiveRules.validId(videoId)) YtArchiveFiles(File(root, videoId)) else null

    /** Writes [meta] (atomically) and updates the index. */
    fun put(meta: YtArchiveMeta) {
        val files = files(meta.videoId) ?: return
        synchronized(lock) {
            files.dir.mkdirs()
            val tmp = File(files.dir, "$META.tmp")
            tmp.writeText(json.encodeToString(YtArchiveMeta.serializer(), meta))
            if (!tmp.renameTo(files.meta)) {
                files.meta.delete()
                tmp.renameTo(files.meta)
            }
            loaded()[meta.videoId] = meta
        }
    }

    /** Changes the entry of [videoId] if it exists; returns the new meta. */
    fun update(videoId: String, change: (YtArchiveMeta) -> YtArchiveMeta): YtArchiveMeta? = synchronized(lock) {
        val old = loaded()[videoId] ?: return null
        val new = change(old)
        if (new != old) put(new)
        new
    }

    fun delete(videoId: String) {
        val files = files(videoId) ?: return
        synchronized(lock) {
            files.dir.deleteRecursively()
            loaded().remove(videoId)
        }
    }

    /**
     * Gives up the parts above [to] (null drops it): VIDEO→AUDIO deletes
     * the video, AUDIO→TEXT the audio and the stills. Returns the new meta, null when dropped.
     */
    fun downgrade(videoId: String, to: YtArchiveTier?, now: Long): YtArchiveMeta? {
        val files = files(videoId) ?: return null
        if (to == null) {
            delete(videoId)
            return null
        }
        return update(videoId) { meta ->
            if (meta.tier.level <= to.level) return@update meta
            var next = meta
            if (to.level < YtArchiveTier.VIDEO.level) {
                files.video.delete()
                next = next.copy(videoBytes = 0, videoMime = null, videoHeight = null)
            }
            if (to.level < YtArchiveTier.AUDIO.level) {
                files.audio.delete()
                files.storyboard.deleteRecursively()
                next = next.copy(audioBytes = 0, audioMime = null)
            }
            next.copy(tierLevel = to.level, tierAt = now)
        }
    }

    /** Forgets the index, so the next use reads the disk again (tests, a restore). */
    fun reload() = synchronized(lock) { index = null }

    /**
     * Writes the folder of [videoId] as a zip to [out]: meta.json, thumbnail, transcript (also as
     * plain text, [transcriptText]), script, stills, and the streams named with an extension from
     * their MIME type, so other players open them. False when there is no such entry.
     */
    fun exportZip(videoId: String, out: java.io.OutputStream, transcriptText: String?): Boolean {
        val meta = get(videoId) ?: return false
        val files = files(videoId) ?: return false
        ZipOutputStream(out).use { zip ->
            fun add(name: String, file: File) {
                if (!file.isFile) return
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            add(META, files.meta)
            add(files.thumbnail.name, files.thumbnail)
            add(files.transcript.name, files.transcript)
            add(files.script.name, files.script)
            if (!transcriptText.isNullOrBlank()) {
                zip.putNextEntry(ZipEntry("transcript.txt"))
                zip.write(transcriptText.toByteArray())
                zip.closeEntry()
            }
            add("audio." + extension(meta.audioMime, "m4a"), files.audio)
            add("video." + extension(meta.videoMime, "mp4"), files.video)
            files.storyboard.listFiles()?.sortedBy { it.name }?.forEach { add("storyboard/${it.name}", it) }
        }
        return true
    }

    /** The bytes of all files of the part, as they are on disk now. */
    fun measure(videoId: String): Triple<Long, Long, Long> {
        val files = files(videoId) ?: return Triple(0, 0, 0)
        fun size(file: File): Long = if (file.isDirectory) file.listFiles()?.sumOf { size(it) } ?: 0 else file.length()
        val audio = size(files.audio) + size(files.storyboard)
        val video = size(files.video)
        val text = size(files.dir) - audio - video
        return Triple(text.coerceAtLeast(0), audio, video)
    }

    companion object {
        const val DIR = "yt_archive"
        private const val META = "meta.json"

        fun extension(mime: String?, fallback: String): String = when {
            mime == null -> fallback
            mime.contains("webm") -> "webm"
            mime.contains("mp4") && mime.startsWith("audio") -> "m4a"
            mime.contains("mp4") -> "mp4"
            mime.contains("3gpp") -> "3gp"
            else -> fallback
        }
    }
}
