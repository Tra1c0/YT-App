package com.github.libretube.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class YtTitleCalmTest {
    private fun case(title: String) = YtTitleCalm.calm(title, fixCase = true, fixMarks = false)
    private fun marks(title: String) = YtTitleCalm.calm(title, fixCase = false, fixMarks = true)

    @Test
    fun `a shouting title reads in sentence case`() {
        assertEquals("I bought the cheapest car in the world", case("I BOUGHT THE CHEAPEST CAR IN THE WORLD"))
        assertEquals("This changes everything. Watch until the end", case("THIS CHANGES EVERYTHING. WATCH UNTIL THE END"))
    }

    @Test
    fun `acronyms stay in a shouting title`() {
        assertEquals("NASA found something on mars", case("NASA FOUND SOMETHING ON MARS"))
        assertEquals("BMW and HTML stay, gold goes", case("BMW AND HTML STAY, GOLD GOES"))
        assertEquals("GTA 6 trailer in 4K", case("GTA 6 TRAILER IN 4K"))
        assertEquals("Why the USA never stopped building", case("WHY THE USA NEVER STOPPED BUILDING"))
        assertEquals("My new NVIDIA GPU is here", case("MY NEW NVIDIA GPU IS HERE"))
    }

    @Test
    fun `parts after a colon, a dash and brackets start with a capital`() {
        assertEquals("Top 10: Best games ever", case("TOP 10: BEST GAMES EVER"))
        assertEquals("Song name - Artist name (Official video)", case("SONG NAME - ARTIST NAME (OFFICIAL VIDEO)"))
        // a hyphen inside a word is no break
        assertEquals("Mein E-mail problem endlich gelöst", case("MEIN E-MAIL PROBLEM ENDLICH GELÖST"))
    }

    @Test
    fun `english I and contractions`() {
        assertEquals("I'm done, I don't care anymore", case("I'M DONE, I DON'T CARE ANYMORE"))
    }

    @Test
    fun `german letters are lowered without losing them`() {
        assertEquals("Größte straße der welt über 900 KM", case("GRÖẞTE STRAẞE DER WELT ÜBER 900 KM"))
        assertEquals("Übermorgen ändert sich alles", case("ÜBERMORGEN ÄNDERT SICH ALLES"))
    }

    @Test
    fun `a turkish phone does not change the result`() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("This is insane", case("THIS IS INSANE"))
            assertEquals("Biggest win in history", case("BIGGEST WIN IN HISTORY"))
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun `one shouted word in a normal title is lowered`() {
        assertEquals("This is insane", case("This is INSANE"))
        assertEquals("You will NOT believe what happened", case("You will NOT believe what HAPPENED"))
        assertEquals("Watch this before buying", case("WATCH this before buying"))
    }

    @Test
    fun `short capital words and acronyms stay in a normal title`() {
        assertEquals("My trip to the USA with the new GoPro", case("My trip to the USA with the new GoPro"))
        assertEquals("Review of the NASA mission and the GTA map", case("Review of the NASA mission and the GTA map"))
        assertEquals("Testing the RTX 4090 in 4K", case("Testing the RTX 4090 in 4K"))
        assertEquals("Ein Tag bei UNICEF in Berlin", case("Ein Tag bei UNICEF in Berlin"))
    }

    @Test
    fun `german titles keep a capital on the shouted word`() {
        assertEquals("Das ist der Wahnsinn heute", case("Das ist der WAHNSINN heute"))
    }

    @Test
    fun `mixed case words are left alone`() {
        assertEquals("iPhone vs. MacBook comparison", case("iPhone vs. MacBook comparison"))
    }

    @Test
    fun `short titles are left alone`() {
        assertEquals("LOL", case("LOL"))
        assertEquals("WOW!", case("WOW!"))
        assertEquals("BEST DAY", case("BEST DAY"))
    }

    @Test
    fun `titles without case are left alone`() {
        assertEquals("東京の夜 2024", case("東京の夜 2024"))
        assertEquals("12345 67890", case("12345 67890"))
    }

    @Test
    fun `punctuation runs collapse`() {
        assertEquals("You won't believe this!", marks("You won't believe this!!!"))
        assertEquals("Is this real?", marks("Is this real???"))
        assertEquals("What?!", marks("What?!?!"))
        assertEquals("No way!?", marks("No way!?!?!!"))
        assertEquals("Wait!", marks("Wait‼"))
        assertEquals("Hmm...", marks("Hmm......"))
        assertEquals("Hmm...", marks("Hmm..."))
        assertEquals("Hi!", marks("Hi!"))
        assertEquals("Hä?!", marks("Hä?!"))
        assertEquals("Wow!", marks("Wow！！！"))
    }

    @Test
    fun `emoji runs become one emoji`() {
        assertEquals("Fire 🔥", marks("Fire 🔥🔥🔥"))
        assertEquals("😱 Unbelievable 🔥", marks("😱😱😱 Unbelievable 🔥 💯 🤯"))
        assertEquals("Nice 👍🏽 work", marks("Nice 👍🏽👍🏽 work"))
        assertEquals("Family 👨‍👩‍👧 day", marks("Family 👨‍👩‍👧👨‍👩‍👧 day"))
        assertEquals("Love ❤️ it", marks("Love ❤️❤️❤️ it"))
        assertEquals("Deutschland 🇩🇪 vs", marks("Deutschland 🇩🇪🇩🇪 vs"))
        assertEquals("Top 1️⃣ pick", marks("Top 1️⃣2️⃣ pick"))
        // single emoji and plain digits stay
        assertEquals("Good 🙂 day 2 of 3", marks("Good 🙂 day 2 of 3"))
    }

    @Test
    fun `both together`() {
        assertEquals("This is insane! 🔥", YtTitleCalm.calm("THIS IS INSANE!!! 🔥🔥🔥"))
        assertEquals("NASA did what?! 😱", YtTitleCalm.calm("NASA DID WHAT?!?! 😱😱"))
    }

    @Test
    fun `never empty`() {
        assertEquals("🔥", YtTitleCalm.calm("🔥🔥🔥"))
        assertEquals("!", YtTitleCalm.calm("!!!"))
        assertEquals("   ", YtTitleCalm.calm("   "))
        assertEquals("", YtTitleCalm.calm(""))
        for (t in listOf("?", "...", "🇩🇪", "a", "ẞ", "ǆ", "x́")) assertTrue(YtTitleCalm.calm(t).isNotBlank())
    }

    @Test
    fun `nothing to do gives the same title`() {
        val title = "A calm title about cooking pasta"
        assertTrue(title === YtTitleCalm.calm(title))
        assertEquals("ABC", YtTitleCalm.calm("ABC", fixCase = false, fixMarks = false))
    }
}
