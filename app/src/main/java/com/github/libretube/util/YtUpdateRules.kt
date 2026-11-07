package com.github.libretube.util

import com.github.libretube.obj.update.UpdateAsset

/**
 * YT: the pure decisions of the self-updater (which release counts as newer, which asset is the
 * APK, whether a checksum or a signer set matches). No Android types, so they run in JVM tests.
 */
object YtUpdateRules {
    private val SHA256_DIGEST = Regex("^sha256:([0-9a-fA-F]{64})$")
    private val APK_BUILD = Regex("yt\\.(\\d+)", RegexOption.IGNORE_CASE)

    /** The last number in [text] ("YT Build 88" -> 88, "build-88" -> 88), null when it has none. */
    fun buildNumber(text: String): Int? =
        Regex("(\\d+)\\D*$").find(text)?.groupValues?.get(1)?.toIntOrNull()

    /** The build number of an app version name ("32.1-yt.88" -> 88). */
    fun currentBuild(versionName: String): Int? =
        buildNumber(versionName.substringAfterLast("yt.", ""))

    /** Only a newer build is an update; an unnumbered local build takes any numbered release. */
    fun isNewer(currentBuild: Int?, updateBuild: Int?): Boolean =
        updateBuild != null && (currentBuild == null || updateBuild > currentBuild)

    /**
     * The APK of a release: the one named after [build] ("YT-32.1-yt.88.apk"), or the only APK
     * when the names carry no build. Other files (mapping.txt) and non-HTTPS links never count.
     */
    fun pickApk(assets: List<UpdateAsset>, build: Int?): UpdateAsset? {
        val apks = assets.filter {
            it.name.endsWith(".apk", ignoreCase = true) && it.downloadUrl.startsWith("https://")
        }
        if (build != null) {
            apks.firstOrNull { apkBuild(it.name) == build }?.let { return it }
        }
        return apks.singleOrNull()
    }

    private fun apkBuild(name: String): Int? =
        APK_BUILD.findAll(name).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()

    /** The expected SHA-256 in lowercase hex, null when the digest is missing or another kind. */
    fun expectedSha256(digest: String?): String? =
        digest?.trim()?.let { SHA256_DIGEST.find(it) }?.groupValues?.get(1)?.lowercase()

    /** True when there is nothing to compare against or the hashes are equal. */
    fun digestMatches(expectedHex: String?, actualHex: String): Boolean =
        expectedHex == null || expectedHex.equals(actualHex, ignoreCase = true)

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** The download must be signed by exactly the keys of the installed app. */
    fun sameSigners(installed: Set<String>, archive: Set<String>): Boolean =
        installed.isNotEmpty() && installed == archive
}
