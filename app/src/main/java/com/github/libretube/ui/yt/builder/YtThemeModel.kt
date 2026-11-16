package com.github.libretube.ui.yt.builder

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement

/*
 * YT: the theme document of the design "Eigenes" (theme builder, phase 1). Pure data: the
 * renderer draws the design's surfaces from it, the editor edits it, the store writes it as JSON.
 * Every value may be out of range in a file; YtThemeValidate clamps it before anything uses it.
 * Sizes are in dp (the design dp of YtTheme, which scale with the screen width), angles in
 * degrees, colours ARGB (in JSON as "#AARRGGBB").
 */

/** The schema of this build. A newer file is read as far as it is understood (YtThemeCodec). */
const val YT_THEME_SCHEMA = 3

@Serializable
data class YtThemeDoc(
    val schema: Int = YT_THEME_SCHEMA,
    val id: String,
    val name: String,
    /** Key of the design the theme started from (YtDesign.key); the classic screens take its look. */
    val base: String = "youtube",
    val mode: YtThemeMode = YtThemeMode.DARK,
    val tokens: YtTokens = YtTokens(),
    val components: YtComponents = YtComponents(),
    val feed: YtFeedLayout = YtFeedLayout(),
    val nav: YtNavConfig = YtNavConfig(),
    val mini: YtMiniConfig = YtMiniConfig(),
    /** Fonts and images that came with the theme (files in its assets folder). */
    val assets: List<YtAsset> = emptyList(),
    /** The animator (phase 2, schema 2; motion paths and morphs since schema 3): animations, groups, state rules, easing presets (YtAnimModel). */
    val animations: YtAnimations = YtAnimations(),
    /**
     * What a schema 1 file had in its reserved `animations` field (never written by a build, but
     * kept as it was rather than lost; see the migration in YtThemeCodec).
     */
    val animationsLegacy: JsonElement? = null,
    val meta: YtThemeMeta = YtThemeMeta(),
)

/**
 * DARK: one look, always dark screens around it. BOTH: a day look and a night look; the
 * light/dark choice of the designs (YtDesignDark) picks. A theme that should only ever be light
 * is a BOTH theme with the choice on light.
 */
@Serializable
enum class YtThemeMode { DARK, BOTH }

@Serializable
data class YtThemeMeta(
    val created: Long = 0L,
    val modified: Long = 0L,
    /** Text of the toolbar wordmark. */
    val wordmark: String = "YT",
)

/** A file of the theme: [file] is the name inside the theme's assets folder. */
@Serializable
data class YtAsset(val file: String, val kind: YtAssetKind)

@Serializable
enum class YtAssetKind { FONT, IMAGE }

// ---- tokens ----

/** The colour roles every design has (YtPalette); a theme may add its own names. */
object YtRole {
    const val BACKGROUND = "background"
    const val SURFACE = "surface"
    const val RAISED = "raised"
    const val TEXT = "text"
    const val TEXT_SECONDARY = "textSecondary"
    const val ACCENT = "accent"
    const val ON_ACCENT = "onAccent"
    const val LIVE = "live"
    const val LIME = "lime"
    const val LAVENDER = "lavender"
    const val INK = "ink"
    const val PINK = "pink"

    val all = listOf(BACKGROUND, SURFACE, RAISED, TEXT, TEXT_SECONDARY, ACCENT, ON_ACCENT, LIVE, LIME, LAVENDER, INK, PINK)
}

@Serializable
data class YtTokens(
    val colors: Map<String, YtColorToken> = emptyMap(),
    val type: Map<String, YtTypeToken> = emptyMap(),
    val spacing: Map<String, Float> = emptyMap(),
    val radii: Map<String, Float> = emptyMap(),
    val shadows: Map<String, List<YtShadow>> = emptyMap(),
    val motion: YtMotionToken = YtMotionToken(),
)

/** A colour token: [day] in the light look (or the only one), [night] in the dark look of a BOTH theme. */
@Serializable
data class YtColorToken(val day: YtPaint, val night: YtPaint? = null)

/** Type: [font] is a bundled family (YtFontKeys) or "asset:<file>" for an imported font. */
@Serializable
data class YtTypeToken(
    val font: String = "geist",
    val weight: Int = 400,
    val size: Float = 14f,
    /** 0: the font's own line height. */
    val lineHeight: Float = 0f,
    val letterSpacing: Float = 0f,
    val italic: Boolean = false,
    val caps: Boolean = false,
)

object YtFontKeys {
    val bundled = listOf(
        "geist", "geistMono", "inter", "googleSans", "jakarta", "fraunces", "figtree", "nunito", "robotoCondensed",
        "archivoNarrow", "anton", "audiowide", "plexMono", "system", "serif", "mono",
    )
    const val ASSET = "asset:"
}

