package com.github.libretube.ui.yt.builder

import kotlin.math.roundToInt

/**
 * YT: the presets of the animator's simple mode (pure). Each builds an ordinary animation (the
 * advanced mode and the timeline edit it further) from a [strength] (1 = the usual amount, 0.25
 * to 3) and a duration. Values are relative to the node's own look, so a preset works anywhere.
 */
object YtAnimPresets {
    const val MIN_STRENGTH = 0.25f
    const val MAX_STRENGTH = 3f

    /** The usual length of [p] in ms (springs settle in their own time and ignore it). */
    fun defaultMs(p: YtPreset): Int = when (p) {
        YtPreset.FADE_IN, YtPreset.FADE_OUT -> 260
        YtPreset.SLIDE_IN, YtPreset.SLIDE_OUT, YtPreset.SCALE_IN, YtPreset.SCALE_OUT -> 320
        YtPreset.BOUNCE -> 700
        YtPreset.SPRING -> 600
        YtPreset.POP -> 300
        YtPreset.PULSE -> 1200
        YtPreset.ROTATE -> 600
        YtPreset.SHAKE -> 450
        YtPreset.WOBBLE -> 600
        YtPreset.STRETCH, YtPreset.COMPRESS -> 360
        YtPreset.EXPAND, YtPreset.COLLAPSE -> 300
        YtPreset.FLOATING -> 2400
        YtPreset.GLOW_PULSE -> 1600
        YtPreset.COLOR_TRANSITION -> 400
        YtPreset.LOADING -> 900
        YtPreset.SHIMMER -> 1400
        YtPreset.STAGGER -> 320
    }

    /** The trigger a preset starts with (the user can change it). */
    fun defaultTrigger(p: YtPreset): YtTriggerKind = when (p) {
        YtPreset.FADE_IN, YtPreset.SLIDE_IN, YtPreset.SCALE_IN, YtPreset.FLOATING, YtPreset.GLOW_PULSE,
        YtPreset.LOADING, YtPreset.SHIMMER, YtPreset.STAGGER, YtPreset.PULSE -> YtTriggerKind.APPEAR
        else -> YtTriggerKind.TAP
    }

    /** A spring preset (physics) rather than keyframes over a duration. */
    fun isSpring(p: YtPreset): Boolean = p == YtPreset.SPRING

    /** What [build] makes: the animations and, for STAGGER, the group that spaces them. */
    data class Built(val anims: List<YtAnim>, val group: YtAnimGroup? = null)

    private fun k(at: Float, value: Float, curve: YtCurve = YtCurve.LINEAR) = YtKeyframe(at, value, curve = curve)
    private fun ck(at: Float, color: Int?, curve: YtCurve = YtCurve.LINEAR) = YtKeyframe(at, color = color, curve = curve)
    private fun track(prop: YtAnimProp, vararg keys: YtKeyframe) = YtTrack(prop, keys.toList())
    private fun r(v: Float) = (v * 100f).roundToInt() / 100f

