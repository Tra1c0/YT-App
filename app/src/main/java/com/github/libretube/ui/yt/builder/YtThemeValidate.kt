package com.github.libretube.ui.yt.builder

/**
 * YT: makes a theme document safe to draw (pure). Every number is clamped to what the renderer
 * can take, broken parts get safe defaults, and what was changed is reported, so a damaged or
 * hand-edited file never crashes the app and the editor can tell what it repaired.
 */
object YtThemeValidate {
    const val MAX_SIZE = 4000f
    const val MAX_RADIUS = 1000f
    const val MAX_SHADOW = 200f
    const val MAX_NODES = 1500
    const val MAX_DEPTH = 24
    const val MAX_CHILDREN = 200
    const val MAX_FILLS = 8
    const val MAX_SHADOWS = 8
    const val MAX_TEXT = 500
    const val MAX_COLUMNS = 8

    /** What validation changed: [path] says where, [what] is a short English reason. */
    data class Issue(val path: String, val what: String)

    data class Result(val doc: YtThemeDoc, val issues: List<Issue>)

    fun clamp(doc: YtThemeDoc): Result {
        val issues = mutableListOf<Issue>()
        val ctx = Ctx(issues)
        val ids = HashSet<String>()
        val tokens = tokens(doc.tokens, ctx)
        val components = YtComponents(
            card = ctx.root("card", doc.components.card, ids),
            header = ctx.root("header", doc.components.header, ids),
            chip = ctx.root("chip", doc.components.chip, ids),
            navBar = ctx.root("navBar", doc.components.navBar, ids),
            navTab = ctx.root("navTab", doc.components.navTab, ids),
            mini = ctx.root("mini", doc.components.mini, ids),
        )
        val feed = doc.feed.let { f ->
            f.copy(
                compact = ctx.int("feed.compact", f.compact, 1, MAX_COLUMNS),
                medium = ctx.int("feed.medium", f.medium, 1, MAX_COLUMNS),
                expanded = ctx.int("feed.expanded", f.expanded, 1, MAX_COLUMNS),
                gap = ctx.num("feed.gap", f.gap, 0f, 200f),
                rowGap = ctx.num("feed.rowGap", f.rowGap, 0f, 200f),
                padding = ctx.insets("feed.padding", f.padding),
            )
        }
        val nav = doc.nav.let { n ->
            n.copy(
                side = ctx.num("nav.side", n.side, 0f, 60f),
                height = ctx.num("nav.height", n.height, 48f, 120f),
                radius = ctx.num("nav.radius", n.radius, 0f, 60f),
                bottom = ctx.num("nav.bottom", n.bottom, 0f, 40f),
            )
        }
        val mini = doc.mini.let { m ->
            m.copy(
                height = ctx.num("mini.height", m.height, 48f, 96f),
                radius = ctx.num("mini.radius", m.radius, 0f, 48f),
                thumbWidth = ctx.num("mini.thumbWidth", m.thumbWidth, 48f, 140f),
                thumbRadius = ctx.num("mini.thumbRadius", m.thumbRadius, 0f, 40f),
            )
        }
        val assets = doc.assets.filter { asset ->
            YtThemeZip.safeAssetName(asset.file).also { if (!it) issues += Issue("assets", "unsafe file name dropped") }
        }.distinctBy { it.file }
        val name = doc.name.take(80).ifBlank { "Theme" }
        val id = doc.id.takeIf { YtThemeIds.valid(it) } ?: YtThemeIds.fresh().also { issues += Issue("id", "invalid id replaced") }
        val meta = doc.meta.copy(wordmark = doc.meta.wordmark.take(40))
        val nodeIds = YtComponentKind.entries.associateWith { k -> YtDocOps.all(components[k]).mapTo(HashSet()) { it.id } }
        val animations = YtAnimValidate.clamp(doc.animations, nodeIds) { path, what -> issues += Issue(path, what) }
        return Result(
            doc.copy(
                schema = YT_THEME_SCHEMA, id = id, name = name, tokens = tokens, components = components,
                feed = feed, nav = nav, mini = mini, assets = assets, meta = meta, animations = animations,
            ),
            issues,
        )
    }

