package com.github.libretube.ui.yt.builder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YtAnimTimelineTest {
    private fun anim(ms: Int = 1000) = YtAnim(
        "a", component = YtComponentKind.CARD, node = "card", durationMs = ms,
        tracks = listOf(
            YtTrack(YtAnimProp.X, listOf(YtKeyframe(0f, 0f), YtKeyframe(0.2f, 10f), YtKeyframe(0.5f, 20f), YtKeyframe(1f, 0f))),
            YtTrack(YtAnimProp.OPACITY, listOf(YtKeyframe(0f, 0f), YtKeyframe(1f, 1f))),
        ),
    )

    private fun ms(a: YtAnim, r: YtKeyRef) = YtAnimTimeline.msOf(a, a.tracks[r.track].keys[r.key])

    @Test
    fun `snapping lands on the frame grid`() {
        assertEquals(1000.0 / 60 * 6, YtAnimTimeline.snap(101.0, 60), 1e-9)
        assertEquals(100.0, YtAnimTimeline.snap(110.0, 30), 1e-9)
        assertEquals(6L, YtAnimTimeline.frameOf(100.0, 60))
    }

    @Test
    fun `a moved selection keeps its spacing and snaps its anchor`() {
        val a = anim()
        val sel = setOf(YtKeyRef(0, 1), YtKeyRef(0, 2))
        val (moved, newSel) = YtAnimTimeline.move(a, sel, YtKeyRef(0, 1), 37.0, 60, snap = true)
        val times = newSel.map { ms(moved, it) }.sorted()
        // anchor 200 + 37 = 237 → frame 14 = 233.33 ms; the other key keeps its 300 ms distance
        assertEquals(233.333, times[0], 0.01)
        assertEquals(533.333, times[1], 0.01)
        // without snapping, exact
        val (free, freeSel) = YtAnimTimeline.move(a, sel, YtKeyRef(0, 1), 37.0, 60, snap = false)
        assertEquals(237.0, freeSel.map { ms(free, it) }.min(), 0.01)
    }

    @Test
    fun `a move stays inside the play and resorts`() {
        val a = anim()
        val (moved, sel) = YtAnimTimeline.move(a, setOf(YtKeyRef(0, 2)), YtKeyRef(0, 2), 5000.0, 60, snap = false)
        assertEquals(1000.0, ms(moved, sel.single()), 0.01)
        // past its neighbour: the keys are sorted again and the selection follows
        val (back, sel2) = YtAnimTimeline.move(a, setOf(YtKeyRef(0, 2)), YtKeyRef(0, 2), -400.0, 60, snap = false)
        assertEquals(YtKeyRef(0, 1), sel2.single())
        assertEquals(100.0, ms(back, sel2.single()), 0.01)
        assertTrue(back.tracks[0].keys.zipWithNext().all { (x, y) -> x.at <= y.at })
    }

    @Test
    fun `delete, duplicate, copy and paste`() {
        val a = anim()
        val del = YtAnimTimeline.delete(a, setOf(YtKeyRef(1, 0), YtKeyRef(1, 1)))
        assertEquals(1, del.tracks.size)
        val (dup, dupSel) = YtAnimTimeline.duplicate(a, setOf(YtKeyRef(0, 1)), 60)
        assertEquals(5, dup.tracks[0].keys.size)
        assertEquals(200.0 + 1000.0 / 60, ms(dup, dupSel.single()), 0.01)
        val clips = YtAnimTimeline.copy(a, setOf(YtKeyRef(0, 1), YtKeyRef(0, 2)))
        assertEquals(0.0, clips[0].relMs, 1e-3)
        assertEquals(300.0, clips[1].relMs, 1e-3)
        val other = a.copy(tracks = listOf(a.tracks[1]))
        val (pasted, sel) = YtAnimTimeline.paste(other, clips, 600.0)
        // the X track is added for the pasted keys
        assertEquals(YtAnimProp.X, pasted.tracks[1].prop)
        val pastedAt = sel.map { ms(pasted, it) }.sorted()
        assertEquals(600.0, pastedAt[0], 1e-3)
        assertEquals(900.0, pastedAt[1], 1e-3)
    }

    @Test
    fun `distribute and align`() {
        val a = anim()
        val sel = setOf(YtKeyRef(0, 0), YtKeyRef(0, 1), YtKeyRef(0, 2), YtKeyRef(0, 3))
        val (spread, s) = YtAnimTimeline.distribute(a, sel)
        assertEquals(listOf(0.0, 333.33, 666.67, 1000.0), s.map { ms(spread, it) }.sorted().map { Math.round(it * 100) / 100.0 })
        val (aligned, s2) = YtAnimTimeline.align(a, setOf(YtKeyRef(0, 1), YtKeyRef(1, 1)), 400.0)
        assertTrue(s2.size == 2 && s2.all { kotlin.math.abs(ms(aligned, it) - 400.0) < 1e-3 })
    }

    @Test
    fun `a new key keeps the curve's value`() {
        val a = anim()
        val (next, ref) = YtAnimTimeline.addKey(a, 0, 350.0)
        assertEquals(15f, next.tracks[0].keys[ref!!.key].value, 1e-4f)
        assertEquals(YtAnimEval.sample(a.tracks[0], 0.7f), YtAnimEval.sample(next.tracks[0], 0.7f), 1e-4f)
    }

    @Test
    fun `the view maps time and zooms around the finger`() {
        val v = YtAnimTimeline.View(pxPerMs = 0.5f)
        assertEquals(50f, v.x(100.0), 1e-4f)
        val z = v.zoomed(2f, 50f)
        assertEquals(100.0, z.ms(50f), 1e-3)
        assertTrue(z.tickMs() <= v.tickMs())
    }
}
