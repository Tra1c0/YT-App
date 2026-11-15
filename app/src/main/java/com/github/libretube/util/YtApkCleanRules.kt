package com.github.libretube.util

import java.util.Locale

/**
 * YT: the pure part of "old YT installation files" on the storage page (YtApkClean finds the
 * files and apps, Y/YtApkCleanUi shows them). Which file names are worth parsing, the build
 * number of a version code, what happens to each file, which installed apps are old YT builds,
 * the one entry of a CI artifact zip, and the numbers of the summary. No Android types.
 */
object YtApkCleanRules {

    /** Version codes of YT are 72 * 100000 + build number (app/build.gradle.kts). */
    const val VERSION_BASE = 72L * 100000
    private const val BUILD_SPAN = 100000L

    /** Every package of the owner starts with this (app.tra1c0.yt, app.tra1c0.yt.debug). */
    const val OWN_NAMESPACE = "app.tra1c0."

    /** The original app YT was forked from, and its debug build. */
    const val LIBRETUBE = "com.github.libretube"

    const val APK_MIME = "application/vnd.android.package-archive"

    /** Files bigger than this are not copied just to read an APK behind a content URI. */
    const val MAX_PEEK_BYTES = 300L * 1000 * 1000

    enum class Kind { APK, ZIP }

    /**
     * What a found file is to the installed app.
     * OLD and CURRENT may be deleted, NEWER can be installed instead, NEWER_FOREIGN (signed with
     * another key, so Android would refuse it as an update) and NEWER_ZIP (would need unzipping)
     * stay; UNKNOWN has no readable version and is only deleted when the user ticks it.
     */
    enum class Verdict { OLD, CURRENT, NEWER, NEWER_FOREIGN, NEWER_ZIP, UNKNOWN }

    /** Installed apps besides YT itself. */
    enum class AppKind { OLD_YT, LIBRETUBE_ORIGINAL, NONE }

    /**
     * Name pre-filter for APKs: only these are opened with the package parser. The identity comes
     * from the parsed package, not from the name ("YT-32.1-yt.88.apk", "YT-32.1-yt.88 (1).apk").
     */
    fun isApkCandidate(name: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        return n.endsWith(".apk") && (n.contains("yt") || n.contains("libretube"))
    }

    /** Name pre-filter for zipped CI artifacts ("YT-32.1-yt.88.apk.zip"). */
    fun isZipCandidate(name: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        return n.endsWith(".zip") && n.contains("yt")
    }

    /** The build number in a YT version code, null for a code outside YT's scheme. */
    fun buildOf(versionCode: Long): Int? =
        if (versionCode >= VERSION_BASE && versionCode < VERSION_BASE + BUILD_SPAN) {
            (versionCode - VERSION_BASE).toInt()
        } else {
            null
        }

    fun versionCodeOf(build: Int): Long = VERSION_BASE + build

    private val ZIP_APK = Regex("^YT-[^/\\\\]+\\.apk$", RegexOption.IGNORE_CASE)
    private val NAME_BUILD = Regex("yt\\.(\\d{1,5})\\.apk$", RegexOption.IGNORE_CASE)

    /**
     * The APK inside a CI artifact zip: only a zip whose single entry is a top-level `YT-*.apk`
     * counts. Any other zip (more entries, a folder, another name) is not ours to touch.
     */
    fun zipApkEntry(entries: List<String>): String? = entries.singleOrNull()?.takeIf { ZIP_APK.matches(it) }

    /** The build number in a release file name ("YT-32.1-yt.88.apk" -> 88). */
    fun buildFromName(name: String): Int? = NAME_BUILD.find(name)?.groupValues?.get(1)?.toIntOrNull()

    /** Whether a parsed package belongs to the owner (YT itself or one of its test packages). */
    fun isOwnFamily(packageName: String, ownPackage: String): Boolean =
        packageName == ownPackage || packageName.startsWith(OWN_NAMESPACE)

    /**
     * The verdict for a found file. [samePackage]: the APK is of the installed package (a zip
     * entry `YT-*.apk` always is); [sameSigner]: signed by the installed app's key;
     * [fileCode]: its version code (for a zip, from the entry name), null when unknown.
     * A file of another own package (a debug build) is never an update of this app, so it is old.
     */
    fun verdict(
        kind: Kind,
        samePackage: Boolean,
        sameSigner: Boolean,
        fileCode: Long?,
        installedCode: Long,
    ): Verdict = when {
        kind == Kind.APK && !samePackage -> Verdict.OLD
        fileCode == null -> Verdict.UNKNOWN
        fileCode < installedCode -> Verdict.OLD
        fileCode == installedCode -> Verdict.CURRENT
        kind == Kind.ZIP -> Verdict.NEWER_ZIP
        sameSigner -> Verdict.NEWER
        else -> Verdict.NEWER_FOREIGN
    }

    /** A newer file is never deleted here, whatever the user ticks. */
    fun selectable(verdict: Verdict): Boolean =
        verdict == Verdict.OLD || verdict == Verdict.CURRENT || verdict == Verdict.UNKNOWN

    /** Ticked when the list opens; the installed version's APK only unless the user keeps it. */
    fun preselected(verdict: Verdict, keepCurrent: Boolean): Boolean =
        verdict == Verdict.OLD || (verdict == Verdict.CURRENT && !keepCurrent)

    /** What the quiet clean-up after an update deletes: the same as ticked, never an unknown. */
    fun autoDelete(verdict: Verdict, keepCurrent: Boolean): Boolean = preselected(verdict, keepCurrent)

