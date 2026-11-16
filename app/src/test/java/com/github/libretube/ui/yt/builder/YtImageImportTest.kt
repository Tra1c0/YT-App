package com.github.libretube.ui.yt.builder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtImageImportTest {
    private fun bytes(vararg b: Int) = ByteArray(16).also { a -> b.forEachIndexed { i, v -> a[i] = v.toByte() } }
    private val png = bytes(0x89, 'P'.code, 'N'.code, 'G'.code, 0x0D, 0x0A, 0x1A, 0x0A)
    private val jpeg = bytes(0xFF, 0xD8, 0xFF, 0xE0)
    private val webp = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)
    private val gif = "GIF89a".toByteArray(Charsets.ISO_8859_1) + ByteArray(10)

    @Test
    fun `formats are told by their bytes`() {
        assertEquals(YtImageImport.Format.PNG, YtImageImport.formatOf(png))
        assertEquals(YtImageImport.Format.JPEG, YtImageImport.formatOf(jpeg))
        assertEquals(YtImageImport.Format.WEBP, YtImageImport.formatOf(webp))
        assertNull(YtImageImport.formatOf(gif))
        assertNull(YtImageImport.formatOf(ByteArray(0)))
        // a RIFF that is not WebP (a WAV file)
        assertNull(YtImageImport.formatOf("RIFF\u0000\u0000\u0000\u0000WAVEfmt ".toByteArray(Charsets.ISO_8859_1)))
    }

    @Test
    fun `checks refuse what cannot be taken`() {
        assertNull(YtImageImport.check(1000, png, 800, 600))
        assertEquals(YtImageImport.Refusal.EMPTY, YtImageImport.check(0, png, 800, 600))
        assertEquals(YtImageImport.Refusal.TOO_LARGE_FILE, YtImageImport.check(YtImageImport.MAX_SOURCE_BYTES + 1, png, 800, 600))
        assertEquals(YtImageImport.Refusal.NOT_AN_IMAGE, YtImageImport.check(1000, gif, 800, 600))
        assertEquals(YtImageImport.Refusal.BAD_SIZE, YtImageImport.check(1000, png, -1, -1))
        assertEquals(YtImageImport.Refusal.BAD_SIZE, YtImageImport.check(1000, png, 20_000, 10))
        assertEquals(YtImageImport.Refusal.TOO_MANY_PIXELS, YtImageImport.check(1000, jpeg, 10_000, 10_000))
    }

    @Test
    fun `large pictures are scaled down to both limits, never up`() {
        assertEquals(800 to 600, YtImageImport.targetSize(800, 600))
        assertEquals(2048 to 1024, YtImageImport.targetSize(2048, 1024))
        // the long side: 4000 × 1000 to 2048 × 512
        assertEquals(2048 to 512, YtImageImport.targetSize(4000, 1000))
        // the pixel count: a 2048-square would be 4.19 MP, so it is a little smaller
        val (w, h) = YtImageImport.targetSize(4000, 4000)
        assertEquals(w, h)
        assertTrue(w.toLong() * h <= YtImageImport.MAX_PIXELS + 2 * w)
        assertTrue(w in 1990..2000)
        // a tall strip keeps at least one pixel across
        assertEquals(1, YtImageImport.targetSize(1, 10_000).first)
        assertEquals(0 to 0, YtImageImport.targetSize(0, 100))
    }

    @Test
    fun `the decoder samples down by powers of two but never below the target`() {
        assertEquals(1, YtImageImport.sampleSize(800, 600, 800, 600))
        assertEquals(1, YtImageImport.sampleSize(4000, 1000, 2048, 512))
        assertEquals(2, YtImageImport.sampleSize(8000, 2000, 2048, 512))
        assertEquals(4, YtImageImport.sampleSize(12_000, 12_000, 1999, 1999))
    }

    @Test
    fun `stored format, fallback, size limit and name`() {
        assertEquals(YtImageImport.Format.PNG, YtImageImport.outputFormat(hasAlpha = true))
        assertEquals(YtImageImport.Format.JPEG, YtImageImport.outputFormat(hasAlpha = false))
        assertEquals(YtImageImport.Format.WEBP, YtImageImport.fallback(YtImageImport.Format.PNG))
        assertNull(YtImageImport.fallback(YtImageImport.Format.JPEG))
        assertTrue(YtImageImport.resultOk(1000))
        assertFalse(YtImageImport.resultOk(0))
        assertFalse(YtImageImport.resultOk((YtThemeZip.MAX_ENTRY_BYTES + 1).toInt()))
        assertEquals("Mein_Bild_HEIC.jpg", YtImageImport.assetName("Mein Bild.HEIC.png", YtImageImport.Format.JPEG, emptyList()))
        assertEquals("photo_2.png", YtImageImport.assetName("content/photo.png", YtImageImport.Format.PNG, listOf("photo.png")))
        assertEquals("image.png", YtImageImport.assetName(null, YtImageImport.Format.PNG, emptyList()))
        assertEquals("image.png", YtImageImport.assetName("...png", YtImageImport.Format.PNG, emptyList()))
        // every name it makes is a safe asset name of an image
        for (n in listOf("a b c.jpeg", "../../etc/passwd", "ÄÖÜ.webp", ".hidden.png")) {
            val name = YtImageImport.assetName(n, YtImageImport.Format.WEBP, emptyList())
            assertTrue(name, YtThemeZip.safeAssetName(name))
            assertEquals(YtAssetKind.IMAGE, YtThemeZip.kindOf(name))
        }
    }

    @Test
    fun `an image fill without a usable file becomes a flat colour`() {
        fun doc(p: YtPaint) = testDoc().let { d ->
            d.copy(components = d.components.copy(card = d.components.card.copy(style = YtStyle(fills = listOf(p)))))
        }
        val ok = YtThemeValidate.clamp(doc(YtPaint.image("pic.png"))).doc.components.card.style.fills.first()
        assertEquals(YtPaintType.IMAGE, ok.type)
        assertEquals("pic.png", ok.image)
        val bad = YtThemeValidate.clamp(doc(YtPaint(type = YtPaintType.IMAGE, image = "../x.png"))).doc.components.card.style.fills.first()
        assertEquals(YtPaintType.SOLID, bad.type)
        val font = YtThemeValidate.clamp(doc(YtPaint(type = YtPaintType.IMAGE, image = "f.ttf"))).doc.components.card.style.fills.first()
        assertEquals(YtPaintType.SOLID, font.type)
        // and it survives the file
        val back = (YtThemeCodec.decode(YtThemeCodec.encode(doc(YtPaint.image("pic.png")))) as YtThemeCodec.Read.Ok).doc
        assertEquals("pic.png", back.components.card.style.fills.first().image)
    }
}
