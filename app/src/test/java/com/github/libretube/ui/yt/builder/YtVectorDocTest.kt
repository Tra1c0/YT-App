package com.github.libretube.ui.yt.builder

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vector data in the theme document: round trip, schema migration, repair, edit speed. */
class YtVectorDocTest {
    private val drawn = YtPath(
        listOf(
            YtSubpath(
                listOf(
                    YtPoint(0f, 0f), YtPoint(40f, -10f, ix = -10f, iy = 0f, ox = 10f, oy = 0f, kind = YtPointKind.SMOOTH),
                    YtPoint(80f, 0f), YtPoint(90f, 50f, ix = 0f, iy = -20f, ox = 0f, oy = 20f, kind = YtPointKind.SYMMETRIC),
                    YtPoint(40f, 90f, ix = 20f, iy = 0f, ox = -20f, oy = 0f, kind = YtPointKind.SMOOTH), YtPoint(0f, 50f),
                ),
                closed = true,
            ),
        ),
        YtFillRule.EVEN_ODD, 90f, 90f,
    )

    private fun withVector(): YtThemeDoc {
        val doc = testDoc()
        val card = doc.components.card
        val shape = YtNode("pen1", type = YtNodeType.SHAPE, shape = YtShapeKind.PATH, path = drawn, width = YtSize.fixed(90f), height = YtSize.fixed(90f), style = YtStyle(fills = listOf(YtPaint.token(YtRole.ACCENT))))
        val star = YtNode("star1", type = YtNodeType.SHAPE, shape = YtShapeKind.STAR, shapeSpec = YtShapeSpec(sides = 7, inner = 0.4f, rounding = 3f))
        val image = YtNode("img1", type = YtNodeType.IMAGE, icon = "a.png", mask = YtMask(YtShapeGeom.pathOf(YtMorphTarget(YtShapeKind.STAR), 96f, 64f), invert = true))
        val root = card.copy(children = card.children + shape + star + image)
        val anim = YtAnim(
            "m1", component = YtComponentKind.CARD, node = "pen1",
            tracks = listOf(YtTrack(YtAnimProp.PATH, listOf(YtKeyframe(0f, 0f), YtKeyframe(1f, 1f))), YtTrack(YtAnimProp.MORPH, listOf(YtKeyframe(0f, 0f), YtKeyframe(1f, 1f)))),
            motion = YtMotionPath(YtPath(listOf(YtSubpath(listOf(YtPoint(0f, 0f), YtPoint(100f, 30f)), closed = false)), w = 1f, h = 1f), orient = true, angle = 5f),
            morph = YtMorphTarget(YtShapeKind.POLYGON, YtShapeSpec(sides = 6)),
        )
        return doc.copy(components = doc.components.copy(card = root), animations = YtAnimations(items = listOf(anim)))
    }

    @Test
    fun `paths, masks, motion paths and morphs survive save and load`() {
        val doc = withVector()
        val r = YtThemeCodec.decode(YtThemeCodec.encode(doc))
        assertTrue(r.toString(), r is YtThemeCodec.Read.Ok)
        r as YtThemeCodec.Read.Ok
        assertEquals(doc, r.doc)
        assertTrue(r.issues.toString(), r.issues.isEmpty())
        assertEquals(3, YT_THEME_SCHEMA)
    }

    @Test
    fun `a schema 2 file is migrated to 3 and keeps everything`() {
        val doc = testDoc()
        val raw = YtThemeCodec.json.parseToJsonElement(YtThemeCodec.encode(doc)) as JsonObject
        val v2 = JsonObject(raw.toMutableMap().apply { put("schema", JsonPrimitive(2)) })
        val r = YtThemeCodec.decode(v2.toString()) as YtThemeCodec.Read.Ok
        assertEquals(2, r.fromSchema)
        assertEquals(YT_THEME_SCHEMA, r.doc.schema)
        assertEquals(doc, r.doc)
        val migrated = YtThemeCodec.migrate(v2, 2)
        assertEquals(JsonPrimitive(3), migrated["schema"])
    }

