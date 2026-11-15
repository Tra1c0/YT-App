package com.github.libretube.ui.yt.archive.risk

import com.github.libretube.ui.yt.archive.YtArchiveCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The learning model: cold start, learning, labels, decay, caps, persistence, reason, threshold. */
class YtRiskModelTest {
    private val t0 = 1_700_000_000_000L
    private val day = YtRiskModel.DAY_MS

    private var next = 0

    private fun video(
        title: String,
        channel: String? = "UCplain",
        description: String? = null,
        id: String = "v${next++}",
    ) = YtArchiveCandidate(id, channel, channel?.let { "Name $it" }, title, description, t0, 600)

    private val plainTitles = listOf(
        "Wandern im Schwarzwald", "Kochen mit Gemüse", "Mein Garten im Herbst", "Fahrradtour an der Elbe",
        "Brot backen leicht gemacht", "Gitarre lernen Akkorde", "Tipps zum Energiesparen", "Unboxing Kamera",
    )

    /** [n] stayed videos across many channels, with everyday titles. */
    private fun YtRiskModel.keptBackground(n: Int, now: Long = t0) {
        repeat(n) { i -> observe(video(plainTitles[i % plainTitles.size] + " $i", "UCother${i % 25}"), false, now) }
    }

    @Test
    fun coldStartPlainVideoIsLowWithoutReason() {
        val model = YtRiskModel()
        val score = model.score(video("Wandern im Schwarzwald"), null, TestWords)
        assertEquals(0.5 / 30.5, score.risk, 1e-6)
        assertFalse(score.high)
        assertNull(score.reason)
    }

    @Test
    fun coldStartKeywordsRaiseConservatively() {
        val model = YtRiskModel()
        val plain = model.score(video("Ein Abend im Kino"), null, TestWords).risk
        val one = model.score(video("Der Pate Full Movie HD"), null, TestWords)
        assertTrue(one.risk > plain * 3)
        assertFalse("one strong phrase alone is no alert", one.high)
        assertEquals("Stichwörter: Full Movie", one.reason)

        val two = model.score(video("Der Pate full movie – watch before it gets deleted"), null, TestWords)
        assertTrue(two.high)
        assertEquals("Stichwörter: Full Movie, before it gets deleted", two.reason)

        val weak = model.score(video("Skandal um das Drama"), null, TestWords)
        assertFalse(weak.high)
        assertTrue(weak.risk < 0.1)
    }

    @Test
    fun oneDeletionIsNotEnough() {
        val model = YtRiskModel()
        model.keptBackground(100)
        model.observe(video("Wochenrückblick", "UCone"), true, t0)
        val score = model.score(video("Wochenrückblick neu", "UCone"), null, TestWords)
        val stranger = model.score(video("Wochenrückblick neu", "UCnew"), null, TestWords)
        assertFalse(score.high)
        assertTrue(score.risk < 0.1)
        assertTrue(score.risk > stranger.risk)
    }

    @Test
    fun learningAChannelMovesItsScoreAndNamesIt() {
        val model = YtRiskModel()
        model.keptBackground(300)
        val before = model.score(video("Folge 99", "UCrisky"), null, TestWords).risk
        repeat(15) { model.observe(video("Clip $it", "UCrisky"), true, t0) }
        repeat(5) { model.observe(video("Clip ${it + 50}", "UCrisky"), false, t0) }
        val after = model.score(video("Folge 99", "UCrisky"), null, TestWords)
        assertTrue(after.risk > before * 10)
        assertTrue("${after.risk}", after.high)
        assertTrue(after.reason!!, after.reason!!.startsWith("Kanal hat 15 von 20 Videos gelöscht"))

        // a channel whose videos all stayed sits below a channel nothing is known of
        repeat(40) { model.observe(video("Clip $it", "UCsafe"), false, t0) }
        val safe = model.score(video("Folge 99", "UCsafe"), null, TestWords).risk
        val unknown = model.score(video("Folge 99", "UCunknown"), null, TestWords).risk
        assertTrue(safe < unknown)
    }

