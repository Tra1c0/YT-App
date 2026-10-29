package com.github.libretube.ui.yt

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.github.libretube.R

/** YT: fonts bundled in res/font, for the Compose design screens. */
object YtFonts {
    val geist = FontFamily(
        Font(R.font.font_geist_400, FontWeight.Normal),
        Font(R.font.font_geist_500, FontWeight.Medium),
        Font(R.font.font_geist_600, FontWeight.SemiBold),
        Font(R.font.font_geist_700, FontWeight.Bold),
        Font(R.font.font_geist_800, FontWeight.ExtraBold),
        Font(R.font.font_geist_900, FontWeight.Black),
    )
    val geistMono = FontFamily(
        Font(R.font.font_geist_mono_400, FontWeight.Normal),
        Font(R.font.font_geist_mono_500, FontWeight.Medium),
    )
    val anton = FontFamily(Font(R.font.font_anton_400, FontWeight.Normal))
    val figtree = FontFamily(
        Font(R.font.font_figtree_400, FontWeight.Normal),
        Font(R.font.font_figtree_500, FontWeight.Medium),
        Font(R.font.font_figtree_600, FontWeight.SemiBold),
        Font(R.font.font_figtree_700, FontWeight.Bold),
    )
}

/** The color roles of one design, named after the design briefs. */
data class YtPalette(
    val background: Color,
    val surface: Color,
    val raised: Color,
    val text: Color,
    val textSecondary: Color,
    val accent: Color,
    val onAccent: Color,
    val live: Color,
    val lime: Color,
    val lavender: Color,
    val ink: Color,
)

object YtPalettes {
    /** "YouTube, neu gedacht": graphite, warm white, YouTube red, lime and lavender collections. */
    val YouTube = YtPalette(
        background = Color(0xFF101014),
        surface = Color(0xFF1C1C23),
        raised = Color(0xFF282830),
        text = Color(0xFFF6F4F2),
        textSecondary = Color(0xFFB8B8C5),
        accent = Color(0xFFFF334B),
        onAccent = Color(0xFFFFFFFF),
        live = Color(0xFFC91F3D),
        lime = Color(0xFFC9F26F),
        lavender = Color(0xFFB4AEFF),
        ink = Color(0xFF101014),
    )
}

@Composable
fun YtTheme(palette: YtPalette, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            background = palette.background,
            onBackground = palette.text,
            surface = palette.background,
            onSurface = palette.text,
            surfaceVariant = palette.raised,
            onSurfaceVariant = palette.textSecondary,
        ),
        content = content,
    )
}
