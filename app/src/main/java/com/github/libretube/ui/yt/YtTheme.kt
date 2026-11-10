package com.github.libretube.ui.yt

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import com.github.libretube.R
import com.github.libretube.helpers.YtDesign
import com.github.libretube.helpers.YtDesignDark

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
    /** Which design this is, for the pieces that differ per design beyond colors and surfaces. */
    val signature: YtSignature = YtSignature.YOUTUBE,
)

/** The designs, as the shared screens tell them apart. */
enum class YtSignature { YOUTUBE, MOVO, AFTER, SIDE, CLAY, EDITORIAL, GLASS, POP, SHAPES, FROST, KIESEL, FREQUENCY }

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
        signature = YtSignature.MOVO,
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
        signature = YtSignature.AFTER,
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
        signature = YtSignature.SIDE,
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
        signature = YtSignature.CLAY,
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
        signature = YtSignature.EDITORIAL,
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
        signature = YtSignature.GLASS,
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
        signature = YtSignature.POP,
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
        signature = YtSignature.SHAPES,
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
        signature = YtSignature.FROST,
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

/** "Kiesel": black and cream with lavender and peach, tumbling pebbles (inspiration image 8). */
val YtPalettes.Kiesel: YtPalette
    get() = YtPalette(
        signature = YtSignature.KIESEL,
        background = Color(0xFFE9E5E0),
        surface = Color(0xFFF7F4F1),
        raised = Color(0xFFDDD8D2),
        text = Color(0xFF0E0E0E),
        textSecondary = Color(0xFF7A7672),
        accent = Color(0xFFB4A7F5),
        onAccent = Color(0xFF0E0E0E),
        live = Color(0xFFF6C6A2),
        lime = Color(0xFFF6C6A2),
        lavender = Color(0xFFB4A7F5),
        ink = Color(0xFF0E0E0E),
        pink = Color(0xFFF6C6A2),
        font = YtFonts.jakarta,
        light = true,
        motion = YtMotionStyle.SPRINGY,
        surfaceStyle = YtSurfaceStyle.FLAT,
    )

/**
 * "Frequency": the layout of YouTube neu with the shapes of Formen, on a deep night. One vivid
 * signal coral (a nod to YouTube's red, dark type on it), aqua and violet as the shape colours,
 * amber as a third tag. Everything moves with the sound (FrequencyPulse); the colours never do,
 * so every text role keeps its contrast (YtFrequencyPaletteTest).
 */
val YtPalettes.Frequency: YtPalette
    get() = YtPalette(
        signature = YtSignature.FREQUENCY,
        background = Color(0xFF0B0A18),
        surface = Color(0xFF17152B),
        raised = Color(0xFF231F3F),
        text = Color(0xFFF4F2FF),
        textSecondary = Color(0xFFB7B2D6),
        accent = Color(0xFFFF4D6D),
        onAccent = Color(0xFF170812),
        live = Color(0xFFE0234D),
        // the shape colours: aqua and violet
        lime = Color(0xFF5BE7DA),
        lavender = Color(0xFFB3A6FF),
        ink = Color(0xFF0B0A18),
        pink = Color(0xFFFFC46B),
        font = YtFonts.geist,
        motion = YtMotionStyle.SPRINGY,
        surfaceStyle = YtSurfaceStyle.FREQUENCY,
    )

/*
 * YT: the dark mode of the light designs (YtDesignDark). Same roles, same type, motion and
 * surfaces; only the colours move to a dark variant of the design's own palette. Every text
 * role reads with at least 4.5:1 on background, surface and raised (YtNightPaletteTest).
 */

/** "Clay" at night: warm dark dough browns, cream type, the mauve lifted so it reads. */
val YtPalettes.ClayDark: YtPalette
    get() = YtPalettes.Clay.copy(
        background = Color(0xFF2A201C),
        surface = Color(0xFF3A2C26),
        raised = Color(0xFF46352E),
        text = Color(0xFFF7E8DA),
        textSecondary = Color(0xFFCBB1A5),
        accent = Color(0xFFD9A5A6),
        onAccent = Color(0xFF2D1A1D),
        lime = Color(0xFF3C4A5C),
        lavender = Color(0xFF3F2F29),
        ink = Color(0xFFF7E8DA),
        pink = Color(0xFFC98E8F),
        light = false,
    )