    private fun tokens(t: YtTokens, ctx: Ctx): YtTokens {
        val colors = LinkedHashMap<String, YtColorToken>()
        // every palette role exists, so a token reference always resolves
        for ((name, token) in t.colors.entries.take(64)) {
            if (name.isBlank() || name.length > 40) continue
            colors[name] = YtColorToken(ctx.paint("colors.$name", token.day, 0), token.night?.let { ctx.paint("colors.$name.night", it, 0) })
        }
        for (role in YtRole.all) {
            if (role !in colors) {
                ctx.issue("colors.$role", "missing role added")
                colors[role] = YtColorToken(YtPaint.solid(YtThemeSeeds.fallbackRole(role)))
            }
        }
        val type = LinkedHashMap<String, YtTypeToken>()
        for ((name, token) in t.type.entries.take(32)) {
            type[name] = token.copy(
                font = token.font.take(120),
                weight = ctx.int("type.$name.weight", token.weight, 100, 900),
                size = ctx.num("type.$name.size", token.size, 4f, 200f),
                lineHeight = ctx.num("type.$name.lineHeight", token.lineHeight, 0f, 400f),
                letterSpacing = ctx.num("type.$name.letterSpacing", token.letterSpacing, -2f, 4f),
            )
        }
        for (name in YtThemeSeeds.typeNames) if (name !in type) type[name] = YtThemeSeeds.fallbackType(name)
        val spacing = t.spacing.entries.take(32).associate { (k, v) -> k to ctx.num("spacing.$k", v, 0f, 400f) }
        val radii = t.radii.entries.take(32).associate { (k, v) -> k to ctx.num("radii.$k", v, 0f, MAX_RADIUS) }
        val shadows = t.shadows.entries.take(16).associate { (k, v) -> k to v.take(MAX_SHADOWS).map { ctx.shadow("shadows.$k", it) } }
        val m = t.motion
        val motion = m.copy(
            stiffness = ctx.num("motion.stiffness", m.stiffness, 20f, 10000f),
            damping = ctx.num("motion.damping", m.damping, 0.1f, 2f),
            pressScale = ctx.num("motion.pressScale", m.pressScale, 0.8f, 1.1f),
            durationMs = ctx.int("motion.durationMs", m.durationMs, 0, 3000),
        )
        return YtTokens(colors, type, spacing, radii, shadows, motion)
    }

    private class Ctx(val issues: MutableList<Issue>) {
        var count = 0

        fun issue(path: String, what: String) {
            if (issues.size < 200) issues += Issue(path, what)
        }

        fun num(path: String, v: Float, min: Float, max: Float): Float {
            if (v.isNaN() || v.isInfinite()) {
                issue(path, "not a number")
                return min.coerceAtLeast(0f).coerceAtMost(max)
            }
            if (v < min || v > max) {
                issue(path, "clamped")
                return v.coerceIn(min, max)
            }
            return v
        }

        fun opt(path: String, v: Float?, min: Float, max: Float): Float? = v?.let { num(path, it, min, max) }

        fun int(path: String, v: Int, min: Int, max: Int): Int {
            if (v < min || v > max) issue(path, "clamped")
            return v.coerceIn(min, max)
        }

        fun insets(path: String, i: YtInsets) = YtInsets(
            num("$path.start", i.start, 0f, 400f), num("$path.top", i.top, 0f, 400f),
            num("$path.end", i.end, 0f, 400f), num("$path.bottom", i.bottom, 0f, 400f),
        )

        fun size(path: String, s: YtSize): YtSize = when (s.kind) {
            YtSizeKind.FIXED -> s.copy(value = num(path, s.value, 0f, MAX_SIZE))
            YtSizeKind.PERCENT -> s.copy(value = num(path, s.value, 0f, 100f))
            YtSizeKind.FILL -> s.copy(value = num(path, s.value, 0f, 100f))
            YtSizeKind.HUG -> s.copy(value = 0f)
        }

