package com.github.libretube.ui.yt.builder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtAnimControlTest {
    private val kind = YtComponentKind.CHIP

    private fun anim(id: String, trigger: YtTrigger, prop: YtAnimProp = YtAnimProp.SCALE_X, to: Float = 1.2f, ms: Int = 100, group: String? = null, delay: Int = 0) =
        YtAnim(id, component = kind, node = "n", trigger = trigger, durationMs = ms, delayMs = delay, group = group, tracks = listOf(YtTrack(prop, listOf(YtKeyframe(0f, prop.rest), YtKeyframe(1f, to)))))

    private fun state(from: String, to: String) = YtTrigger(YtTriggerKind.STATE, from, to)

    @Test
    fun `the shown state follows the priority`() {
        val m = YtStateMachine(listOf("glow"))
        assertEquals(YtStateNames.IDLE to YtStateNames.SELECTED, m.set(YtStateNames.SELECTED, true))
        assertEquals(YtStateNames.SELECTED to YtStateNames.PRESSED, m.set(YtStateNames.PRESSED, true))
        // loading outranks pressed
        assertEquals(YtStateNames.PRESSED to YtStateNames.LOADING, m.set(YtStateNames.LOADING, true))
        // pressed going off while loading shows: no transition
        assertNull(m.set(YtStateNames.PRESSED, false))
        assertEquals(YtStateNames.LOADING to YtStateNames.SELECTED, m.set(YtStateNames.LOADING, false))
        assertNull(m.set(YtStateNames.SELECTED, true))
        m.set(YtStateNames.SELECTED, false)
        assertEquals(YtStateNames.IDLE to "glow", m.apply(YtStateAction.TOGGLE, "glow"))
        assertEquals("glow" to YtStateNames.IDLE, m.apply(YtStateAction.TOGGLE, "glow"))
        assertNull(m.apply(YtStateAction.CLEAR, "glow"))
    }

    @Test
    fun `the best matching transition plays`() {
        val list = listOf(
            anim("any", state("*", "*")),
            anim("toPressed", state("*", YtStateNames.PRESSED)),
            anim("exact", state(YtStateNames.IDLE, YtStateNames.PRESSED)),
            anim("exact2", state(YtStateNames.IDLE, YtStateNames.PRESSED), prop = YtAnimProp.OPACITY, to = 0.5f),
            anim("fromPressed", state(YtStateNames.PRESSED, "*")),
        )
        val index = YtAnimIndex(YtAnimations(list), kind)
        assertEquals(listOf("exact", "exact2"), index.transitions(YtStateNames.IDLE, YtStateNames.PRESSED).map { it.id })
        assertEquals(listOf("toPressed"), index.transitions(YtStateNames.SELECTED, YtStateNames.PRESSED).map { it.id })
        assertEquals(listOf("fromPressed"), index.transitions(YtStateNames.PRESSED, YtStateNames.IDLE).map { it.id })
        assertEquals(listOf("any"), index.transitions(YtStateNames.IDLE, YtStateNames.SELECTED).map { it.id })
    }

    @Test
    fun `press and release play their transitions without a jump`() {
        val down = anim("down", state("*", YtStateNames.PRESSED), to = 0.9f, ms = 200)
        val up = YtAnim("up", component = kind, node = "n", trigger = state(YtStateNames.PRESSED, "*"), durationMs = 200, tracks = listOf(YtTrack(YtAnimProp.SCALE_X, listOf(YtKeyframe(0f, 0.9f), YtKeyframe(1f, 1f)))))
        val c = YtAnimController(YtAnimIndex(YtAnimations(listOf(down, up)), kind), YtAnimEngine())
        assertTrue(c.index.touch && c.index.ownsPress)
        c.state(YtStateNames.PRESSED, true)
        c.engine.advance(50.0)
        val mid = c.engine.node("n").float(YtAnimProp.SCALE_X)
        assertEquals(0.975f, mid, 1e-4f)
        // released early: the way back starts at 0.975, not at 0.9
        c.state(YtStateNames.PRESSED, false)
        assertEquals(mid, c.engine.node("n").float(YtAnimProp.SCALE_X), 1e-5f)
        c.engine.advance(500.0)
        assertEquals(1f, c.engine.node("n").float(YtAnimProp.SCALE_X), 1e-5f)
    }

    @Test
    fun `rules change custom states from events`() {
        val grow = anim("grow", state("*", YtStateNames.EXPANDED), prop = YtAnimProp.HEIGHT, to = 20f)
        val shrink = anim("shrink", state(YtStateNames.EXPANDED, "*"), prop = YtAnimProp.HEIGHT, to = 0f)
        val anims = YtAnimations(listOf(grow, shrink), rules = listOf(YtStateRule(kind, YtTriggerKind.DOUBLE_TAP, YtStateNames.EXPANDED)))
        val c = YtAnimController(YtAnimIndex(anims, kind), YtAnimEngine())
        assertTrue(c.index.touch)
        c.event(YtTriggerKind.DOUBLE_TAP)
        assertTrue(c.machine.isOn(YtStateNames.EXPANDED))
        c.engine.advance(200.0)
        assertEquals(20f, c.engine.node("n").float(YtAnimProp.HEIGHT), 1e-4f)
        c.event(YtTriggerKind.DOUBLE_TAP)
        assertFalse(c.machine.isOn(YtStateNames.EXPANDED))
        assertEquals(YtRenderStates.bitOf(YtStateNames.EXPANDED), YtRenderStates.bitsOf(setOf(YtStateNames.EXPANDED)))
    }

    @Test
    fun `groups schedule parallel, sequence and stagger`() {
        val groups = listOf(YtAnimGroup("p"), YtAnimGroup("s", mode = YtGroupMode.SEQUENCE), YtAnimGroup("g", mode = YtGroupMode.STAGGER, staggerMs = 50))
        val tap = YtTrigger(YtTriggerKind.TAP)
        val fired = listOf(
            anim("p1", tap, group = "p"), anim("p2", tap, group = "p"),
            anim("s1", tap, ms = 100, group = "s", delay = 20), anim("s2", tap, ms = 300, group = "s"), anim("s3", tap, group = "s"),
            anim("g1", tap, group = "g"), anim("g2", tap, group = "g"), anim("g3", tap, group = "g"),
            anim("solo", tap),
        )
        val at = YtAnimCombine.schedule(fired, groups).associate { it.first.id to it.second }
        assertEquals(0, at["p1"])
        assertEquals(0, at["p2"])
        assertEquals(0, at["s1"])
        assertEquals(120, at["s2"])
        assertEquals(420, at["s3"])
        assertEquals(listOf(0, 50, 100), listOf(at["g1"], at["g2"], at["g3"]))
        assertEquals(0, at["solo"])
    }

    @Test
    fun `conflicts are found and marked`() {
        val tap = YtTrigger(YtTriggerKind.TAP)
        val anims = YtAnimations(listOf(anim("a", tap), anim("b", tap), anim("c", YtTrigger(YtTriggerKind.APPEAR), prop = YtAnimProp.OPACITY, to = 0f)))
        val c = YtAnimCombine.conflicts(anims)
        assertEquals(1, c.size)
        assertEquals(YtAnimProp.SCALE_X, c[0].prop)
        assertEquals(listOf("a", "b"), c[0].anims)
        assertTrue(c[0].sameTrigger)
    }

    @Test
    fun `event animations win over the state transition of the same input`() {
        val tr = anim("tr", state("*", YtStateNames.SELECTED), to = 2f)
        val ev = anim("ev", YtTrigger(YtTriggerKind.SELECTED), to = 3f)
        val c = YtAnimController(YtAnimIndex(YtAnimations(listOf(tr, ev)), kind), YtAnimEngine())
        c.state(YtStateNames.SELECTED, true)
        c.event(YtTriggerKind.SELECTED)
        c.engine.advance(1000.0)
        assertEquals(3f, c.engine.node("n").float(YtAnimProp.SCALE_X), 1e-5f)
    }

    @Test
    fun `presets all build valid animations`() {
        val ids = YtAnimOps.ids(YtAnimations())
        for (p in YtPreset.entries) {
            val built = YtAnimPresets.build(p, YtComponentKind.CARD, "card", strength = 1.5f, durationMs = 500, children = if (p == YtPreset.STAGGER) listOf("x", "y") else emptyList(), newId = ids)
            assertTrue(p.name, built.anims.isNotEmpty() && built.anims.all { it.playing().isNotEmpty() })
            for (a in built.anims) {
                assertEquals(p, a.preset)
                assertEquals(1.5f, a.strength, 0f)
                if (a.kind == YtAnimKind.TIME) assertEquals(500, a.durationMs)
                for (t in a.tracks) assertTrue(t.keys.zipWithNext().all { (x, y) -> x.at <= y.at })
            }
            if (p == YtPreset.STAGGER) assertEquals(YtGroupMode.STAGGER, built.group?.mode)
        }
        // strength scales the movement
        val weak = YtAnimPresets.tracks(YtPreset.SHAKE, 0.5f, 0).single().keys.maxOf { it.value }
        val strong = YtAnimPresets.tracks(YtPreset.SHAKE, 2f, 0).single().keys.maxOf { it.value }
        assertEquals(strong, weak * 4f, 1e-3f)
        assertEquals(YtAnimKind.SPRING, YtAnimPresets.build(YtPreset.SPRING, kind, "n", newId = ids).anims.single().kind)
    }
}
