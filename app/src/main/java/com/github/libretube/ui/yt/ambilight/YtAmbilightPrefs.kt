package com.github.libretube.ui.yt.ambilight

import com.github.libretube.helpers.PreferenceHelper

/** YT: the ambilight's preference keys (Settings → Darstellung, R/xml/appearance_settings.xml). */
object YtAmbilightPrefs {
    const val ENABLED = "yt_ambilight"
    const val INTENSITY = "yt_ambilight_intensity"
    const val CHROME = "yt_ambilight_chrome"
    const val BEAT = "yt_ambilight_beat"
    const val BARS = "yt_ambilight_bars"

    /** Every key above starts with this (the engine listens for changes of these). */
    const val PREFIX = "yt_ambilight"

    fun read(): YtAmbilightSettings = YtAmbilightSettings(
        enabled = PreferenceHelper.getBoolean(ENABLED, false),
        // a SliderPreference stores its value as a string
        intensity = YtAmbilightSettings.intensityOf(
            PreferenceHelper.getString(INTENSITY, YtAmbilightSettings.INTENSITY_DEFAULT.toString()).toFloatOrNull()
                ?: YtAmbilightSettings.INTENSITY_DEFAULT,
        ),
        chrome = PreferenceHelper.getBoolean(CHROME, true),
        beat = PreferenceHelper.getBoolean(BEAT, true),
        bars = PreferenceHelper.getBoolean(BARS, true),
    )
}