/**
 * The feel of presses and selections: a spring ([stiffness], [damping] ratio) and how far a
 * pressed element gives ([pressScale]). The animator (phase 2) builds on this.
 */
@Serializable
data class YtMotionToken(
    val stiffness: Float = 500f,
    val damping: Float = 0.8f,
    val pressScale: Float = 0.97f,
    val durationMs: Int = 220,
    val easing: YtEasing = YtEasing.STANDARD,
)

@Serializable
enum class YtEasing { LINEAR, STANDARD, EMPHASIZED, DECELERATE, ACCELERATE }

// ---- paints ----

@Serializable
enum class YtPaintType { SOLID, LINEAR, RADIAL, IMAGE }

/**
 * A fill or a stroke colour. SOLID: [color], or the token [ref] if set. LINEAR: [stops] along
 * [angle] (0° left to right, 90° top to bottom). RADIAL: [stops] from the centre ([centerX],
 * [centerY] as shares of the box) out to [radius] (a share of the longer side). IMAGE (fills
 * only): the theme's image asset [image], covering the box (cropped to it, centred); [color]
 * shows until it is loaded and stands for it in the contrast checks. A build without IMAGE reads
 * it as a flat [color].
 */
@Serializable
data class YtPaint(
    val type: YtPaintType = YtPaintType.SOLID,
    @Serializable(with = YtArgbSerializer::class) val color: Int = 0xFF000000.toInt(),
    val ref: String? = null,
    val stops: List<YtStop> = emptyList(),
    val angle: Float = 90f,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val radius: Float = 0.5f,
    val enabled: Boolean = true,
    /** IMAGE: the file name in the theme's assets folder. */
    val image: String? = null,
) {
    companion object {
        fun solid(argb: Int) = YtPaint(color = argb)
        fun image(file: String, placeholder: Int = 0xFF3A3D45.toInt()) = YtPaint(type = YtPaintType.IMAGE, color = placeholder, image = file)
        fun token(name: String) = YtPaint(ref = name)
    }
}

@Serializable
data class YtStop(
    val at: Float,
    @Serializable(with = YtArgbSerializer::class) val color: Int,
    val ref: String? = null,
)

@Serializable
data class YtStroke(val paint: YtPaint, val width: Float = 1f, val enabled: Boolean = true)

/** A drop shadow, or with [inner] an inner one; a glow is a shadow without offset. */
@Serializable
data class YtShadow(
    val x: Float = 0f,
    val y: Float = 4f,
    val blur: Float = 12f,
    val spread: Float = 0f,
    @Serializable(with = YtArgbSerializer::class) val color: Int = 0x40000000,
    val inner: Boolean = false,
    val enabled: Boolean = true,
)

/**
 * Corner radii per corner in dp, and [smoothing] 0..1: 0 a circular arc, 1 a fully continuous
 * (squircle) corner. [ref] takes all four from a radius token.
 */
@Serializable
data class YtCorners(
    val topStart: Float = 0f,
    val topEnd: Float = 0f,
    val bottomEnd: Float = 0f,
    val bottomStart: Float = 0f,
    val smoothing: Float = 0f,
    val ref: String? = null,
) {
    companion object {
        fun all(radius: Float, smoothing: Float = 0f) = YtCorners(radius, radius, radius, radius, smoothing)
    }
}

@Serializable
data class YtStyle(
    val fills: List<YtPaint> = emptyList(),
    val strokes: List<YtStroke> = emptyList(),
    val shadows: List<YtShadow> = emptyList(),
    val corners: YtCorners = YtCorners(),
    val opacity: Float = 1f,
)

// ---- layout ----

@Serializable
enum class YtLayoutMode { FLOW_H, FLOW_V, GRID, FREE }

@Serializable
enum class YtAlign { START, CENTER, END, STRETCH }

@Serializable
enum class YtJustify { START, CENTER, END, SPACE_BETWEEN }

/** How a container places its children. [gap] along the flow (grid: between columns), [crossGap] between grid rows. */
@Serializable
data class YtLayout(
    val mode: YtLayoutMode = YtLayoutMode.FLOW_V,
    val gap: Float = 0f,
    val crossGap: Float = 0f,
    val columns: Int = 2,
    val align: YtAlign = YtAlign.START,
    val justify: YtJustify = YtJustify.START,
    val clip: Boolean = false,
)

@Serializable
enum class YtSizeKind { FIXED, HUG, FILL, PERCENT }

