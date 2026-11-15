package com.github.libretube.ui.yt.builder

/**
 * YT: makes a theme's animations safe to play (pure): numbers clamped, keyframes sorted, ids
 * unique, references to missing nodes, groups or states dropped, counts capped. Runs inside
 * YtThemeValidate.clamp on every read.
 */
object YtAnimValidate {
    const val MAX_MS = 60_000
    const val MAX_ANIMS = 256
    const val MAX_TRACKS = 32
    const val MAX_KEYS = 64
    const val MAX_REPEAT = 1000
    const val MAX_GROUPS = 64
    const val MAX_RULES = 64
    const val MAX_CUSTOM = 16
    const val MAX_EASINGS = 32
    const val MAX_VELOCITY = 10_000f

    private val idPattern = Regex("[A-Za-z0-9_-]{1,40}")

    /**
     * [a] clamped. [nodes] holds the node ids of each component; [issue] gets (path, reason) of
     * every repair.
     */
    fun clamp(a: YtAnimations, nodes: Map<YtComponentKind, Set<String>>, issue: (String, String) -> Unit): YtAnimations {
        val custom = a.customStates.filter { YtStateNames.validCustom(it).also { ok -> if (!ok) issue("animations.customStates", "invalid state name dropped") } }
            .distinct().take(MAX_CUSTOM)
        val states = YtStateNames.builtIn.toSet() + custom
        val groupIds = HashSet<String>()
        val groups = a.groups.filter { g ->
            (idPattern.matches(g.id) && groupIds.add(g.id)).also { if (!it) issue("animations.groups", "bad or doubled group id dropped") }
        }.take(MAX_GROUPS).map { it.copy(name = it.name.take(60), staggerMs = it.staggerMs.coerceIn(0, 5000)) }
        val ids = HashSet<String>()
        val items = ArrayList<YtAnim>()
        for (raw in a.items) {
            if (items.size >= MAX_ANIMS) {
                issue("animations", "too many animations, the rest dropped")
                break
            }
            val path = "animations.${raw.id}"
            if (!idPattern.matches(raw.id) || !ids.add(raw.id)) {
                issue(path, "bad or doubled animation id dropped")
                continue
            }
            if (raw.node !in nodes[raw.component].orEmpty()) {
                issue(path, "animation of a missing node dropped")
                continue
            }
            items += anim(raw, path, states, groupIds, issue)
        }
        val rules = a.rules.filter { (it.state in states && it.state != YtStateNames.IDLE).also { ok -> if (!ok) issue("animations.rules", "rule of an unknown state dropped") } }
            .take(MAX_RULES)
        val easings = a.easings.filter { it.name.isNotBlank() }.distinctBy { it.name }.take(MAX_EASINGS).map { e ->
            e.copy(name = e.name.take(40), x1 = fin(e.x1, 0f).coerceIn(0f, 1f), y1 = fin(e.y1, 0f).coerceIn(-5f, 5f), x2 = fin(e.x2, 1f).coerceIn(0f, 1f), y2 = fin(e.y2, 1f).coerceIn(-5f, 5f))
        }
        return YtAnimations(items, groups, rules, custom, easings)
    }

    private fun fin(v: Float, fallback: Float) = if (v.isNaN() || v.isInfinite()) fallback else v

    private fun anim(a: YtAnim, path: String, states: Set<String>, groups: Set<String>, issue: (String, String) -> Unit): YtAnim {
        fun ms(name: String, v: Int, min: Int = 0): Int = v.coerceIn(min, MAX_MS).also { if (it != v) issue("$path.$name", "clamped") }
        val trigger = a.trigger.let { t ->
            val from = if (t.from == YtStateNames.ANY || t.from in states) t.from else YtStateNames.ANY.also { issue("$path.trigger", "unknown state") }
            val to = if (t.to == YtStateNames.ANY || t.to in states) t.to else YtStateNames.ANY.also { issue("$path.trigger", "unknown state") }
            t.copy(from = from, to = to, scrollRange = fin(t.scrollRange, 600f).coerceIn(1f, 20_000f))
        }
        val seen = HashSet<YtAnimProp>()
        val tracks = a.tracks.filter { t ->
            (t.keys.isNotEmpty() && seen.add(t.prop)).also { if (!it) issue("$path.tracks", "empty or doubled track dropped") }
        }.take(MAX_TRACKS).map { track(it) }
        return a.copy(
            name = a.name.take(60),
            trigger = trigger,
            durationMs = ms("durationMs", a.durationMs),
            delayMs = ms("delayMs", a.delayMs),
            spring = YtSpringSpec(fin(a.spring.stiffness, 400f).coerceIn(1f, 10_000f), fin(a.spring.damping, 0.6f).coerceIn(0.05f, 10f)),
            repeat = a.repeat.coerceIn(-1, MAX_REPEAT),
            tracks = tracks,
            group = a.group?.takeIf { it in groups },
            strength = fin(a.strength, 1f).coerceIn(YtAnimPresets.MIN_STRENGTH, YtAnimPresets.MAX_STRENGTH),
        )
    }

