package com.github.libretube.ui.yt.builder

import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * YT: the rules of an image picked for a theme (pure): which files are taken (PNG, JPEG, WebP by
 * their first bytes, not by name or MIME type; at most [MAX_SOURCE_BYTES]; sane pixel sizes), how
 * far they are scaled down ([MAX_EDGE] px on the long side and [MAX_PIXELS] in all, never up),
 * how they are stored (PNG where the picture has transparency, else JPEG) and the asset name.
 * YtBuilderStore.importImage does the Android part (decoding, scaling, writing).
 */
object YtImageImport {
    enum class Format(val ext: String) { PNG("png"), JPEG("jpg"), WEBP("webp") }

    /** The picked file may be this large; what is stored is far smaller after scaling. */
    const val MAX_SOURCE_BYTES = 20L * 1024 * 1024

    /** The long side of a stored image, px. */
    const val MAX_EDGE = 2048

    /** Pixels of a stored image (4 MP: 16 MB as ARGB in memory). */
    const val MAX_PIXELS = 4_000_000L

    /** A source larger than this per side, or in all, is refused before it is decoded. */
    const val MAX_SOURCE_EDGE = 16_384
    const val MAX_SOURCE_PIXELS = 80_000_000L

    /** JPEG quality of a stored opaque image. */
    const val JPEG_QUALITY = 88

    /** Why an image is not taken. */
    enum class Refusal { EMPTY, TOO_LARGE_FILE, NOT_AN_IMAGE, BAD_SIZE, TOO_MANY_PIXELS, TOO_LARGE_RESULT }

    /** The format by the file's first bytes (at least 12), or null for anything else (GIF, HEIC, SVG …). */
    fun formatOf(head: ByteArray): Format? {
        fun at(i: Int) = if (i < head.size) head[i].toInt() and 0xFF else -1
        fun ascii(from: Int, to: Int) = if (head.size >= to) String(head.copyOfRange(from, to), Charsets.ISO_8859_1) else ""
        return when {
            at(0) == 0x89 && at(1) == 'P'.code && at(2) == 'N'.code && at(3) == 'G'.code -> Format.PNG
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> Format.JPEG
            ascii(0, 4) == "RIFF" && ascii(8, 12) == "WEBP" -> Format.WEBP
            else -> null
        }
    }

    /** The checks before decoding: [bytes] of the file, its [head], the decoded bounds [w] × [h]. */
    fun check(bytes: Long, head: ByteArray, w: Int, h: Int): Refusal? = when {
        bytes <= 0L -> Refusal.EMPTY
        bytes > MAX_SOURCE_BYTES -> Refusal.TOO_LARGE_FILE
        formatOf(head) == null -> Refusal.NOT_AN_IMAGE
        w <= 0 || h <= 0 || w > MAX_SOURCE_EDGE || h > MAX_SOURCE_EDGE -> Refusal.BAD_SIZE
        w.toLong() * h > MAX_SOURCE_PIXELS -> Refusal.TOO_MANY_PIXELS
        else -> null
    }

    /** The stored size of a [w] × [h] picture: scaled down to fit both limits, aspect kept, never up. */
    fun targetSize(w: Int, h: Int): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return 0 to 0
        val byEdge = MAX_EDGE.toDouble() / max(w, h)
        val byPixels = sqrt(MAX_PIXELS.toDouble() / (w.toDouble() * h))
        val s = minOf(1.0, byEdge, byPixels)
        if (s >= 1.0) return w to h
        return max(1, (w * s).roundToInt()).coerceAtMost(MAX_EDGE) to max(1, (h * s).roundToInt()).coerceAtMost(MAX_EDGE)
    }

    /**
     * The decoder's power-of-two sample size: the largest that still leaves at least the target
     * size, so the last step is a smooth scale and not a coarse one.
     */
    fun sampleSize(w: Int, h: Int, targetW: Int, targetH: Int): Int {
        var s = 1
        while (w / (s * 2) >= targetW && h / (s * 2) >= targetH && s < 1024) s *= 2
        return s
    }

    /** PNG keeps transparency; an opaque picture is stored as JPEG. */
    fun outputFormat(hasAlpha: Boolean): Format = if (hasAlpha) Format.PNG else Format.JPEG

    /** When [format]'s file is too large: PNG goes to lossy WebP, which keeps transparency; JPEG has no smaller form. */
    fun fallback(format: Format): Format? = if (format == Format.PNG) Format.WEBP else null

    /** Whether the stored file may go into the theme (a theme file entry has a size limit). */
    fun resultOk(bytes: Int): Boolean = bytes > 0 && bytes <= YtThemeZip.MAX_ENTRY_BYTES

    /** A safe asset name from the picked file's name, not one of [taken]. */
    fun assetName(displayName: String?, format: Format, taken: Collection<String>): String {
        val raw = (displayName ?: "image").substringAfterLast('/')
        val stem = raw.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_-]"), "_").trim('_').take(40).ifEmpty { "image" }
        var name = "$stem.${format.ext}"
        var i = 2
        while (name in taken) name = "${stem}_${i++}.${format.ext}"
        return name
    }
}