/** FIXED: [value] dp. HUG: the content. FILL: the room left ([value] is the weight, 0 = 1). PERCENT: [value] % of the parent's content box. */
@Serializable
data class YtSize(val kind: YtSizeKind = YtSizeKind.HUG, val value: Float = 0f) {
    companion object {
        val HUG = YtSize(YtSizeKind.HUG)
        val FILL = YtSize(YtSizeKind.FILL)
        fun fixed(dp: Float) = YtSize(YtSizeKind.FIXED, dp)
        fun percent(p: Float) = YtSize(YtSizeKind.PERCENT, p)
    }
}

@Serializable
data class YtInsets(val start: Float = 0f, val top: Float = 0f, val end: Float = 0f, val bottom: Float = 0f) {
    companion object {
        fun all(v: Float) = YtInsets(v, v, v, v)
        fun sym(h: Float, v: Float) = YtInsets(h, v, h, v)
    }
}

/** Where a child of a FREE container is pinned: x/y count from this edge (or the centre). */
@Serializable
enum class YtAnchor { START, CENTER, END }

// ---- nodes ----

@Serializable
enum class YtNodeType { CONTAINER, TEXT, IMAGE, ICON, SHAPE, SLOT }

/**
 * The shape of a SHAPE node. RECT, ELLIPSE, PILL and LINE are drawn from the corners (phase 1);
 * TRIANGLE, POLYGON, STAR and SQUIRCLE from [YtNode.shapeSpec] (phase 3, parametric until turned
 * into a path); PATH from [YtNode.path] (YtVector).
 */
@Serializable
enum class YtShapeKind {
    RECT, ELLIPSE, PILL, LINE, TRIANGLE, POLYGON, STAR, SQUIRCLE, PATH;

    /** Drawn from the corners (YtCornerShape) rather than a vector outline. */
    val cornered: Boolean get() = this == RECT || this == ELLIPSE || this == PILL || this == LINE
}

/** Real data a SLOT node shows: the card's video, the header's actions, the nav tab's parts. */
@Serializable
enum class YtSlot(val text: Boolean) {
    THUMBNAIL(false), TITLE(true), CHANNEL(true), AVATAR(false), DURATION(true), PROGRESS(false), META(true),
    WORDMARK(true), SEARCH(false), PROFILE(false), NAV_ICON(false), NAV_LABEL(true), CHIP_LABEL(true),
}

@Serializable
enum class YtTextAlign { START, CENTER, END }

/** Text of a TEXT node or a text slot: its type token [styleRef], colour, lines. */
@Serializable
data class YtTextSpec(
    val text: String = "",
    val styleRef: String = "body",
    val color: YtPaint = YtPaint.token(YtRole.TEXT),
    val maxLines: Int = 2,
    val align: YtTextAlign = YtTextAlign.START,
    /** Overrides of the token, 0 / null to keep it. */
    val size: Float = 0f,
    val weight: Int = 0,
)

@Serializable
data class YtNode(
    val id: String,
    val type: YtNodeType = YtNodeType.CONTAINER,
    val name: String = "",
    val layout: YtLayout = YtLayout(),
    val width: YtSize = YtSize.HUG,
    val height: YtSize = YtSize.HUG,
    val minW: Float? = null,
    val maxW: Float? = null,
    val minH: Float? = null,
    val maxH: Float? = null,
    /** Width / height, kept when the other side follows from the rules. */
    val aspect: Float? = null,
    val padding: YtInsets = YtInsets(),
    /** Position in a FREE parent, dp from the anchor edges. */
    val x: Float = 0f,
    val y: Float = 0f,
    val anchorH: YtAnchor = YtAnchor.START,
    val anchorV: YtAnchor = YtAnchor.START,
    val rotation: Float = 0f,
    val z: Int = 0,
    val visible: Boolean = true,
    /** Locked nodes are drawn but cannot be picked on the canvas. */
    val locked: Boolean = false,
    val style: YtStyle = YtStyle(),
    val text: YtTextSpec? = null,
    val slot: YtSlot? = null,
    /** ICON: a glyph of YtIcons. */
    val icon: String? = null,
    val shape: YtShapeKind = YtShapeKind.RECT,
    /** The parameters of a TRIANGLE, POLYGON, STAR or SQUIRCLE shape (phase 3). */
    val shapeSpec: YtShapeSpec = YtShapeSpec(),
    /** The outline of a PATH shape, in its own box (phase 3, YtVector). */
    val path: YtPath? = null,
    /** Only what lies inside this shape is drawn (phase 3): fills, content and images alike. */
    val mask: YtMask? = null,
    /** Changes for a screen size, orientation or font scale (YtResponsive). */
    val overrides: List<YtOverride> = emptyList(),
    /** Changes in a state of the component: "selected" (chip, nav tab), "pressed". */
    val states: Map<String, YtPatch> = emptyMap(),
    val children: List<YtNode> = emptyList(),
)

