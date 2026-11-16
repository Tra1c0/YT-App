package com.github.libretube.ui.yt.ssi

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** SSI-3: a hit as a saved research keeps it (enough to show, play, compare and report it again). */
@Serializable
data class YtSsiSavedHit(
    val videoId: String,
    val segmentId: Long? = null,
    val title: String,
    val channelName: String? = null,
    val uploaded: Long? = null,
    val startMs: Long = 0,
    val endMs: Long = 0,
    val hitMs: Long = 0,
    val playStartMs: Long? = null,
    val text: String = "",
    val timing: YtSsiTiming = YtSsiTiming.LINE,
    val vanished: Boolean = false,
) {
    /** The hit back, as a search would give it (place [rank]; no ranking signals). */
    fun toHit(rank: Int): YtSsiHit = YtSsiHit(
        videoId = videoId,
        title = title,
        channelName = channelName,
        uploaded = uploaded,
        language = null,
        segmentId = segmentId,
        startMs = startMs,
        endMs = endMs,
        hitMs = hitMs,
        contextStartMs = startMs,
        contextEndMs = endMs,
        text = text,
        highlights = emptyList(),
        matches = emptySet(),
        methods = emptySet(),
        timing = timing,
        availability = if (vanished) YtSsiAvailability.VANISHED_ARCHIVED else YtSsiAvailability.PUBLIC,
        rank = rank,
        fused = 0.0,
        ranks = emptyMap(),
        span = playStartMs?.let { YtSsiSpan(startMs, endMs, it, timing, verified = false) },
    )

    companion object {
        fun of(hit: YtSsiHit) = YtSsiSavedHit(
            videoId = hit.videoId,
            segmentId = hit.segmentId,
            title = hit.title,
            channelName = hit.channelName,
            uploaded = hit.uploaded,
            startMs = hit.startMs,
            endMs = hit.endMs,
            hitMs = hit.hitMs,
            playStartMs = hit.span?.playStartMs,
            text = hit.text,
            timing = hit.timing,
            vanished = hit.availability == YtSsiAvailability.VANISHED_ARCHIVED,
        )
    }
}

/**
 * SSI-3: a saved research ("Recherche speichern"): the query and the claim it checked, the hits
 * that were chosen (all of the analysed ones when none was chosen), their verified relations as
 * they were, the keys chosen for the comparison and the user's notes.
 */
@Serializable
data class YtSsiResearch(
    val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val query: String,
    val claim: String = "",
    val hits: List<YtSsiSavedHit> = emptyList(),
    val stances: List<YtSsiStance> = emptyList(),
    val chosen: List<String> = emptyList(),
    val notes: String = "",
) {
    val stanceMap: Map<Long, YtSsiStance> get() = stances.associateBy { it.segmentId }
}

/**
 * YT (SSI-3): saved researches as JSON files in one folder (`filesDir/yt_ssi/research/`), one
 * file per research, written to a temporary file and renamed, so a crash never leaves half a
 * file. Unreadable files are skipped, not deleted. Pure JVM (a [File] folder), tested.
 */
class YtSsiResearchStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun file(id: String): File = File(dir, safe(id) + ".json")

    fun save(research: YtSsiResearch): YtSsiResearch {
        dir.mkdirs()
        val tmp = File(dir, safe(research.id) + ".tmp")
        tmp.writeText(json.encodeToString(YtSsiResearch.serializer(), research))
        val target = file(research.id)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw java.io.IOException("could not store research ${research.id}")
            }
        }
        return research
    }

    fun load(id: String): YtSsiResearch? = file(id).takeIf { it.isFile }?.let { read(it) }

    /** Every readable research, most recently changed first. */
    fun list(): List<YtSsiResearch> = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
        .mapNotNull(::read)
        .sortedByDescending { it.updatedAt }

    fun delete(id: String): Boolean = file(id).delete()

    fun count(): Int = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.size ?: 0

    private fun read(f: File): YtSsiResearch? = runCatching { json.decodeFromString(YtSsiResearch.serializer(), f.readText()) }.getOrNull()

    companion object {
        /** A research id from its creation time (unique enough for one person's phone). */
        fun newId(now: Long, salt: Int = 0): String = "r" + now.toString(36) + if (salt != 0) "-" + salt.toString(36) else ""

        private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")

        /**
         * The research to store for the current state: [hits] cut to the [chosen] ones when there
         * are any, else to those with a stance, else the first [MAX_HITS]; their stances only.
         */
        fun build(
            id: String,
            createdAt: Long,
            now: Long,
            query: String,
            claim: String,
            hits: List<YtSsiHit>,
            stances: Map<Long, YtSsiStance>,
            chosen: List<String>,
            key: (YtSsiHit) -> String,
            notes: String,
        ): YtSsiResearch {
            val picked = hits.filter { key(it) in chosen }.ifEmpty { hits.filter { it.segmentId != null && it.segmentId in stances } }
                .ifEmpty { hits }.take(MAX_HITS)
            val ids = picked.mapNotNull { it.segmentId }.toSet()
            return YtSsiResearch(
                id = id,
                createdAt = createdAt,
                updatedAt = now,
                query = query,
                claim = claim,
                hits = picked.map(YtSsiSavedHit::of),
                stances = stances.values.filter { it.segmentId in ids },
                chosen = chosen.filter { c -> picked.any { key(it) == c } },
                notes = notes,
            )
        }

        const val MAX_HITS = 40
    }
}