    /**
     * The tracks of [p] at strength [s]. [accent]: the colour of the colour transition and the
     * glow. Keyframes are shares of the duration.
     */
    fun tracks(p: YtPreset, s: Float, accent: Int): List<YtTrack> {
        val e = YtCurve.STRONG_OUT
        val io = YtCurve.EASE_IN_OUT
        return when (p) {
            YtPreset.FADE_IN -> listOf(track(YtAnimProp.OPACITY, k(0f, 0f, YtCurve.EASE_OUT), k(1f, 1f)))
            YtPreset.FADE_OUT -> listOf(track(YtAnimProp.OPACITY, k(0f, 1f, YtCurve.EASE_IN), k(1f, 0f)))
            YtPreset.SLIDE_IN -> listOf(
                track(YtAnimProp.Y, k(0f, r(24f * s), e), k(1f, 0f)),
                track(YtAnimProp.OPACITY, k(0f, 0f, YtCurve.EASE_OUT), k(0.6f, 1f)),
            )
            YtPreset.SLIDE_OUT -> listOf(
                track(YtAnimProp.Y, k(0f, 0f, YtCurve.EASE_IN), k(1f, r(24f * s))),
                track(YtAnimProp.OPACITY, k(0.3f, 1f, YtCurve.EASE_IN), k(1f, 0f)),
            )
            YtPreset.SCALE_IN -> {
                val from = r((1f - 0.2f * s).coerceAtLeast(0f))
                listOf(
                    track(YtAnimProp.SCALE_X, k(0f, from, e), k(1f, 1f)),
                    track(YtAnimProp.SCALE_Y, k(0f, from, e), k(1f, 1f)),
                    track(YtAnimProp.OPACITY, k(0f, 0f, YtCurve.EASE_OUT), k(0.6f, 1f)),
                )
            }
            YtPreset.SCALE_OUT -> {
                val to = r((1f - 0.2f * s).coerceAtLeast(0f))
                listOf(
                    track(YtAnimProp.SCALE_X, k(0f, 1f, YtCurve.EASE_IN), k(1f, to)),
                    track(YtAnimProp.SCALE_Y, k(0f, 1f, YtCurve.EASE_IN), k(1f, to)),
                    track(YtAnimProp.OPACITY, k(0.3f, 1f, YtCurve.EASE_IN), k(1f, 0f)),
                )
            }
            YtPreset.BOUNCE -> listOf(
                track(
                    YtAnimProp.Y,
                    k(0f, 0f, YtCurve.EASE_OUT), k(0.3f, r(-16f * s), YtCurve.EASE_IN), k(0.55f, 0f, YtCurve.EASE_OUT),
                    k(0.75f, r(-6f * s), YtCurve.EASE_IN), k(0.9f, 0f, YtCurve.EASE_OUT), k(0.95f, r(-1.5f * s), YtCurve.EASE_IN), k(1f, 0f),
                ),
            )
            // physics: a push of the scale that the spring brings back (no jump at the start)
            YtPreset.SPRING -> listOf(
                YtTrack(YtAnimProp.SCALE_X, listOf(k(0f, 1f), k(1f, 1f)), velocity = r(-2.5f * s)),
                YtTrack(YtAnimProp.SCALE_Y, listOf(k(0f, 1f), k(1f, 1f)), velocity = r(-2.5f * s)),
            )
            YtPreset.POP -> {
                val top = r(1f + 0.12f * s)
                val low = r(1f - 0.03f * s)
                listOf(
                    track(YtAnimProp.SCALE_X, k(0f, 1f, YtCurve.EASE_OUT), k(0.4f, top, io), k(0.75f, low, io), k(1f, 1f)),
                    track(YtAnimProp.SCALE_Y, k(0f, 1f, YtCurve.EASE_OUT), k(0.4f, top, io), k(0.75f, low, io), k(1f, 1f)),
                )
            }
            YtPreset.PULSE -> {
                val top = r(1f + 0.06f * s)
                listOf(
                    track(YtAnimProp.SCALE_X, k(0f, 1f, io), k(0.5f, top, io), k(1f, 1f)),
                    track(YtAnimProp.SCALE_Y, k(0f, 1f, io), k(0.5f, top, io), k(1f, 1f)),
                )
            }
            YtPreset.ROTATE -> listOf(track(YtAnimProp.ROTATION, k(0f, 0f, io), k(1f, r(360f * s))))
            YtPreset.SHAKE -> listOf(
                track(
                    YtAnimProp.X,
                    k(0f, 0f), k(0.15f, r(-8f * s)), k(0.3f, r(8f * s)), k(0.45f, r(-6f * s)), k(0.6f, r(6f * s)),
                    k(0.75f, r(-3f * s)), k(0.9f, r(3f * s)), k(1f, 0f),
                ),
            )
            YtPreset.WOBBLE -> listOf(
                track(
                    YtAnimProp.ROTATION,
                    k(0f, 0f, io), k(0.2f, r(-6f * s), io), k(0.4f, r(5f * s), io), k(0.6f, r(-4f * s), io), k(0.8f, r(2f * s), io), k(1f, 0f),
                ),
            )
            YtPreset.STRETCH -> listOf(
                track(YtAnimProp.SCALE_X, k(0f, 1f, io), k(0.5f, r(1f + 0.2f * s), io), k(1f, 1f)),
                track(YtAnimProp.SCALE_Y, k(0f, 1f, io), k(0.5f, r(1f - 0.1f * s), io), k(1f, 1f)),
            )
            YtPreset.COMPRESS -> listOf(
                track(YtAnimProp.SCALE_Y, k(0f, 1f, io), k(0.5f, r((1f - 0.2f * s).coerceAtLeast(0f)), io), k(1f, 1f)),
                track(YtAnimProp.SCALE_X, k(0f, 1f, io), k(0.5f, r(1f + 0.06f * s), io), k(1f, 1f)),
            )
            YtPreset.EXPAND -> listOf(track(YtAnimProp.HEIGHT, k(0f, 0f, e), k(1f, r(24f * s))))
            YtPreset.COLLAPSE -> listOf(track(YtAnimProp.HEIGHT, k(0f, 0f, e), k(1f, r(-24f * s))))
            YtPreset.FLOATING -> listOf(track(YtAnimProp.Y, k(0f, 0f, io), k(0.5f, r(-6f * s), io), k(1f, 0f)))
            YtPreset.GLOW_PULSE -> listOf(track(YtAnimProp.GLOW, k(0f, 0f, io), k(0.5f, r(12f * s), io), k(1f, 0f)))
            YtPreset.COLOR_TRANSITION -> listOf(YtTrack(YtAnimProp.FILL_COLOR, listOf(ck(0f, null, io), ck(1f, accent))))
            YtPreset.LOADING -> listOf(track(YtAnimProp.ROTATION, k(0f, 0f), k(1f, 360f)))
            YtPreset.SHIMMER -> listOf(track(YtAnimProp.SHIMMER, k(0f, 0f), k(1f, 1f)))
            YtPreset.STAGGER -> listOf(
                track(YtAnimProp.Y, k(0f, r(12f * s), e), k(1f, 0f)),
                track(YtAnimProp.OPACITY, k(0f, 0f, YtCurve.EASE_OUT), k(0.7f, 1f)),
            )
        }
    }

