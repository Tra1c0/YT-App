package com.github.libretube.ui.yt.ssi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.TreeMap

class YtSsiCharsMapTest {
    /**
     * A part of the nmt_nfkc rules (what XLM-R's map does for these keys): full-width forms,
     * ligatures, compatibility characters, controls removed, tab/new line/odd spaces to a space,
     * a decomposed accent composed, and a longer key that starts with a shorter one.
     */
    private val rules = linkedMapOf(
        "Ａ" to "A", "Ｂ" to "B", "ａ" to "a", "ｈ" to "h", "ｌ" to "l", "ｏ" to "o", "１" to "1",
        "ﬁ" to "fi", "ﬂ" to "fl", "ﬀ" to "ff", "Ⅻ" to "XII", "²" to "2", "…" to "...", "㎏" to "kg",
        "\u0001" to "", "\u0007" to "", "\u007F" to "", "​" to "", "﻿" to "",
        "\t" to " ", "\n" to " ", "\r" to " ", "　" to " ", " " to " ",
        "é" to "é", "Ａ́" to "Á",
        "𝐀" to "A", // MATHEMATICAL BOLD CAPITAL A (4 UTF-8 bytes)
    )

    private val map = YtSsiCharsMap.parse(DoubleArrayBuilder.blob(rules))!!

    @Test
    fun fullWidthFormsBecomeAscii() {
        assertEquals("Aa1 hallo", map.normalize("Ａａ１ ｈａｌｌｏ"))
    }

    @Test
    fun ligaturesAndCompatibilityCharactersAreSpelledOut() {
        assertEquals("fish flat off XII m2 ... 5kg", map.normalize("ﬁsh ﬂat oﬀ Ⅻ m² … 5㎏"))
    }

    @Test
    fun controlCharactersGoAndOddSpacesBecomeSpaces() {
        assertEquals("ab cd e f g h", map.normalize("a\u0001b\u0007\tc\u007Fd\ne　f g\rh​﻿"))
    }

    @Test
    fun theLongestKeyWins() {
        // "Ａ" alone would be "A"; with the combining accent the longer key "Ａ́" matches
        assertEquals("Á Ae", map.normalize("Ａ́ Ａe"))
        assertEquals("café", map.normalize("café"))
    }

    @Test
    fun unmappedTextStaysAsItIs() {
        val text = "Grüße, 東京 — ok? 😀"
        assertEquals(text, map.normalize(text))
        assertEquals("", map.normalize(""))
    }

    @Test
    fun fourByteKeysAndLoneSurrogates() {
        assertEquals("xAx", map.normalize("x𝐀x"))
        // a high surrogate without its pair is no valid UTF-8: the replacement character
        assertEquals("a�b", map.normalize("a\uD835b"))
    }

    @Test
    fun brokenBlobsAreRefused() {
        assertNull(YtSsiCharsMap.parse(ByteArray(3)))
        // the trie would be longer than the blob
        assertNull(YtSsiCharsMap.parse(byteArrayOf(0x00, 0x10, 0x00, 0x00, 1, 2, 3, 4)))
        // not a multiple of 4
        assertNull(YtSsiCharsMap.parse(byteArrayOf(0x03, 0x00, 0x00, 0x00, 1, 2, 3, 4)))
        assertNull(YtSsiCharsMap.fromBase64("not base64 !!"))
    }

    /**
     * The first 260 bytes of XLM-R's own precompiled_charsmap (its size and the trie's first 64 units): the
     * reader walks them as SentencePiece does. Controls point at replacement 0 (the empty string,
     * removed), tab, new line, form feed and carriage return at replacement 1 (" "), and a byte
     * whose child lies beyond the truncated array is simply no match.
     */
    @Test
    fun walksTheRealTrieLayout() {
        val b = Base64.getDecoder().decode(XLMR_START)
        assertEquals(177_152, (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or ((b[2].toInt() and 0xFF) shl 16))
        val units = IntArray((b.size - 4) / 4) { i ->
            val at = 4 + i * 4
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)
        }
        fun value(c: Int) = YtSsiCharsMap.longest(units, byteArrayOf(c.toByte()), 0)?.second
        for (c in listOf(0x01, 0x02, 0x07, 0x08, 0x0B, 0x0E, 0x1A)) assertEquals("byte $c", 0, value(c))
        for (c in listOf(0x09, 0x0A, 0x0C, 0x0D)) assertEquals("byte $c", 1, value(c))
        assertNull(value('A'.code))
    }