        fun paint(path: String, p0: YtPaint, depth: Int): YtPaint {
            // an image fill names a safe image file; anything else is a flat colour
            val p = if (p0.type == YtPaintType.IMAGE && (p0.image == null || !YtThemeZip.safeAssetName(p0.image) || YtThemeZip.kindOf(p0.image) != YtAssetKind.IMAGE)) {
                issue(path, "image fill without a usable file")
                p0.copy(type = YtPaintType.SOLID, image = null)
            } else {
                p0
            }
            val stops = if (p.type == YtPaintType.SOLID) p.stops.take(16) else YtColor.normalizeStops(p.stops, p.color)
            if (p.type != YtPaintType.SOLID && stops.size != p.stops.size) issue(path, "gradient stops repaired")
            return p.copy(
                ref = p.ref?.take(40)?.takeIf { it.isNotBlank() },
                stops = stops,
                angle = if (p.angle.isNaN() || p.angle.isInfinite()) 90f else ((p.angle % 360f) + 360f) % 360f,
                centerX = num("$path.centerX", p.centerX, -1f, 2f),
                centerY = num("$path.centerY", p.centerY, -1f, 2f),
                radius = num("$path.radius", p.radius, 0.01f, 4f),
            )
        }

        fun shadow(path: String, s: YtShadow) = s.copy(
            x = num("$path.x", s.x, -MAX_SHADOW, MAX_SHADOW),
            y = num("$path.y", s.y, -MAX_SHADOW, MAX_SHADOW),
            blur = num("$path.blur", s.blur, 0f, MAX_SHADOW),
            spread = num("$path.spread", s.spread, -MAX_SHADOW, MAX_SHADOW),
        )

        fun corners(path: String, c: YtCorners) = c.copy(
            topStart = num("$path.topStart", c.topStart, 0f, MAX_RADIUS),
            topEnd = num("$path.topEnd", c.topEnd, 0f, MAX_RADIUS),
            bottomEnd = num("$path.bottomEnd", c.bottomEnd, 0f, MAX_RADIUS),
            bottomStart = num("$path.bottomStart", c.bottomStart, 0f, MAX_RADIUS),
            smoothing = num("$path.smoothing", c.smoothing, 0f, 1f),
        )

        fun style(path: String, s: YtStyle) = YtStyle(
            fills = s.fills.take(MAX_FILLS).map { paint("$path.fill", it, 0) },
            strokes = s.strokes.take(MAX_FILLS).map { it.copy(paint = paint("$path.stroke", it.paint, 0), width = num("$path.strokeWidth", it.width, 0f, 100f)) },
            shadows = s.shadows.take(MAX_SHADOWS).map { shadow("$path.shadow", it) },
            corners = corners("$path.corners", s.corners),
            opacity = num("$path.opacity", s.opacity, 0f, 1f),
        )

        fun patch(path: String, p: YtPatch) = p.copy(
            width = p.width?.let { size("$path.width", it) },
            height = p.height?.let { size("$path.height", it) },
            x = opt("$path.x", p.x, -MAX_SIZE, MAX_SIZE),
            y = opt("$path.y", p.y, -MAX_SIZE, MAX_SIZE),
            rotation = opt("$path.rotation", p.rotation, -3600f, 3600f),
            padding = p.padding?.let { insets("$path.padding", it) },
            gap = opt("$path.gap", p.gap, 0f, 400f),
            columns = p.columns?.coerceIn(1, MAX_COLUMNS),
            opacity = opt("$path.opacity", p.opacity, 0f, 1f),
            corners = p.corners?.let { corners("$path.corners", it) },
            fills = p.fills?.take(MAX_FILLS)?.map { paint("$path.fill", it, 0) },
            strokes = p.strokes?.take(MAX_FILLS)?.map { it.copy(paint = paint("$path.stroke", it.paint, 0), width = num("$path.strokeWidth", it.width, 0f, 100f)) },
            textColor = p.textColor?.let { paint("$path.textColor", it, 0) },
            textSize = opt("$path.textSize", p.textSize, 4f, 200f),
            maxLines = p.maxLines?.coerceIn(1, 20),
            scale = opt("$path.scale", p.scale, 0.5f, 1.5f),
        )

