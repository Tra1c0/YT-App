package com.github.libretube.ui.yt.builder

/**
 * YT: the starting point of a new theme (pure): the colours, type, corners and motion of the
 * design it starts from ([YtSeed]) as tokens, and a component tree for each surface that already
 * looks like a complete design (a YouTube-like card with real video data, a header, chips, a
 * floating bar, the mini player's body).
 */
object YtThemeSeeds {
    /** What a theme takes from the design it starts from. Colours ARGB, the font a YtFontKeys key. */
    data class YtSeed(
        val design: String,
        val roles: Map<String, Int>,
        val font: String = "geist",
        val light: Boolean = false,
        /** Corner radius of cards in dp. */
        val radius: Float = 14f,
        val smoothing: Float = 0f,
        val stiffness: Float = 500f,
        val damping: Float = 0.8f,
    )

    val typeNames = listOf("display", "title", "body", "caption", "label", "navLabel")

    fun fallbackType(name: String): YtTypeToken = when (name) {
        "display" -> YtTypeToken(weight = 800, size = 22f, letterSpacing = -0.3f)
        "title" -> YtTypeToken(weight = 500, size = 15f, lineHeight = 20f)
        "caption" -> YtTypeToken(weight = 400, size = 12f, lineHeight = 16f)
        "label" -> YtTypeToken(weight = 500, size = 13f)
        "navLabel" -> YtTypeToken(weight = 500, size = 11f)
        else -> YtTypeToken(weight = 400, size = 14f, lineHeight = 19f)
    }

    /** The colour of a palette role nothing else gives (a neutral dark look). */
    fun fallbackRole(role: String): Int = when (role) {
        YtRole.BACKGROUND -> 0xFF101014.toInt()
        YtRole.SURFACE -> 0xFF1C1C23.toInt()
        YtRole.RAISED -> 0xFF282830.toInt()
        YtRole.TEXT -> 0xFFF6F4F2.toInt()
        YtRole.TEXT_SECONDARY -> 0xFFB8B8C5.toInt()
        YtRole.ACCENT -> 0xFFFF334B.toInt()
        YtRole.ON_ACCENT -> 0xFFFFFFFF.toInt()
        YtRole.LIVE -> 0xFFC91F3D.toInt()
        YtRole.LIME -> 0xFFC9F26F.toInt()
        YtRole.LAVENDER -> 0xFFB4AEFF.toInt()
        YtRole.INK -> 0xFF101014.toInt()
        YtRole.PINK -> 0xFFE557D6.toInt()
        else -> 0xFF808080.toInt()
    }

    /** A new theme from [seed], named [name]. */
    fun create(seed: YtSeed, id: String, name: String, now: Long = 0L, night: Map<String, Int>? = null): YtThemeDoc {
        val colors = YtRole.all.associateWith { role ->
            val day = seed.roles[role] ?: fallbackRole(role)
            YtColorToken(YtPaint.solid(day), night?.get(role)?.let { YtPaint.solid(it) })
        }
        val type = typeNames.associateWith { fallbackType(it).copy(font = seed.font) }
        val r = seed.radius
        val tokens = YtTokens(
            colors = colors,
            type = type,
            spacing = mapOf("xs" to 4f, "s" to 8f, "m" to 12f, "l" to 16f, "xl" to 24f),
            radii = mapOf("card" to r, "chip" to 17f, "thumb" to r, "avatar" to 18f),
            shadows = mapOf("raised" to listOf(YtShadow(0f, 6f, 18f, 0f, 0x33000000))),
            motion = YtMotionToken(stiffness = seed.stiffness, damping = seed.damping),
        )
        val mode = if (night != null || seed.light) YtThemeMode.BOTH else YtThemeMode.DARK
        return YtThemeDoc(
            id = id,
            name = name,
            base = seed.design,
            mode = mode,
            tokens = tokens,
            components = YtComponents(
                card = card(r, seed.smoothing),
                header = header(),
                chip = chip(),
                navBar = navBar(),
                navTab = navTab(),
                mini = mini(),
            ),
            nav = YtNavConfig(radius = (r + 12f).coerceAtMost(32f)),
            mini = YtMiniConfig(radius = (r + 8f).coerceAtMost(30f), thumbRadius = (r - 2f).coerceIn(0f, 20f)),
            meta = YtThemeMeta(created = now, modified = now, wordmark = "YT"),
        )
    }