    @Test
    fun learningATopicMovesUnknownChannelsAndNamesIt() {
        val model = YtRiskModel()
        model.keptBackground(300)
        val before = model.score(video("Neuer Rechtsstreit beim Verein", "UCfresh"), null, TestWords)
        repeat(10) { model.observe(video("Rechtsstreit Folge $it", "UCtopic$it"), true, t0) }
        val after = model.score(video("Neuer Rechtsstreit beim Verein", "UCfresh"), null, TestWords)
        assertTrue(after.risk > before.risk * 2)
        assertTrue(after.reason!!, after.reason!!.contains("Thema: Rechtsstreit"))
        assertTrue(model.factors(video("Neuer Rechtsstreit beim Verein", "UCfresh")).words > 1.0)
    }

    @Test
    fun theSameLabelTwiceCountsOnceAndAChangeTakesTheOldOneBack() {
        val model = YtRiskModel()
        val v = video("Wandern", "UCa", id = "same")
        assertTrue(model.observe(v, false, t0))
        assertFalse(model.observe(v, false, t0 + 1000))
        assertEquals(1.0, model.state.keptDocs, 1e-9)
        assertTrue(model.observe(v, true, t0 + 2000))
        assertEquals(0.0, model.state.keptDocs, 1e-6)
        assertEquals(1.0, model.state.goneDocs, 1e-9)
        assertEquals(1.0, model.state.channels["UCa"]!!.gone, 1e-9)
        assertEquals(0.0, model.state.channels["UCa"]!!.kept, 1e-6)
        assertEquals(Triple(1, 1, 0), model.counts())
    }

    @Test
    fun countsDecayWithTheHalfLifeAndFadeOut() {
        val model = YtRiskModel()
        model.observe(video("Leak der Woche", "UCd"), true, t0)
        model.decay(t0 + 180 * day)
        assertEquals(0.5, model.state.goneDocs, 1e-6)
        assertEquals(0.5, model.state.channels["UCd"]!!.gone, 1e-6)
        // less than a day later nothing changes
        model.decay(t0 + 180 * day + day / 2)
        assertEquals(0.5, model.state.goneDocs, 1e-6)
        // after years the channel and its words are gone from the stored model
        model.decay(t0 + 180 * day * 6)
        assertTrue(model.state.channels.isEmpty())
        assertTrue(model.state.words.isEmpty())
    }

    @Test
    fun oldLabelsGoAndCapsHold() {
        val model = YtRiskModel()
        model.observe(video("Alt", "UCold", id = "old"), false, t0)
        val later = t0 + (YtRiskModel.LABEL_DAYS + 1) * day
        repeat(YtRiskModel.MAX_LABELS + 200) { i ->
            model.observe(video("unique$i word$i other${i}x", "UCch$i"), i % 50 == 0, later)
        }
        assertFalse("old" in model.state.labels)
        assertTrue(model.state.labels.size <= YtRiskModel.MAX_LABELS)
        assertTrue(model.state.channels.size <= YtRiskModel.MAX_CHANNELS)
        assertTrue(model.state.words.size <= YtRiskModel.MAX_WORDS)
        // words of vanished videos survive the cut first
        assertTrue("unique0" in model.state.words)
    }

    @Test
    fun persistenceRoundTripKeepsTheScores() {
        val model = YtRiskModel()
        model.keptBackground(50)
        repeat(6) { model.observe(video("Rechtsstreit $it", "UCr"), true, t0) }
        val probe = video("Rechtsstreit neu", "UCr", id = "probe")
        val back = YtRiskModel.decode(model.encode())
        assertEquals(model.factors(probe), back.factors(probe))
        assertEquals(model.state.labels, back.state.labels)
        assertEquals(model.state.decayedAt, back.state.decayedAt)
    }

