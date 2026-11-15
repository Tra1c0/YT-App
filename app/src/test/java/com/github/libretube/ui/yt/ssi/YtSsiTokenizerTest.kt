package com.github.libretube.ui.yt.ssi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class YtSsiTokenizerTest {
    // XLM-R's layout: <s> 0, <pad> 1, </s> 2, <unk> 3, then pieces with log probabilities
    private val names = listOf("<s>", "<pad>", "</s>", "<unk>", "▁", "▁hal", "lo", "▁hallo", "▁welt", "w", "e", "l", "t", "▁w", "a", "h", "o")
    private val scores = floatArrayOf(0f, 0f, 0f, 0f, -3f, -6f, -5f, -7f, -8f, -9f, -9f, -9f, -9f, -7f, -9f, -9f, -9f)
    private val tokenizer = YtSsiTokenizer.of(names, scores, unk = 3, bos = 0, eos = 2, pad = 1)

    @Test
    fun picksTheBestTotalScore() {
        // "▁hallo" alone (-7) beats "▁hal" + "lo" (-11)
        assertEquals(listOf("▁hallo", "▁welt"), tokenizer.pieces("Hallo Welt".lowercase()))
        assertArrayEquals(intArrayOf(0, 7, 8, 2), tokenizer.encode("hallo welt"))
    }

    @Test
    fun unknownCharactersBecomeOneUnk() {
        // "ß" and "ü" are not in the vocabulary: one <unk> for the two in a row
        assertEquals(listOf("▁w", "<unk>", "t"), tokenizer.pieces("wßüt"))
    }

    @Test
    fun normalisesAndSplitsAtWhiteSpace() {
        // NFKC turns the full-width letters into ASCII; tabs and double spaces are one split
        assertEquals(tokenizer.pieces("hallo welt"), tokenizer.pieces("ｈａｌｌｏ\t  welt"))
    }

    @Test
    fun truncatesToTheLimitWithSpecials() {
        val ids = tokenizer.encode("hallo hallo hallo hallo hallo", maxTokens = 4)
        assertArrayEquals(intArrayOf(0, 7, 7, 2), ids)
    }

    @Test
    fun readsTheHuggingFaceJson() {
        val json = """
            {"version":"1.0","added_tokens":[{"id":0,"content":"<s>","special":true},{"content":"</s>","id":2},{"id":1,"content":"<pad>"},{"id":3,"content":"<unk>"}],
             "normalizer":{"type":"Precompiled","precompiled_charsmap":"AAAABBBB"},
             "model":{"type":"Unigram","unk_id":3,"vocab":[["<s>",0.0],["<pad>",0.0],["</s>",0.0],["<unk>",0.0],["▁a\"b",-1.5],["▁x",-2e0]],"byte_fallback":false}}
        """.trimIndent()
        val read = YtSsiTokenizer.fromJson(json)
        assertEquals(6, read.vocabSize)
        assertEquals(3, read.unkId)
        assertEquals(0, read.bosId)
        assertEquals(2, read.eosId)
        assertEquals(1, read.padId)
        assertEquals(listOf("▁x"), read.pieces("x"))
        assertEquals(listOf("▁a\"b"), read.pieces("a\"b"))
    }

    @Test
    fun cacheKeepsTheVocabulary() {
        val file = File.createTempFile("vocab", ".bin")
        try {
            YtSsiTokenizer.writeCache(tokenizer, file)
            val back = YtSsiTokenizer.readCache(file)!!
            assertArrayEquals(tokenizer.encode("hallo welt wßüt"), back.encode("hallo welt wßüt"))
        } finally {
            file.delete()
        }
    }
}