    private fun text(slot: YtSlot, id: String, style: String, color: String = YtRole.TEXT, lines: Int = 1) = YtNode(
        id = id, type = YtNodeType.SLOT, slot = slot, name = id,
        width = YtSize.FILL,
        text = YtTextSpec(styleRef = style, color = YtPaint.token(color), maxLines = lines),
    )

    fun card(radius: Float, smoothing: Float = 0f) = YtNode(
        id = "card", name = "Card",
        layout = YtLayout(YtLayoutMode.FLOW_V, gap = 10f),
        width = YtSize.FILL,
        children = listOf(
            YtNode(
                id = "thumbFrame", name = "Thumbnail",
                layout = YtLayout(YtLayoutMode.FREE, clip = true),
                width = YtSize.FILL, aspect = 16f / 9f,
                style = YtStyle(fills = listOf(YtPaint.token(YtRole.RAISED)), corners = YtCorners.all(radius, smoothing)),
                children = listOf(
                    YtNode(id = "thumb", type = YtNodeType.SLOT, slot = YtSlot.THUMBNAIL, name = "Picture", width = YtSize.FILL, height = YtSize.FILL),
                    YtNode(
                        id = "progress", type = YtNodeType.SLOT, slot = YtSlot.PROGRESS, name = "Progress",
                        width = YtSize.FILL, height = YtSize.fixed(3f), anchorV = YtAnchor.END,
                        style = YtStyle(fills = listOf(YtPaint.token(YtRole.ACCENT))),
                    ),
                    YtNode(
                        id = "badge", name = "Duration",
                        layout = YtLayout(YtLayoutMode.FLOW_H),
                        x = 8f, y = 8f, anchorH = YtAnchor.END, anchorV = YtAnchor.END,
                        padding = YtInsets.sym(6f, 2f),
                        style = YtStyle(fills = listOf(YtPaint.solid(0xCC000000.toInt())), corners = YtCorners.all(6f)),
                        children = listOf(
                            YtNode(
                                id = "duration", type = YtNodeType.SLOT, slot = YtSlot.DURATION, name = "Time",
                                text = YtTextSpec(styleRef = "caption", color = YtPaint.solid(0xFFFFFFFF.toInt()), maxLines = 1),
                            ),
                        ),
                    ),
                ),
            ),
            YtNode(
                id = "infoRow", name = "Info",
                layout = YtLayout(YtLayoutMode.FLOW_H, gap = 10f),
                width = YtSize.FILL, padding = YtInsets(2f, 0f, 2f, 0f),
                children = listOf(
                    YtNode(
                        id = "avatar", type = YtNodeType.SLOT, slot = YtSlot.AVATAR, name = "Avatar",
                        width = YtSize.fixed(36f), height = YtSize.fixed(36f),
                        style = YtStyle(fills = listOf(YtPaint.token(YtRole.RAISED)), corners = YtCorners.all(18f)),
                    ),
                    YtNode(
                        id = "texts", name = "Texts",
                        layout = YtLayout(YtLayoutMode.FLOW_V, gap = 3f),
                        width = YtSize.FILL,
                        children = listOf(
                            text(YtSlot.TITLE, "title", "title", lines = 2),
                            text(YtSlot.META, "meta", "caption", YtRole.TEXT_SECONDARY),
                        ),
                    ),
                ),
            ),
        ),
    )

    fun header() = YtNode(
        id = "header", name = "Header",
        layout = YtLayout(YtLayoutMode.FLOW_H, gap = 10f, align = YtAlign.CENTER),
        width = YtSize.FILL, height = YtSize.fixed(56f),
        padding = YtInsets.sym(16f, 0f),
        children = listOf(
            YtNode(
                id = "wordmark", type = YtNodeType.SLOT, slot = YtSlot.WORDMARK, name = "Wordmark",
                text = YtTextSpec(styleRef = "display", color = YtPaint.token(YtRole.TEXT), maxLines = 1),
            ),
            YtNode(id = "headerSpace", name = "Space", width = YtSize.FILL, height = YtSize.fixed(1f)),
            YtNode(
                id = "search", type = YtNodeType.SLOT, slot = YtSlot.SEARCH, name = "Search",
                width = YtSize.fixed(40f), height = YtSize.fixed(40f),
                style = YtStyle(fills = listOf(YtPaint.token(YtRole.RAISED)), corners = YtCorners.all(20f)),
                text = YtTextSpec(color = YtPaint.token(YtRole.TEXT)),
            ),
            YtNode(
                id = "profile", type = YtNodeType.SLOT, slot = YtSlot.PROFILE, name = "Profile",
                width = YtSize.fixed(40f), height = YtSize.fixed(40f),
                style = YtStyle(fills = listOf(YtPaint.token(YtRole.RAISED)), corners = YtCorners.all(20f)),
                text = YtTextSpec(color = YtPaint.token(YtRole.TEXT)),
            ),
        ),
    )

