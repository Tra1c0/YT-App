package com.github.libretube.ui.yt

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.github.libretube.R
import com.github.libretube.helpers.YtDesign

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
    val pink: Color = Color(0xFFE557D6),
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

/** "Movo": near-black, cobalt, candy pink, acid lime and a light lavender chapter surface. */
val YtPalettes.Movo: YtPalette
    get() = YtPalette(
        background = Color(0xFF101112),
        surface = Color(0xFF1D1E21),
        raised = Color(0xFF2B2C30),
        text = Color(0xFFF7F6F2),
        textSecondary = Color(0xFFB5B6BC),
        accent = Color(0xFF5260ED),
        onAccent = Color(0xFFFFFFFF),
        live = Color(0xFFE557D6),
        lime = Color(0xFFC9F26F),
        lavender = Color(0xFFC9C3FF),
        ink = Color(0xFF101112),
        pink = Color(0xFFE557D6),
    )

/** "After": deep burgundy, bone white and a small tangerine accent. */
val YtPalettes.After: YtPalette
    get() = YtPalette(
        background = Color(0xFF1A0B0F),
        surface = Color(0xFF2B1219),
        raised = Color(0xFF3C1B24),
        text = Color(0xFFFFF4DF),
        textSecondary = Color(0xFFD8C6B6),
        accent = Color(0xFFFF8B4B),
        onAccent = Color(0xFF2B1219),
        live = Color(0xFFFF8B4B),
        // collection cards: tangerine and bone instead of lime and lavender
        lime = Color(0xFFFF8B4B),
        lavender = Color(0xFFF2E3CF),
        ink = Color(0xFF2B1219),
    )

/** "Side": matte graphite, raised surfaces and warm pale apricot. */
val YtPalettes.Side: YtPalette
    get() = YtPalette(
        background = Color(0xFF292A2C),
        surface = Color(0xFF353638),
        raised = Color(0xFF414245),
        text = Color(0xFFF5F1EB),
        textSecondary = Color(0xFFC4C2BD),
        accent = Color(0xFFFFC68D),
        onAccent = Color(0xFF2A1E12),
        live = Color(0xFFFFC68D),
        // collection cards: apricot and warm white
        lime = Color(0xFFFFC68D),
        lavender = Color(0xFFE9E2D8),
        ink = Color(0xFF292A2C),
    )

/** The Compose palette of a design, or null for the classic look. */
val YtDesign.palette: YtPalette?
    get() = when (this) {
        YtDesign.YOUTUBE -> YtPalettes.YouTube
        YtDesign.MOVO -> YtPalettes.Movo
        YtDesign.AFTER -> YtPalettes.After
        YtDesign.SIDE -> YtPalettes.Side
        else -> null
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