// ---- responsive ----

/** Window width classes (Material): compact < 600 dp, medium < 840 dp, expanded. */
@Serializable
enum class YtWidthClass { COMPACT, MEDIUM, EXPANDED }

@Serializable
enum class YtOrientation { PORTRAIT, LANDSCAPE }

/** A [patch] that applies where every set condition holds. */
@Serializable
data class YtOverride(
    val width: YtWidthClass? = null,
    val orientation: YtOrientation? = null,
    /** Applies from this font scale on (e.g. 1.3 for large system text). */
    val minFontScale: Float? = null,
    val patch: YtPatch = YtPatch(),
)

/** Changes to a node; null keeps the node's own value. */
@Serializable
data class YtPatch(
    val width: YtSize? = null,
    val height: YtSize? = null,
    val x: Float? = null,
    val y: Float? = null,
    val rotation: Float? = null,
    val visible: Boolean? = null,
    val padding: YtInsets? = null,
    val gap: Float? = null,
    val mode: YtLayoutMode? = null,
    val columns: Int? = null,
    val opacity: Float? = null,
    val corners: YtCorners? = null,
    val fills: List<YtPaint>? = null,
    val strokes: List<YtStroke>? = null,
    val textColor: YtPaint? = null,
    val textSize: Float? = null,
    val maxLines: Int? = null,
    /** Pressed: how far it gives; null takes the motion token. */
    val scale: Float? = null,
)

// ---- components ----

@Serializable
enum class YtComponentKind { CARD, HEADER, CHIP, NAV_BAR, NAV_TAB, MINI }

/** The components the renderer draws, each a node tree. */
@Serializable
data class YtComponents(
    val card: YtNode = YtNode("card"),
    val header: YtNode = YtNode("header"),
    val chip: YtNode = YtNode("chip"),
    val navBar: YtNode = YtNode("navBar"),
    val navTab: YtNode = YtNode("navTab"),
    val mini: YtNode = YtNode("mini"),
) {
    operator fun get(kind: YtComponentKind): YtNode = when (kind) {
        YtComponentKind.CARD -> card
        YtComponentKind.HEADER -> header
        YtComponentKind.CHIP -> chip
        YtComponentKind.NAV_BAR -> navBar
        YtComponentKind.NAV_TAB -> navTab
        YtComponentKind.MINI -> mini
    }

    fun with(kind: YtComponentKind, root: YtNode): YtComponents = when (kind) {
        YtComponentKind.CARD -> copy(card = root)
        YtComponentKind.HEADER -> copy(header = root)
        YtComponentKind.CHIP -> copy(chip = root)
        YtComponentKind.NAV_BAR -> copy(navBar = root)
        YtComponentKind.NAV_TAB -> copy(navTab = root)
        YtComponentKind.MINI -> copy(mini = root)
    }
}

/** Columns of the feed per width class, and its spacing. */
@Serializable
data class YtFeedLayout(
    val compact: Int = 1,
    val medium: Int = 2,
    val expanded: Int = 3,
    val gap: Float = 12f,
    val rowGap: Float = 18f,
    val padding: YtInsets = YtInsets(12f, 4f, 12f, 16f),
    val chips: Boolean = true,
) {
    fun columns(width: YtWidthClass): Int = when (width) {
        YtWidthClass.COMPACT -> compact
        YtWidthClass.MEDIUM -> medium
        YtWidthClass.EXPANDED -> expanded
    }
}

/** The bar: floating (a pill off the edges) or docked (edge to edge). */
@Serializable
data class YtNavConfig(
    val floating: Boolean = true,
    /** Floating: the distance to the screen edges. */
    val side: Float = 14f,
    val height: Float = 64f,
    val radius: Float = 26f,
    val bottom: Float = 10f,
)

/** The docked mini player's measures (YtMiniSpec), clamped to what the player can do. */
@Serializable
data class YtMiniConfig(
    val height: Float = 62f,
    val radius: Float = 22f,
    val thumbWidth: Float = 86f,
    val thumbRadius: Float = 12f,
)

// ---- JSON colour ----

/** ARGB as "#AARRGGBB" in JSON; reads #RGB, #RRGGBB, #AARRGGBB and plain numbers. */
object YtArgbSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("YtArgb", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeString(YtColor.hex(value))
    override fun deserialize(decoder: Decoder): Int {
        val raw = decoder.decodeString()
        return YtColor.parse(raw) ?: raw.toLongOrNull()?.toInt() ?: 0xFF000000.toInt()
    }
}
