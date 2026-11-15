package com.github.libretube.ui.yt.archive.script

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * YT: what the script part spent today and on which video, so it stays cheap: at most
 * [MAX_DIALOGUES_PER_DAY] transcript scripts and [MAX_AUDIO_PER_DAY] audio analyses a day, at most
 * [MAX_TRIES] tries per video and kind, sound over [MAX_AUDIO_SECONDS] never sent. [last] is the
 * diagnosis text of the latest run. Pure Kotlin; the store is passed in.
 */
data class YtScriptLedger(
    val day: String = "",
    val dialogues: Int = 0,
    val audios: Int = 0,
    /** "t:<id>" / "a:<id>" → tries, oldest first. */
    val tries: Map<String, Int> = emptyMap(),
    val last: String? = null,
) {
    /** The ledger for [today]: the day's counts start again, the tries per video stay. */
    fun on(today: String): YtScriptLedger = if (day == today) this else copy(day = today, dialogues = 0, audios = 0)

    fun tries(kind: Kind, videoId: String): Int = tries[kind.key(videoId)] ?: 0

    fun mayTry(kind: Kind, videoId: String): Boolean = tries(kind, videoId) < MAX_TRIES

    fun underCap(kind: Kind): Boolean = when (kind) {
        Kind.DIALOGUE -> dialogues < MAX_DIALOGUES_PER_DAY
        Kind.AUDIO -> audios < MAX_AUDIO_PER_DAY
    }

    /** One more try of [kind] for [videoId], counted before the request is made. */
    fun spend(kind: Kind, videoId: String): YtScriptLedger {
        val key = kind.key(videoId)
        val next = LinkedHashMap(tries)
        val count = (next.remove(key) ?: 0) + 1
        next[key] = count
        while (next.size > MAX_TRACKED) next.remove(next.keys.first())
        return when (kind) {
            Kind.DIALOGUE -> copy(dialogues = dialogues + 1, tries = next)
            Kind.AUDIO -> copy(audios = audios + 1, tries = next)
        }
    }

    fun toJson(): String = YtArchiveScript.JSON.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put("day", day)
            put("dialogues", dialogues)
            put("audios", audios)
            putJsonObject("tries") { tries.forEach { (k, v) -> put(k, v) } }
            last?.let { put("last", it) }
        },
    )

    enum class Kind(private val prefix: String) {
        DIALOGUE("t"),
        AUDIO("a"),
        ;

        fun key(videoId: String) = "$prefix:$videoId"
    }

    companion object {
        const val MAX_DIALOGUES_PER_DAY = 20
        const val MAX_AUDIO_PER_DAY = 5
        const val MAX_TRIES = 2
        const val MAX_AUDIO_SECONDS = 3L * 3600

        /** Videos remembered for the tries; the oldest are forgotten first. */
        const val MAX_TRACKED = 400

        fun parse(text: String?): YtScriptLedger {
            val root = text?.let { runCatching { YtArchiveScript.JSON.parseToJsonElement(it) }.getOrNull() } as? JsonObject
                ?: return YtScriptLedger()
            fun int(name: String) = (root[name] as? JsonPrimitive)?.intOrNull ?: 0
            val tries = LinkedHashMap<String, Int>()
            (root["tries"] as? JsonObject)?.forEach { (k, v) -> (v as? JsonPrimitive)?.intOrNull?.let { tries[k] = it } }
            return YtScriptLedger(
                day = (root["day"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                dialogues = int("dialogues"),
                audios = int("audios"),
                tries = tries,
                last = (root["last"] as? JsonPrimitive)?.contentOrNull,
            )
        }
    }
}

/** Where the ledger lives (preferences in the app, memory in tests). */
interface YtScriptStore {
    fun load(): YtScriptLedger
    fun save(ledger: YtScriptLedger)
}
