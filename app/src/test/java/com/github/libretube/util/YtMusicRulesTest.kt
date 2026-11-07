package com.github.libretube.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtMusicRulesTest {

    private fun music(
        id: String? = "UCx",
        name: String? = "Some Channel",
        category: String? = "Education",
        title: String? = "A talk",
        marked: Set<String> = emptySet(),
    ) = YtMusicRules.isMusic(id, name, category, title, marked)

    @Test
    fun channelNames() {
        assertTrue(YtMusicRules.channelLooksMusic("Daft Punk - Topic"))
        assertTrue(YtMusicRules.channelLooksMusic("TaylorSwiftVEVO"))
        assertTrue(YtMusicRules.channelLooksMusic("Proximity Music"))
        assertFalse(YtMusicRules.channelLooksMusic("Topic Talks"))
        assertFalse(YtMusicRules.channelLooksMusic("vevo fan reviews"))
        assertFalse(YtMusicRules.channelLooksMusic(null))
        assertFalse(YtMusicRules.channelLooksMusic("  "))
    }

    @Test
    fun plainVideoIsNotMusic() = assertFalse(music())

    @Test
    fun categoryCounts() {
        assertTrue(music(category = "Music"))
        assertTrue(music(category = " musik "))
        assertFalse(music(category = null))
        assertFalse(music(category = ""))
    }

    @Test
    fun markedChannelByIdOrName() {
        assertTrue(music(marked = setOf("UCx")))
        assertTrue(music(id = null, marked = setOf("Some Channel")))
        assertFalse(music(marked = setOf("UCother")))
    }

    @Test
    fun titleHints() {
        assertTrue(music(title = "Song (Official Music Video)"))
        assertTrue(music(title = "Song [Official Audio]"))
        assertFalse(music(title = "Why music theory matters"))
    }

    @Test
    fun savedPosition() {
        assertTrue(YtMusicRules.restoreSavedPosition(true, isMusic = false, musicFromStart = true))
        assertFalse(YtMusicRules.restoreSavedPosition(true, isMusic = true, musicFromStart = true))
        assertTrue(YtMusicRules.restoreSavedPosition(true, isMusic = true, musicFromStart = false))
        assertFalse(YtMusicRules.restoreSavedPosition(false, isMusic = false, musicFromStart = false))
    }

    @Test
    fun backgroundAudio() {
        assertTrue(YtMusicRules.audioInBackground(true, true, pictureVisible = false, changingConfiguration = false))
        assertFalse(YtMusicRules.audioInBackground(false, true, pictureVisible = false, changingConfiguration = false))
        assertFalse(YtMusicRules.audioInBackground(true, false, pictureVisible = false, changingConfiguration = false))
        assertFalse(YtMusicRules.audioInBackground(true, true, pictureVisible = true, changingConfiguration = false))
        assertFalse(YtMusicRules.audioInBackground(true, true, pictureVisible = false, changingConfiguration = true))
    }
}
