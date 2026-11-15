package com.github.libretube.ui.yt.archive

import com.github.libretube.ui.yt.news.YtNewsCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.random.Random

class YtArchiveStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val id = "dQw4w9WgXcQ"

    private fun store() = YtArchiveStore(File(temp.root, YtArchiveStore.DIR))

    private fun meta(tier: YtArchiveTier = YtArchiveTier.TEXT) = YtArchiveMeta(
        videoId = id, title = "Title", channelName = "Channel", tierLevel = tier.level,
        savedAt = 1, tierAt = 1, textBytes = 10, audioBytes = 20, videoBytes = 30,
        audioMime = "audio/webm", videoMime = "video/mp4",
        chapters = listOf(YtArchiveChapter(0, "Intro"), YtArchiveChapter(65, "Main")),
    )

    @Test
    fun putReadsBackAfterReload() {
        val store = store()
        store.put(meta())
        val again = YtArchiveStore(store.root)
        assertEquals(meta(), again[id])
        assertEquals(60, again.usedBytes())
        assertEquals(1, again.all().size)
    }

    @Test
    fun unfinishedFoldersAndForeignNamesAreIgnored() {
        val root = File(temp.root, YtArchiveStore.DIR)
        File(root, "aaaaaaaaaaa").mkdirs()
        File(root, "aaaaaaaaaaa/audio").writeText("x")
        File(root, "not a video id").mkdirs()
        val store = YtArchiveStore(root)
        assertTrue(store.all().isEmpty())
        assertFalse(File(root, "aaaaaaaaaaa").exists())
        assertNull(store.files("../escape"))
    }

    @Test
    fun downgradeDeletesTheHigherParts() {
        val store = store()
        store.put(meta(YtArchiveTier.VIDEO))
        val files = store.files(id)!!
        files.video.writeText("video")
        files.audio.writeText("audio")
        files.storyboard.mkdirs()
        File(files.storyboard, "0.jpg").writeText("img")
        files.thumbnail.writeText("thumb")

        val audio = store.downgrade(id, YtArchiveTier.AUDIO, 5)!!
        assertEquals(YtArchiveTier.AUDIO, audio.tier)
        assertEquals(0, audio.videoBytes)
        assertFalse(files.video.exists())
        assertTrue(files.audio.exists())

        val text = store.downgrade(id, YtArchiveTier.TEXT, 6)!!
        assertEquals(YtArchiveTier.TEXT, text.tier)
        assertFalse(files.audio.exists())
        assertFalse(files.storyboard.exists())
        assertTrue(files.thumbnail.exists())

        assertNull(store.downgrade(id, null, 7))
        assertFalse(files.dir.exists())
        assertNull(store[id])
    }

    @Test
    fun measureSplitsParts() {
        val store = store()
        store.put(meta())
        val files = store.files(id)!!
        files.audio.writeBytes(ByteArray(100))
        files.storyboard.mkdirs()
        File(files.storyboard, "0.jpg").writeBytes(ByteArray(50))
        files.video.writeBytes(ByteArray(300))
        val (text, audio, video) = store.measure(id)
        assertEquals(150, audio)
        assertEquals(300, video)
        assertEquals(files.meta.length(), text)
    }

    @Test
    fun exportZipNamesStreamsWithExtensions() {
        val store = store()
        store.put(meta(YtArchiveTier.VIDEO))
        val files = store.files(id)!!
        files.audio.writeText("a")
        files.video.writeText("v")
        val out = ByteArrayOutputStream()
        assertTrue(store.exportZip(id, out, "[0:00] hello"))
        val names = ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.map { it.name }.toList()
        }
        assertTrue(names.containsAll(listOf("meta.json", "audio.webm", "video.mp4", "transcript.txt")))
        assertFalse(store.exportZip("aaaaaaaaaaa", ByteArrayOutputStream(), null))
    }

    @Test
    fun transcriptRoundTrip() {
        val store = store()
        store.put(meta())
        val files = store.files(id)!!
        val lines = YtArchiveText.lines(listOf(YtNewsCue(1.5, "Hallo"), YtNewsCue(3.0, "Welt"), YtNewsCue(30.0, "Ende")))
        assertEquals(YtArchiveText.Line(1500, 3000, "Hallo"), lines[0])
        // a far next line: held at most 8 s
        assertEquals(11_000, lines[1].end)
        YtArchiveText.write(lines, files.transcript)
        assertEquals(lines, YtArchiveText.read(files.transcript))
        assertTrue(YtArchiveText.read(File(temp.root, "missing")).isEmpty())
    }

    private val labels = YtArchiveText.Labels("Kapitel", "Transkript", "Skript", "Kein Text", { "Sprecher $it" })

    @Test
    fun renderPrefersTheScriptWithSpeakers() {
        val script = YtArchiveText.parseScript(
            """{"speakers":[{"id":"A","name":"Anna"},{"id":"B","name":null}],
               "lines":[{"start":0,"speaker":"A","text":"Hallo"},{"start":4000,"speaker":"A","text":"noch was"},{"start":65000,"speaker":"B","text":"Tschüss"}],
               "chapters":[]}"""
        )
        assertNotNull(script)
        val text = YtArchiveText.render(meta(), emptyList(), script, labels)
        assertTrue(text, text.contains("[0:00] Anna: Hallo"))
        assertTrue(text, text.contains("[0:04] noch was"))
        assertTrue(text, text.contains("[1:05] Sprecher B: Tschüss"))
        assertTrue(text, text.contains("[1:05] Main"))
    }

    @Test
    fun renderFallsBackToTheTranscript() {
        val text = YtArchiveText.render(meta(), listOf(YtArchiveText.Line(2000, 3000, "Guten Tag.")), null, labels)
        assertTrue(text, text.contains("Transkript"))
        assertTrue(text, text.contains("[0:02] Guten Tag."))
        assertTrue(YtArchiveText.render(meta(), emptyList(), null, labels).endsWith("Kein Text"))
        assertNull(YtArchiveText.parseScript("not json"))
    }

    @Test
    fun throttle() {
        val hour = 60L * 60 * 1000
        var s = YtArchiveThrottle.State()
        val now = 1_800_000_000_000L
        assertFalse(YtArchiveThrottle.paused(s, now))
        s = YtArchiveThrottle.afterBlock(s, now, "bot")
        assertTrue(YtArchiveThrottle.paused(s, now + 5 * hour))
        assertFalse(YtArchiveThrottle.paused(s, now + 6 * hour))
        s = YtArchiveThrottle.afterBlock(s, now, "bot")
        assertEquals(now + 12 * hour, s.backoffUntil)
        repeat(5) { s = YtArchiveThrottle.afterBlock(s, now, "bot") }
        assertEquals(now + 48 * hour, s.backoffUntil)
        s = YtArchiveThrottle.afterRun(s, now, "text", blocked = false)
        assertEquals(0, s.strikes)

        assertEquals(YtArchiveThrottle.MEDIA_PER_DAY, YtArchiveThrottle.mediaLeft(s, now, 0))
        repeat(3) { s = YtArchiveThrottle.countMedia(s, now, 0) }
        assertEquals(YtArchiveThrottle.MEDIA_PER_DAY - 3, YtArchiveThrottle.mediaLeft(s, now, 0))
        assertEquals(YtArchiveThrottle.MEDIA_PER_DAY, YtArchiveThrottle.mediaLeft(s, now + 24 * hour, 0))

        val random = Random(1)
        repeat(100) { assertTrue(YtArchiveThrottle.jitterMs(random) in 2_000..8_000) }
    }
}