    /**
     * An installed app other than YT itself: an old YT build when it carries YT's signing key or
     * the owner's package namespace; LibreTube's own packages otherwise are the original app.
     */
    fun classifyApp(packageName: String, ownPackage: String, sameSigner: Boolean): AppKind = when {
        packageName == ownPackage -> AppKind.NONE
        sameSigner || packageName.startsWith(OWN_NAMESPACE) -> AppKind.OLD_YT
        packageName == LIBRETUBE || packageName.startsWith("$LIBRETUBE.") -> AppKind.LIBRETUBE_ORIGINAL
        else -> AppKind.NONE
    }

    /** LibreTube (Original) is not ours: listed, but only removed when the user ticks it. */
    fun appPreselected(kind: AppKind): Boolean = kind == AppKind.OLD_YT

    /**
     * The folder a file sits in, relative to the shared storage root ("Download/Telegram"),
     * or null when it lies outside it.
     */
    fun folderOf(path: String, storageRoot: String): String? {
        val root = storageRoot.trimEnd('/')
        val parent = path.substringBeforeLast('/', "")
        if (root.isEmpty() || !parent.startsWith("$root/")) return null
        return parent.removePrefix("$root/")
    }

    /** The result of one clean-up, for the summary line. */
    data class Outcome(
        val filesDeleted: Int = 0,
        val bytesFreed: Long = 0,
        val appsRemoved: Int = 0,
        val filesFailed: Int = 0,
        val appsKept: Int = 0,
    ) {
        operator fun plus(other: Outcome) = Outcome(
            filesDeleted + other.filesDeleted,
            bytesFreed + other.bytesFreed,
            appsRemoved + other.appsRemoved,
            filesFailed + other.filesFailed,
            appsKept + other.appsKept,
        )
    }

    enum class Part { FILES, FREED, APPS, FILES_FAILED, APPS_KEPT, NOTHING }

    /**
     * The parts of the summary in order, each with its number ("7 Dateien gelöscht · 412 MB frei ·
     * 1 alte App deinstalliert"). Zero parts are left out; with none left it says nothing happened.
     */
    fun summaryParts(outcome: Outcome): List<Pair<Part, Long>> {
        val parts = buildList {
            if (outcome.filesDeleted > 0) {
                add(Part.FILES to outcome.filesDeleted.toLong())
                if (outcome.bytesFreed > 0) add(Part.FREED to outcome.bytesFreed)
            }
            if (outcome.appsRemoved > 0) add(Part.APPS to outcome.appsRemoved.toLong())
            if (outcome.filesFailed > 0) add(Part.FILES_FAILED to outcome.filesFailed.toLong())
            if (outcome.appsKept > 0) add(Part.APPS_KEPT to outcome.appsKept.toLong())
        }
        return parts.ifEmpty { listOf(Part.NOTHING to 0L) }
    }

    /** Sum of the sizes that are ticked. */
    fun selectedBytes(items: List<Pair<Long, Boolean>>): Long =
        items.sumOf { (size, ticked) -> if (ticked) size.coerceAtLeast(0) else 0L }

    /** How many bytes of a zip's end are read to find its central directory. */
    const val ZIP_TAIL_BYTES = 66 * 1024

    private const val EOCD_SIG = 0x06054b50
    private const val CEN_SIG = 0x02014b50
    private const val EOCD_LEN = 22
    private const val CEN_LEN = 46

    /**
     * The entry names in a zip's central directory, read from its last bytes [tail] (the file is
     * [fileLength] long), without extracting anything. At most [max] names; null when the end
     * record is missing, the directory lies outside [tail], or the zip uses ZIP64.
     */
    fun zipEntryNames(tail: ByteArray, fileLength: Long, max: Int = 8): List<String>? {
        if (tail.size < EOCD_LEN || fileLength < tail.size) return null
        // the end record is last, followed only by a comment of up to 65535 bytes
        var eocd = -1
        var i = tail.size - EOCD_LEN
        val lowest = (tail.size - EOCD_LEN - 0xFFFF).coerceAtLeast(0)
        while (i >= lowest) {
            if (int32(tail, i) == EOCD_SIG && i + EOCD_LEN + uint16(tail, i + 20) == tail.size) {
                eocd = i
                break
            }
            i--
        }
        if (eocd < 0) return null
        val total = uint16(tail, eocd + 10)
        val size = uint32(tail, eocd + 12)
        val offset = uint32(tail, eocd + 16)
        if (total == 0xFFFF || size == 0xFFFFFFFFL || offset == 0xFFFFFFFFL) return null
        val tailStart = fileLength - tail.size
        val start = offset - tailStart
        if (start < 0 || start + size > eocd) return null
        val names = mutableListOf<String>()
        var pos = start.toInt()
        repeat(minOf(total, max)) {
            if (pos + CEN_LEN > eocd || int32(tail, pos) != CEN_SIG) return null
            val nameLen = uint16(tail, pos + 28)
            val extraLen = uint16(tail, pos + 30)
            val commentLen = uint16(tail, pos + 32)
            if (pos + CEN_LEN + nameLen > eocd) return null
            names += String(tail, pos + CEN_LEN, nameLen, Charsets.UTF_8)
            pos += CEN_LEN + nameLen + extraLen + commentLen
        }
        // more entries than read: one name more says "not a single entry" all the same
        if (total > max) names += ""
        return names
    }

    private fun uint16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun int32(b: ByteArray, at: Int): Int = uint16(b, at) or (uint16(b, at + 2) shl 16)

    private fun uint32(b: ByteArray, at: Int): Long = int32(b, at).toLong() and 0xFFFFFFFFL
}
