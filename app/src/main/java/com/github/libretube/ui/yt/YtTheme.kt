package com.github.libretube.ui.yt

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
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

    /** Wordmark "after". */
    val fraunces = FontFamily(Font(R.font.font_fraunces_800, FontWeight.ExtraBold))

    /** Room titles in "After". */
    val archivoNarrow = FontFamily(Font(R.font.font_archivo_narrow_700, FontWeight.Bold))

    /** Wordmark of "YouTube, neu gedacht". */
    val robotoCondensed = FontFamily(Font(R.font.font_roboto_condensed_700, FontWeight.Bold))

    /** Clay and Pop: soft rounded type. */
    val nunito = FontFamily(
        Font(R.font.font_nunito_400, FontWeight.Normal),
        Font(R.font.font_nunito_600, FontWeight.SemiBold),
        Font(R.font.font_nunito_700, FontWeight.Bold),
        Font(R.font.font_nunito_800, FontWeight.ExtraBold),
        Font(R.font.font_nunito_900, FontWeight.Black),
    )

    /** Editorial: neutral grotesque. */
    val inter = FontFamily(
        Font(R.font.font_inter_400, FontWeight.Normal),
        Font(R.font.font_inter_500, FontWeight.Medium),
        Font(R.font.font_inter_600, FontWeight.SemiBold),
        Font(R.font.font_inter_700, FontWeight.Bold),
        Font(R.font.font_inter_900, FontWeight.Black),
    )

    /** Editorial: durations and badges. */
    val plexMono = FontFamily(Font(R.font.font_plex_mono_500, FontWeight.Medium))

    /** Glas and Formen. */
    val googleSans = FontFamily(
        Font(R.font.font_google_sans_400, FontWeight.Normal),
        Font(R.font.font_google_sans_500, FontWeight.Medium),
        Font(R.font.font_google_sans_700, FontWeight.Bold),
    )

    /** Frost. */
    val jakarta = FontFamily(
        Font(R.font.font_jakarta_400, FontWeight.Normal),
        Font(R.font.font_jakarta_500, FontWeight.Medium),
        Font(R.font.font_jakarta_600, FontWeight.SemiBold),
        Font(R.font.font_jakarta_700, FontWeight.Bold),
        Font(R.font.font_jakarta_800, FontWeight.ExtraBold),
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
    /** Type of the design screens that are shared between designs (video page, personal area). */
    val font: FontFamily = YtFonts.geist,
    /** Light designs draw dark text on light surfaces. */
    val light: Boolean = false,
    /** Character of the design's motion. */
    val motion: YtMotionStyle = YtMotionStyle.CALM,
    /** How pills and cards of the shared screens are drawn (video page, personal area, Shorts). */
    val surfaceStyle: YtSurfaceStyle = YtSurfaceStyle.FLAT,
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
        motion = YtMotionStyle.SPRINGY,
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
        motion = YtMotionStyle.CINEMATIC,
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
        motion = YtMotionStyle.SOFT,
    )

/** "Clay": peach, cream and mauve, puffy surfaces. */
val YtPalettes.Clay: YtPalette
    get() = YtPalette(
        background = Color(0xFFF5D8C7),
        surface = Color(0xFFF7E6D6),
        raised = Color(0xFFFBE8D9),
        text = Color(0xFF2D2535),
        textSecondary = Color(0xFF8E7A78),
        accent = Color(0xFF9D6A6C),
        onAccent = Color(0xFFFFFFFF),
        live = Color(0xFFF82C34),
        lime = Color(0xFFB4CAE3),
        lavender = Color(0xFFF3DFD0),
        ink = Color(0xFF2D2535),
        pink = Color(0xFFB37A7B),
        font = YtFonts.nunito,
        light = true,
        motion = YtMotionStyle.SPRINGY,
        surfaceStyle = YtSurfaceStyle.CLAY,
    )

/** "Editorial": white paper, black type, one signal blue. */
val YtPalettes.Editorial: YtPalette
    get() = YtPalette(
        background = Color(0xFFFFFFFF),
        surface = Color(0xFFF4F4F4),
        raised = Color(0xFFE8E8E8),
        text = Color(0xFF0A0A0A),
        textSecondary = Color(0xFF555555),
        accent = Color(0xFF0F54F6),
        onAccent = Color(0xFFFFFFFF),
        live = Color(0xFF0F54F6),
        lime = Color(0xFFE8E8E8),
        lavender = Color(0xFFF4F4F4),
        ink = Color(0xFF0A0A0A),
        font = YtFonts.inter,
        light = true,
        motion = YtMotionStyle.CRISP,
        surfaceStyle = YtSurfaceStyle.EDITORIAL,
    )

/** "Glas": mint with frosted glass surfaces. */
val YtPalettes.Glass: YtPalette
    get() = YtPalette(
        background = Color(0xFFD1EAE3),
        surface = Color(0x8CFFFFFF),
        raised = Color(0xFFEFF9F8),
        text = Color(0xFF0F2B2E),
        textSecondary = Color(0xFF5C7C7C),
        accent = Color(0xFF0F2B2E),
        onAccent = Color(0xFFFFFFFF),
        live = Color(0xFFF03A3A),
        lime = Color(0xFFC4E6E0),
        lavender = Color(0xFFE2F3F0),
        ink = Color(0xFF0F2B2E),
        pink = Color(0xFFF03A3A),
        font = YtFonts.googleSans,
        light = true,
        motion = YtMotionStyle.SOFT,
        surfaceStyle = YtSurfaceStyle.GLASS,
    )