    @Test
    fun theTokenizerUsesTheMapFromTheJson() {
        val blob = Base64.getEncoder().encodeToString(DoubleArrayBuilder.blob(rules))
        val json = """
            {"version": "1.0", "added_tokens": [],
             "normalizer": {"type": "Sequence", "normalizers": [
               {"type": "Precompiled", "precompiled_charsmap": "$blob"},
               {"type": "Replace", "pattern": {"Regex": " {2,}"}, "content": " "}]},
             "pre_tokenizer": {"type": "Metaspace", "replacement": "▁"},
             "model": {"type": "Unigram", "unk_id": 3, "vocab": [["<s>", 0.0], ["<pad>", 0.0], ["</s>", 0.0], ["<unk>", 0.0],
               ["▁fi", -2.0], ["sh", -2.0], ["▁hallo", -1.0], ["▁", -5.0], ["f", -6.0], ["i", -6.0], ["s", -6.0], ["h", -6.0]]}}
        """.trimIndent()
        val tokenizer = YtSsiTokenizer.fromJson(json)
        assertNotNull(tokenizer.charsMap)
        assertEquals(listOf("▁fi", "sh", "▁hallo"), tokenizer.pieces("ﬁsh\u0007　ｈａｌｌｏ"))
        assertTrue(tokenizer.normalizer, tokenizer.normalizer.startsWith("charsmap ("))

        // the cache keeps the map
        val file = File.createTempFile("yt_ssi_vocab", ".bin")
        try {
            YtSsiTokenizer.writeCache(tokenizer, file)
            val back = YtSsiTokenizer.readCache(file)!!
            assertNotNull(back.charsMap)
            assertEquals(tokenizer.pieces("ﬁsh ｈａｌｌｏ"), back.pieces("ﬁsh ｈａｌｌｏ"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun withoutAMapNfkcIsTheFallback() {
        val json = """{"normalizer": {"type": "Precompiled", "precompiled_charsmap": null},
            "model": {"type": "Unigram", "unk_id": 3, "vocab": [["<s>", 0.0], ["<pad>", 0.0], ["</s>", 0.0], ["<unk>", 0.0], ["▁fish", -1.0]]}}"""
        val tokenizer = YtSsiTokenizer.fromJson(json)
        assertNull(tokenizer.charsMap)
        assertEquals("NFKC fallback", tokenizer.normalizer)
        assertEquals(listOf("▁fish"), tokenizer.pieces("ﬁsh\u0007"))
    }

    /**
     * Builds a darts-clone double array the way its builder lays it out (a unit per node: label
     * in the low byte, "has a value" in bit 8, the offset to its children in bits 10–31; the
     * value at the children's base with bit 31 set), plus SentencePiece's blob around it.
     */
    private object DoubleArrayBuilder {
        class Node {
            val children = TreeMap<Int, Node>()
            var value: Int? = null
        }

        fun blob(rules: Map<String, String>): ByteArray {
            val normalized = ByteArrayOutputStream()
            val root = Node()
            for ((key, replacement) in rules) {
                var node = root
                for (b in key.toByteArray(Charsets.UTF_8)) node = node.children.getOrPut(b.toInt() and 0xFF) { Node() }
                node.value = normalized.size()
                normalized.write(replacement.toByteArray(Charsets.UTF_8))
                normalized.write(0)
            }
            val units = HashMap<Int, Int>()
            val usedBases = HashSet<Int>()
            val queue = ArrayDeque<Pair<Node, Int>>()
            queue += root to 0
            units[0] = 0
            while (queue.isNotEmpty()) {
                val (node, pos) = queue.removeFirst()
                val labels = node.children.keys.toMutableList().apply { if (node.value != null) add(0) }
                if (labels.isEmpty()) continue
                var base = 1
                while (base in usedBases || base == pos || labels.any { (base xor it) == 0 || (base xor it) in units }) base++
                usedBases += base
                val offset = pos xor base
                require(offset < (1 shl 21))
                val hasLeaf = if (node.value != null) 1 shl 8 else 0
                val old = units.getValue(pos)
                units[pos] = (old and 0xFF) or hasLeaf or (offset shl 10)
                node.value?.let { units[base] = (1 shl 31) or it }
                for ((label, child) in node.children) {
                    units[base xor label] = label
                    queue += child to (base xor label)
                }
            }
            val size = (units.keys.maxOrNull() ?: 0) + 1
            val out = ByteArrayOutputStream()
            fun le(v: Int) = repeat(4) { out.write((v ushr (8 * it)) and 0xFF) }
            le(size * 4)
            for (i in 0 until size) le(units[i] ?: 0)
            out.write(normalized.toByteArray())
            return out.toByteArray()
        }
    }

    private companion object {
        const val XLMR_START = "ALQCAACEAAAAAACAAQAAgMz8AgC4BQAAhyIAgMzkAgC4PQAAeyIAgMzsAgC4BQAAiyIAgMw8AADNvAAAmwkAgJ4JAIChCQCAgx0AAIAZAACBGQAAPR0AgDUdAIBNHQCARR0AgIAxAACBMQAApAkAgIkxAAA9WAMAPEgDAEAKAIA+aAMAAYUAAIQBAQADjQAAAokAAAWVAAAElQAAB50AAAaZAAAJqQAACKEAAAutAAAKpQAADbkAAAy9AAAPvQAADrkAABHFAAAQwQAAE80AABLJAAAV1QAAFNEAABfdAAAW2QAAGeUAABjhAAAb7QAAGukAAB31AAAc8QAAH/0AAB75AAA="
    }
}
