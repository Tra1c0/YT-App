package com.github.libretube.util

import com.github.libretube.obj.update.UpdateAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtUpdateRulesTest {
    private fun asset(name: String, url: String = "https://github.com/Tra1c0/YT-App/releases/download/x/$name") =
        UpdateAsset(name = name, downloadUrl = url, size = 10)

    @Test
    fun buildNumbers() {
        assertEquals(88, YtUpdateRules.buildNumber("YT Build 88"))
        assertEquals(88, YtUpdateRules.buildNumber("build-88"))
        assertNull(YtUpdateRules.buildNumber("YT"))
        assertEquals(88, YtUpdateRules.currentBuild("32.1-yt.88"))
        assertNull(YtUpdateRules.currentBuild("32.1"))
    }

    @Test
    fun onlyNewerBuildsCount() {
        assertTrue(YtUpdateRules.isNewer(87, 88))
        assertFalse(YtUpdateRules.isNewer(88, 88))
        assertFalse(YtUpdateRules.isNewer(90, 88))
        assertTrue(YtUpdateRules.isNewer(null, 88))
        assertFalse(YtUpdateRules.isNewer(null, null))
    }

    @Test
    fun picksTheApkOfTheBuild() {
        val apk = asset("YT-32.1-yt.88.apk")
        val assets = listOf(asset("mapping.txt"), asset("YT-32.1-yt.87.apk"), apk)
        assertEquals(apk, YtUpdateRules.pickApk(assets, 88))
        // no APK named after the build and more than one APK: nothing to guess
        assertNull(YtUpdateRules.pickApk(assets, 89))
        // a single APK is taken when its name carries no build
        val single = asset("app-release.apk")
        assertEquals(single, YtUpdateRules.pickApk(listOf(asset("mapping.txt"), single), 88))
        assertNull(YtUpdateRules.pickApk(listOf(asset("mapping.txt")), 88))
        assertNull(YtUpdateRules.pickApk(emptyList(), 88))
        // never over plain HTTP
        assertNull(YtUpdateRules.pickApk(listOf(asset("YT-32.1-yt.88.apk", "http://x/a.apk")), 88))
    }

    @Test
    fun digests() {
        val hex = "a".repeat(32) + "B".repeat(32)
        assertEquals(hex.lowercase(), YtUpdateRules.expectedSha256("sha256:$hex"))
        assertNull(YtUpdateRules.expectedSha256(null))
        assertNull(YtUpdateRules.expectedSha256("sha512:$hex"))
        assertNull(YtUpdateRules.expectedSha256("sha256:abc"))
        assertTrue(YtUpdateRules.digestMatches(hex.lowercase(), hex))
        assertFalse(YtUpdateRules.digestMatches(hex.lowercase(), "c".repeat(64)))
        assertTrue(YtUpdateRules.digestMatches(null, "c".repeat(64)))
        assertEquals("00ff10", YtUpdateRules.hex(byteArrayOf(0, -1, 16)))
    }

    @Test
    fun signers() {
        assertTrue(YtUpdateRules.sameSigners(setOf("k1"), setOf("k1")))
        assertFalse(YtUpdateRules.sameSigners(setOf("k1"), setOf("k2")))
        assertFalse(YtUpdateRules.sameSigners(setOf("k1"), setOf("k1", "k2")))
        assertFalse(YtUpdateRules.sameSigners(emptySet(), emptySet()))
    }
}
