package com.github.libretube.ui.yt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtRenderingPolicyTest {
    @Test fun softwareGraphicsUseTheLegibleFallback() {
        assertFalse(YtRenderingPolicy.hardwareEffects("Android Emulator (Google SwiftShader)", false, false))
        assertFalse(YtRenderingPolicy.hardwareEffects("llvmpipe (LLVM 19)", false, false))
        assertFalse(YtRenderingPolicy.hardwareEffects(null, false, false))
    }

    @Test fun hardwareGlassReturnsWhenPowerSavingEnds() {
        assertTrue(YtRenderingPolicy.hardwareEffects("Adreno (TM) 740", false, false))
        assertTrue(YtRenderingPolicy.hardwareEffects("Mali-G715", false, false))
        assertFalse(YtRenderingPolicy.hardwareEffects("Adreno (TM) 740", false, true))
        assertFalse(YtRenderingPolicy.hardwareEffects("Mali-G715", true, false))
    }
}