/** "Editorial" at night: near-black paper, off-white ink, the same signal blue. */
val YtPalettes.EditorialDark: YtPalette
    get() = YtPalettes.Editorial.copy(
        background = Color(0xFF0B0B0B),
        surface = Color(0xFF161616),
        raised = Color(0xFF242424),
        text = Color(0xFFF2F2EE),
        textSecondary = Color(0xFFA8A8A8),
        lime = Color(0xFF242424),
        lavender = Color(0xFF161616),
        ink = Color(0xFFF2F2EE),
        light = false,
    )

/** "Glas" at night: dark teal-tinted glass over a deep sea green, light mint as the accent. */
val YtPalettes.GlassDark: YtPalette
    get() = YtPalettes.Glass.copy(
        background = Color(0xFF0E1F1F),
        surface = Color(0x40345A57),
        raised = Color(0xFF1C3333),
        text = Color(0xFFE3F4F1),
        textSecondary = Color(0xFF9CBDB9),
        accent = Color(0xFFCFEDE7),
        onAccent = Color(0xFF0F2B2E),
        lime = Color(0xFF1F3D3A),
        lavender = Color(0xFF173030),
        ink = Color(0xFFE3F4F1),
        light = false,
    )

/** "Pop" at night: deep plum, light lilac in place of the plum accent, pink and yellow kept. */
val YtPalettes.PopDark: YtPalette
    get() = YtPalettes.Pop.copy(
        background = Color(0xFF1E1028),
        surface = Color(0xFF2B1A38),
        raised = Color(0xFF382548),
        text = Color(0xFFF5ECFF),
        textSecondary = Color(0xFFBFAED3),
        accent = Color(0xFFD5CDFA),
        onAccent = Color(0xFF1D032E),
        light = false,
    )

/** "Kiesel" at night: dark warm grey for the cream, light panels for the black ones. */
val YtPalettes.KieselDark: YtPalette
    get() = YtPalettes.Kiesel.copy(
        background = Color(0xFF1F1D1B),
        surface = Color(0xFF2B2826),
        raised = Color(0xFF36322F),
        text = Color(0xFFEDE8E3),
        textSecondary = Color(0xFFABA59F),
        light = false,
    )

/**
 * [day] in the normal look of a light design, [night] in its dark mode. For the colours the
 * light designs draw outside their palette; dark-only designs always get [day].
 */
fun ytDayNight(day: Color, night: Color): Color = if (YtDesignDark.active) night else day

/**
 * The accent where it is type. At night a light design may keep an accent that is made for fills
 * (Editorial's signal blue with white on it); as type it is lifted until it reads (YtContrast).
 */
val YtPalette.accentText: Color
    get() = if (!YtDesignDark.active) accent else Color(YtContrast.readable(accent.toArgb(), background.toArgb()))

/** Whether a light design is drawing its dark mode right now. */
val ytNight: Boolean
    get() = YtDesignDark.active

/** The Compose palette of a design, or null for the classic look. */
val YtDesign.palette: YtPalette?
    get() {
        val night = YtDesignDark.active
        return when (this) {
            YtDesign.YOUTUBE -> YtPalettes.YouTube
            YtDesign.MOVO -> YtPalettes.Movo
            YtDesign.AFTER -> YtPalettes.After
            YtDesign.SIDE -> YtPalettes.Side.copy(font = YtFonts.figtree)
            YtDesign.CLAY -> if (night) YtPalettes.ClayDark else YtPalettes.Clay
            YtDesign.EDITORIAL -> if (night) YtPalettes.EditorialDark else YtPalettes.Editorial
            YtDesign.GLASS -> if (night) YtPalettes.GlassDark else YtPalettes.Glass
            YtDesign.POP -> if (night) YtPalettes.PopDark else YtPalettes.Pop
            YtDesign.SHAPES -> YtPalettes.Shapes
            YtDesign.FROST -> YtPalettes.Frost
            YtDesign.KIESEL -> if (night) YtPalettes.KieselDark else YtPalettes.Kiesel
            YtDesign.FREQUENCY -> YtPalettes.Frequency
            YtDesign.STANDARD -> null
        }
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
    val motion = YtMotionPrefs.enabled
    // the glass designs let their light follow the tilt of the phone: one sensor per screen
    val glass = palette.surfaceStyle == YtSurfaceStyle.GLASS || palette.surfaceStyle == YtSurfaceStyle.FROST
    val tilt = rememberYtGravity(motion && glass)
    CompositionLocalProvider(
        LocalDensity provides density,
        LocalYtMotion provides motion,
        LocalYtMotionStyle provides palette.motion,
        LocalYtTilt provides tilt,
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