    private fun repeatOf(p: YtPreset) = when (p) {
        YtPreset.PULSE, YtPreset.FLOATING, YtPreset.GLOW_PULSE, YtPreset.LOADING, YtPreset.SHIMMER -> -1
        else -> 0
    }

    /**
     * The animation(s) of [p] for [node] of [component]. STAGGER on a node with [children]
     * makes one animation per child in a new STAGGER group; [newId] gives fresh ids.
     */
    fun build(
        p: YtPreset,
        component: YtComponentKind,
        node: String,
        strength: Float = 1f,
        durationMs: Int = defaultMs(p),
        accent: Int = 0xFF3EA6FF.toInt(),
        children: List<String> = emptyList(),
        name: String = p.name,
        newId: () -> String,
    ): Built {
        val s = strength.coerceIn(MIN_STRENGTH, MAX_STRENGTH)
        fun one(target: String, group: String? = null) = YtAnim(
            id = newId(),
            name = name,
            component = component,
            node = target,
            trigger = YtTrigger(defaultTrigger(p)),
            kind = if (isSpring(p)) YtAnimKind.SPRING else YtAnimKind.TIME,
            durationMs = durationMs.coerceIn(0, YtAnimValidate.MAX_MS),
            spring = if (isSpring(p)) YtSpringSpec(stiffness = 380f, damping = 0.32f) else YtSpringSpec(),
            repeat = repeatOf(p),
            tracks = tracks(p, s, accent),
            group = group,
            preset = p,
            strength = s,
        )
        if (p == YtPreset.STAGGER && children.isNotEmpty()) {
            val g = YtAnimGroup(newId(), name, YtGroupMode.STAGGER, (60f * s).roundToInt().coerceIn(10, 400))
            return Built(children.map { one(it, g.id) }, g)
        }
        return Built(listOf(one(node)))
    }

    /** [a] made again from its preset with a new [strength] and [durationMs], keeping trigger, group and id. */
    fun retune(a: YtAnim, strength: Float, durationMs: Int, accent: Int): YtAnim {
        val p = a.preset ?: return a.copy(durationMs = durationMs)
        val s = strength.coerceIn(MIN_STRENGTH, MAX_STRENGTH)
        return a.copy(tracks = tracks(p, s, accent), strength = s, durationMs = durationMs.coerceIn(0, YtAnimValidate.MAX_MS))
    }
}
