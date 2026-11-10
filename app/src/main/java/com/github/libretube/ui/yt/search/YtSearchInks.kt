package com.github.libretube.ui.yt.search

/*
 * YT: the colours of the design search as plain ARGB ints, so the JVM tests can check that
 * every text reads (4.5:1) on whatever lies below: each surface is either opaque or sits on a
 * scrim strong enough that a white or a black screen behind it changes little.
 */

/** The design search per design (the names of YtSignature). */
enum class YtSearchKind { YOUTUBE, MOVO, AFTER, SIDE, CLAY, EDITORIAL, GLASS, POP, SHAPES, FROST, KIESEL, FREQUENCY }

/**
 * Colours of one design's search.
 * [scrim] covers the screen, [panel] lies under the list (0 for none), [field] under the text
 * field, [chip] under the history chips. The texts go on the surface named in their name.
 */
data class YtSearchInks(
    val scrim: Int,
    val panel: Int,
    val field: Int,
    val fieldText: Int,
    val fieldHint: Int,
    val rowText: Int,
    val rowSecondary: Int,
    val chip: Int,
    val chipText: Int,
    /** The caret, the selected filter and small marks; not used for text. */
    val mark: Int,
    /** Tiles and pebbles cycle through these fills; their text is black or white, whichever reads. */
    val tiles: List<Int> = emptyList(),
)

object YtSearchPalette {
    private const val NONE = 0x00000000

    /** The inks of [kind]; [dark]: the dark mode of a light design (YtDesignDark). */
    fun inks(kind: YtSearchKind, dark: Boolean = false): YtSearchInks =
        (if (dark) darkInks(kind) else null) ?: dayInks(kind)

    /** The light designs at night: their own dark palettes; null for the dark-only designs. */
    private fun darkInks(kind: YtSearchKind): YtSearchInks? = when (kind) {
        YtSearchKind.CLAY -> YtSearchInks(
            scrim = 0xF52A201C.toInt(), panel = NONE,
            field = 0xFF46352E.toInt(), fieldText = 0xFFF7E8DA.toInt(), fieldHint = 0xFFC9AEA2.toInt(),
            rowText = 0xFFF7E8DA.toInt(), rowSecondary = 0xFFCBB1A5.toInt(),
            chip = 0xFF46352E.toInt(), chipText = 0xFFF7E8DA.toInt(), mark = 0xFFD9A5A6.toInt(),
        )
        YtSearchKind.EDITORIAL -> YtSearchInks(
            scrim = 0xFF0B0B0B.toInt(), panel = NONE,
            field = NONE, fieldText = 0xFFF2F2EE.toInt(), fieldHint = 0xFFA8A8A8.toInt(),
            rowText = 0xFFF2F2EE.toInt(), rowSecondary = 0xFFA8A8A8.toInt(),
            chip = 0xFF242424.toInt(), chipText = 0xFFF2F2EE.toInt(), mark = 0xFF3F76F8.toInt(),
        )
        YtSearchKind.GLASS -> YtSearchInks(
            scrim = 0xE60E1F1F.toInt(), panel = NONE,
            field = 0x8C2E5452.toInt(), fieldText = 0xFFE3F4F1.toInt(), fieldHint = 0xFF9CBDB9.toInt(),
            rowText = 0xFFE3F4F1.toInt(), rowSecondary = 0xFF9CBDB9.toInt(),
            chip = 0x992E5452.toInt(), chipText = 0xFFE3F4F1.toInt(), mark = 0xFFCFEDE7.toInt(),
        )
        YtSearchKind.POP -> YtSearchInks(
            scrim = 0xF51E1028.toInt(), panel = NONE,
            field = 0xFFFDE682.toInt(), fieldText = 0xFF1D032E.toInt(), fieldHint = 0xFF4D3F63.toInt(),
            rowText = 0xFFF5ECFF.toInt(), rowSecondary = 0xFFBFAED3.toInt(),
            chip = 0xFFF4B6CF.toInt(), chipText = 0xFF1D032E.toInt(), mark = 0xFFE0457F.toInt(),
            tiles = listOf(0xFFF4B6CF.toInt(), 0xFFFDE682.toInt(), 0xFFDCD6F5.toInt(), 0xFF4A2E66.toInt()),
        )
        YtSearchKind.KIESEL -> YtSearchInks(
            scrim = 0xF51F1D1B.toInt(), panel = NONE,
            field = 0xFF3A3632.toInt(), fieldText = 0xFFEDE8E3.toInt(), fieldHint = 0xFFABA59F.toInt(),
            rowText = 0xFFEDE8E3.toInt(), rowSecondary = 0xFFABA59F.toInt(),
            chip = 0xFF36322F.toInt(), chipText = 0xFFEDE8E3.toInt(), mark = 0xFFB4A7F5.toInt(),
            tiles = listOf(0xFFB4A7F5.toInt(), 0xFFF6C6A2.toInt(), 0xFF4A4541.toInt(), 0xFF3A3632.toInt()),
        )
        else -> null
    }

