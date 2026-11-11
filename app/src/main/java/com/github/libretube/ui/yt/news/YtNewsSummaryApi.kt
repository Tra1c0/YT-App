package com.github.libretube.ui.yt.news

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.messages.BetaCitationsConfigParam
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaPlainTextSource
import com.anthropic.models.beta.messages.BetaRequestDocumentBlock
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.MessageCreateParams
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.future.await

/**
 * YT: the request and the reading of the answer for the news summaries, through the official
 * SDK. Free of Android, so the request shape and the answer mapping are tested on the JVM.
 *
 * The transcripts go in as plain-text documents with citations on; every statement of the
 * answer then carries the character range it rests on, which [YtNewsTranscriptDoc] turns back
 * into the second. Citations and structured outputs do not mix, so the answer is plain lines.
 * Thinking is left at the model's default (it cannot be switched off there); the effort is low,
 * as a short summary needs little. A declined request is retried server-side on the model the
 * service picks for that case ("fallbacks: default").
 */
object YtNewsSummaryApi {
    /** The only place the model is named. */
    const val MODEL = "claude-opus-5-5"

    /** Room for the adaptive thinking plus a few short lines; billed only as far as used. */
    private const val MAX_TOKENS = 16_000L

    private val TIMEOUT: Duration = Duration.ofSeconds(120)

    private const val SYSTEM = "Du fasst Nachrichtenvideos anhand ihres Transkripts zusammen. " +
        "Schreibe auf Deutsch, kurz und neutral. Gib nur wieder, was im Transkript gesagt wird: " +
        "keine eigenen Meinungen, Wertungen, Vermutungen oder Hintergründe. " +
        "Jede Aussage stützt sich auf eine Stelle im Transkript."

    private const val ASK_SINGLE = "Nenne die 3 bis 5 wichtigsten Kernaussagen dieses Videos. " +
        "Schreibe jede Aussage als eigene Zeile, die mit \"- \" beginnt, höchstens 25 Wörter, " +
        "ohne Überschrift, Einleitung oder Schlusssatz. Ist das Transkript nicht deutsch, " +
        "schreibe die Aussagen trotzdem auf Deutsch."

    private const val ASK_STORY = "Die Dokumente sind Transkripte mehrerer Videos zur selben " +
        "Nachricht, eines pro Quelle; der Titel nennt den Kanal. Was sagt welche Quelle? " +
        "Nenne für jede Quelle 1 bis 2 Kernaussagen, als eigene Zeile in der Form " +
        "\"- Kanal: Aussage\", höchstens 25 Wörter, ohne Überschrift, Einleitung oder Schlusssatz. " +
        "Schreibe auf Deutsch, auch wenn ein Transkript es nicht ist."

    /** A client for [apiKey]; never built without a key. [baseUrl] and [retries] are for tests. */
    fun client(apiKey: String, baseUrl: String? = null, retries: Int? = null): AnthropicClient =
        AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .timeout(TIMEOUT)
            .apply { baseUrl?.let { baseUrl(it) } }
            .apply { retries?.let { maxRetries(it) } }
            .build()

    /** The request for one video ([sources] has one entry) or a story of several. */
    fun params(sources: List<YtNewsSource>): MessageCreateParams {
        val content = ArrayList<BetaContentBlockParam>(sources.size + 1)
        for (source in sources) {
            val document = BetaRequestDocumentBlock.builder()
                .source(BetaPlainTextSource.builder().data(source.doc.text).build())
                .title(source.title)
                .citations(BetaCitationsConfigParam.builder().enabled(true).build())
                .build()
            content += BetaContentBlockParam.ofDocument(document)
        }
        val ask = if (sources.size > 1) ASK_STORY else ASK_SINGLE
        content += BetaContentBlockParam.ofText(BetaTextBlockParam.builder().text(ask).build())
        return MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(MAX_TOKENS)
            .system(SYSTEM)
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
            .addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01)
            .fallbacksDefault()
            .addUserMessageOfContentBlockParams(content)
            .build()
    }

    /** What an answer amounts to: its blocks, or that it was declined. */
    sealed interface Answer {
        data class Blocks(val blocks: List<YtNewsAnswerBlock>) : Answer
        data object Refused : Answer
    }

    /**
     * The text blocks of [message] with their character citations. A refusal is checked before
     * the content is read: its content may be empty or a partial answer that must not be shown.
     */
    fun answer(message: BetaMessage): Answer {
        if (message.stopReason().orElse(null) == BetaStopReason.REFUSAL) return Answer.Refused
        val blocks = message.content().mapNotNull { block ->
            val text = block.text().orElse(null) ?: return@mapNotNull null
            val cites = text.citations().orElse(emptyList()).mapNotNull { citation ->
                val location = citation.charLocation().orElse(null) ?: return@mapNotNull null
                YtNewsCite(
                    documentIndex = location.documentIndex().toInt(),
                    startCharIndex = location.startCharIndex().toInt(),
                    citedText = location.citedText(),
                )
            }
            YtNewsAnswerBlock(text.text(), cites)
        }
        return Answer.Blocks(blocks)
    }

    /**
     * One request: the statements of [sources], or why there are none. Cancelling the coroutine
     * cancels the request's future, so a closed overlay stops waiting at once.
     */
    suspend fun summarize(client: AnthropicClient, sources: List<YtNewsSource>): Result {
        val message = client.async().beta().messages().create(params(sources)).await()
        return when (val answer = answer(message)) {
            Answer.Refused -> Result.Failed(YtNewsSummaryFailure.REFUSED)
            is Answer.Blocks -> {
                val max = if (sources.size > 1) YtNewsCitations.MAX_STORY else YtNewsCitations.MAX_SINGLE
                val statements = YtNewsCitations.statements(answer.blocks, sources, max)
                if (statements.isEmpty()) Result.Failed(YtNewsSummaryFailure.REFUSED) else Result.Done(statements)
            }
        }
    }

    sealed interface Result {
        data class Done(val statements: List<YtNewsStatement>) : Result
        data class Failed(val reason: YtNewsSummaryFailure) : Result
    }

    /**
     * Whether [apiKey] works: a free lookup of the model (no tokens). Null when it does, else
     * the reason. Blocking.
     */
    fun check(apiKey: String): YtNewsSummaryFailure? {
        val client = client(apiKey)
        return try {
            check(client)
        } finally {
            client.close()
        }
    }

    fun check(client: AnthropicClient): YtNewsSummaryFailure? = try {
        client.models().retrieve(MODEL)
        null
    } catch (e: Exception) {
        failure(e)
    }

    /** The reason behind an error of a request, most specific first. */
    fun failure(error: Throwable): YtNewsSummaryFailure {
        val e = unwrap(error)
        return when (e) {
            is UnauthorizedException -> YtNewsSummaryFailure.INVALID_KEY
            // a key without access to the model, or a workspace that may not use it
            is PermissionDeniedException -> YtNewsSummaryFailure.INVALID_KEY
            is RateLimitException -> YtNewsSummaryFailure.RATE_LIMITED
            is AnthropicServiceException -> YtNewsSummaryFailure.SERVICE
            is AnthropicIoException, is IOException -> YtNewsSummaryFailure.OFFLINE
            else -> YtNewsSummaryFailure.SERVICE
        }
    }

    private fun unwrap(error: Throwable): Throwable {
        var e = error
        while ((e is CompletionException || e is ExecutionException) && e.cause != null) e = e.cause!!
        return e
    }
}
