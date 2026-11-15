package com.github.libretube.ui.yt.archive.risk

import com.github.libretube.ui.yt.archive.YtArchiveCandidate
import com.github.libretube.ui.yt.archive.YtRiskScore
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * YT: the risk estimator without Android: the learning model plus the AI cache behind one lock,
 * the scoring of a list of candidates (local first, then the AI in batches for the videos it has
 * not seen, highest local risk first, within the daily limit) and the stored JSON of both. The app
 * side ([YtArchiveRisk]) adds the file, the settings and the strings.
 */

/** The stored file: the model and the AI state. */
@Serializable
class YtRiskFile(
    @SerialName("model") val model: YtRiskState = YtRiskState(),
    @SerialName("ai") val ai: YtRiskAiState = YtRiskAiState(),
)

class YtRiskEngine(
    file: YtRiskFile = YtRiskFile(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val lock = Any()
    private val model = YtRiskModel(file.model)
    private val ai = file.ai

    private fun today(now: Long): String = Instant.ofEpochMilli(now).atZone(zone()).toLocalDate().toString()

    /** Learns one label; true when the model changed (then it should be saved). */
    fun observe(candidate: YtArchiveCandidate, vanished: Boolean): Boolean =
        synchronized(lock) { model.observe(candidate, vanished, clock()) }

    /**
     * Scores [candidates]. With [ask] (the AI check is on and set up) the videos without an AI
     * answer are sent first, in batches of [YtRiskAiRules.BATCH]. A failed request ends the AI
     * part for this call; the local score stands. Only cancellation is thrown.
     */
    suspend fun score(candidates: List<YtArchiveCandidate>, words: YtRiskWords, ask: YtRiskAsk?, german: Boolean): List<YtRiskScore> {
        if (ask != null) askAi(candidates, ask, german)
        return synchronized(lock) {
            model.decay(clock())
            candidates.map { c -> model.score(c, ai.cache[c.videoId]?.takeIf { it.risk != null }, words) }
        }
    }

    private suspend fun askAi(candidates: List<YtArchiveCandidate>, ask: YtRiskAsk, german: Boolean) {
        val pending = synchronized(lock) {
            candidates.distinctBy { it.videoId }.filter { it.videoId !in ai.cache }
                .map { it to model.factors(it).risk }
                .sortedByDescending { it.second }
                .map { it.first }
        }
        val system = YtRiskAiRules.system(german)
        for (batch in pending.chunked(YtRiskAiRules.BATCH)) {
            val now = clock()
            if (!synchronized(lock) { ai.take(today(now)) }) return
            val answer = try {
                ask.complete(system, YtRiskAiRules.prompt(batch))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // offline, no credit, rate limit…: local scores only, try again another time
                return
            }
            val items = YtRiskAiRules.parse(answer).orEmpty()
            synchronized(lock) {
                // asked once means never asked again, with or without a usable answer
                batch.forEachIndexed { i, c ->
                    val item = items[(i + 1).toString()]
                    ai.cache[c.videoId] = YtRiskAiAnswer(item?.risk, item?.reason, now)
                }
                ai.prune(now)
            }
        }
    }

    /** The AI's answer for [videoId], if it was asked. */
    fun aiAnswer(videoId: String): YtRiskAiAnswer? = synchronized(lock) { ai.cache[videoId] }

    /** For the diagnosis line: learned channels, labels, and today's AI requests. */
    fun describe(): String = synchronized(lock) {
        val (channels, gone, kept) = model.counts()
        "channels learned $channels, labels $gone vanished / $kept stayed, " +
            "AI today ${ai.requestsOn(today(clock()))}/${YtRiskAiRules.MAX_REQUESTS_PER_DAY}"
    }

    fun encode(): String = synchronized(lock) {
        YtRiskModel.JSON.encodeToString(YtRiskFile.serializer(), YtRiskFile(model.state, ai))
    }

    companion object {
        /** The stored file read back; a damaged one starts over. */
        fun decode(text: String?, clock: () -> Long = System::currentTimeMillis): YtRiskEngine {
            val file = text?.let { runCatching { YtRiskModel.JSON.decodeFromString(YtRiskFile.serializer(), it) }.getOrNull() }
            return YtRiskEngine(file ?: YtRiskFile(), clock)
        }
    }
}
