package com.github.libretube.ui.yt.builder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class YtThemeZipTest {
    private val ttf = byteArrayOf(0, 1, 0, 0) + ByteArray(60) { it.toByte() }
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) + ByteArray(20)

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun read(bytes: ByteArray) = YtThemeZip.read(ByteArrayInputStream(bytes))

    @Test
    fun `export and import keep the theme and its fonts`() {
        val doc = testDoc().copy(assets = listOf(YtAsset("brand.ttf", YtAssetKind.FONT)))
        val out = ByteArrayOutputStream()
        YtThemeZip.write(doc, mapOf("brand.ttf" to ttf, "stray.ttf" to ttf), out)
        val r = read(out.toByteArray()) as YtThemeZip.Import.Ok
        assertEquals(doc, r.doc)
        assertArrayEquals(ttf, r.assets["brand.ttf"])
        assertEquals(setOf("brand.ttf"), r.assets.keys)
    }

    @Test
    fun `asset names with paths are skipped`() {
        val json = YtThemeCodec.encode(testDoc()).toByteArray()
        val r = read(zip("theme.json" to json, "assets/../../evil.ttf" to ttf, "assets/sub/x.png" to png, "other.txt" to byteArrayOf(1))) as YtThemeZip.Import.Ok
        assertTrue(r.assets.isEmpty())
        assertTrue(r.notes.toString(), r.notes.any { it.startsWith("skipped") })
        assertTrue(r.notes.any { it.startsWith("ignored") })
    }

    @Test
    fun `a renamed file is refused and a missing one reported`() {
        val doc = testDoc().copy(assets = listOf(YtAsset("a.ttf", YtAssetKind.FONT), YtAsset("b.png", YtAssetKind.IMAGE)))
        val json = YtThemeCodec.encode(doc).toByteArray()
        val r = read(zip("theme.json" to json, "assets/a.ttf" to png)) as YtThemeZip.Import.Ok
        assertTrue(r.notes.contains("damaged: a.ttf"))
        assertTrue(r.notes.contains("missing: b.png"))
        assertTrue(r.doc.assets.isEmpty())
    }

    @Test
    fun `files that are not themes fail cleanly`() {
        assertTrue(read(byteArrayOf(1, 2, 3, 4, 5)) is YtThemeZip.Import.Failed)
        assertTrue(read(ByteArray(0)) is YtThemeZip.Import.Failed)
        assertTrue(read(zip("readme.txt" to byteArrayOf(1))) is YtThemeZip.Import.Failed)
        assertTrue(read(zip("theme.json" to "{broken".toByteArray())) is YtThemeZip.Import.Failed)
    }

    @Test
    fun `too many entries fail`() {
        val entries = (0..YtThemeZip.MAX_ENTRIES + 1).map { "assets/f$it.png" to png }.toTypedArray()
        assertTrue(read(zip(*entries)) is YtThemeZip.Import.Failed)
    }

    @Test
    fun `an oversized entry fails`() {
        val big = png + ByteArray((YtThemeZip.MAX_ENTRY_BYTES + 10).toInt())
        assertTrue(read(zip("assets/big.png" to big)) is YtThemeZip.Import.Failed)
    }

    @Test
    fun `asset names`() {
        assertTrue(YtThemeZip.safeAssetName("Inter-Bold.ttf"))
        assertTrue(YtThemeZip.safeAssetName("bg.webp"))
        assertFalse(YtThemeZip.safeAssetName("../a.ttf"))
        assertFalse(YtThemeZip.safeAssetName(".hidden.ttf"))
        assertFalse(YtThemeZip.safeAssetName("a.exe"))
        assertFalse(YtThemeZip.safeAssetName("a b.ttf"))
    }

    @Test
    fun `signatures`() {
        assertTrue(YtThemeZip.signatureOk("x.ttf", ttf))
        assertTrue(YtThemeZip.signatureOk("x.otf", "OTTO".toByteArray()))
        assertTrue(YtThemeZip.signatureOk("x.png", png))
        assertTrue(YtThemeZip.signatureOk("x.jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0)))
        assertTrue(YtThemeZip.signatureOk("x.webp", "RIFF0000WEBP".toByteArray()))
        assertFalse(YtThemeZip.signatureOk("x.ttf", png))
        assertFalse(YtThemeZip.signatureOk("x.png", ByteArray(0)))
    }
}
