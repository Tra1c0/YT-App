package com.github.libretube.ui.yt.ssi

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.text.Normalizer

/**
 * YT: the tokenizer of the embedding model in plain Kotlin: SentencePiece's Unigram model as the
 * Hugging Face file `tokenizer.json` describes it (XLM-RoBERTa's vocabulary, used by
 * multilingual-e5-small). Text is normalised by the model's own compiled character map
 * ([YtSsiCharsMap], "precompiled_charsmap" in the file: NFKC plus SentencePiece's "nmt" rules),
 * split at white space, every word gets the "▁" prefix (Metaspace), and each word is cut into
 * the pieces with the best total log probability (Viterbi). Unknown characters become <unk>.
 * The ids get <s> … </s> around them. A file without a character map falls back to NFKC with
 * control characters removed (close to the map except for a few rare characters). Pure.
 */
class YtSsiTokenizer(
    private val pieces: Map<String, Int>,
    private val scores: FloatArray,
    val unkId: Int,
    val bosId: Int,
    val eosId: Int,
    val padId: Int,
    /** The model's character map; null: NFKC. */
    val charsMap: YtSsiCharsMap? = null,
) {
    private val maxPiece = pieces.keys.maxOfOrNull { it.length } ?: 1
    private val unkScore = (scores.minOrNull() ?: 0f) - 10f

    val vocabSize: Int get() = scores.size

    /** The ids of [text] with <s> and </s>, at most [maxTokens] in all. */
    fun encode(text: String, maxTokens: Int = 256): IntArray {
        val ids = ArrayList<Int>()
        for (word in words(text)) {
            ids += word(SPACE + word)
            if (ids.size >= maxTokens - 2) break
        }
        val body = ids.take((maxTokens - 2).coerceAtLeast(0))
        return (listOf(bosId) + body + eosId).toIntArray()
    }

    /** The pieces of [text] as strings, without <s> and </s> (for tests and the diagnosis). */
    fun pieces(text: String): List<String> {
        val byId = HashMap<Int, String>(pieces.size).apply { pieces.forEach { (k, v) -> put(v, k) } }
        return words(text).flatMap { word(SPACE + it) }.map { byId[it] ?: "<unk>" }
    }

    /** [text] as the model's normaliser makes it (before the split at white space). */
    fun normalize(text: String): String = charsMap?.normalize(text)
        ?: Normalizer.normalize(text, Normalizer.Form.NFKC).filter { !Character.isISOControl(it) || it.isWhitespace() }

    /** For Diagnose: which normaliser is in use. */
    val normalizer: String get() = charsMap?.let { "charsmap (${it.blob.size / 1024} KB)" } ?: "NFKC fallback"

    private fun words(text: String): List<String> = normalize(text).split(WHITE).filter { it.isNotEmpty() }

    /** The best cut of one word ("▁word") into pieces: Viterbi over the piece scores. */
    private fun word(word: String): List<Int> {
        val n = word.length
        val best = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val from = IntArray(n + 1) { -1 }
        val piece = IntArray(n + 1) { -1 }
        best[0] = 0f
        var end = 0
        while (end < n) {
            // the next code point boundary
            end += Character.charCount(word.codePointAt(end))
            val oneStart = end - Character.charCount(word.codePointBefore(end))
            var start = end - 1
            val lowest = (end - maxPiece).coerceAtLeast(0)
            var unkCovered = false
            while (start >= lowest) {
                if (start > 0 && Character.isLowSurrogate(word[start])) {
                    start--
                    continue
                }
                if (best[start] != Float.NEGATIVE_INFINITY) {
                    val id = pieces[word.substring(start, end)]
                    if (id != null && id != unkId) {
                        val s = best[start] + scores[id]
                        if (s > best[end]) {
                            best[end] = s
                            from[end] = start
                            piece[end] = id
                        }
                        if (start == oneStart) unkCovered = true
                    }
                }
                start--
            }
            // a character no piece starts with: <unk> for it alone
            if (!unkCovered && best[oneStart] != Float.NEGATIVE_INFINITY) {
                val s = best[oneStart] + unkScore
                if (s > best[end]) {
                    best[end] = s
                    from[end] = oneStart
                    piece[end] = unkId
                }
            }
        }
        val out = ArrayList<Int>()
        var at = n
        while (at > 0) {
            val id = piece[at]
            val start = from[at]
            if (start < 0) return listOf(unkId)
            // SentencePiece joins unknowns in a row into one
            if (!(id == unkId && out.lastOrNull() == unkId)) out += id
            at = start
        }
        return out.asReversed()
    }

    companion object {
        const val SPACE = "▁"
        private val WHITE = Regex("\\s+")

        /**
         * Reads the vocabulary of a Hugging Face `tokenizer.json` of a Unigram model: model.vocab
         * ([[piece, score], …]), model.unk_id and the ids of <s>, </s>, <pad> from added_tokens
         * (XLM-R's 0, 2, 1 when missing). Throws when the file has no Unigram vocabulary.
         */
        fun fromJson(text: String): YtSsiTokenizer {
            val model = Regex("\"model\"\\s*:\\s*\\{").find(text) ?: error("no model")
            if (!Regex("\"type\"\\s*:\\s*\"Unigram\"").containsMatchIn(text.substring(model.range.first, minOf(text.length, model.range.first + 400)))
                && !text.substring(model.range.first).contains("\"Unigram\"")
            ) {
                error("not a Unigram tokenizer")
            }
            val vocabAt = text.indexOf("\"vocab\"", model.range.last).takeIf { it >= 0 } ?: error("no vocab")
            val reader = Reader(text, text.indexOf('[', vocabAt))
            val names = ArrayList<String>()
            val values = ArrayList<Float>()
            reader.expect('[')
            while (true) {
                reader.space()
                if (reader.peek() == ']') break
                reader.expect('[')
                names += reader.string()
                reader.expect(',')
                values += reader.number().toFloat()
                reader.expect(']')
                reader.space()
                if (reader.peek() == ',') reader.skip() else break
            }
            val unk = Regex("\"unk_id\"\\s*:\\s*(\\d+)").find(text, model.range.first)?.groupValues?.get(1)?.toInt() ?: 3
            // the added tokens are a short list near the top; the regexes only look there
            val addedAt = text.indexOf("\"added_tokens\"")
            val added = if (addedAt >= 0) text.substring(addedAt, minOf(text.length, addedAt + 20_000)) else ""
            fun special(content: String, default: Int): Int {
                val escaped = Regex.escape(content)
                val a = Regex("\\{[^{}]*\"id\"\\s*:\\s*(\\d+)[^{}]*\"content\"\\s*:\\s*\"$escaped\"[^{}]*\\}").find(added)
                val b = Regex("\\{[^{}]*\"content\"\\s*:\\s*\"$escaped\"[^{}]*\"id\"\\s*:\\s*(\\d+)[^{}]*\\}").find(added)
                return (a ?: b)?.groupValues?.get(1)?.toInt() ?: names.indexOf(content).takeIf { it >= 0 } ?: default
            }
            return of(names, values.toFloatArray(), unk, special("<s>", 0), special("</s>", 2), special("<pad>", 1), charsMap(text))
        }

        /**
         * The "precompiled_charsmap" of the file's normaliser (base64), or null when there is
         * none (another normaliser, or null) or it cannot be read.
         */
        fun charsMap(text: String): YtSsiCharsMap? {
            val key = Regex("\"precompiled_charsmap\"\\s*:\\s*\"").find(text) ?: return null
            val start = key.range.last + 1
            val end = text.indexOf('"', start).takeIf { it > start } ?: return null
            return YtSsiCharsMap.fromBase64(text.substring(start, end))
        }

        fun of(names: List<String>, scores: FloatArray, unk: Int, bos: Int, eos: Int, pad: Int, charsMap: YtSsiCharsMap? = null): YtSsiTokenizer {
            val map = HashMap<String, Int>(names.size * 2)
            names.forEachIndexed { i, name -> map.putIfAbsent(name, i) }
            return YtSsiTokenizer(map, scores, unk, bos, eos, pad, charsMap)
        }

        /** A compact copy of the vocabulary, read much faster than the JSON on later starts. */
        fun writeCache(tokenizer: YtSsiTokenizer, file: File) {
            val byId = arrayOfNulls<String>(tokenizer.scores.size)
            tokenizer.pieces.forEach { (k, v) -> if (v in byId.indices) byId[v] = k }
            DataOutputStream(file.outputStream().buffered()).use { out ->
                out.writeInt(CACHE_VERSION)
                out.writeInt(tokenizer.unkId)
                out.writeInt(tokenizer.bosId)
                out.writeInt(tokenizer.eosId)
                out.writeInt(tokenizer.padId)
                out.writeInt(byId.size)
                for (i in byId.indices) {
                    out.writeUTF(byId[i] ?: "")
                    out.writeFloat(tokenizer.scores[i])
                }
                val blob = tokenizer.charsMap?.blob ?: ByteArray(0)
                out.writeInt(blob.size)
                out.write(blob)
            }
        }

        fun readCache(file: File): YtSsiTokenizer? = runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != CACHE_VERSION) return null
                val unk = input.readInt()
                val bos = input.readInt()
                val eos = input.readInt()
                val pad = input.readInt()
                val count = input.readInt()
                val names = ArrayList<String>(count)
                val scores = FloatArray(count)
                for (i in 0 until count) {
                    names += input.readUTF()
                    scores[i] = input.readFloat()
                }
                val size = input.readInt()
                val charsMap = if (size > 0) {
                    YtSsiCharsMap.parse(ByteArray(size).also { input.readFully(it) }) ?: return null
                } else {
                    null
                }
                of(names, scores, unk, bos, eos, pad, charsMap)
            }
        }.getOrNull()

        /** 2: with the character map (a version-1 cache is read from the JSON again). */
        private const val CACHE_VERSION = 2
    }

    /** Just enough JSON for the vocabulary array: strings with escapes and numbers. */
    private class Reader(private val s: String, private var i: Int) {
        fun peek(): Char = s[i]
        fun skip() { i++ }
        fun space() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun expect(c: Char) {
            space()
            if (i >= s.length || s[i] != c) error("expected '$c' at $i")
            i++
        }

        fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            'n' -> out.append('\n')
                            't' -> out.append('\t')
                            'r' -> out.append('\r')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'u' -> {
                                out.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> out.append(e)
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        fun number(): Double {
            space()
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDouble()
        }
    }
}