    fun chip() = YtNode(
        id = "chip", name = "Chip",
        layout = YtLayout(YtLayoutMode.FLOW_H, align = YtAlign.CENTER),
        height = YtSize.fixed(34f),
        padding = YtInsets.sym(14f, 0f),
        style = YtStyle(fills = listOf(YtPaint.token(YtRole.RAISED)), corners = YtCorners.all(17f)),
        states = mapOf(YtStates.SELECTED to YtPatch(fills = listOf(YtPaint.token(YtRole.TEXT)))),
        children = listOf(
            YtNode(
                id = "chipLabel", type = YtNodeType.SLOT, slot = YtSlot.CHIP_LABEL, name = "Label",
                text = YtTextSpec(styleRef = "label", color = YtPaint.token(YtRole.TEXT), maxLines = 1),
                states = mapOf(YtStates.SELECTED to YtPatch(textColor = YtPaint.token(YtRole.BACKGROUND))),
            ),
        ),
    )

    fun navBar() = YtNode(
        id = "navBar", name = "Bar",
        layout = YtLayout(YtLayoutMode.FLOW_H, align = YtAlign.STRETCH),
        width = YtSize.FILL, height = YtSize.FILL,
        padding = YtInsets.sym(6f, 0f),
        style = YtStyle(
            fills = listOf(YtPaint.token(YtRole.SURFACE)),
            strokes = listOf(YtStroke(YtPaint.solid(0x1FFFFFFF), 1f)),
            shadows = listOf(YtShadow(0f, 8f, 24f, 0f, 0x40000000)),
        ),
    )

    fun navTab() = YtNode(
        id = "navTab", name = "Tab",
        layout = YtLayout(YtLayoutMode.FLOW_V, gap = 3f, align = YtAlign.CENTER, justify = YtJustify.CENTER),
        width = YtSize.FILL, height = YtSize.FILL,
        children = listOf(
            YtNode(
                id = "navIcon", type = YtNodeType.SLOT, slot = YtSlot.NAV_ICON, name = "Icon",
                width = YtSize.fixed(24f), height = YtSize.fixed(24f),
                text = YtTextSpec(color = YtPaint.token(YtRole.TEXT_SECONDARY)),
                states = mapOf(YtStates.SELECTED to YtPatch(textColor = YtPaint.token(YtRole.TEXT))),
            ),
            YtNode(
                id = "navLabel", type = YtNodeType.SLOT, slot = YtSlot.NAV_LABEL, name = "Label",
                text = YtTextSpec(styleRef = "navLabel", color = YtPaint.token(YtRole.TEXT_SECONDARY), maxLines = 1, align = YtTextAlign.CENTER),
                states = mapOf(YtStates.SELECTED to YtPatch(textColor = YtPaint.token(YtRole.TEXT))),
            ),
            YtNode(
                id = "navMark", type = YtNodeType.SHAPE, shape = YtShapeKind.PILL, name = "Indicator",
                width = YtSize.fixed(18f), height = YtSize.fixed(3f), visible = false,
                style = YtStyle(fills = listOf(YtPaint.token(YtRole.ACCENT))),
                states = mapOf(YtStates.SELECTED to YtPatch(visible = true)),
            ),
        ),
    )

    fun mini() = YtNode(
        id = "mini", name = "Mini player",
        layout = YtLayout(YtLayoutMode.FLOW_V, gap = 2f),
        width = YtSize.FILL, height = YtSize.FILL,
        style = YtStyle(
            fills = listOf(YtPaint.token(YtRole.SURFACE)),
            strokes = listOf(YtStroke(YtPaint.solid(0x1FFFFFFF), 1f)),
        ),
        children = listOf(
            text(YtSlot.TITLE, "miniTitle", "label"),
            text(YtSlot.CHANNEL, "miniChannel", "caption", YtRole.TEXT_SECONDARY),
            YtNode(
                id = "miniProgress", type = YtNodeType.SLOT, slot = YtSlot.PROGRESS, name = "Progress",
                width = YtSize.FILL, height = YtSize.fixed(2f),
                style = YtStyle(fills = listOf(YtPaint.token(YtRole.ACCENT))),
            ),
        ),
    )
}

/** Names of component states the renderer knows. */
object YtStates {
    const val SELECTED = "selected"
    const val PRESSED = "pressed"
}
