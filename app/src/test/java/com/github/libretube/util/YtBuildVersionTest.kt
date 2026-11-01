package com.github.libretube.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtBuildVersionTest {
    @Test fun onlyNewerStableBuildsAreOffered() {
        assertTrue(YtBuildVersion.isNewer("32.1-yt.64", "build-65"))
        assertFalse(YtBuildVersion.isNewer("32.1-yt.64", "build-64"))
        assertFalse(YtBuildVersion.isNewer("32.1-yt.64", "build-63"))
        assertFalse(YtBuildVersion.isNewer("32.1-yt.64", "check-65-abcdef0"))
        assertFalse(YtBuildVersion.isNewer("32.1-yt.64", "screens-65"))
    }

    @Test fun baseVersionAndDisplayNameDoNotBecomeBuildNumbers() {
        assertTrue(YtBuildVersion.isNewer("33.0-yt.9", "build-10"))
        assertFalse(YtBuildVersion.isNewer("32.1", "build-65"))
        assertFalse(YtBuildVersion.isNewer("32.1-yt.64", "YT Build 65"))
        assertFalse(YtBuildVersion.isNewer("32.1-yt.64", ""))
        assertFalse(YtBuildVersion.isNewer("32.1-yt.64", "build-99999999999999999999999999"))
    }
}
