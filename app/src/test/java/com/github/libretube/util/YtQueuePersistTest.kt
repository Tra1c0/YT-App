package com.github.libretube.util

import com.github.libretube.util.YtQueuePersist.EndAction
import com.github.libretube.util.YtQueuePersist.JoinPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtQueuePersistTest {
    // the tests use the id as the url; the store keys by url.toID()
    private val videoKey: (YtSavedVideo) -> String? = { it.url.takeIf { u -> u.isNotBlank() } }
    private val idKey: (String) -> String? = { it.takeIf { id -> id.isNotEmpty() } }

    private fun video(id: String, duration: Long? = 600) = YtSavedVideo(url = id, title = "t$id", duration = duration)

    private fun build(
        ids: List<String>,
        current: String?,
        ended: String? = null,
        cap: Int = YtQueuePersist.MAX_ITEMS,
        before: Int = YtQueuePersist.MAX_BEFORE,
    ) = YtQueuePersist.build(
        items = ids,
        currentKey = current,
        endedKey = ended,
        offline = false,
        audio = false,
        now = 7L,
        key = idKey,
        convert = { video(it) },
        cap = cap,
        before = before,
    )

    // --- build ---

    @Test
    fun buildKeepsOrderCurrentAndVersion() {
        val q = build(listOf("a", "b", "c"), "b")!!
        assertEquals(listOf("a", "b", "c"), q.items.map { it.url })
        assertEquals("b", q.currentId)
        assertEquals(1, q.currentIndex)
        assertEquals(YtQueuePersist.VERSION, q.version)
        assertEquals(7L, q.savedAt)
    }

    @Test
    fun buildDropsItemsWithoutIdAndKeepsTheFirstCopy() {
        val q = build(listOf("a", "", "b", "a", "c"), "b")!!
        assertEquals(listOf("a", "b", "c"), q.items.map { it.url })
    }

    @Test
    fun buildOfAnEmptyQueueIsNothing() {
        assertNull(build(emptyList(), null))
        assertNull(build(listOf("", ""), null))
    }

    @Test
    fun buildWithoutCurrentStartsAtTheFirst() {
        val q = build(listOf("a", "b"), "zzz")!!
        assertNull(q.currentId)
        assertEquals(0, q.currentIndex)
    }

    @Test
    fun buildKeepsTheEndedMarkOnlyForTheCurrentItem() {
        assertEquals("b", build(listOf("a", "b"), "b", ended = "b")!!.endedId)
        assertNull(build(listOf("a", "b"), "b", ended = "a")!!.endedId)
    }

    @Test
    fun buildCapsALongQueueAroundTheCurrentItem() {
        val ids = (0 until 1000).map { "v$it" }
        val q = build(ids, "v300")!!
        assertEquals(YtQueuePersist.MAX_ITEMS, q.items.size)
        // 50 before the current one, the rest after it
        assertEquals("v250", q.items.first().url)
        assertEquals("v300", q.items[q.currentIndex].url)
        assertEquals(50, q.currentIndex)
        assertEquals("v749", q.items.last().url)
        // near the end earlier ones fill up
        val late = build(ids, "v900")!!
        assertEquals("v500", late.items.first().url)
        assertEquals("v900", late.items[late.currentIndex].url)
    }

    @Test
    fun trimFillsUpWithEarlierItemsNearTheEnd() {
        val list = (0 until 10).toList()
        assertEquals(listOf(5, 6, 7, 8, 9), YtQueuePersist.trim(list, currentIndex = 9, cap = 5, before = 1))
        assertEquals(listOf(0, 1, 2, 3, 4), YtQueuePersist.trim(list, currentIndex = -1, cap = 5, before = 1))
        assertEquals(listOf(3, 4, 5, 6, 7), YtQueuePersist.trim(list, currentIndex = 4, cap = 5, before = 1))
        assertEquals(list, YtQueuePersist.trim(list, currentIndex = 4, cap = 50))
        assertTrue(YtQueuePersist.trim(list, 0, cap = 0).isEmpty())
    }

    // --- encode / decode ---

    @Test
    fun encodeDecodeRoundTrip() {
        val q = YtQueuePersist.build(
            items = listOf("a", "b"),
            currentKey = "b",
            endedKey = "b",
            offline = true,
            audio = true,
            now = 42L,
            key = idKey,
            convert = {
                YtSavedVideo(
                    url = "/watch?v=$it", title = "Title $it", uploaderName = "U", uploaderUrl = "/channel/x",
                    uploaderAvatar = "https://a", thumbnail = "https://t", duration = 61, uploaded = 5,
                    uploadedDate = "2026-01-01", views = 9, verified = true, isShort = it == "a",
                )
            },
        )!!
        val back = YtQueuePersist.decode(YtQueuePersist.encode(q)) { it.url.removePrefix("/watch?v=") }
        assertEquals(q, back)
    }

    @Test
    fun decodeIgnoresBrokenUnknownAndEmptyFiles() {
        assertNull(YtQueuePersist.decode(null, videoKey))
        assertNull(YtQueuePersist.decode("", videoKey))
        assertNull(YtQueuePersist.decode("{not json", videoKey))
        assertNull(YtQueuePersist.decode("[1,2,3]", videoKey))
        // another version (an older or a newer app)
        assertNull(YtQueuePersist.decode("""{"version":2,"items":[{"url":"a"}]}""", videoKey))
        // no version at all
        assertNull(YtQueuePersist.decode("""{"items":[{"url":"a"}]}""", videoKey))
        // no videos
        assertNull(YtQueuePersist.decode("""{"version":1,"items":[]}""", videoKey))
        // a key that throws on a strange url is no crash
        assertNull(YtQueuePersist.decode("""{"version":1,"items":[{"url":"a"}]}""") { error("bad") })
    }

    @Test
    fun decodeToleratesUnknownFieldsAndCleansUp() {
        val text = """
            {"version":1,"future":true,"items":[
              {"url":"a","title":"A","extra":1},{"url":""},{"url":"b"},{"url":"a"}],
             "currentId":"gone","currentIndex":9}
        """.trimIndent()
        val q = YtQueuePersist.decode(text, videoKey)!!
        assertEquals(listOf("a", "b"), q.items.map { it.url })
        assertNull(q.currentId)
        assertEquals(1, q.currentIndex)
    }

    @Test
    fun decodeFindsTheCurrentItemById() {
        val text = """{"version":1,"items":[{"url":"a"},{"url":"b"},{"url":"c"}],"currentId":"c","currentIndex":0,"endedId":"a"}"""
        val q = YtQueuePersist.decode(text, videoKey)!!
        assertEquals("c", q.currentId)
        assertEquals(2, q.currentIndex)
        // an ended mark of another item is dropped
        assertNull(q.endedId)
    }

    // --- resume ---

    @Test
    fun resumeAtTheCurrentItem() {
        val q = YtSavedQueue(YtQueuePersist.VERSION, listOf(video("a"), video("b"), video("c")), currentId = "b", currentIndex = 1)
        assertEquals(1, YtQueuePersist.resumeIndex(q, videoKey))
    }

    @Test
    fun resumeFallsBackToTheIndex() {
        val q = YtSavedQueue(YtQueuePersist.VERSION, listOf(video("a"), video("b"), video("c")), currentId = null, currentIndex = 2)
        assertEquals(2, YtQueuePersist.resumeIndex(q, videoKey))
        assertEquals(2, YtQueuePersist.resumeIndex(q.copy(currentIndex = 99), videoKey))
    }

    @Test
    fun resumeAfterAnEndedItemGoesToTheNext() {
        val q = YtSavedQueue(YtQueuePersist.VERSION, listOf(video("a"), video("b"), video("c")), currentId = "b", currentIndex = 1, endedId = "b")
        assertEquals(2, YtQueuePersist.resumeIndex(q, videoKey))
        // the last one ended: it starts over
        val last = q.copy(currentId = "c", currentIndex = 2, endedId = "c")
        assertEquals(2, YtQueuePersist.resumeIndex(last, videoKey))
    }

    @Test
    fun liveStreamsAreKept() {
        val q = YtQueuePersist.build(listOf("live", "b"), "live", null, false, false, 0L, idKey, { video(it, duration = if (it == "live") -1 else 60) })!!
        assertEquals(listOf("live", "b"), q.items.map { it.url })
        assertEquals(-1L, q.items[0].duration)
        assertEquals(0, YtQueuePersist.resumeIndex(q, videoKey))
    }

    // --- debounce and write decision ---

    @Test
    fun waitIsTheDebounceButNeverPastTheLimit() {
        assertEquals(YtQueuePersist.DEBOUNCE_MS, YtQueuePersist.waitMs(firstChangeAt = 1_000, now = 1_000))
        assertEquals(YtQueuePersist.DEBOUNCE_MS, YtQueuePersist.waitMs(firstChangeAt = 1_000, now = 2_000))
        assertEquals(200L, YtQueuePersist.waitMs(firstChangeAt = 1_000, now = 1_000 + YtQueuePersist.MAX_WAIT_MS - 200))
        assertEquals(0L, YtQueuePersist.waitMs(firstChangeAt = 1_000, now = 1_000 + YtQueuePersist.MAX_WAIT_MS + 5_000))
    }

    @Test
    fun writeRules() {
        // nothing before the start decided about the saved queue
        assertFalse(YtQueuePersist.shouldWrite(ready = false, queueEmpty = false, revision = 3, written = 1, force = true))
        // an empty queue never overwrites the saved one (the player's end empties it)
        assertFalse(YtQueuePersist.shouldWrite(ready = true, queueEmpty = true, revision = 3, written = 1, force = true))
        // a change is written, no change is not, unless forced
        assertTrue(YtQueuePersist.shouldWrite(ready = true, queueEmpty = false, revision = 3, written = 1, force = false))
        assertFalse(YtQueuePersist.shouldWrite(ready = true, queueEmpty = false, revision = 3, written = 3, force = false))
        assertTrue(YtQueuePersist.shouldWrite(ready = true, queueEmpty = false, revision = 3, written = 3, force = true))
    }

    // --- end of an item ---

    @Test
    fun endRules() {
        // the player emptied to load another video: no end
        assertEquals(EndAction.NONE, YtQueuePersist.endAction(timelineEmpty = true, repeatOne = false, hasNext = false))
        assertEquals(EndAction.NONE, YtQueuePersist.endAction(timelineEmpty = false, repeatOne = true, hasNext = false))
        assertEquals(EndAction.ITEM_ENDED, YtQueuePersist.endAction(timelineEmpty = false, repeatOne = false, hasNext = true))
        assertEquals(EndAction.FINISHED, YtQueuePersist.endAction(timelineEmpty = false, repeatOne = false, hasNext = false))
    }

    // --- restore ---

    @Test
    fun restoreOnlyIntoAnEmptyQueueWithoutPlayer() {
        assertTrue(YtQueuePersist.restoreCandidate(hasSaved = true, queueEmpty = true, playerAlive = false))
        assertFalse(YtQueuePersist.restoreCandidate(hasSaved = false, queueEmpty = true, playerAlive = false))
        assertFalse(YtQueuePersist.restoreCandidate(hasSaved = true, queueEmpty = false, playerAlive = false))
        assertFalse(YtQueuePersist.restoreCandidate(hasSaved = true, queueEmpty = true, playerAlive = true))
    }

    // --- join ---

    @Test
    fun aStartedVideoPlaysBeforeTheRestoredQueue() {
        val joined = YtQueuePersist.join(listOf("a", "b", "c"), listOf("n"), "b", JoinPlace.BEFORE_CURRENT, idKey)
        assertEquals(listOf("a", "n", "b", "c"), joined)
    }

    @Test
    fun aVideoAlreadyQueuedMovesBeforeTheCurrentItem() {
        val joined = YtQueuePersist.join(listOf("a", "b", "c", "d"), listOf("d"), "b", JoinPlace.BEFORE_CURRENT, idKey)
        assertEquals(listOf("a", "d", "b", "c"), joined)
        val earlier = YtQueuePersist.join(listOf("a", "b", "c"), listOf("a"), "c", JoinPlace.BEFORE_CURRENT, idKey)
        assertEquals(listOf("b", "a", "c"), earlier)
    }

    @Test
    fun theCurrentItemItselfKeepsItsPlace() {
        val joined = YtQueuePersist.join(listOf("a", "b", "c"), listOf("b"), "b", JoinPlace.BEFORE_CURRENT, idKey)
        assertEquals(listOf("a", "b", "c"), joined)
    }

    @Test
    fun aVideoFromAPlaylistGoesToTheEnd() {
        assertEquals(JoinPlace.AT_END, YtQueuePersist.joinPlace(hasListContext = true))
        assertEquals(JoinPlace.BEFORE_CURRENT, YtQueuePersist.joinPlace(hasListContext = false))
        val joined = YtQueuePersist.join(listOf("a", "b", "c"), listOf("b2", "a"), "b", JoinPlace.AT_END, idKey)
        assertEquals(listOf("b", "c", "b2", "a"), joined)
    }

    @Test
    fun aDraftJoinsInItsOrderOnceEach() {
        val joined = YtQueuePersist.join(listOf("a", "b", "c"), listOf("x", "c", "x", "y"), "b", JoinPlace.BEFORE_CURRENT, idKey)
        assertEquals(listOf("a", "x", "c", "y", "b"), joined)
    }

    @Test
    fun joinWithoutAnchorPutsItFirst() {
        assertEquals(listOf("n", "a", "b"), YtQueuePersist.join(listOf("a", "b"), listOf("n"), null, JoinPlace.BEFORE_CURRENT, idKey))
        assertEquals(listOf("n", "a", "b"), YtQueuePersist.join(listOf("a", "b"), listOf("n"), "gone", JoinPlace.BEFORE_CURRENT, idKey))
        assertEquals(listOf("n"), YtQueuePersist.join(emptyList(), listOf("n"), null, JoinPlace.BEFORE_CURRENT, idKey))
    }

    @Test
    fun joinOfNothingUsableKeepsTheList() {
        val list = listOf("a", "b")
        assertEquals(list, YtQueuePersist.join(list, listOf(""), "a", JoinPlace.BEFORE_CURRENT, idKey))
        assertEquals(list, YtQueuePersist.join(list, emptyList(), "a", JoinPlace.AT_END, idKey))
    }

    @Test
    fun countOnlyForARealQueue() {
        assertFalse(YtQueuePersist.showsCount(1))
        assertTrue(YtQueuePersist.showsCount(2))
    }

    @Test
    fun aSavedQueueOfAnyAgeIsRestored() {
        // "immer": the save time never decides
        val old = YtSavedQueue(YtQueuePersist.VERSION, listOf(video("a")), currentId = "a", savedAt = 1L)
        assertNotNull(YtQueuePersist.decode(YtQueuePersist.encode(old), videoKey))
    }
}
