package com.github.libretube.ui.yt.ssi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.MessageDigest

class YtSsiHfChecksumTest {
    private val modelUrl = "https://huggingface.co/Xenova/multilingual-e5-small/resolve/main/onnx/model_quantized.onnx"

    @Test
    fun parsesResolveUrls() {
        val ref = YtSsiHfChecksum.ref(modelUrl)!!
        assertEquals("Xenova/multilingual-e5-small", ref.repo)
        assertEquals("main", ref.rev)
        assertEquals("onnx/model_quantized.onnx", ref.path)
        assertEquals("https://huggingface.co/api/models/Xenova/multilingual-e5-small/tree/main/onnx", ref.treeUrl)
        val top = YtSsiHfChecksum.ref("https://huggingface.co/a/b/resolve/abc123/tokenizer.json")!!
        assertEquals("https://huggingface.co/api/models/a/b/tree/abc123", top.treeUrl)
        assertNull(YtSsiHfChecksum.ref("https://example.com/a/b/resolve/main/x"))
    }

    @Test
    fun lfsEntryGivesSha256() {
        val sha = "a".repeat(64)
        val listing = """[{"type":"file","oid":"${"1".repeat(40)}","size":5,"path":"onnx/model.onnx"},
            {"type":"file","oid":"${"2".repeat(40)}","size":118000000,"lfs":{"oid":"$sha","size":118000000,"pointerSize":134},"xetHash":"${"f".repeat(64)}","path":"onnx/model_quantized.onnx"}]"""
        assertEquals(YtSsiHfChecksum.Expected.Sha256(sha), YtSsiHfChecksum.expected(listing, YtSsiHfChecksum.ref(modelUrl)!!))
    }

    @Test
    fun smallFileGivesGitBlob() {
        val listing = """[{"type":"file","oid":"${"3".repeat(40)}","size":42,"path":"tokenizer.json"}]"""
        val ref = YtSsiHfChecksum.ref("https://huggingface.co/a/b/resolve/main/tokenizer.json")!!
        assertEquals(YtSsiHfChecksum.Expected.GitBlob("3".repeat(40), 42), YtSsiHfChecksum.expected(listing, ref))
    }

    @Test
    fun missingOrBrokenListingGivesNull() {
        val ref = YtSsiHfChecksum.ref(modelUrl)!!
        assertNull(YtSsiHfChecksum.expected("[]", ref))
        assertNull(YtSsiHfChecksum.expected("not json", ref))
        assertNull(YtSsiHfChecksum.expected("""{"error":"x"}""", ref))
    }

    @Test
    fun gitBlobShaMatchesGit() {
        // `printf 'hello\n' | git hash-object --stdin` = ce013625030ba8dba906f756967f9e9ca394464a
        val content = "hello\n".toByteArray()
        val md = MessageDigest.getInstance("SHA-1")
        md.update(YtSsiHfChecksum.gitBlobHeader(content.size.toLong()))
        md.update(content)
        assertEquals("ce013625030ba8dba906f756967f9e9ca394464a", YtSsiHfChecksum.hex(md.digest()))
    }
}
