package com.github.libretube.ui.yt.ssi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtSsiSourcesTest {
    private val said = "Die neue Studie zeigt dass der Meeresspiegel bis zum Jahr 2100 um einen Meter steigen könnte sagen die Forscher"

    private fun item(id: String, channel: String, text: String = "", desc: String? = null, up: Long? = null, title: String = "Titel $id", dur: Long? = null, vec: FloatArray? = null) =
        YtSsiSources.Item(id, "c-$channel", channel, title, desc, up, dur, text, vec)

    @Test
    fun threeVideosOneOrigin() {
        val origins = YtSsiSources.cluster(
            listOf(
                item("b", "Kanal B", said + " heute im Studio", up = 2_000),
                item("a", "Kanal A", said, up = 1_000),
                item("c", "Kanal C", "Wie berichtet: $said", up = 3_000),
                item("d", "Kanal D", up = 500),
            ),
        )
        assertEquals(2, origins.size)
        val big = origins.first { it.size == 3 }
        assertEquals(listOf("a", "b", "c"), big.videos) // earliest first
        assertEquals("a", big.key)
        assertTrue(YtSsiSameOrigin.NEAR_DUPLICATE in big.reasons)
        assertEquals(setOf("d"), origins.first { it.size == 1 }.videos.toSet())
        assertTrue(origins.first { it.size == 1 }.reasons.isEmpty())
    }

    @Test
    fun vectorsAlikeCountOnce() {
        val v = floatArrayOf(0.6f, 0.8f, 0f)
        val w = floatArrayOf(0.61f, 0.79f, 0.01f)
        val origins = YtSsiSources.cluster(listOf(item("a", "A", "eins zwei", vec = v), item("b", "B", "drei vier", vec = w), item("c", "C", "fünf", vec = floatArrayOf(0f, 0f, 1f))))
        assertEquals(2, origins.size)
    }

    @Test
    fun theSameCitedSourceFromTwoChannels() {
        val a = item("a", "A", desc = "Quelle: https://www.nature.com/articles/s41586-024-1234?utm_source=yt")
        val b = item("b", "B", desc = "Studie: www.nature.com/articles/s41586-024-1234/ und mehr")
        val origins = YtSsiSources.cluster(listOf(a, b))
        assertEquals(1, origins.size)
        assertEquals(setOf(YtSsiSameOrigin.SAME_LINK), origins[0].reasons)
    }

    @Test
    fun profileLinksAndOneChannelsOwnSiteDoNotJoin() {
        val a = item("a", "A", desc = "Unterstützt mich: https://patreon.com/kanal https://instagram.com/x https://www.youtube.com/@kanal")
        val b = item("b", "B", desc = "https://patreon.com/kanal https://instagram.com/x")
        assertEquals(2, YtSsiSources.cluster(listOf(a, b)).size)
        val c = item("c", "A", desc = "Mehr auf https://meine-seite.de/blog")
        val d = item("d", "A", desc = "Mehr auf https://meine-seite.de/blog")
        assertEquals(2, YtSsiSources.cluster(listOf(c, d)).size)
    }

    @Test
    fun aDescriptionLinkingAnotherVideoOfTheSet() {
        val a = item("aaaaaaaaaaa", "A")
        val b = item("bbbbbbbbbbb", "B", desc = "Reaktion auf https://youtu.be/aaaaaaaaaaa")
        val origins = YtSsiSources.cluster(listOf(a, b))
        assertEquals(1, origins.size)
        assertEquals(setOf(YtSsiSameOrigin.LINKS_VIDEO), origins[0].reasons)
        // both reacting to the same video outside the set
        val c = item("c", "C", desc = "Original: https://www.youtube.com/watch?v=zzzzzzzzzzz&t=12s")
        val d = item("d", "D", desc = "https://youtu.be/zzzzzzzzzzz")
        assertEquals(1, YtSsiSources.cluster(listOf(c, d)).size)
    }

    @Test
    fun reuploads() {
        val a = item("a", "A", title = "Die große Rede (ganz)", dur = 600)
        val b = item("b", "B", title = "die GROSSE Rede – ganz", dur = 602)
        val c = item("c", "C", title = "Die große Rede (ganz)", dur = 900)
        val origins = YtSsiSources.cluster(listOf(a, b, c))
        assertEquals(2, origins.size)
        assertTrue(origins.any { it.videos.toSet() == setOf("a", "b") && YtSsiSameOrigin.REUPLOAD in it.reasons })
        assertFalse(YtSsiSources.reupload(a, a.copy(videoId = "x"))) // same channel
    }

    @Test
    fun linksAreNormalised() {
        val links = YtSsiSources.links("Siehe https://www.Example.org/Studie/?ref=1, http://m.example.org/studie und https://youtube.com/playlist?list=PL1 sowie https://youtu.be/abcdefghijk.")
        assertEquals(setOf("example.org/studie", "yt:abcdefghijk"), links)
    }

    @Test
    fun shinglesNeedEnoughWords() {
        assertTrue(YtSsiSources.shingles("zu kurz hier").isEmpty())
        assertEquals(1.0, YtSsiSources.jaccard(YtSsiSources.shingles(said), YtSsiSources.shingles(said)), 1e-9)
    }
}
