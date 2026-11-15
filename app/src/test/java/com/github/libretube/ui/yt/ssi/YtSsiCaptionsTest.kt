package com.github.libretube.ui.yt.ssi

import com.github.libretube.ui.yt.news.YtNewsCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YtSsiCaptionsTest {
    @Test
    fun json3UrlReplacesTheFormat() {
        assertEquals(
            "https://www.youtube.com/api/timedtext?v=abc&lang=de&fmt=json3",
            YtSsiCaptions.json3Url("https://www.youtube.com/api/timedtext?v=abc&lang=de&fmt=ttml"),
        )
        assertEquals("https://x/t?fmt=json3&v=1", YtSsiCaptions.json3Url("https://x/t?fmt=vtt&v=1"))
        assertEquals("https://x/t?v=1&fmt=json3", YtSsiCaptions.json3Url("https://x/t?v=1"))
    }

    /** YouTube's speech-recognition json3: a window event, words with tOffsetMs, line breaks. */
    private val asr = """
        {"wireMagic":"pb3","pens":[{}],"events":[
          {"tStartMs":0,"dDurationMs":50000,"id":1,"wpWinPosId":1},
          {"tStartMs":1200,"dDurationMs":3000,"wWinId":1,"segs":[{"utf8":"das","acAsrConf":0},{"utf8":" stimmt","tOffsetMs":320},{"utf8":" so","tOffsetMs":800}]},
          {"tStartMs":2900,"wWinId":1,"aAppend":1,"segs":[{"utf8":"\n"}]},
          {"tStartMs":4200,"dDurationMs":2000,"wWinId":1,"segs":[{"utf8":"[Musik]"}]},
          {"tStartMs":6500,"dDurationMs":2500,"wWinId":1,"segs":[{"utf8":"nicht"},{"utf8":" ganz","tOffsetMs":400}]}
        ]}
    """.trimIndent()

    @Test
    fun speechRecognitionGivesWordStarts() {
        val track = YtSsiCaptions.parseJson3(asr)!!
        assertEquals(YtSsiTiming.WORD, track.timing)
        assertEquals(listOf("das", "stimmt", "so", "nicht", "ganz"), track.units.map { it.text })
        assertEquals(listOf(1200L, 1520L, 2000L, 6500L, 6900L), track.units.map { it.startMs })
        // a word ends where the next starts, the last one of a line at the line's end
        assertEquals(1520L, track.units[0].endMs)
        assertEquals(4200L, track.units[2].endMs)
        track.units.forEach { assert(it.word) }
    }

    @Test
    fun manualTrackIsReadByLine() {
        val manual = """{"events":[{"tStartMs":1000,"dDurationMs":2500,"segs":[{"utf8":"Erste Zeile."}]},{"tStartMs":4000,"dDurationMs":0,"segs":[{"utf8":"Zweite"},{"utf8":" Zeile"}]}]}"""
        val track = YtSsiCaptions.parseJson3(manual)!!
        assertEquals(YtSsiTiming.LINE, track.timing)
        assertEquals(listOf("Erste Zeile.", "Zweite Zeile"), track.units.map { it.text })
        assertEquals(3500L, track.units[0].endMs)
        // no duration: held up to 8 s
        assertEquals(12_000L, track.units[1].endMs)
    }

    @Test
    fun notJson3IsNull() {
        assertNull(YtSsiCaptions.parseJson3("<tt><p begin=\"1s\">x</p></tt>"))
    }

    @Test
    fun ttmlCuesAreLines() {
        val track = YtSsiCaptions.lines(listOf(YtNewsCue(1.0, "a (Applaus) b"), YtNewsCue(3.0, "c")))
        assertEquals(YtSsiTiming.LINE, track.timing)
        assertEquals(listOf("a b", "c"), track.units.map { it.text })
        assertEquals(3000L, track.units[0].endMs)
    }
}
