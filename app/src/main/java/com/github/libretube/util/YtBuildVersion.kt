package com.github.libretube.util

/** The CI build number is the version of YT; the LibreTube base version is independent. */
object YtBuildVersion {
    private val installed = Regex("^.+-yt\\.([0-9]+)$")
    private val released = Regex("^build-([0-9]+)$")

    fun isNewer(versionName: String, releaseTag: String): Boolean {
        val current = installed.matchEntire(versionName)?.groupValues?.get(1)?.toLongOrNull()
            ?: return false
        val candidate = released.matchEntire(releaseTag)?.groupValues?.get(1)?.toLongOrNull()
            ?: return false
        return candidate > current
    }
}
