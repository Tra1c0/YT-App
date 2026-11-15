package com.github.libretube.ui.yt.builder

import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily
import com.github.libretube.ui.yt.YtFonts
import java.io.File

/**
 * YT: the fonts a theme names: the families bundled with the app by key, and imported font files
 * of the theme (assets). An imported file is loaded once through Typeface; a damaged or missing
 * file falls back to the default family, so a font never stops the text from drawing.
 */
object YtBuilderFonts {
    private val bundled: Map<String, FontFamily> by lazy {
        mapOf(
            "geist" to YtFonts.geist,
            "geistMono" to YtFonts.geistMono,
            "inter" to YtFonts.inter,
            "googleSans" to YtFonts.googleSans,
            "jakarta" to YtFonts.jakarta,
            "fraunces" to YtFonts.fraunces,
            "figtree" to YtFonts.figtree,
            "nunito" to YtFonts.nunito,
            "robotoCondensed" to YtFonts.robotoCondensed,
            "archivoNarrow" to YtFonts.archivoNarrow,
            "anton" to YtFonts.anton,
            "audiowide" to YtFonts.audiowide,
            "plexMono" to YtFonts.plexMono,
            "system" to FontFamily.Default,
            "serif" to FontFamily.Serif,
            "mono" to FontFamily.Monospace,
        )
    }

    /** Loaded asset families by file path; bounded, a theme has few fonts. */
    private val loaded = object : LinkedHashMap<String, FontFamily>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FontFamily>?) = size > 12
    }

    fun family(key: String, assets: File?): FontFamily {
        if (key.startsWith(YtFontKeys.ASSET)) {
            val name = key.removePrefix(YtFontKeys.ASSET)
            if (assets == null || !YtThemeZip.safeAssetName(name)) return FontFamily.Default
            val file = File(assets, name)
            synchronized(loaded) { loaded[file.path]?.let { return it } }
            val family = runCatching {
                if (!file.isFile) return FontFamily.Default
                FontFamily(Typeface.createFromFile(file))
            }.getOrDefault(FontFamily.Default)
            synchronized(loaded) { loaded[file.path] = family }
            return family
        }
        return bundled[key] ?: YtFonts.geist
    }

    /** Whether [file] is a font Android can read (checked once on import). */
    fun readable(file: File): Boolean = runCatching { Typeface.createFromFile(file) != Typeface.DEFAULT }.getOrDefault(false)

    /** The key of a bundled family, "geist" for any other. */
    fun keyOf(family: FontFamily): String = bundled.entries.firstOrNull { it.value === family }?.key ?: "geist"

    fun forget(assets: File) {
        synchronized(loaded) { loaded.keys.removeAll { it.startsWith(assets.path) } }
    }
}