    @Test
    fun aiWeightShrinksAsLabelsGrow() {
        val cold = YtRiskModel()
        val probe = video("Interview mit dem Bürgermeister", "UCx")
        val sure = YtRiskAiAnswer(0.97, "Rechtsstreit mit dem Label", t0)
        val coldScore = cold.score(probe, sure, TestWords)
        assertTrue(coldScore.high)
        assertEquals("KI: Rechtsstreit mit dem Label", coldScore.reason)
        assertFalse(cold.score(probe, YtRiskAiAnswer(0.9, null, t0), TestWords).high)
        // a low AI estimate brings the risk under the cold base
        assertTrue(cold.score(probe, YtRiskAiAnswer(0.02, null, t0), TestWords).risk < 0.5 / 30.5)
        // neutral leaves it
        assertEquals(cold.factors(probe).risk, cold.factors(probe, YtRiskAiAnswer(YtRiskModel.AI_NEUTRAL)).risk, 1e-9)

        val taught = YtRiskModel()
        taught.keptBackground(200)
        repeat(60) { taught.observe(video("Clip $it", "UCt${it % 30}"), true, t0) }
        assertTrue(taught.factors(probe, sure).ai < cold.factors(probe, sure).ai / 2)
        // keywords lose weight the same way
        val kw = video("Full Movie", "UCx")
        assertTrue(taught.factors(kw).keywords < cold.factors(kw).keywords * 0.6)
    }

    @Test
    fun theAlertLineIsThePublishedThreshold() {
        assertEquals(0.35, YtRiskModel.HIGH, 0.0)
        val base = YtRiskModel.logit(0.5 / 30.5)
        val words = TestWords
        val probe = video("x")
        // just under and at the line, through the AI factor alone
        fun withAi(r: Double) = YtRiskModel().factors(probe, YtRiskAiAnswer(r))
        val under = generateSequence(0.5) { it + 0.001 }.first { withAi(it).risk >= 0.34 }
        val at = generateSequence(under) { it + 0.0005 }.first { withAi(it).risk >= 0.35 }
        assertFalse(YtRiskModel().score(probe, YtRiskAiAnswer(under), words).high)
        assertTrue(YtRiskModel().score(probe, YtRiskAiAnswer(at), words).high)
        assertTrue(base < 0)
    }

    @Test
    fun reasonNamesAtMostTheTwoStrongestFactors() {
        val f = YtRiskFactors(
            base = -4.0,
            channel = 2.0, channelGone = 3, channelTotal = 40,
            words = 1.0, topics = listOf("Rechtsstreit"),
            keywords = 0.3, keywordLabels = listOf("Drama"),
            ai = 1.5, aiRisk = 0.6, aiReason = null,
        )
        assertEquals("Kanal hat 3 von 40 Videos gelöscht · KI schätzt 60 %", YtRiskModel.reason(f, TestWords))
        assertNull(YtRiskModel.reason(YtRiskFactors(base = -4.0, keywords = 0.3, keywordLabels = listOf("Drama")), TestWords))
    }

    @Test
    fun channelNameStandsInForAMissingId() {
        val model = YtRiskModel()
        val v = YtArchiveCandidate("n1", null, " Some Channel ", "Titel", null, null, null)
        model.observe(v, true, t0)
        assertEquals(1.0, model.state.channels["name:some channel"]!!.gone, 1e-9)
        assertNull(YtRiskModel.channelKey(YtArchiveCandidate("n2", " ", null, "Titel", null, null, null)))
    }
}

/** German reason words as the app's strings have them. */
object TestWords : YtRiskWords {
    override fun channel(gone: Int, total: Int) = "Kanal hat $gone von $total Videos gelöscht"
    override fun topic(words: List<String>) = "Thema: " + words.joinToString(", ")
    override fun keywords(words: List<String>) = "Stichwörter: " + words.joinToString(", ")
    override fun ai(reason: String?, percent: Int) = if (reason != null) "KI: $reason" else "KI schätzt $percent %"
}