    private fun track(t: YtTrack): YtTrack {
        val keys = t.keys.take(MAX_KEYS).map { k ->
            k.copy(
                at = fin(k.at, 0f).coerceIn(0f, 1f),
                value = fin(k.value, t.prop.rest).coerceIn(t.prop.min, t.prop.max),
                color = if (t.prop.colour) k.color else null,
                curve = curve(k.curve),
            )
        }.sortedBy { it.at }
        return t.copy(keys = keys, velocity = fin(t.velocity, 0f).coerceIn(-MAX_VELOCITY, MAX_VELOCITY))
    }

    fun curve(c: YtCurve): YtCurve = c.copy(
        x1 = fin(c.x1, 0f).coerceIn(0f, 1f), y1 = fin(c.y1, 0f).coerceIn(-5f, 5f),
        x2 = fin(c.x2, 1f).coerceIn(0f, 1f), y2 = fin(c.y2, 1f).coerceIn(-5f, 5f),
        name = c.name?.take(40),
    )
}

/** YT: changes to a document's animations (pure), used by the editor. */
object YtAnimOps {
    /** An id not used by any animation or group yet ("a1", "a2", …). */
    fun freshId(a: YtAnimations, prefix: String = "a"): String {
        val taken = a.items.map { it.id }.toHashSet() + a.groups.map { it.id }
        var n = taken.size + 1
        while ("$prefix$n" in taken) n++
        return "$prefix$n"
    }

    /** A source of fresh ids for several new parts at once. */
    fun ids(a: YtAnimations, prefix: String = "a"): () -> String {
        val taken = (a.items.map { it.id } + a.groups.map { it.id }).toHashSet()
        var n = taken.size
        return {
            do n++ while ("$prefix$n" in taken)
            "$prefix$n".also { taken += it }
        }
    }

    fun add(doc: YtThemeDoc, built: YtAnimPresets.Built): YtThemeDoc = doc.copy(
        animations = doc.animations.copy(
            items = doc.animations.items + built.anims,
            groups = doc.animations.groups + listOfNotNull(built.group),
        ),
    )

    fun update(doc: YtThemeDoc, id: String, change: (YtAnim) -> YtAnim): YtThemeDoc =
        doc.copy(animations = doc.animations.copy(items = doc.animations.items.map { if (it.id == id) change(it) else it }))

    /** Removes [id]; a group left without members goes as well. */
    fun remove(doc: YtThemeDoc, id: String): YtThemeDoc {
        val items = doc.animations.items.filter { it.id != id }
        return doc.copy(animations = tidy(doc.animations.copy(items = items)))
    }

    /** Drops the animations of nodes that are gone (after a delete), and their empty groups. */
    fun withoutNodes(doc: YtThemeDoc): YtThemeDoc {
        val present = YtComponentKind.entries.associateWith { k -> YtDocOps.all(doc.components[k]).map { it.id }.toHashSet() }
        val items = doc.animations.items.filter { it.node in present[it.component].orEmpty() }
        if (items.size == doc.animations.items.size) return doc
        return doc.copy(animations = tidy(doc.animations.copy(items = items)))
    }

    private fun tidy(a: YtAnimations): YtAnimations {
        val used = a.items.mapNotNull { it.group }.toSet()
        return a.copy(groups = a.groups.filter { it.id in used })
    }

    fun of(doc: YtThemeDoc, kind: YtComponentKind, node: String): List<YtAnim> =
        doc.animations.items.filter { it.component == kind && it.node == node }
}
