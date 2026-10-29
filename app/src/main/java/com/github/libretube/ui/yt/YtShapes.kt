package com.github.libretube.ui.yt

import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * YT: the expressive shapes of the designs Pop, Clay and Formen: scalloped "cookies",
 * soft blobs and a wavy edge, drawn as plain paths so they also cast shadows.
 */

/** A circle with [lobes] soft scallops, like the Material 3 "cookie" shapes. */
fun CookieShape(lobes: Int, depth: Float = 0.07f): Shape = GenericShape { size, _ ->
    val center = Offset(size.width / 2f, size.height / 2f)
    val radius = minOf(size.width, size.height) / 2f
    val steps = lobes * 16
    for (i in 0..steps) {
        val angle = 2 * PI * i / steps
        val r = radius * (1 - depth + depth * cos(lobes * angle)).toFloat()
        val x = center.x + r * cos(angle - PI / 2).toFloat()
        val y = center.y + r * sin(angle - PI / 2).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/**
 * A soft, slightly irregular blob. [bulge] moves the four corner points in or out (fractions of
 * the size), so neighbouring blobs can lean against each other as in the Clay navigation.
 */
fun BlobShape(
    topStart: Float = 0.5f,
    topEnd: Float = 0.5f,
    bottomEnd: Float = 0.5f,
    bottomStart: Float = 0.5f,
): Shape = GenericShape { size, _ ->
    blob(size, topStart, topEnd, bottomEnd, bottomStart)
}

private fun Path.blob(size: Size, ts: Float, te: Float, be: Float, bs: Float) {
    val w = size.width
    val h = size.height
    // each corner is a cubic curve whose handle length follows the given roundness
    val r = minOf(w, h)
    val c1 = r * ts
    val c2 = r * te
    val c3 = r * be
    val c4 = r * bs
    moveTo(c1, 0f)
    lineTo(w - c2, 0f)
    cubicTo(w - c2 * 0.35f, 0f, w, c2 * 0.35f, w, c2)
    lineTo(w, h - c3)
    cubicTo(w, h - c3 * 0.35f, w - c3 * 0.35f, h, w - c3, h)
    lineTo(c4, h)
    cubicTo(c4 * 0.35f, h, 0f, h - c4 * 0.35f, 0f, h - c4)
    lineTo(0f, c1)
    cubicTo(0f, c1 * 0.35f, c1 * 0.35f, 0f, c1, 0f)
    close()
}

/** A bar whose top edge rises in a soft wave over each of [bumps] items (Pop navigation). */
fun WavyBarShape(bumps: Int, height: Float = 0.16f): Shape = GenericShape { size, _ ->
    val w = size.width
    val h = size.height
    val amp = h * height
    val seg = w / bumps
    moveTo(0f, amp)
    for (i in 0 until bumps) {
        val x0 = i * seg
        cubicTo(x0 + seg * 0.25f, -amp * 0.6f, x0 + seg * 0.75f, -amp * 0.6f, x0 + seg, amp)
    }
    lineTo(w, h - amp)
    for (i in bumps - 1 downTo 0) {
        val x1 = i * seg
        cubicTo(x1 + seg * 0.75f, h + amp * 0.6f, x1 + seg * 0.25f, h + amp * 0.6f, x1, h - amp)
    }
    close()
}

/**
 * The Pop feature shape: small corners except one large rounded top end corner, as on the
 * yellow card and its image. Both radii are fractions of the width.
 */
fun FeatureShape(smallFraction: Float, largeFraction: Float): Shape = GenericShape { size, _ ->
    val w = size.width
    val h = size.height
    val small = w * smallFraction
    val large = w * largeFraction
    moveTo(small, 0f)
    lineTo(w - large, 0f)
    cubicTo(w - large * 0.45f, 0f, w, large * 0.45f, w, large)
    lineTo(w, h - small)
    cubicTo(w, h - small * 0.45f, w - small * 0.45f, h, w - small, h)
    lineTo(small, h)
    cubicTo(small * 0.45f, h, 0f, h - small * 0.45f, 0f, h - small)
    lineTo(0f, small)
    cubicTo(0f, small * 0.45f, small * 0.45f, 0f, small, 0f)
    close()
}
