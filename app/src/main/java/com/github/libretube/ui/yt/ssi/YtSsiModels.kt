package com.github.libretube.ui.yt.ssi

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Where the semantic part stands. */
sealed interface YtSsiModelState {
    data object Missing : YtSsiModelState
    data class Downloading(val done: Long, val total: Long?) : YtSsiModelState
    data object Ready : YtSsiModelState

    /** The runtime cannot run on this phone (no 64-bit ARM, or the native library did not load). */
    data object Unsupported : YtSsiModelState
    data class Failed(val reason: String) : YtSsiModelState
}

/**
 * YT: the embedding model's files and the one loaded embedder. The model is downloaded only when
 * the user asks (Settings → SSI-Suche), from a fixed HTTPS address into
 * `filesDir/yt_ssi/model/<id>/`, each file into a ".part" first and moved in place only after its
 * SHA-256 matched: the pinned one of [YtSsiModelSpec] or, while none is pinned, the one the
 * server declares for the file (recorded in `verified.json`). Without the model SSI searches by
 * words only and says so.
 */
object YtSsiModels {
    private const val TAG = "YtSsiModels"
    val spec: YtSsiModelSpec = YtSsiModelSpec.E5_SMALL

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow<YtSsiModelState>(YtSsiModelState.Missing)
    val state: StateFlow<YtSsiModelState> = _state.asStateFlow()
    private var download: Job? = null

    private val mutex = Mutex()
    private var embedder: YtSsiEmbedder? = null
    private var loadFailed: String? = null

    @Serializable
    data class Verified(val model: String, val tokenizer: String, val pinned: Boolean, val at: Long)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun dir(context: Context): File = File(context.filesDir, "yt_ssi/model/${spec.id}")
    private fun modelFile(context: Context) = File(dir(context), "model.onnx")
    private fun tokenizerFile(context: Context) = File(dir(context), "tokenizer.json")
    private fun vocabCache(context: Context) = File(dir(context), "vocab.bin")
    private fun verifiedFile(context: Context) = File(dir(context), "verified.json")

    fun installed(context: Context): Boolean = verifiedFile(context).isFile && modelFile(context).isFile && tokenizerFile(context).isFile

    fun verified(context: Context): Verified? =
        runCatching { json.decodeFromString(Verified.serializer(), verifiedFile(context).readText()) }.getOrNull()

    fun bytes(context: Context): Long = dir(context).listFiles()?.sumOf { it.length() } ?: 0L

    /**
     * Whether ONNX Runtime can run here: its library is packed for 64-bit ARM only
     * (app/build.gradle.kts) and unpacked at install (legacy packaging), so it is a file in the
     * app's native library folder exactly where it can be loaded.
     */
    fun runtimeAvailable(context: Context): Boolean =
        runCatching { File(context.applicationInfo.nativeLibraryDir, "libonnxruntime.so").isFile }.getOrDefault(false)

    /** Brings [state] in line with the disk (call before showing it). */
    fun refresh(context: Context) {
        if (_state.value is YtSsiModelState.Downloading) return
        _state.value = when {
            loadFailed == UNSUPPORTED || !runtimeAvailable(context) -> YtSsiModelState.Unsupported
            installed(context) -> YtSsiModelState.Ready
            _state.value is YtSsiModelState.Failed -> _state.value
            else -> YtSsiModelState.Missing
        }
    }

