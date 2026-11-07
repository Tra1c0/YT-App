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

    /** What the dialog says about an install session's status (PackageInstaller.STATUS_*). */
    enum class InstallVerdict {
        /** Installed (an update of YT itself usually ends the process before this). */
        DONE,

        /** Android wants the user to confirm. */
        CONFIRM,

        /** The user said no. */
        ABORTED,

        /** A definite failure: invalid, conflicting signer, no space, incompatible. */
        FAILED,

        /**
         * Not a definite failure. Play Protect answers a sideloaded update it does not know with
         * BLOCKED (or a plain FAILURE, or a TIMEOUT while its dialog waits) and then installs the
         * same APK itself once the user picks "Install anyway"; the update then arrives after a
         * failure. The dialog stays neutral ("Installing…") instead of saying it failed.
         */
        UNSURE,
    }

    // the values of PackageInstaller.STATUS_*, here so the rule needs no Android classes
    const val STATUS_PENDING_USER_ACTION = -1
    const val STATUS_SUCCESS = 0
    const val STATUS_FAILURE = 1
    const val STATUS_FAILURE_BLOCKED = 2
    const val STATUS_FAILURE_ABORTED = 3
    const val STATUS_FAILURE_TIMEOUT = 8

    fun installVerdict(status: Int): InstallVerdict = when (status) {
        STATUS_SUCCESS -> InstallVerdict.DONE
        STATUS_PENDING_USER_ACTION -> InstallVerdict.CONFIRM
        STATUS_FAILURE_ABORTED -> InstallVerdict.ABORTED
        STATUS_FAILURE, STATUS_FAILURE_BLOCKED, STATUS_FAILURE_TIMEOUT -> InstallVerdict.UNSURE
        // INVALID, CONFLICT, STORAGE, INCOMPATIBLE and anything unknown below them
        else -> if (status in 4..7) InstallVerdict.FAILED else InstallVerdict.UNSURE
    }
}
