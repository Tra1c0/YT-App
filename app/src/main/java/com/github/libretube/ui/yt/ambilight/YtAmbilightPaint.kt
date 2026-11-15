package com.github.libretube.ui.yt.ambilight

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import java.nio.ByteBuffer
import kotlin.math.min
import kotlin.math.roundToInt
import com.github.libretube.ui.yt.ambilight.YtAmbilightZones as Z

/**
 * YT: how the ambilight is drawn. Every glow is the same soft blob (a radial falloff in a 64 px
 * alpha-only bitmap), stretched to an ellipse and tinted by the paint's colour; overlapping
 * blobs of neighbouring zones blend into one another like light on a wall. Nothing is
 * allocated while drawing: one paint, one rect, one bitmap made once.
 *
 * Each place takes its strength from the design's profile, the strength setting, the zone's
 * presence and the beat, never more than the zone's contrast cap (YtAmbilight.pageCap/barCap),
 * so text over a glow keeps its contrast. Main thread (draw passes).
 */
internal object YtAmbilightPaint {
    private const val SIZE = 64

    private val blob: Bitmap by lazy { makeBlob() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val rect = RectF()

    /** One soft blob centred at [cx], [cy] with radii [rx], [ry]. */
    private fun blob(canvas: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, color: Int, alpha: Float) {
        if (alpha <= 0.004f || rx <= 0f || ry <= 0f) return
        // an alpha-only bitmap is drawn in the paint's colour
        paint.color = (color and 0xFFFFFF) or ((alpha * 255f).roundToInt().coerceIn(0, 255) shl 24)
        rect.set(cx - rx, cy - ry, cx + rx, cy + ry)
        canvas.drawBitmap(blob, null, rect, paint)
    }

    /** The alpha of zone [zone]'s glow at strength [base] on the page or screens. */
    private fun pageAlpha(zone: Int, base: Float): Float {
        val e = YtAmbilight
        return min(base * e.settings.intensity * e.presence[zone] * e.beatGain, e.pageCap[zone])
    }

    private fun barAlpha(zone: Int, base: Float): Float {
        val e = YtAmbilight
        return min(base * e.settings.intensity * e.presence[zone] * e.beatGain, e.barCap[zone])
    }

    /**
     * The video page, at the top of the area below the player ([width] x [height], the visible
     * part): the bottom edge of the picture spills down onto the page, its sides down the
     * page's edges; with "Leiste & Hintergrund" the whole page takes a faint wash first.
     * True if anything was drawn.
     */
    fun page(canvas: Canvas, width: Int, height: Int): Boolean {
        val e = YtAmbilight
        if (!e.showing || width <= 0 || height <= 0) return false
        val p = e.profile
        val c = e.pageColor
        val w = width.toFloat()
        if (e.settings.chrome && p.wash > 0f) {
            val a = pageAlpha(Z.ALL, p.wash)
            if (a > 0.004f) canvas.drawColor((c[Z.ALL] and 0xFFFFFF) or ((a * 255f).roundToInt() shl 24))
        }
        val reach = min(height.toFloat(), w * 0.62f * p.spread)
        val rx = w * 0.55f * p.spread
        blob(canvas, 0f, 0f, rx, reach, c[Z.BOTTOM_LEFT], pageAlpha(Z.BOTTOM_LEFT, p.page))
        blob(canvas, w / 2f, 0f, rx, reach, c[Z.BOTTOM], pageAlpha(Z.BOTTOM, p.page))
        blob(canvas, w, 0f, rx, reach, c[Z.BOTTOM_RIGHT], pageAlpha(Z.BOTTOM_RIGHT, p.page))
        val side = w * 0.16f
        blob(canvas, 0f, reach * 0.3f, side, reach * 1.15f, c[Z.LEFT], pageAlpha(Z.LEFT, p.page * 0.8f))
        blob(canvas, w, reach * 0.3f, side, reach * 1.15f, c[Z.RIGHT], pageAlpha(Z.RIGHT, p.page * 0.8f))
        return true
    }

    /**
     * Letterbox or pillarbox bars of the player ([width] x [height]) around the picture
     * [video]: each bar glows in the colours of the picture's edge beside it. The picture
     * itself is clipped out (the surface covers it anyway). True if a bar was drawn.
     */
    fun bars(canvas: Canvas, width: Int, height: Int, video: RectF, minBar: Float): Boolean {
        val e = YtAmbilight
        if (!e.showing || !e.settings.bars) return false
        val left = video.left
        val right = width - video.right
        val top = video.top
        val bottom = height - video.bottom
        if (left < minBar && right < minBar && top < minBar && bottom < minBar) return false
        val c = e.barColor
        val vw = video.width()
        val vh = video.height()
        val base = BARS
        val save = canvas.save()
        canvas.clipOutRect(video)
        if (left >= minBar || right >= minBar) {
            val ry = vh * 0.38f
            val rl = left * 1.05f
            val rr = right * 1.05f
            blob(canvas, video.left, video.top + vh * 0.18f, rl, ry, c[Z.TOP_LEFT], barAlpha(Z.TOP_LEFT, base))
            blob(canvas, video.left, video.centerY(), rl, ry, c[Z.LEFT], barAlpha(Z.LEFT, base))
            blob(canvas, video.left, video.bottom - vh * 0.18f, rl, ry, c[Z.BOTTOM_LEFT], barAlpha(Z.BOTTOM_LEFT, base))
            blob(canvas, video.right, video.top + vh * 0.18f, rr, ry, c[Z.TOP_RIGHT], barAlpha(Z.TOP_RIGHT, base))
            blob(canvas, video.right, video.centerY(), rr, ry, c[Z.RIGHT], barAlpha(Z.RIGHT, base))
            blob(canvas, video.right, video.bottom - vh * 0.18f, rr, ry, c[Z.BOTTOM_RIGHT], barAlpha(Z.BOTTOM_RIGHT, base))
        }
        if (top >= minBar || bottom >= minBar) {
            val rx = vw * 0.38f
            val rt = top * 1.05f
            val rb = bottom * 1.05f
            blob(canvas, video.left + vw * 0.18f, video.top, rx, rt, c[Z.TOP_LEFT], barAlpha(Z.TOP_LEFT, base))
            blob(canvas, video.centerX(), video.top, rx, rt, c[Z.TOP], barAlpha(Z.TOP, base))
            blob(canvas, video.right - vw * 0.18f, video.top, rx, rt, c[Z.TOP_RIGHT], barAlpha(Z.TOP_RIGHT, base))
            blob(canvas, video.left + vw * 0.18f, video.bottom, rx, rb, c[Z.BOTTOM_LEFT], barAlpha(Z.BOTTOM_LEFT, base))
            blob(canvas, video.centerX(), video.bottom, rx, rb, c[Z.BOTTOM], barAlpha(Z.BOTTOM, base))
            blob(canvas, video.right - vw * 0.18f, video.bottom, rx, rb, c[Z.BOTTOM_RIGHT], barAlpha(Z.BOTTOM_RIGHT, base))
        }
        canvas.restoreToCount(save)
        return true
    }

    /**
     * Around the docked mini player [card] (the card is clipped out: nothing over the picture):
     * each side glows in the colour of the picture's edge on that side, the corners in theirs.
     * [fade] 0..1 lets it come in as the player docks.
     */
    fun halo(canvas: Canvas, card: RectF, density: Float, fade: Float) {
        val e = YtAmbilight
        val p = e.profile
        if (!e.showing || p.halo <= 0f || fade <= 0f) return
        val c = e.pageColor
        val r = p.haloDp * density * p.spread
        val base = p.halo * fade
        val along = card.width() * 0.55f
        blob(canvas, card.centerX(), card.top, along, r, c[Z.TOP], pageAlpha(Z.TOP, base))
        blob(canvas, card.centerX(), card.bottom, along, r, c[Z.BOTTOM], pageAlpha(Z.BOTTOM, base))
        blob(canvas, card.left, card.centerY(), r, card.height() * 0.8f, c[Z.LEFT], pageAlpha(Z.LEFT, base))
        blob(canvas, card.right, card.centerY(), r, card.height() * 0.8f, c[Z.RIGHT], pageAlpha(Z.RIGHT, base))
        val corner = r * 1.2f
        blob(canvas, card.left, card.top, corner, corner, c[Z.TOP_LEFT], pageAlpha(Z.TOP_LEFT, base))
        blob(canvas, card.right, card.top, corner, corner, c[Z.TOP_RIGHT], pageAlpha(Z.TOP_RIGHT, base))
        blob(canvas, card.left, card.bottom, corner, corner, c[Z.BOTTOM_LEFT], pageAlpha(Z.BOTTOM_LEFT, base))
        blob(canvas, card.right, card.bottom, corner, corner, c[Z.BOTTOM_RIGHT], pageAlpha(Z.BOTTOM_RIGHT, base))
    }

    /**
     * The screens' edges while a video is docked ("Leiste & Hintergrund"): a glow from the top
     * (behind the header, in the colours of the picture's top edge) and along both sides.
     */
    fun edges(canvas: Canvas, width: Int, height: Int, density: Float, fade: Float) {
        val e = YtAmbilight
        val p = e.profile
        if (!e.showing || p.edges <= 0f || fade <= 0f) return
        val c = e.pageColor
        val w = width.toFloat()
        val h = height.toFloat()
        val base = p.edges * fade
        val reach = 120f * density * p.spread
        blob(canvas, 0f, 0f, w * 0.5f, reach, c[Z.TOP_LEFT], pageAlpha(Z.TOP_LEFT, base))
        blob(canvas, w / 2f, 0f, w * 0.5f, reach, c[Z.TOP], pageAlpha(Z.TOP, base))
        blob(canvas, w, 0f, w * 0.5f, reach, c[Z.TOP_RIGHT], pageAlpha(Z.TOP_RIGHT, base))
        val side = 22f * density * p.spread
        val ry = h * 0.3f
        val sideBase = base * 0.8f
        blob(canvas, 0f, h * 0.22f, side, ry, c[Z.TOP_LEFT], pageAlpha(Z.TOP_LEFT, sideBase))
        blob(canvas, 0f, h * 0.5f, side, ry, c[Z.LEFT], pageAlpha(Z.LEFT, sideBase))
        blob(canvas, 0f, h * 0.78f, side, ry, c[Z.BOTTOM_LEFT], pageAlpha(Z.BOTTOM_LEFT, sideBase))
        blob(canvas, w, h * 0.22f, side, ry, c[Z.TOP_RIGHT], pageAlpha(Z.TOP_RIGHT, sideBase))
        blob(canvas, w, h * 0.5f, side, ry, c[Z.RIGHT], pageAlpha(Z.RIGHT, sideBase))
        blob(canvas, w, h * 0.78f, side, ry, c[Z.BOTTOM_RIGHT], pageAlpha(Z.BOTTOM_RIGHT, sideBase))
    }

    /**
     * The nav bar [bar] ("Leiste & Hintergrund"): its top edge glows in the colours of the
     * picture's bottom edge; a glass bar also takes a faint tint of the whole picture.
     */
    fun nav(canvas: Canvas, bar: RectF, density: Float, fade: Float) {
        val e = YtAmbilight
        val p = e.profile
        if (!e.showing || fade <= 0f || bar.width() <= 0f) return
        val c = e.pageColor
        if (p.glass) {
            blob(canvas, bar.centerX(), bar.centerY(), bar.width() * 0.62f, bar.height() * 0.95f, c[Z.ALL], pageAlpha(Z.ALL, GLASS_TINT * fade))
        }
        if (p.nav <= 0f) return
        val base = p.nav * fade
        val ry = 26f * density * p.spread
        val rx = bar.width() * 0.45f
        blob(canvas, bar.left, bar.top, rx, ry, c[Z.BOTTOM_LEFT], pageAlpha(Z.BOTTOM_LEFT, base))
        blob(canvas, bar.centerX(), bar.top, rx, ry, c[Z.BOTTOM], pageAlpha(Z.BOTTOM, base))
        blob(canvas, bar.right, bar.top, rx, ry, c[Z.BOTTOM_RIGHT], pageAlpha(Z.BOTTOM_RIGHT, base))
    }

    /** The strength of the bars' glow (no text lies there but the player's controls). */
    private const val BARS = 0.75f

    /** The tint of a glass bar (faint: its icons and labels lie on it). */
    private const val GLASS_TINT = 0.16f

    /** A radial falloff, (1 - d²)², soft at the centre and gone at the rim. */
    private fun makeBlob(): Bitmap {
        val bytes = ByteArray(SIZE * SIZE)
        val half = (SIZE - 1) / 2f
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val dx = (x - half) / half
                val dy = (y - half) / half
                val d2 = (dx * dx + dy * dy).coerceAtMost(1f)
                val f = (1f - d2) * (1f - d2)
                bytes[y * SIZE + x] = (f * 255f).roundToInt().coerceIn(0, 255).toByte()
            }
        }
        return Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ALPHA_8).apply {
            copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
        }
    }
}