    /** Starts the download (once); the state follows it. */
    fun download(context: Context) {
        val app = context.applicationContext
        if (download?.isActive == true) return
        if (!runtimeAvailable(app)) {
            _state.value = YtSsiModelState.Unsupported
            return
        }
        download = scope.launch {
            _state.value = YtSsiModelState.Downloading(0, null)
            try {
                val dir = dir(app).apply { mkdirs() }
                verifiedFile(app).delete()
                val total = arrayOf(0L, 0L)
                val tokenizerSha = fetch(spec.tokenizerUrl, spec.tokenizerSha256, tokenizerFile(app)) { done, size ->
                    total[0] = done
                    _state.value = YtSsiModelState.Downloading(done, size?.let { it + spec.approxMb * 1_000_000L })
                }
                val base = total[0]
                val modelSha = fetch(spec.modelUrl, spec.modelSha256, modelFile(app)) { done, size ->
                    _state.value = YtSsiModelState.Downloading(base + done, size?.let { base + it })
                }
                vocabCache(app).delete()
                val pinned = spec.modelSha256.isNotEmpty() && spec.tokenizerSha256.isNotEmpty()
                File(dir, "verified.json").writeText(
                    json.encodeToString(Verified.serializer(), Verified(modelSha, tokenizerSha, pinned, System.currentTimeMillis())),
                )
                loadFailed = null
                _state.value = YtSsiModelState.Ready
            } catch (e: CancellationException) {
                _state.value = YtSsiModelState.Missing
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "model download failed", e)
                _state.value = YtSsiModelState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancelDownload() {
        download?.cancel()
    }

    /** Deletes the model (and the loaded embedder); the vectors in the index stay until rebuilt. */
    suspend fun delete(context: Context) = mutex.withLock {
        embedder?.close()
        embedder = null
        dir(context).deleteRecursively()
        loadFailed = null
        _state.value = YtSsiModelState.Missing
    }

    /**
     * One file into [target]; returns its SHA-256 (hex). It is checked against [pinned]; without
     * one against the checksum the hub's file listing names for it ([YtSsiHfChecksum]: SHA-256 of
     * a large file, git blob SHA-1 of a small one), else against an X-Linked-Etag of the hub
     * itself. The storage server the download redirects to is never asked: its ETag can be a
     * storage hash, not the file's SHA-256. A file that can be checked against none is refused.
     */
    private suspend fun fetch(url: String, pinned: String, target: File, progress: (Long, Long?) -> Unit): String {
        val part = File(target.parentFile, target.name + ".part")
        part.delete()
        val listed = if (pinned.isEmpty()) listedChecksum(url) else null
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        response.use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val expected: YtSsiHfChecksum.Expected = when {
                pinned.isNotEmpty() -> YtSsiHfChecksum.Expected.Sha256(pinned.lowercase())
                listed != null -> listed
                else -> hubSha(r)?.let { YtSsiHfChecksum.Expected.Sha256(it) } ?: throw IOException("no checksum")
            }
            val body = r.body ?: throw IOException("empty")
            val size = body.contentLength().takeIf { it > 0 }
            val digest = MessageDigest.getInstance("SHA-256")
            val blob = (expected as? YtSsiHfChecksum.Expected.GitBlob)?.let {
                MessageDigest.getInstance("SHA-1").apply { update(YtSsiHfChecksum.gitBlobHeader(it.size)) }
            }
            var done = 0L
            body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        blob?.update(buffer, 0, n)
                        done += n
                        if (done - lastReport > 512 * 1024) {
                            lastReport = done
                            progress(done, size)
                        }
                    }
                }
            }
            val sha = YtSsiHfChecksum.hex(digest.digest())
            val ok = when (expected) {
                is YtSsiHfChecksum.Expected.Sha256 -> sha == expected.hex
                is YtSsiHfChecksum.Expected.GitBlob -> done == expected.size && YtSsiHfChecksum.hex(blob!!.digest()) == expected.sha1
            }
            if (!ok) {
                part.delete()
                lastCheck = "mismatch: ${target.name} got ${sha.take(12)} want ${expected.short()}"
                throw IOException("checksum mismatch (${target.name})")
            }
            lastCheck = "ok: ${target.name} ${expected.short()}"
            if (!part.renameTo(target)) {
                target.delete()
                if (!part.renameTo(target)) throw IOException("rename failed")
            }
            return sha
        }
    }

    /** The last checksum check, for the Diagnose line. */
    @Volatile
    var lastCheck: String? = null
        private set

    private fun YtSsiHfChecksum.Expected.short(): String = when (this) {
        is YtSsiHfChecksum.Expected.Sha256 -> "sha256 ${hex.take(12)}"
        is YtSsiHfChecksum.Expected.GitBlob -> "git ${sha1.take(12)}"
    }

    /** The checksum the hub's file listing names for [url]'s file; null when it cannot be read. */
    private fun listedChecksum(url: String): YtSsiHfChecksum.Expected? {
        val ref = YtSsiHfChecksum.ref(url) ?: return null
        return runCatching {
            client.newCall(Request.Builder().url(ref.treeUrl).build()).execute().use { r ->
                if (!r.isSuccessful) null else r.body?.string()?.let { YtSsiHfChecksum.expected(it, ref) }
            }
        }.onFailure { Log.w(TAG, "file listing failed", it) }.getOrNull()
    }

    /** An X-Linked-Etag SHA-256 declared by the hub itself (huggingface.co), never by the storage server. */
    private fun hubSha(response: Response): String? {
        var r: Response? = response
        while (r != null) {
            if (r.request.url.host == "huggingface.co") {
                val value = r.header("x-linked-etag")?.removePrefix("W/")?.trim('"')?.lowercase()
                if (value != null && SHA.matches(value)) return value
            }
            r = r.priorResponse
        }
        return null
    }

    private val SHA = Regex("^[0-9a-f]{64}$")
    private const val UNSUPPORTED = "unsupported"

    /**
     * The embedder, loaded on first use; null without the model or when the runtime cannot run
     * here (then [state] says Unsupported). Use it under [use], one call at a time.
     */
    suspend fun <T> use(context: Context, block: (YtSsiEmbedder) -> T): T? = mutex.withLock {
        val loaded = embedder ?: load(context) ?: return@withLock null
        block(loaded)
    }

    /** For Diagnose: the normaliser of the loaded tokenizer (charsmap or NFKC), null before the first load. */
    @Volatile
    var tokenizerNormalizer: String? = null
        private set

    private fun load(context: Context): YtSsiEmbedder? {
        if (loadFailed == UNSUPPORTED || !installed(context)) return null
        if (!runtimeAvailable(context)) {
            loadFailed = UNSUPPORTED
            _state.value = YtSsiModelState.Unsupported
            return null
        }
        return try {
            val tokenizer = YtSsiTokenizer.readCache(vocabCache(context)) ?: YtSsiTokenizer.fromJson(tokenizerFile(context).readText()).also {
                runCatching { YtSsiTokenizer.writeCache(it, vocabCache(context)) }
            }
            tokenizerNormalizer = tokenizer.normalizer
            YtSsiOnnxEmbedder(modelFile(context), tokenizer, spec).also { embedder = it }
        } catch (e: UnsatisfiedLinkError) {
            // the runtime is packed for 64-bit ARM only (app/build.gradle.kts)
            Log.w(TAG, "runtime not available", e)
            loadFailed = UNSUPPORTED
            _state.value = YtSsiModelState.Unsupported
            null
        } catch (e: Throwable) {
            Log.w(TAG, "model load failed", e)
            if (e is NoClassDefFoundError || e is ExceptionInInitializerError || e.cause is UnsatisfiedLinkError) {
                loadFailed = UNSUPPORTED
                _state.value = YtSsiModelState.Unsupported
            } else {
                _state.value = YtSsiModelState.Failed(e.message ?: e.javaClass.simpleName)
            }
            null
        }
    }
}