/** "Pop": lavender, plum, butter yellow and pink, expressive shapes. */
val YtPalettes.Pop: YtPalette
    get() = YtPalette(
        background = Color(0xFFE6E5FD),
        surface = Color(0xFFF7F6FD),
        raised = Color(0xFFDCD6F5),
        text = Color(0xFF1D032E),
        textSecondary = Color(0xFF7A6A8F),
        accent = Color(0xFF3B2054),
        onAccent = Color(0xFFFFFFFF),
        live = Color(0xFFE0457F),
        lime = Color(0xFFFDE682),
        lavender = Color(0xFFF4B6CF),
        ink = Color(0xFF1D032E),
        pink = Color(0xFFE0457F),
        font = YtFonts.nunito,
        light = true,
        motion = YtMotionStyle.SPRINGY,
        surfaceStyle = YtSurfaceStyle.POP,
    )

/** "Formen": night blue to violet with bright Material shapes (inspiration images 5 and 6). */
val YtPalettes.Shapes: YtPalette
    get() = YtPalette(
        background = Color(0xFF12122A),
        surface = Color(0xFFF4F1FA),
        raised = Color(0xFF2A2950),
        text = Color(0xFFF5F3FF),
        textSecondary = Color(0xFFB8B5D8),
        accent = Color(0xFF8B7CF6),
        onAccent = Color(0xFFFFFFFF),
        live = Color(0xFFF28B82),
        lime = Color(0xFFC6F36B),
        lavender = Color(0xFFE6E1FF),
        ink = Color(0xFF17132B),
        pink = Color(0xFFF4A9B8),
        font = YtFonts.googleSans,
        motion = YtMotionStyle.SPRINGY,
        surfaceStyle = YtSurfaceStyle.SHAPES,
    )

/** "Frost": sage with frosted glass and a dark pill (inspiration image 7). */
val YtPalettes.Frost: YtPalette
    get() = YtPalette(
        background = Color(0xFF6F8A80),
        surface = Color(0x33FFFFFF),
        raised = Color(0x26FFFFFF),
        text = Color(0xFFF3F6F4),
        textSecondary = Color(0xFFD5E0DA),
        accent = Color(0xFFF08A3C),
        onAccent = Color(0xFF1F2A26),
        live = Color(0xFFF08A3C),
        lime = Color(0xFFE8EEEA),
        lavender = Color(0xFFCFDCD5),
        ink = Color(0xFF1F2A26),
        font = YtFonts.jakarta,
        motion = YtMotionStyle.SOFT,
        surfaceStyle = YtSurfaceStyle.FROST,
    )

/** The Compose palette of a design, or null for the classic look. */
val YtDesign.palette: YtPalette?
    get() = when (this) {
        YtDesign.YOUTUBE -> YtPalettes.YouTube
        YtDesign.MOVO -> YtPalettes.Movo
        YtDesign.AFTER -> YtPalettes.After
        YtDesign.SIDE -> YtPalettes.Side.copy(font = YtFonts.figtree)
        YtDesign.CLAY -> YtPalettes.Clay
        YtDesign.EDITORIAL -> YtPalettes.Editorial
        YtDesign.GLASS -> YtPalettes.Glass
        YtDesign.POP -> YtPalettes.Pop
        YtDesign.SHAPES -> YtPalettes.Shapes
        YtDesign.FROST -> YtPalettes.Frost
        else -> null
    }

/**
 * Width of the phone in the design references. All design screens are measured against it
 * (see docs/DESIGN-MASSE.md) and scaled to the real screen width, so every phone shows the
 * same proportions as the reference.
 */
const val YT_REFERENCE_WIDTH_DP = 393f

/** Factor from reference dp to the dp of this screen. */
fun ytScale(configuration: Configuration): Float {
    val width = minOf(configuration.screenWidthDp, configuration.screenHeightDp).toFloat()
    return (width / YT_REFERENCE_WIDTH_DP).coerceIn(0.8f, 1.3f)
}

/**
 * Density for the design screens: dp and sp grow with the screen width. The text keeps the
 * size of the reference; only an accessibility font size above "large" still enlarges it.
 */
fun ytDensity(base: Density, configuration: Configuration): Density = Density(
    density = base.density * ytScale(configuration),
    fontScale = (base.fontScale / 1.15f).coerceAtLeast(1f),
)

@Composable
fun YtTheme(palette: YtPalette, content: @Composable () -> Unit) {
    val density = ytDensity(LocalDensity.current, LocalConfiguration.current)
    CompositionLocalProvider(
        LocalDensity provides density,
        LocalYtMotion provides YtMotionPrefs.enabled,
        LocalYtMotionStyle provides palette.motion,
    ) {
        val scheme = if (palette.light) {
            lightColorScheme(
                primary = palette.accent,
                onPrimary = palette.onAccent,
                background = palette.background,
                onBackground = palette.text,
                surface = palette.background,
                onSurface = palette.text,
                surfaceVariant = palette.raised,
                onSurfaceVariant = palette.textSecondary,
            )
        } else {
            darkColorScheme(
                primary = palette.accent,
                onPrimary = palette.onAccent,
                background = palette.background,
                onBackground = palette.text,
                surface = palette.background,
                onSurface = palette.text,
                surfaceVariant = palette.raised,
                onSurfaceVariant = palette.textSecondary,
            )
        }
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
