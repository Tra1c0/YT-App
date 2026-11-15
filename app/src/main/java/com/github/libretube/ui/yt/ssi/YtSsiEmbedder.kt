package com.github.libretube.ui.yt.ssi

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.LongBuffer

/** Turns text into sentence vectors (L2-normalised). */
interface YtSsiEmbedder : Closeable {
    /** Model and analysis version, stored with every vector ("e5-small-int8/1"). */
    val model: String
    val dim: Int

    /** One vector per text; [query] marks the search side (e5 embeds queries and passages differently). */
    fun embed(texts: List<String>, query: Boolean): List<FloatArray>
}

/**
 * The model SSI downloads on request: a multilingual sentence-embedding model as int8 ONNX and
 * its tokenizer. Never in the APK. [sha256] pinned checksums; empty ones are taken from the
 * server's declared SHA-256 of the file (Hugging Face sends it with every LFS file) and recorded
 * as such, see [YtSsiModels].
 */
data class YtSsiModelSpec(
    val id: String,
    val name: String,
    val licence: String,
    val modelUrl: String,
    val modelSha256: String,
    val tokenizerUrl: String,
    val tokenizerSha256: String,
    /** About how big the two files are, for the button (MB). */
    val approxMb: Int,
    val dim: Int,
    val queryPrefix: String,
    val passagePrefix: String,
    val maxTokens: Int,
) {
    val version: String get() = "$id/${YtSsiVersions.VECTORS}"

    companion object {
        /**
         * intfloat/multilingual-e5-small (MIT licence; 12 layers, 384 dimensions, ~100 languages,
         * trained for retrieval with "query: " / "passage: " prefixes), in the ONNX export with
         * dynamic int8 quantisation of the Xenova/multilingual-e5-small repository (same
         * licence). The file sizes and checksums could not be read from here (the host was not
         * reachable while building): about 118 MB model + 17 MB tokenizer, verified against the
         * SHA-256 the server declares until checksums are pinned below.
         */
        val E5_SMALL = YtSsiModelSpec(
            id = "multilingual-e5-small-int8",
            name = "multilingual-e5-small (int8)",
            licence = "MIT",
            modelUrl = "https://huggingface.co/Xenova/multilingual-e5-small/resolve/main/onnx/model_quantized.onnx",
            modelSha256 = "",
            tokenizerUrl = "https://huggingface.co/Xenova/multilingual-e5-small/resolve/main/tokenizer.json",
            tokenizerSha256 = "",
            approxMb = 135,
            dim = 384,
            queryPrefix = "query: ",
            passagePrefix = "passage: ",
            maxTokens = 256,
        )
    }
}

/**
 * The embedder on ONNX Runtime (CPU, two threads so playback stays smooth). Inputs are found
 * by name (input_ids, attention_mask and, for BERT-like exports, token_type_ids); the output is
 * either the last hidden states (then mean-pooled over the mask) or a ready sentence vector.
 */
class YtSsiOnnxEmbedder(
    modelFile: File,
    private val tokenizer: YtSsiTokenizer,
    private val spec: YtSsiModelSpec,
) : YtSsiEmbedder {
    override val model: String get() = spec.version
    override val dim: Int get() = spec.dim

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        // no usage reports to the library's makers
        runCatching { env.setTelemetry(false) }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(modelFile.path, options)
    }

    private val inputs = session.inputNames

    override fun embed(texts: List<String>, query: Boolean): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val prefix = if (query) spec.queryPrefix else spec.passagePrefix
        val encoded = texts.map { tokenizer.encode(prefix + it, spec.maxTokens) }
        val length = encoded.maxOf { it.size }
        val batch = encoded.size
        val ids = LongArray(batch * length) { tokenizer.padId.toLong() }
        val mask = LongArray(batch * length)
        for ((b, row) in encoded.withIndex()) {
            for ((t, id) in row.withIndex()) {
                ids[b * length + t] = id.toLong()
                mask[b * length + t] = 1L
            }
        }
        val shape = longArrayOf(batch.toLong(), length.toLong())
        val tensors = LinkedHashMap<String, OnnxTensor>()
        try {
            tensors[INPUT_IDS] = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape)
            if (MASK in inputs) tensors[MASK] = OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape)
            if (TYPES in inputs) tensors[TYPES] = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(batch * length)), shape)
            session.run(tensors).use { result ->
                val value = result.get(0).value
                @Suppress("UNCHECKED_CAST")
                return when (value) {
                    // tokens × dim per text: mean pooling
                    is Array<*> -> if (value.firstOrNull() is Array<*>) {
                        (value as Array<Array<FloatArray>>).mapIndexed { b, hidden ->
                            YtSsiVectors.meanPool(hidden, LongArray(length) { mask[b * length + it] })
                        }
                    } else {
                        (value as Array<FloatArray>).map { YtSsiVectors.normalize(it) }
                    }
                    else -> error("unexpected output ${value?.javaClass}")
                }
            }
        } finally {
            tensors.values.forEach { it.close() }
        }
    }

    override fun close() {
        runCatching { session.close() }
    }

    private companion object {
        const val INPUT_IDS = "input_ids"
        const val MASK = "attention_mask"
        const val TYPES = "token_type_ids"
    }
}
