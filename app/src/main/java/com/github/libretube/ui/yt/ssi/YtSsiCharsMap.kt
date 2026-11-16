package com.github.libretube.ui.yt.ssi

import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * YT: SentencePiece's precompiled normaliser, pure: the "precompiled_charsmap" of a Hugging Face
 * `tokenizer.json` (for XLM-R and multilingual-e5 the compiled "nmt_nfkc" rules: NFKC, control
 * characters removed, tab/new line/odd spaces made a space).
 *
 * The blob: a little-endian uint32 with the trie's size in bytes, the trie (a darts-clone double
 * array of little-endian uint32 units) and the replacement strings (UTF-8, each ended by a 0
 * byte). The trie maps a UTF-8 byte sequence to the offset of its replacement.
 *
 * Normalising follows SentencePiece (normalizer.cc, NormalizePrefix): at each position the
 * longest key of the trie the rest of the input starts with is replaced by its string; where no
 * key matches, one character is kept as it is. A lone surrogate (no valid UTF-8) becomes U+FFFD,
 * as SentencePiece does with malformed input.
 */
class YtSsiCharsMap private constructor(
    private val units: IntArray,
    private val normalized: ByteArray,
    /** The blob as read (kept for the tokenizer's cache). */
    val blob: ByteArray,
) {
    val trieUnits: Int get() = units.size

    fun normalize(text: String): String {
        if (text.isEmpty()) return text
        val input = utf8(text)
        val out = ByteArrayOutputStream(input.size + 8)
        var i = 0
        while (i < input.size) {
            val match = longest(units, input, i)
            if (match != null && match.second in normalized.indices) {
                var end = match.second
                while (end < normalized.size && normalized[end] != 0.toByte()) end++
                out.write(normalized, match.second, end - match.second)
                i += match.first
            } else {
                val n = charLength(input[i]).coerceAtMost(input.size - i)
                out.write(input, i, n)
                i += n
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    companion object {
        /** The map in [blob], or null when the blob is not one. */
        fun parse(blob: ByteArray): YtSsiCharsMap? {
            if (blob.size < 4) return null
            val trieBytes = le(blob, 0).toLong() and 0xFFFFFFFFL
            if (trieBytes <= 0 || trieBytes % 4 != 0L || 4 + trieBytes > blob.size) return null
            val units = IntArray((trieBytes / 4).toInt()) { le(blob, 4 + it * 4) }
            val normalized = blob.copyOfRange(4 + trieBytes.toInt(), blob.size)
            return YtSsiCharsMap(units, normalized, blob)
        }

        /** The map from the base64 text of tokenizer.json, or null. */
        fun fromBase64(text: String): YtSsiCharsMap? =
            runCatching { Base64.getDecoder().decode(text.replace("\\", "").trim()) }.getOrNull()?.let(::parse)

        /**
         * The longest key of the double array [units] that [key] starts with at [from]: its
         * length in bytes and its value, or null. darts-clone's common prefix search: a unit
         * holds its label in the low byte (a value unit has bit 31 set, so it never matches a
         * label), "has a value" in bit 8, and an offset to its children in bits 10–31 (shifted
         * by 8 more when bit 9 is set); a child with label c sits at (position ^ offset ^ c),
         * the value at (position ^ offset). A truncated or broken array just ends the search.
         */
        internal fun longest(units: IntArray, key: ByteArray, from: Int): Pair<Int, Int>? {
            if (units.isEmpty()) return null
            var pos = offset(units[0])
            var found: Pair<Int, Int>? = null
            var i = from
            while (i < key.size) {
                val c = key[i].toInt() and 0xFF
                if (c == 0) break
                pos = pos xor c
                if (pos !in units.indices) break
                val unit = units[pos]
                if (label(unit) != c) break
                pos = pos xor offset(unit)
                if (hasLeaf(unit)) {
                    if (pos !in units.indices) break
                    found = (i - from + 1) to value(units[pos])
                }
                i++
            }
            return found
        }

        private fun hasLeaf(unit: Int) = (unit ushr 8) and 1 == 1
        private fun value(unit: Int) = unit and 0x7FFFFFFF
        private fun label(unit: Int) = unit and (0x800000FF).toInt()
        private fun offset(unit: Int) = (unit ushr 10) shl ((unit and (1 shl 9)) ushr 6)

        private fun le(b: ByteArray, at: Int): Int =
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
                ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

        /** The length of the UTF-8 sequence that starts with [lead] (1 for a stray byte). */
        private fun charLength(lead: Byte): Int {
            val b = lead.toInt() and 0xFF
            return when {
                b < 0x80 -> 1
                b ushr 5 == 0x6 -> 2
                b ushr 4 == 0xE -> 3
                b ushr 3 == 0x1E -> 4
                else -> 1
            }
        }

        /** [text] as UTF-8, lone surrogates as U+FFFD. */
        private fun utf8(text: String): ByteArray {
            var clean = true
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) {
                    i += 2
                    continue
                }
                if (Character.isSurrogate(c)) {
                    clean = false
                    break
                }
                i++
            }
            if (clean) return text.toByteArray(Charsets.UTF_8)
            val sb = StringBuilder(text.length)
            i = 0
            while (i < text.length) {
                val c = text[i]
                if (Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) {
                    sb.append(c).append(text[i + 1])
                    i += 2
                    continue
                }
                sb.append(if (Character.isSurrogate(c)) '�' else c)
                i++
            }
            return sb.toString().toByteArray(Charsets.UTF_8)
        }
    }
}
