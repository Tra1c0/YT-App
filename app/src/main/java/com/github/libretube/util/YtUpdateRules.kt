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

    // the values of PackageInstaller.STATUS_*, here so the rules need no Android classes
    const val STATUS_PENDING_USER_ACTION = -1
    const val STATUS_SUCCESS = 0
    const val STATUS_FAILURE = 1
    const val STATUS_FAILURE_BLOCKED = 2
    const val STATUS_FAILURE_ABORTED = 3
    const val STATUS_FAILURE_INVALID = 4
    const val STATUS_FAILURE_CONFLICT = 5
    const val STATUS_FAILURE_STORAGE = 6
    const val STATUS_FAILURE_INCOMPATIBLE = 7
    const val STATUS_FAILURE_TIMEOUT = 8

    // hidden PackageManager.INSTALL_FAILED_* codes Android sends as EXTRA_LEGACY_STATUS
    const val LEGACY_VERIFICATION_TIMEOUT = -22
    const val LEGACY_VERIFICATION_FAILURE = -23

    /** Fresh sessions committed by themselves after the first one ended without installing. */
    const val MAX_AUTO_RETRIES = 2

    /** What happens after an install session reported its status. */
    enum class NextStep {
        /** Installed (an update of YT itself usually ends the process before this). */
        DONE,

        /** Android wants the user to confirm; show its dialog. */
        CONFIRM,

        /**
         * Commit a fresh session with the same verified APK. Play Protect scans a sideloaded update
         * it does not know while the session waits; when the scan outlasts the verification
         * window, or Play Protect takes over and the session is dropped, Android ends it with
         * ABORTED/BLOCKED/FAILURE/TIMEOUT even though the user tapped "Install". Play Protect now
         * knows the APK, so the next session usually goes through (the user needed 3-4 tries).
         */
        RETRY,

        /** Hand the verified APK to Android's own installer, which treats it like any sideload. */
        SYSTEM_INSTALLER,

        /** The user said no in Android's confirm dialog. */
        DECLINED,

        /** Nothing more to try (no space, or the APK is gone). */
        FAILED,
    }

    /**
     * The step after a session ended with [status] ([message] = EXTRA_STATUS_MESSAGE). [retriesDone]
     * counts the fresh sessions already committed for this download; [apkAvailable] says whether
     * the verified file is still there. "Failed" is only said when neither a retry nor the
     * system installer is left.
     */
    fun nextStep(
        status: Int,
        message: String?,
        retriesDone: Int,
        apkAvailable: Boolean,
        maxRetries: Int = MAX_AUTO_RETRIES,
    ): NextStep {
        when (status) {
            STATUS_SUCCESS -> return NextStep.DONE
            STATUS_PENDING_USER_ACTION -> return NextStep.CONFIRM
            STATUS_FAILURE_STORAGE -> return NextStep.FAILED
        }
        if (userDeclined(status, message)) return NextStep.DECLINED
        if (!apkAvailable) return NextStep.FAILED
        return if (retriesDone < maxRetries) NextStep.RETRY else NextStep.SYSTEM_INSTALLER
    }

    /**
     * Only Android's own confirm dialog answers "User rejected permissions"; an abandoned or
     * Play Protect-ended session is ABORTED too, but with another message.
     */
    fun userDeclined(status: Int, message: String?): Boolean =
        status == STATUS_FAILURE_ABORTED && message?.contains("rejected", ignoreCase = true) == true

    /** The session ended while Play Protect checked the APK, as far as Android tells. */
    fun playProtectInvolved(status: Int, legacyStatus: Int?, message: String?): Boolean =
        status == STATUS_FAILURE_BLOCKED ||
            legacyStatus == LEGACY_VERIFICATION_TIMEOUT ||
            legacyStatus == LEGACY_VERIFICATION_FAILURE ||
            message?.contains("VERIFICATION", ignoreCase = true) == true

    /** A status for a session other than the one committed last belongs to an earlier try. */
    fun isStale(sessionId: Int?, currentSessionId: Int?): Boolean =
        sessionId != null && sessionId >= 0 && currentSessionId != null && sessionId != currentSessionId
}
