package com.github.libretube.util

import com.github.libretube.util.YtApkCleanRules.AppKind
import com.github.libretube.util.YtApkCleanRules.Kind
import com.github.libretube.util.YtApkCleanRules.Outcome
import com.github.libretube.util.YtApkCleanRules.Part
import com.github.libretube.util.YtApkCleanRules.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class YtApkCleanRulesTest {

    private val own = "app.tra1c0.yt"
    private val installed = YtApkCleanRules.versionCodeOf(136)

    @Test
    fun namePreFilter() {
        assertTrue(YtApkCleanRules.isApkCandidate("YT-32.1-yt.88.apk"))
        assertTrue(YtApkCleanRules.isApkCandidate("YT-32.1-yt.88 (1).apk"))
        assertTrue(YtApkCleanRules.isApkCandidate("yt-32.1-YT.88.APK"))
        assertTrue(YtApkCleanRules.isApkCandidate("LibreTube-v0.29.apk"))
        assertFalse(YtApkCleanRules.isApkCandidate("NewPipe_v0.27.apk"))
        assertFalse(YtApkCleanRules.isApkCandidate("YT-32.1-yt.88.apk.zip"))
        assertTrue(YtApkCleanRules.isZipCandidate("YT-32.1-yt.88.apk.zip"))
        assertFalse(YtApkCleanRules.isZipCandidate("photos.zip"))
        assertFalse(YtApkCleanRules.isZipCandidate("YT-notes.txt"))
    }

    @Test
    fun buildFromVersionCode() {
        assertEquals(136, YtApkCleanRules.buildOf(7_200_136))
        assertEquals(0, YtApkCleanRules.buildOf(7_200_000))
        assertEquals(99_999, YtApkCleanRules.buildOf(7_299_999))
        // upstream LibreTube's own codes and anything else are no YT build
        assertNull(YtApkCleanRules.buildOf(72))
        assertNull(YtApkCleanRules.buildOf(7_300_000))
        assertEquals(7_200_136L, YtApkCleanRules.versionCodeOf(136))
    }

    @Test
    fun buildFromName() {
        assertEquals(88, YtApkCleanRules.buildFromName("YT-32.1-yt.88.apk"))
        assertNull(YtApkCleanRules.buildFromName("YT-debug.apk"))
    }

    @Test
    fun verdicts() {
        fun apk(code: Long?, samePackage: Boolean = true, sameSigner: Boolean = true) =
            YtApkCleanRules.verdict(Kind.APK, samePackage, sameSigner, code, installed)
        assertEquals(Verdict.OLD, apk(YtApkCleanRules.versionCodeOf(100)))
        assertEquals(Verdict.CURRENT, apk(installed))
        assertEquals(Verdict.NEWER, apk(YtApkCleanRules.versionCodeOf(140)))
        // a newer APK with another key would be refused by Android as an update
        assertEquals(Verdict.NEWER_FOREIGN, apk(YtApkCleanRules.versionCodeOf(140), sameSigner = false))
        // another own package (a debug build) is never an update of this app
        assertEquals(Verdict.OLD, apk(YtApkCleanRules.versionCodeOf(200), samePackage = false))
        assertEquals(Verdict.OLD, apk(YtApkCleanRules.versionCodeOf(1), sameSigner = false))

        fun zip(build: Int?) = YtApkCleanRules.verdict(
            Kind.ZIP, true, false, build?.let(YtApkCleanRules::versionCodeOf), installed
        )
        assertEquals(Verdict.OLD, zip(12))
        assertEquals(Verdict.CURRENT, zip(136))
        assertEquals(Verdict.NEWER_ZIP, zip(137))
        assertEquals(Verdict.UNKNOWN, zip(null))
    }

    @Test
    fun newerFilesAreNeverSelectable() {
        assertTrue(YtApkCleanRules.selectable(Verdict.OLD))
        assertTrue(YtApkCleanRules.selectable(Verdict.CURRENT))
        assertTrue(YtApkCleanRules.selectable(Verdict.UNKNOWN))
        assertFalse(YtApkCleanRules.selectable(Verdict.NEWER))
        assertFalse(YtApkCleanRules.selectable(Verdict.NEWER_FOREIGN))
        assertFalse(YtApkCleanRules.selectable(Verdict.NEWER_ZIP))
        for (verdict in Verdict.entries) {
            for (keep in listOf(true, false)) {
                // whatever is preselected or deleted quietly must be selectable at all
                if (YtApkCleanRules.preselected(verdict, keep)) assertTrue(YtApkCleanRules.selectable(verdict))
                if (YtApkCleanRules.autoDelete(verdict, keep)) assertTrue(YtApkCleanRules.selectable(verdict))
            }
        }
    }

    @Test
    fun keepCurrentDecides() {
        assertTrue(YtApkCleanRules.preselected(Verdict.CURRENT, keepCurrent = false))
        assertFalse(YtApkCleanRules.preselected(Verdict.CURRENT, keepCurrent = true))
        assertTrue(YtApkCleanRules.preselected(Verdict.OLD, keepCurrent = true))
        assertFalse(YtApkCleanRules.preselected(Verdict.UNKNOWN, keepCurrent = false))
        assertFalse(YtApkCleanRules.autoDelete(Verdict.UNKNOWN, keepCurrent = false))
        assertTrue(YtApkCleanRules.autoDelete(Verdict.CURRENT, keepCurrent = false))
        assertFalse(YtApkCleanRules.autoDelete(Verdict.NEWER, keepCurrent = false))
    }

    @Test
    fun installedApps() {
        assertEquals(AppKind.NONE, YtApkCleanRules.classifyApp(own, own, sameSigner = true))
        assertEquals(AppKind.OLD_YT, YtApkCleanRules.classifyApp("app.tra1c0.yt.debug", own, sameSigner = false))
        assertEquals(AppKind.OLD_YT, YtApkCleanRules.classifyApp("com.github.libretube", own, sameSigner = true))
        assertEquals(AppKind.OLD_YT, YtApkCleanRules.classifyApp("org.example.test", own, sameSigner = true))
        assertEquals(
            AppKind.LIBRETUBE_ORIGINAL,
            YtApkCleanRules.classifyApp("com.github.libretube", own, sameSigner = false)
        )
        assertEquals(
            AppKind.LIBRETUBE_ORIGINAL,
            YtApkCleanRules.classifyApp("com.github.libretube.debug", own, sameSigner = false)
        )
        assertEquals(AppKind.NONE, YtApkCleanRules.classifyApp("com.github.libretubex", own, sameSigner = false))
        assertEquals(AppKind.NONE, YtApkCleanRules.classifyApp("org.schabi.newpipe", own, sameSigner = false))
        assertTrue(YtApkCleanRules.appPreselected(AppKind.OLD_YT))
        assertFalse(YtApkCleanRules.appPreselected(AppKind.LIBRETUBE_ORIGINAL))
        assertTrue(YtApkCleanRules.isOwnFamily("app.tra1c0.yt.debug", own))
        assertFalse(YtApkCleanRules.isOwnFamily("com.github.libretube", own))
    }

    @Test
    fun zipEntryMatch() {
        assertEquals("YT-32.1-yt.88.apk", YtApkCleanRules.zipApkEntry(listOf("YT-32.1-yt.88.apk")))
        assertNull(YtApkCleanRules.zipApkEntry(listOf("YT-32.1-yt.88.apk", "notes.txt")))
        assertNull(YtApkCleanRules.zipApkEntry(listOf("out/YT-32.1-yt.88.apk")))
        assertNull(YtApkCleanRules.zipApkEntry(listOf("app-release.apk")))
        assertNull(YtApkCleanRules.zipApkEntry(listOf("unit-test-report/index.html")))
        assertNull(YtApkCleanRules.zipApkEntry(emptyList()))
    }

    private fun zip(vararg names: String, comment: String? = null, payload: Int = 1000): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            comment?.let(zip::setComment)
            for (name in names) {
                zip.putNextEntry(ZipEntry(name))
                // random bytes, so the payload does not shrink below the tail that is read
                zip.write(ByteArray(payload).also(java.util.Random(7)::nextBytes))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun centralDirectoryFromTail() {
        val bytes = zip("YT-32.1-yt.88.apk", payload = 200_000)
        // only the end of the file is read, as on the phone
        val tail = bytes.copyOfRange(bytes.size - 4096, bytes.size)
        assertEquals(listOf("YT-32.1-yt.88.apk"), YtApkCleanRules.zipEntryNames(tail, bytes.size.toLong()))
        val two = zip("a.txt", "YT-1.apk")
        assertEquals(listOf("a.txt", "YT-1.apk"), YtApkCleanRules.zipEntryNames(two, two.size.toLong()))
        val commented = zip("YT-32.1-yt.9.apk", comment = "made by CI")
        assertEquals(listOf("YT-32.1-yt.9.apk"), YtApkCleanRules.zipEntryNames(commented, commented.size.toLong()))
    }

    @Test
    fun centralDirectoryCapsAndRejects() {
        val many = zip(*Array(20) { "f$it.txt" })
        val names = YtApkCleanRules.zipEntryNames(many, many.size.toLong(), max = 3)!!
        // capped, but still "more than one entry"
        assertEquals(4, names.size)
        assertNull(YtApkCleanRules.zipApkEntry(names))
        // no end record
        assertNull(YtApkCleanRules.zipEntryNames(ByteArray(100), 100))
        // the directory lies before the bytes that were read
        val big = zip("YT-1.apk", "YT-2.apk", payload = 50_000)
        val shortTail = big.copyOfRange(big.size - 30, big.size)
        assertNull(YtApkCleanRules.zipEntryNames(shortTail, big.size.toLong()))
    }

    @Test
    fun folders() {
        assertEquals("Download", YtApkCleanRules.folderOf("/storage/emulated/0/Download/YT.apk", "/storage/emulated/0"))
        assertEquals(
            "Download/Telegram",
            YtApkCleanRules.folderOf("/storage/emulated/0/Download/Telegram/YT.apk", "/storage/emulated/0/")
        )
        assertNull(YtApkCleanRules.folderOf("/data/user/0/app.tra1c0.yt/cache/update/update.apk", "/storage/emulated/0"))
        assertNull(YtApkCleanRules.folderOf("/storage/emulated/0/YT.apk", "/storage/emulated/0"))
    }

    @Test
    fun summaryNumbers() {
        val outcome = Outcome(filesDeleted = 7, bytesFreed = 412_000_000, appsRemoved = 1)
        assertEquals(
            listOf(Part.FILES to 7L, Part.FREED to 412_000_000L, Part.APPS to 1L),
            YtApkCleanRules.summaryParts(outcome)
        )
        assertEquals(listOf(Part.NOTHING to 0L), YtApkCleanRules.summaryParts(Outcome()))
        assertEquals(
            listOf(Part.FILES_FAILED to 2L, Part.APPS_KEPT to 1L),
            YtApkCleanRules.summaryParts(Outcome(filesFailed = 2, appsKept = 1))
        )
        val sum = Outcome(filesDeleted = 2, bytesFreed = 10) + Outcome(filesDeleted = 1, bytesFreed = 5, appsRemoved = 1)
        assertEquals(Outcome(filesDeleted = 3, bytesFreed = 15, appsRemoved = 1), sum)
        assertEquals(30L, YtApkCleanRules.selectedBytes(listOf(10L to true, 99L to false, 20L to true)))
    }
}