    @Test
    fun `broken vector data is repaired`() {
        val doc = withVector()
        val card = doc.components.card
        val bad = card.copy(
            children = card.children.map {
                when (it.id) {
                    "pen1" -> it.copy(path = YtPath(listOf(YtSubpath(listOf(YtPoint(Float.NaN, 1f), YtPoint(1e9f, 2f)))), w = -5f))
                    "star1" -> it.copy(shape = YtShapeKind.PATH, path = null, shapeSpec = YtShapeSpec(sides = 99, inner = 9f))
                    "img1" -> it.copy(mask = YtMask(YtPath()))
                    else -> it
                }
            },
        )
        val fixed = YtThemeValidate.clamp(doc.copy(components = doc.components.copy(card = bad), animations = YtAnimations(items = listOf(doc.animations.items[0].copy(motion = null, morph = null)))))
        val nodes = YtDocOps.all(fixed.doc.components.card).associateBy { it.id }
        val pen = nodes.getValue("pen1").path!!
        assertEquals(0f, pen.subpaths[0].points[0].x)
        assertEquals(YtVecLimits.MAX_COORD, pen.subpaths[0].points[1].x)
        assertEquals(YtVecLimits.MIN_BOX, pen.w)
        assertEquals(YtShapeKind.RECT, nodes.getValue("star1").shape)
        assertEquals(YtVecLimits.MAX_SIDES, nodes.getValue("star1").shapeSpec.sides)
        assertNull(nodes.getValue("img1").mask)
        // the tracks keep a path and a target to play
        val a = fixed.doc.animations.items[0]
        assertEquals(YtMotionPath(), a.motion)
        assertEquals(YtMorphTarget(), a.morph)
        assertTrue(fixed.issues.isNotEmpty())
    }

    @Test
    fun `editing a point of a 100 point path and rebuilding its cache stays well within a frame`() {
        val pts = (0 until 100).map { i ->
            val a = i * Math.PI * 2 / 100
            val r = if (i % 2 == 0) 100.0 else 80.0
            YtPoint((150 + r * Math.cos(a)).toFloat(), (150 + r * Math.sin(a)).toFloat(), ix = -4f, iy = 3f, ox = 4f, oy = -3f, kind = YtPointKind.SMOOTH)
        }
        var path = YtPath(listOf(YtSubpath(pts)), w = 300f, h = 300f)
        val cache = YtPathCache()
        val geom = YtPathGeom
        // warm up the JIT, then measure many steps: move a point, then rebuild what the renderer draws from
        repeat(2000) { i ->
            path = YtPathOps.move(path, setOf(YtPathGeom.Ref(0, i % 100)), 0.1f, -0.1f)
            cache.rebuild(path, 300f, 300f)
        }
        val steps = 2000
        val start = System.nanoTime()
        repeat(steps) { i ->
            path = YtPathOps.move(path, setOf(YtPathGeom.Ref(0, i % 100)), 0.1f, -0.1f)
            cache.rebuild(path, 330f, 300f)
            geom.hitPoint(path, 100.0, 100.0, 12.0)
        }
        val perStepMs = (System.nanoTime() - start) / 1e6 / steps
        assertTrue("one step took $perStepMs ms", perStepMs < 0.5)
        assertTrue(cache.size > 100 * 7)
        assertTrue(!cache.rebuild(path, 330f, 300f))
    }

    @Test
    fun `the pen maps path units to the turned node box and back`() {
        // a 200 × 100 px box centred at (300, 200), turned 90°, holding a 40 × 20 path box
        val f = YtPenFrame.box(300f, 200f, 200f, 100f, 40f, 20f, 90f)
        val corner = f.toFrame(0f, 0f)
        // the top left corner turned a quarter clockwise lands top right of the centre
        assertEquals(350f, corner.x, 1e-3f)
        assertEquals(100f, corner.y, 1e-3f)
        val centre = f.toFrame(20f, 10f)
        assertEquals(300f, centre.x, 1e-3f)
        assertEquals(200f, centre.y, 1e-3f)
        val back = f.fromFrame(f.toFrame(7f, 13f))
        assertEquals(7f, back.x, 1e-3f)
        assertEquals(13f, back.y, 1e-3f)
        assertEquals(1f, f.units(5f), 1e-4f)
    }

    @Test
    fun `the frame time rating follows the budget`() {
        val s = YtFrameStats(100)
        repeat(100) { s.add(7_000_000L, false) }
        assertEquals(YtFrameStats.Rating.GREEN, s.summary()!!.rating(8.33))
        repeat(100) { s.add(11_000_000L, true) }
        assertEquals(YtFrameStats.Rating.AMBER, s.summary()!!.rating(8.33))
        repeat(100) { s.add(30_000_000L, true) }
        assertEquals(YtFrameStats.Rating.RED, s.summary()!!.rating(8.33))
        assertEquals(YtFrameStats.Rating.GREEN, s.summary()!!.rating(40.0))
    }
}
