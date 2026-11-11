package com.github.libretube.ui.yt.news

/*
 * YT: the services that can write the news summaries, chosen in the settings ("Anbieter"). Each
 * works with the user's own key; none is called before the user picks it. One provider has
 * character citations of its own; the others answer with a line number and a quote, checked by
 * [YtNewsQuotes]. Free of Android, so every provider runs against a fake server in the tests.
 */

/** A summary service. [id] is stored in the settings and part of the cache key. */
enum class YtNewsProviderKind(val id: String) {
    ANTHROPIC("anthropic"),
    OPENAI("openai"),
    GEMINI("gemini"),
    MISTRAL("mistral"),

    /** Any server that speaks the common chat-completions format, at the user's own address. */
    COMPATIBLE("compatible"),
    ;

    companion object {
        fun of(id: String?): YtNewsProviderKind? = entries.firstOrNull { it.id == id }
    }
}

/**
 * What one request needs: the [key] (empty for a local server without one), the [model] in use and,
 * for the compatible provider, its [address].
 */
class YtNewsProviderSetup(
    val kind: YtNewsProviderKind,
    val key: String,
    val model: String,
    val address: String? = null,
) {
    // never print the key
    override fun toString(): String = "YtNewsProviderSetup(${kind.id}, $model)"
}

/** The outcome of one summary request. */
sealed interface YtNewsSummaryResult {
    data class Done(val statements: List<YtNewsStatement>) : YtNewsSummaryResult
    data class Failed(val reason: YtNewsSummaryFailure) : YtNewsSummaryResult
}

interface YtNewsSummaryProvider {
    val kind: YtNewsProviderKind

    /** The model used when the user names none; null when the user must name one. */
    val defaultModel: String?

    /** Whether the key works, by a free request (no tokens): null when it does, else why not. Blocking. */
    fun check(setup: YtNewsProviderSetup): YtNewsSummaryFailure?

    /** One summary of [sources] (one video, or a story of several). Cancelling stops the request. */
    suspend fun summarize(setup: YtNewsProviderSetup, sources: List<YtNewsSource>): YtNewsSummaryResult

    /** The reason behind an error thrown by [summarize] or [check]. */
    fun failure(error: Throwable): YtNewsSummaryFailure
}

object YtNewsProviders {
    fun of(kind: YtNewsProviderKind): YtNewsSummaryProvider = when (kind) {
        YtNewsProviderKind.ANTHROPIC -> YtNewsAnthropic
        YtNewsProviderKind.OPENAI -> YtNewsOpenAi.DEFAULT
        YtNewsProviderKind.GEMINI -> YtNewsGemini.DEFAULT
        YtNewsProviderKind.MISTRAL -> YtNewsChatCompletions.MISTRAL
        YtNewsProviderKind.COMPATIBLE -> YtNewsChatCompletions.COMPATIBLE
    }

    /** The model a request uses: the user's own when set, else the provider's default. */
    fun model(kind: YtNewsProviderKind, own: String?): String? =
        own?.trim()?.takeIf { it.isNotEmpty() } ?: of(kind).defaultModel

    /** The service a key obviously belongs to by its prefix, or null when it cannot be told. */
    fun ownerOf(key: String): YtNewsProviderKind? {
        val k = key.trim()
        return when {
            k.startsWith("sk-ant-") -> YtNewsProviderKind.ANTHROPIC
            k.startsWith("AIza") -> YtNewsProviderKind.GEMINI
            k.startsWith("sk-") -> YtNewsProviderKind.OPENAI
            else -> null
        }
    }

    /**
     * The other service a key entered for [kind] clearly belongs to, or null. The compatible
     * provider takes any key (several services there use "sk-" keys).
     */
    fun mismatch(kind: YtNewsProviderKind, key: String): YtNewsProviderKind? {
        if (kind == YtNewsProviderKind.COMPATIBLE) return null
        val owner = ownerOf(key) ?: return null
        return owner.takeIf { it != kind }
    }

    /**
     * Whether the feature is on: a provider is chosen and has what it needs. The compatible one
     * needs an allowed address and a model; its key is optional (a local server has none).
     */
    fun usable(kind: YtNewsProviderKind?, hasKey: Boolean, ownModel: String?, address: String?): Boolean = when (kind) {
        null -> false
        YtNewsProviderKind.COMPATIBLE ->
            address != null && YtNewsHttp.baseUrl(address) != null && !ownModel.isNullOrBlank()
        else -> hasKey
    }

    /**
     * The part of the cache key that names who wrote a summary, so another provider or model
     * never shows it as its own. Null for the first provider with its default model: summaries
     * cached before there was a choice stay valid.
     */
    fun cacheScope(kind: YtNewsProviderKind, model: String, address: String?): String? = when {
        kind == YtNewsProviderKind.ANTHROPIC && model == YtNewsSummaryApi.MODEL -> null
        kind == YtNewsProviderKind.COMPATIBLE -> "${kind.id}@${address?.let { YtNewsHttp.baseUrl(it)?.host }}/$model"
        else -> "${kind.id}/$model"
    }

    /** The outcome of an answer text of a quoting provider: verified statements, or why none. */
    fun quoted(answer: String?, sources: List<YtNewsSource>): YtNewsSummaryResult {
        if (answer.isNullOrBlank()) return YtNewsSummaryResult.Failed(YtNewsSummaryFailure.REFUSED)
        val claims = YtNewsQuotes.claims(answer)
        if (claims.isNullOrEmpty()) return YtNewsSummaryResult.Failed(YtNewsSummaryFailure.REFUSED)
        val max = if (sources.size > 1) YtNewsCitations.MAX_STORY else YtNewsCitations.MAX_SINGLE
        val statements = YtNewsQuotes.verify(claims, sources, max)
        return if (statements.size < YtNewsQuotes.MIN_STATEMENTS) {
            YtNewsSummaryResult.Failed(YtNewsSummaryFailure.UNVERIFIED)
        } else {
            YtNewsSummaryResult.Done(statements)
        }
    }
}

/** The provider with character citations, through its official SDK ([YtNewsSummaryApi]). */
object YtNewsAnthropic : YtNewsSummaryProvider {
    override val kind = YtNewsProviderKind.ANTHROPIC
    override val defaultModel: String = YtNewsSummaryApi.MODEL

    override fun check(setup: YtNewsProviderSetup): YtNewsSummaryFailure? = YtNewsSummaryApi.check(setup.key, setup.model)

    override suspend fun summarize(setup: YtNewsProviderSetup, sources: List<YtNewsSource>): YtNewsSummaryResult {
        val client = YtNewsSummaryApi.client(setup.key)
        val result = try {
            YtNewsSummaryApi.summarize(client, sources, setup.model)
        } finally {
            client.close()
        }
        return when (result) {
            is YtNewsSummaryApi.Result.Done -> YtNewsSummaryResult.Done(result.statements)
            is YtNewsSummaryApi.Result.Failed -> YtNewsSummaryResult.Failed(result.reason)
        }
    }

    override fun failure(error: Throwable): YtNewsSummaryFailure = YtNewsSummaryApi.failure(error)
}
