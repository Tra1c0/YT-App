package com.github.libretube.ui.yt.builder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtStatePatchRuleTest {
    private val kind = YtComponentKind.CARD

    private fun anim(id: String, trigger: YtTrigger, prop: YtAnimProp, node: String = "n") =
        YtAnim(id, component = kind, node = node, trigger = trigger, tracks = listOf(YtTrack(prop, listOf(YtKeyframe(0f, prop.rest), YtKeyframe(1f, prop.rest + 0.5f)))))

    private val pressedPatch = YtPatch(opacity = 0.7f, scale = 0.95f, fills = listOf(YtPaint.solid(0xFFFF0000.toInt())), padding = YtInsets.all(4f))

    @Test
    fun `without animations the patch stays whole`() {
        assertEquals(pressedPatch, YtStatePatchRule.effective(pressedPatch, emptySet()))
    }

    @Test
    fun `what the animator animates in the state the patch leaves alone`() {
        val e = YtStatePatchRule.effective(pressedPatch, setOf(YtAnimProp.OPACITY, YtAnimProp.SCALE_X))
        assertNull(e.opacity)
        assertNull(e.scale)
        assertEquals(pressedPatch.fills, e.fills)
        // values without an animated counterpart are always kept
        assertEquals(pressedPatch.padding, e.padding)
        assertNull(YtStatePatchRule.effective(pressedPatch, setOf(YtAnimProp.GRADIENT_ANGLE)).fills)
    }

    @Test
    fun `the animated properties of a state come from its transitions and its events`() {
        val anims = listOf(
            anim("down", YtTrigger(YtTriggerKind.TOUCH_DOWN), YtAnimProp.SCALE_X),
            anim("toPressed", YtTrigger(YtTriggerKind.STATE, YtStateNames.ANY, YtStateNames.PRESSED), YtAnimProp.OPACITY),
            anim("tap", YtTrigger(YtTriggerKind.TAP), YtAnimProp.ROTATION),
            anim("other", YtTrigger(YtTriggerKind.TOUCH_DOWN), YtAnimProp.Y, node = "m"),
            anim("dbl", YtTrigger(YtTriggerKind.DOUBLE_TAP), YtAnimProp.CORNERS),
        )
        assertEquals(setOf(YtAnimProp.SCALE_X, YtAnimProp.OPACITY), YtStatePatchRule.animatedIn(anims, emptyList(), "n", YtStateNames.PRESSED))
        assertEquals(setOf(YtAnimProp.Y), YtStatePatchRule.animatedIn(anims, emptyList(), "m", YtStateNames.PRESSED))
        assertTrue(YtStatePatchRule.animatedIn(anims, emptyList(), "n", YtStateNames.SELECTED).isEmpty())
        // a rule that sets a custom state on double tap: the double tap's animations carry it
        val rules = listOf(YtStateRule(kind, YtTriggerKind.DOUBLE_TAP, "big"))
        assertEquals(setOf(YtAnimProp.CORNERS), YtStatePatchRule.animatedIn(anims, rules, "n", "big"))
        // the index answers the same and knows about the double tap
        val index = YtAnimIndex(YtAnimations(items = anims, rules = rules, customStates = listOf("big")), kind)
        assertEquals(setOf(YtAnimProp.SCALE_X, YtAnimProp.OPACITY), index.statePropsOf("n", YtStateNames.PRESSED))
        assertTrue(index.doubleTap)
        assertFalse(YtAnimIndex(YtAnimations(items = anims.filter { it.id != "dbl" }), kind).doubleTap)
    }

    @Test
    fun `no double effect when patch and animation change the same property`() {
        val node = YtNode("n", style = YtStyle(opacity = 1f), states = mapOf(YtStates.PRESSED to YtPatch(opacity = 0.5f, rotation = 4f)))
        val index = YtAnimIndex(YtAnimations(items = listOf(anim("fade", YtTrigger(YtTriggerKind.TOUCH_DOWN), YtAnimProp.OPACITY))), kind)
        val plain = YtResponsive.resolve(node, YtEnv.PHONE, listOf(YtStates.PRESSED))
        assertEquals(0.5f, plain.style.opacity)
        val combined = YtResponsive.resolve(node, YtEnv.PHONE, listOf(YtStates.PRESSED)) { s, p -> YtStatePatchRule.effective(p, index.statePropsOf("n", s)) }
        // the animation carries the opacity (it multiplies the node's own 1.0), the patch still turns it
        assertEquals(1f, combined.style.opacity)
        assertEquals(4f, combined.rotation)
    }

    @Test
    fun `patches are laid on from the lowest priority up`() {
        assertEquals(listOf(YtStateNames.SELECTED, YtStateNames.PRESSED), YtStatePatchRule.order(setOf(YtStateNames.PRESSED, YtStateNames.SELECTED)))
        assertEquals(
            listOf("glow", YtStateNames.SELECTED, YtStateNames.ACTIVE, YtStateNames.PRESSED, YtStateNames.LOADING),
            YtStatePatchRule.order(setOf(YtStateNames.ACTIVE, YtStateNames.LOADING, "glow", YtStateNames.PRESSED, YtStateNames.SELECTED), listOf("glow")),
        )
        val node = YtNode(
            "n",
            states = mapOf(YtStates.SELECTED to YtPatch(opacity = 0.9f), YtStates.PRESSED to YtPatch(opacity = 0.6f)),
        )
        val bits = YtRenderStates.SELECTED or YtRenderStates.PRESSED
        val shown = YtResponsive.resolve(node, YtEnv.PHONE, YtStatePatchRule.order(YtRenderStates.names(bits)))
        assertEquals(0.6f, shown.style.opacity)
    }

    @Test
    fun `the plain press feel takes the patch's scale and steps aside for the animator`() {
        assertEquals(0.9f, YtStatePatchRule.pressScale(0.9f, 0.97f, animatorOwnsPress = false))
        assertEquals(0.97f, YtStatePatchRule.pressScale(null, 0.97f, animatorOwnsPress = false))
        assertNull(YtStatePatchRule.pressScale(0.9f, 0.97f, animatorOwnsPress = true))
        assertEquals(setOf(YtAnimProp.SCALE_X, YtAnimProp.SCALE_Y), YtStatePatchRule.props(YtPatch(scale = 0.9f)))
    }
}
