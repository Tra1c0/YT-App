package com.github.libretube.ui.yt.mini

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * YT: the Side mini player ("Gesprächsblase") in numbers: the post card its long press grows
 * into, where the avatar and the drop badge sit on the bubble and the curves of its motion
 * (the bubble rising out of the bar, the avatar popping in, the heartbeat of a drop). Pure (px,
 * no Android), so the View constraints, the composables and the JVM tests read the same numbers.
 */

/**
 * The post card of the preview, in px of the grown box, left to right, from its top: the picture
 * across the card, the byline under it (avatar, channel and two lines of title, the play disc at
 * its end), the next video as a smaller reply bubble at the card's end under it.
 */
data class YtMiniSideCard(
    /** What the host and the shared parts read (picture, title, pill, next). */
    val geometry: YtMiniPeekGeometry,
    /** The avatar of the byline. */
    val avatar: YtMiniBox,
    /** Channel and title beside the avatar. */
    val text: YtMiniBox,
    /** The line the bubble's foot curves into the bar at (the joint). */
    val joint: Float,
)

object YtMiniSideLayout {
    /** How much the preview enlarges the picture's height. */
    const val GROWTH = 1.8f

    /** From the grown box's top to the picture, and the picture's inset from the bubble's sides. */
    const val TOP = 10
    const val PAD = 8

    /** The byline under the picture: its gap to the picture, its height, the avatar in it. */
    const val BYLINE_GAP = 8
    const val BYLINE = 50
    const val CARD_AVATAR = 30
    const val AVATAR_GAP = 10

    /** The apricot disc behind the play button, in dp (its ring 4 dp outside it). */
    const val DISC = 34

    /** The play disc's hit area at the byline's end, and the gap of the text before it. */
    const val PILL = 44
    const val PILL_GAP = 8

    /** The reply bubble of the next video: gap to the byline, height, share of the card's width. */
    const val NEXT_GAP = 6
    const val NEXT = 44
    const val NEXT_SHARE = 0.62f

    /** From the lowest part of the card to the joint with the bar. */
    const val FOOT = 8

    /** The docked avatar: its size, its ring and how far its centre sits from the bubble's corner. */
    const val AVATAR = 24
    const val RING = 1.5f
    const val CORNER = 1f

    /** The drop badge ("+1") on the bubble's other top corner. */
    const val BADGE = 11

    /**
     * The card of [spec] in a box [boxWidth] px wide ([density] px per dp), the next bubble only
     * with [hasNext]. The finger that held the docked picture (at the bubble's start) never lies
     * on the next bubble, which keeps to the card's end, nor on the play disc above it.
     */
    fun card(spec: YtMiniSpec, boxWidth: Float, density: Float, hasNext: Boolean): YtMiniSideCard {
        val d = density
        val left = spec.body * d + PAD * d
        val right = max(boxWidth - spec.body * d - PAD * d, left + 1f)
        val inner = right - left
        // across the card, a wide cut of the picture; never taller than 16:9 on narrow screens
        val height = min(spec.thumbHeight * GROWTH * d, inner * 9f / 16f)
        val player = YtMiniBox(left, TOP * d, right, TOP * d + height)

        val bylineTop = player.bottom + BYLINE_GAP * d
        val bylineBottom = bylineTop + BYLINE * d
        val avatarSize = CARD_AVATAR * d
        val avatar = YtMiniBox(left, bylineTop + 2f * d, left + avatarSize, bylineTop + 2f * d + avatarSize)
        val pillSize = PILL * d
        val pillTop = bylineTop + (BYLINE * d - pillSize) / 2f
        val pill = YtMiniBox(right - pillSize, pillTop, right, pillTop + pillSize)
        val textLeft = avatar.right + AVATAR_GAP * d
        val text = YtMiniBox(textLeft, bylineTop, max(pill.left - PILL_GAP * d, textLeft), bylineBottom)

        val next = if (hasNext) {
            val top = bylineBottom + NEXT_GAP * d
            YtMiniBox(right - inner * NEXT_SHARE, top, right, top + NEXT * d)
        } else {
            null
        }
        val joint = (next?.bottom ?: bylineBottom) + FOOT * d
        val rest = (spec.height + spec.overlap) * d
        val box = max(rest, joint + spec.overlap * d)
        return YtMiniSideCard(YtMiniPeekGeometry(box, player, text, pill, next), avatar, text, joint)
    }

    /** The centre of the docked avatar, on the bubble's top start corner (px of the docked box). */
    fun avatarCenter(spec: YtMiniSpec, density: Float): Pair<Float, Float> =
        (spec.body + CORNER) * density to CORNER * density

    /** The centre of the drop badge, on the bubble's top end corner. */
    fun badgeCenter(spec: YtMiniSpec, boxWidth: Float, density: Float): Pair<Float, Float> =
        boxWidth - (spec.body + CORNER) * density to CORNER * density

    /** 0 below [from], 1 above [to], smooth between. */
    fun smooth(value: Float, from: Float, to: Float): Float {
        if (to <= from) return if (value >= to) 1f else 0f
        val t = ((value - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** How far the bubble has risen out of the bar at minimize [progress], 0 to 1. */
    fun rise(progress: Float): Float = smooth(progress, 0.45f, 1f)

    /** How much of the docked avatar shows at minimize [progress]: it comes last. */
    fun avatarGate(progress: Float): Float = smooth(progress, 0.88f, 1f)

    /** An ease out that swings [overshoot] past 1 and settles (0 at 0, 1 at 1). */
    fun backOut(t: Float, overshoot: Float = 1.7f): Float {
        val u = t.coerceIn(0f, 1f) - 1f
        return 1f + (overshoot + 1f) * u * u * u + overshoot * u * u
    }

    /** The avatar pops in after the landing: nothing for 140 ms, then a small overshoot. */
    const val POP_DELAY = 140f
    const val POP = 360f

    fun avatarPop(ms: Float): Float = if (ms <= POP_DELAY) 0f else backOut((ms - POP_DELAY) / POP, 1.6f)

    /**
     * The heartbeat of the bubble at [ms] after a drop: a small beat, then 120 ms later a bigger
     * one (the haptic is TICK then CLICK at +120 ms), as an added scale (0 at rest).
     */
    fun heartbeat(ms: Float): Float = 0.025f * beat(ms, 0f, 110f) + 0.05f * beat(ms, 120f, 260f)

    const val HEARTBEAT = 400L

    private fun beat(ms: Float, start: Float, length: Float): Float {
        if (ms <= start || ms >= start + length) return 0f
        return sin(PI.toFloat() * (ms - start) / length)
    }

    /**
     * The timeline of an accepted drop ([t] 0 to 1 over [DROP_MS]): the badge's scale (it pops
     * with an overshoot, holds and shrinks away), the ring and sparks around it (0 to 1, gone
     * after), the label bubble's presence.
     */
    const val DROP_MS = 1800

    fun badgeScale(t: Float): Float = when {
        t <= 0f || t >= 1f -> 0f
        t < 0.2f -> backOut(t / 0.2f, 2.2f)
        t < 0.86f -> 1f
        else -> 1f - smooth(t, 0.86f, 0.97f)
    }

    fun burst(t: Float): Float = if (t <= 0f) 0f else (t / 0.32f).coerceAtMost(1f)

    fun label(t: Float): Float = when {
        t <= 0f || t >= 1f -> 0f
        t < 0.5f -> smooth(t, 0.08f, 0.18f)
        else -> 1f - smooth(t, 0.86f, 0.98f)
    }

    /** A moment of the drop's timeline where everything stands still (no motion: shown as is). */
    const val DROP_STILL = 0.5f
}