        fun root(path: String, n: YtNode, ids: MutableSet<String>): YtNode = node(path, n, 0, ids)

        fun node(path: String, n: YtNode, depth: Int, ids: MutableSet<String>): YtNode {
            count++
            var id = n.id.take(40).ifBlank { "n$count" }
            if (!ids.add(id)) {
                issue(path, "duplicate id renamed")
                id = "${id}_$count"
                ids.add(id)
            }
            val p = "$path/$id"
            val children = when {
                depth >= MAX_DEPTH -> {
                    if (n.children.isNotEmpty()) issue(p, "too deep, children dropped")
                    emptyList()
                }
                count >= MAX_NODES -> {
                    if (n.children.isNotEmpty()) issue(p, "too many nodes, children dropped")
                    emptyList()
                }
                else -> n.children.take(MAX_CHILDREN).map { node(p, it, depth + 1, ids) }
            }
            var minW = opt("$p.minW", n.minW, 0f, MAX_SIZE)
            var maxW = opt("$p.maxW", n.maxW, 0f, MAX_SIZE)
            var minH = opt("$p.minH", n.minH, 0f, MAX_SIZE)
            var maxH = opt("$p.maxH", n.maxH, 0f, MAX_SIZE)
            // a minimum above the maximum: the editor reports it (YtLayoutEngine); here it is only kept drawable
            if (minW != null && maxW != null && minW > maxW) maxW = minW
            if (minH != null && maxH != null && minH > maxH) maxH = minH
            val aspect = n.aspect?.let { a -> if (a.isNaN() || a <= 0f) null else a.coerceIn(0.05f, 20f) }
            return n.copy(
                id = id,
                name = n.name.take(60),
                layout = n.layout.copy(
                    gap = num("$p.gap", n.layout.gap, 0f, 400f),
                    crossGap = num("$p.crossGap", n.layout.crossGap, 0f, 400f),
                    columns = int("$p.columns", n.layout.columns, 1, MAX_COLUMNS),
                ),
                width = size("$p.width", n.width),
                height = size("$p.height", n.height),
                minW = minW, maxW = maxW, minH = minH, maxH = maxH,
                aspect = aspect,
                padding = insets("$p.padding", n.padding),
                x = num("$p.x", n.x, -MAX_SIZE, MAX_SIZE),
                y = num("$p.y", n.y, -MAX_SIZE, MAX_SIZE),
                rotation = num("$p.rotation", n.rotation, -3600f, 3600f),
                z = n.z.coerceIn(-1000, 1000),
                style = style(p, n.style),
                text = n.text?.let { t ->
                    t.copy(
                        text = t.text.take(MAX_TEXT),
                        color = paint("$p.textColor", t.color, 0),
                        maxLines = int("$p.maxLines", t.maxLines, 1, 20),
                        size = if (t.size == 0f) 0f else num("$p.textSize", t.size, 4f, 200f),
                        weight = if (t.weight == 0) 0 else int("$p.weight", t.weight, 100, 900),
                    )
                },
                icon = n.icon?.take(40),
                overrides = n.overrides.take(16).map { o -> o.copy(minFontScale = opt("$p.fontScale", o.minFontScale, 0.5f, 4f), patch = patch("$p.override", o.patch)) },
                states = n.states.entries.take(8).associate { (k, v) -> k.take(20) to patch("$p.state", v) },
                children = children,
            )
        }
    }
}

/** YT: theme ids are safe as folder names (pure). */
object YtThemeIds {
    private val pattern = Regex("[a-z0-9_-]{1,40}")

    fun valid(id: String): Boolean = pattern.matches(id)

    fun fresh(now: Long = System.currentTimeMillis()): String =
        "t" + now.toString(36) + (1000..9999).random().toString(36)
}