    private fun dayInks(kind: YtSearchKind): YtSearchInks = when (kind) {
        YtSearchKind.YOUTUBE -> YtSearchInks(
            scrim = 0xF5101014.toInt(), panel = NONE,
            field = 0xFF282830.toInt(), fieldText = 0xFFF6F4F2.toInt(), fieldHint = 0xFFB8B8C5.toInt(),
            rowText = 0xFFF6F4F2.toInt(), rowSecondary = 0xFFB8B8C5.toInt(),
            chip = 0xFF282830.toInt(), chipText = 0xFFF6F4F2.toInt(), mark = 0xFFFF334B.toInt(),
        )
        YtSearchKind.MOVO -> YtSearchInks(
            scrim = 0xF5101112.toInt(), panel = NONE,
            field = 0xFFC9F26F.toInt(), fieldText = 0xFF101112.toInt(), fieldHint = 0xFF3F4A22.toInt(),
            rowText = 0xFFF7F6F2.toInt(), rowSecondary = 0xFFB5B6BC.toInt(),
            chip = 0xFF2B2C30.toInt(), chipText = 0xFFF7F6F2.toInt(), mark = 0xFF5260ED.toInt(),
            tiles = listOf(0xFF5260ED.toInt(), 0xFFE557D6.toInt(), 0xFFC9C3FF.toInt(), 0xFF2B2C30.toInt()),
        )
        YtSearchKind.AFTER -> YtSearchInks(
            scrim = 0xF51A0B0F.toInt(), panel = 0xFF000000.toInt(),
            field = 0xFF000000.toInt(), fieldText = 0xFFFFF4DF.toInt(), fieldHint = 0xFFD8C6B6.toInt(),
            rowText = 0xFFFFF4DF.toInt(), rowSecondary = 0xFFD8C6B6.toInt(),
            chip = 0xFF2B1219.toInt(), chipText = 0xFFFFF4DF.toInt(), mark = 0xFFFF8B4B.toInt(),
        )
        YtSearchKind.SIDE -> YtSearchInks(
            scrim = 0xF5292A2C.toInt(), panel = NONE,
            field = 0xFFFFC68D.toInt(), fieldText = 0xFF2A1E12.toInt(), fieldHint = 0xFF5A4530.toInt(),
            rowText = 0xFFF5F1EB.toInt(), rowSecondary = 0xFFC4C2BD.toInt(),
            chip = 0xFF414245.toInt(), chipText = 0xFFF5F1EB.toInt(), mark = 0xFFFFC68D.toInt(),
        )
        YtSearchKind.CLAY -> YtSearchInks(
            scrim = 0xF5F5D8C7.toInt(), panel = NONE,
            field = 0xFFFBE8D9.toInt(), fieldText = 0xFF2D2535.toInt(), fieldHint = 0xFF6B5654.toInt(),
            rowText = 0xFF2D2535.toInt(), rowSecondary = 0xFF6B5654.toInt(),
            chip = 0xFFFBE8D9.toInt(), chipText = 0xFF2D2535.toInt(), mark = 0xFF9D6A6C.toInt(),
        )
        YtSearchKind.EDITORIAL -> YtSearchInks(
            scrim = 0xFFFFFFFF.toInt(), panel = NONE,
            field = NONE, fieldText = 0xFF0A0A0A.toInt(), fieldHint = 0xFF555555.toInt(),
            rowText = 0xFF0A0A0A.toInt(), rowSecondary = 0xFF555555.toInt(),
            chip = 0xFFF4F4F4.toInt(), chipText = 0xFF0A0A0A.toInt(), mark = 0xFF0F54F6.toInt(),
        )
        YtSearchKind.GLASS -> YtSearchInks(
            scrim = 0xE6D1EAE3.toInt(), panel = NONE,
            field = 0x8CFFFFFF.toInt(), fieldText = 0xFF0F2B2E.toInt(), fieldHint = 0xFF2F4C4E.toInt(),
            rowText = 0xFF0F2B2E.toInt(), rowSecondary = 0xFF2F4C4E.toInt(),
            chip = 0x99FFFFFF.toInt(), chipText = 0xFF0F2B2E.toInt(), mark = 0xFF0F2B2E.toInt(),
        )
        YtSearchKind.POP -> YtSearchInks(
            scrim = 0xF5E6E5FD.toInt(), panel = NONE,
            field = 0xFFFDE682.toInt(), fieldText = 0xFF1D032E.toInt(), fieldHint = 0xFF4D3F63.toInt(),
            rowText = 0xFF1D032E.toInt(), rowSecondary = 0xFF5E4E73.toInt(),
            chip = 0xFFF4B6CF.toInt(), chipText = 0xFF1D032E.toInt(), mark = 0xFFE0457F.toInt(),
            tiles = listOf(0xFFF4B6CF.toInt(), 0xFFFDE682.toInt(), 0xFFDCD6F5.toInt(), 0xFF3B2054.toInt()),
        )
        YtSearchKind.SHAPES -> YtSearchInks(
            scrim = 0xF512122A.toInt(), panel = NONE,
            field = 0xFFC6F36B.toInt(), fieldText = 0xFF17132B.toInt(), fieldHint = 0xFF3D4620.toInt(),
            rowText = 0xFFF5F3FF.toInt(), rowSecondary = 0xFFB8B5D8.toInt(),
            chip = 0xFF2A2950.toInt(), chipText = 0xFFF5F3FF.toInt(), mark = 0xFFC6F36B.toInt(),
            tiles = listOf(0xFF8B7CF6.toInt(), 0xFFF4A9B8.toInt(), 0xFFE6E1FF.toInt(), 0xFF2A2950.toInt()),
        )
        // Frequency: Formen's bright field in aqua with night ink, the rows on the night,
        // coral marks, chips in coral, violet and amber
        YtSearchKind.FREQUENCY -> YtSearchInks(
            scrim = 0xF50B0A18.toInt(), panel = NONE,
            field = 0xFF5BE7DA.toInt(), fieldText = 0xFF0B0A18.toInt(), fieldHint = 0xFF1C4743.toInt(),
            rowText = 0xFFF4F2FF.toInt(), rowSecondary = 0xFFB7B2D6.toInt(),
            chip = 0xFF231F3F.toInt(), chipText = 0xFFF4F2FF.toInt(), mark = 0xFFFF4D6D.toInt(),
            tiles = listOf(0xFFFF4D6D.toInt(), 0xFFB3A6FF.toInt(), 0xFFFFC46B.toInt(), 0xFF231F3F.toInt()),
        )
        YtSearchKind.FROST -> YtSearchInks(
            scrim = 0x8C2E3B36.toInt(), panel = 0xE63A4A44.toInt(),
            field = 0xFF1F2A26.toInt(), fieldText = 0xFFF3F6F4.toInt(), fieldHint = 0xFFC9D6CF.toInt(),
            rowText = 0xFFF3F6F4.toInt(), rowSecondary = 0xFFD5E0DA.toInt(),
            chip = 0x1FFFFFFF, chipText = 0xFFF3F6F4.toInt(), mark = 0xFFF08A3C.toInt(),
        )
        YtSearchKind.KIESEL -> YtSearchInks(
            scrim = 0xF5E9E5E0.toInt(), panel = NONE,
            field = 0xFF0E0E0E.toInt(), fieldText = 0xFFF7F4F1.toInt(), fieldHint = 0xFFB5B0AA.toInt(),
            rowText = 0xFF0E0E0E.toInt(), rowSecondary = 0xFF5F5B57.toInt(),
            chip = 0xFFDDD8D2.toInt(), chipText = 0xFF0E0E0E.toInt(), mark = 0xFFB4A7F5.toInt(),
            tiles = listOf(0xFFB4A7F5.toInt(), 0xFFF6C6A2.toInt(), 0xFFDDD8D2.toInt(), 0xFF0E0E0E.toInt()),
        )
    }

    /** Black or white text on [fill], whichever reads better (tiles and pebbles). */
    fun onFill(fill: Int): Int {
        val r = (fill ushr 16 and 0xFF) / 255.0
        val g = (fill ushr 8 and 0xFF) / 255.0
        val b = (fill and 0xFF) / 255.0
        fun lin(c: Double) = if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        val l = 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
        val onWhite = 1.05 / (l + 0.05)
        val onBlack = (l + 0.05) / 0.05
        return if (onBlack >= onWhite) 0xFF0E0E10.toInt() else 0xFFFFFFFF.toInt()
    }

    /** The fill of the [index]-th tile or pebble. */
    fun tile(kind: YtSearchKind, index: Int, dark: Boolean = false): Int {
        val inks = inks(kind, dark)
        return if (inks.tiles.isEmpty()) inks.chip else inks.tiles[Math.floorMod(index, inks.tiles.size)]
    }
}
