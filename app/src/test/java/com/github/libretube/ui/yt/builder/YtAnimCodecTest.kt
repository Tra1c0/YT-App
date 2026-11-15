package com.github.libretube.ui.yt.builder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YtAnimCodecTest {
    private fun ok(text: String): YtThemeCodec.Read.Ok {
        val r = YtThemeCodec.decode(text)
        assertTrue(r.toString(), r is YtThemeCodec.Read.Ok)
        return r as YtThemeCodec.Read.Ok
    }

    private fun animated(): YtThemeDoc {
        val doc = testDoc()
        val card = doc.components.card.id
        val chip = doc.components.chip.id
        val ids = YtAnimOps.ids(doc.animations)
        var d = doc
        d = YtAnimOps.add(d, YtAnimPresets.build(YtPreset.POP, YtComponentKind.CARD, card, 1.3f, 280, newId = ids))
        d = YtAnimOps.add(d, YtAnimPresets.build(YtPreset.SPRING, YtComponentKind.CHIP, chip, newId = ids))
        d = YtAnimOps.add(d, YtAnimPresets.build(YtPreset.COLOR_TRANSITION, YtComponentKind.CHIP, chip, accent = 0xFFFF3366.toInt(), newId = ids))
        d = YtAnimOps.add(d, YtAnimPresets.build(YtPreset.STAGGER, YtComponentKind.CARD, card, children = doc.components.card.children.map { it.id }, newId = ids))
        return d.copy(
            animations = d.animations.copy(
                customStates = listOf("glow"),
                rules = listOf(YtStateRule(YtComponentKind.CARD, YtTriggerKind.LONG_PRESS, "glow")),
                easings = listOf(YtEasingPreset("snappy", 0.2f, 1.4f, 0.4f, 1f)),
                items = d.animations.items + YtAnim(
                    "t1", component = YtComponentKind.CARD, node = card, trigger = YtTrigger(YtTriggerKind.STATE, "*", "glow"),
                    tracks = listOf(YtTrack(YtAnimProp.GLOW, listOf(YtKeyframe(0f, 0f, curve = YtCurve.bezier(0.2f, 1.4f, 0.4f, 1f, "snappy")), YtKeyframe(1f, 8f)))),
                ),
            ),
        )
    }

    @Test
    fun `animations survive a round trip exactly`() {
        val doc = animated()
        val back = ok(YtThemeCodec.encode(doc))
        assertTrue(back.issues.toString(), back.issues.isEmpty())
        assertEquals(doc.animations, back.doc.animations)
        // colours of keyframes are hex, own colours stay null
        val text = YtThemeCodec.encode(doc)
        assertTrue(text.contains("\"#FFFF3366\""))
    }

    @Test
    fun `saved animations play identically after a restart`() {
        val doc = animated()
        val restored = ok(YtThemeCodec.encode(doc)).doc
        fun trace(d: YtThemeDoc): List<Float> {
            val out = ArrayList<Float>()
            for (kind in YtComponentKind.entries) {
                val c = YtAnimController(YtAnimIndex(d.animations, kind), YtAnimEngine { _, _ -> 0xFF202020.toInt() })
                c.event(YtTriggerKind.TAP)
                c.event(YtTriggerKind.APPEAR)
                c.event(YtTriggerKind.LONG_PRESS)
                repeat(40) {
                    c.engine.advance(1000.0 / 120)
                    for (a in d.animations.items) for (t in a.tracks) {
                        val n = c.engine.nodeOrNull(a.node) ?: continue
                        out += if (t.prop.colour) n.argb(t.prop, 0).toFloat() else n.float(t.prop)
                    }
                }
            }
            return out
        }
        val a = trace(doc)
        assertTrue(a.isNotEmpty())
        assertEquals(a, trace(restored))
    }

    @Test
    fun `broken animation values are repaired on reading`() {
        val doc = animated()
        val bad = doc.animations.items.first().let { a ->
            a.copy(
                durationMs = 9_999_999, repeat = -50, delayMs = -3,
                spring = YtSpringSpec(-1f, -5f),
                tracks = a.tracks.map { t -> t.copy(keys = t.keys.reversed().map { it.copy(at = it.at * 3f, value = 1e9f) }) },
            )
        }
        val orphan = bad.copy(id = "orphan", node = "gone")
        val dup = bad.copy(name = "dup")
        val wrongState = bad.copy(id = "ws", trigger = YtTrigger(YtTriggerKind.STATE, "nope", "glow"))
        val d = doc.copy(animations = doc.animations.copy(items = listOf(bad, orphan, dup, wrongState) + doc.animations.items.drop(1), customStates = listOf("glow", "idle", "bad name!")))
        val back = ok(YtThemeCodec.encode(d))
        val a = back.doc.animations
        assertTrue(back.issues.isNotEmpty())
        assertTrue(a.items.none { it.id == "orphan" })
        assertEquals(1, a.items.count { it.id == bad.id })
        assertEquals(listOf("glow"), a.customStates)
        val fixed = a.items.first { it.id == bad.id }
        assertEquals(YtAnimValidate.MAX_MS, fixed.durationMs)
        assertEquals(-1, fixed.repeat)
        assertEquals(0, fixed.delayMs)
        assertTrue(fixed.spring.stiffness >= 1f && fixed.spring.damping.isFinite())
        for (t in fixed.tracks) {
            assertTrue(t.keys.zipWithNext().all { (x, y) -> x.at <= y.at })
            assertTrue(t.keys.all { it.at in 0f..1f && it.value <= t.prop.max })
        }
        assertEquals(YtStateNames.ANY, a.items.first { it.id == "ws" }.trigger.from)
        // values that JSON cannot even hold are repaired as well
        val nan = YtAnimValidate.clamp(YtAnimations(listOf(bad.copy(spring = YtSpringSpec(Float.NaN, Float.POSITIVE_INFINITY)))), mapOf(bad.component to setOf(bad.node))) { _, _ -> }
        assertEquals(YtSpringSpec(400f, 0.6f), nan.items.single().spring)
    }

    @Test
    fun `deleting a node drops its animations`() {
        val doc = animated()
        val child = doc.components.card.children.first().id
        val cut = doc.copy(components = doc.components.with(YtComponentKind.CARD, YtDocOps.remove(doc.components.card, child)))
        val tidy = YtAnimOps.withoutNodes(cut)
        assertTrue(tidy.animations.items.none { it.node == child })
        assertTrue(tidy.animations.items.size < doc.animations.items.size)
    }
}
