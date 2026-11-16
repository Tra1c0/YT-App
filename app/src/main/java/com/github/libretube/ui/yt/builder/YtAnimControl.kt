package com.github.libretube.ui.yt.builder

/*
 * YT: what decides which animation plays when (pure): the schedule of groups (parallel,
 * sequence, stagger), the conflicts the editor shows, the component's state machine and the
 * controller that turns YT's events into starts on an engine.
 */

object YtAnimCombine {
    /** How long [a] takes from its trigger to its end (one play when it repeats forever). */
    fun lengthMs(a: YtAnim): Int {
        val plays = if (a.infinite) 1 else a.repeat.coerceAtLeast(0) + 1
        return (a.delayMs.coerceAtLeast(0) + YtAnimEval.playMs(a).toLong() * plays).coerceAtMost(600_000L).toInt()
    }

    /**
     * The start offsets (ms, on top of each animation's own delay) of [fired], all started by
     * the same event, in list order. Without a group or in a PARALLEL group: 0. SEQUENCE: after
     * the members before it have ended. STAGGER: the n-th member [YtAnimGroup.staggerMs] × n later.
     */
    fun schedule(fired: List<YtAnim>, groups: List<YtAnimGroup>): List<Pair<YtAnim, Int>> {
        if (fired.isEmpty()) return emptyList()
        val byId = groups.associateBy { it.id }
        val cursor = HashMap<String, Int>()
        return fired.map { a ->
            val g = a.group?.let(byId::get)
            when (g?.mode) {
                null, YtGroupMode.PARALLEL -> a to 0
                YtGroupMode.SEQUENCE -> {
                    val at = cursor[g.id] ?: 0
                    cursor[g.id] = at + lengthMs(a)
                    a to at
                }
                YtGroupMode.STAGGER -> {
                    val i = cursor[g.id] ?: 0
                    cursor[g.id] = i + 1
                    a to i * g.staggerMs.coerceIn(0, 5000)
                }
            }
        }
    }

    /** Several animations change [prop] of [node]: the last started wins, from the current value. */
    data class Conflict(val component: YtComponentKind, val node: String, val prop: YtAnimProp, val anims: List<String>, val sameTrigger: Boolean)

    fun conflicts(all: YtAnimations): List<Conflict> {
        val map = LinkedHashMap<Triple<YtComponentKind, String, YtAnimProp>, MutableList<YtAnim>>()
        for (a in all.items) {
            if (!a.enabled) continue
            for (t in a.playing()) map.getOrPut(Triple(a.component, a.node, t.prop)) { mutableListOf() }.let { if (a !in it) it += a }
        }
        return map.filter { it.value.size > 1 }.map { (k, list) ->
            Conflict(k.first, k.second, k.third, list.map { it.id }, list.map { it.trigger }.distinct().size < list.size)
        }
    }
}

/** The animations of one component, sorted for quick lookups at runtime. */
class YtAnimIndex(val anims: YtAnimations, val kind: YtComponentKind) {
    val items: List<YtAnim> = anims.of(kind).filter { it.playing().isNotEmpty() }
    private val byTrigger: Array<List<YtAnim>> = Array(YtTriggerKind.entries.size) { i -> items.filter { it.trigger.kind.ordinal == i } }
    val stateAnims: List<YtAnim> = items.filter { it.trigger.kind == YtTriggerKind.STATE }
    val rules: List<YtStateRule> = anims.rules.filter { it.component == kind }
    val targets: Set<String> = items.mapTo(HashSet()) { it.node }

    val empty: Boolean get() = items.isEmpty() && rules.isEmpty()

    fun on(t: YtTriggerKind): List<YtAnim> = byTrigger[t.ordinal]

    private fun uses(vararg kinds: YtTriggerKind) = kinds.any { k -> on(k).isNotEmpty() || rules.any { it.on == k } }

    private fun mentions(state: String) = stateAnims.any { it.trigger.from == state || it.trigger.to == state }

    val touch: Boolean = uses(YtTriggerKind.TAP, YtTriggerKind.DOUBLE_TAP, YtTriggerKind.LONG_PRESS, YtTriggerKind.TOUCH_DOWN, YtTriggerKind.TOUCH_UP) ||
        mentions(YtStateNames.PRESSED) || stateAnims.any { it.trigger.from == YtStateNames.ANY && it.trigger.to == YtStateNames.ANY }
    val scroll: Boolean = uses(YtTriggerKind.SCROLL, YtTriggerKind.SCROLL_UP, YtTriggerKind.SCROLL_DOWN)
    val player: Boolean = uses(YtTriggerKind.PLAYING, YtTriggerKind.PAUSED) || mentions(YtStateNames.ACTIVE)
    val loading: Boolean = uses(YtTriggerKind.LOADING_START, YtTriggerKind.LOADING_END) || mentions(YtStateNames.LOADING)

    /** The press is the animator's: the theme's plain press feel steps aside. */
    val ownsPress: Boolean = uses(YtTriggerKind.TOUCH_DOWN, YtTriggerKind.TOUCH_UP) || mentions(YtStateNames.PRESSED)

    /** A double tap starts something: a tap then waits for the double-tap window (YtTapArbiter). */
    val doubleTap: Boolean = uses(YtTriggerKind.DOUBLE_TAP)

    private val stateProps = HashMap<Pair<String, String>, Set<YtAnimProp>>()

    /** What the animator animates on [node] when [state] changes: the state patch leaves these alone (YtStatePatchRule). */
    fun statePropsOf(node: String, state: String): Set<YtAnimProp> =
        stateProps.getOrPut(node to state) { YtStatePatchRule.animatedIn(items, rules, node, state) }

    /** The props animated on [node] (the renderer adds only the drawing these need). */
    fun props(node: String): Set<YtAnimProp> = items.filter { it.node == node }.flatMapTo(HashSet()) { a -> a.playing().map { it.prop } }

    /**
     * The animations of the change [from] → [to]: the best matching rule wins (exact both,
     * then any → [to], then [from] → any, then any → any); all animations of that rule play.
     */
    fun transitions(from: String, to: String): List<YtAnim> {
        var best = -1
        val out = ArrayList<YtAnim>()
        for (a in stateAnims) {
            val f = a.trigger.from
            val t = a.trigger.to
            val score = when {
                f == from && t == to -> 3
                f == YtStateNames.ANY && t == to -> 2
                f == from && t == YtStateNames.ANY -> 1
                f == YtStateNames.ANY && t == YtStateNames.ANY -> 0
                else -> -1
            }
            if (score < 0 || score < best) continue
            if (score > best) {
                best = score
                out.clear()
            }
            out += a
        }
        return out
    }
}

/**
 * The states of a component instance. Several may be on at once (pressed while selected); the
 * shown one ([primary]) is the first that is on in [YtStateNames.priority], then the custom ones
 * in their order, else idle. A change of the shown state is a transition.
 */
class YtStateMachine(private val custom: List<String> = emptyList()) {
    private val on = LinkedHashSet<String>()

    var primary: String = YtStateNames.IDLE
        private set

    fun isOn(state: String): Boolean = state in on

    val states: Set<String> get() = on

    /** Sets [state]; returns the transition (from, to) when the shown state changed. */
    fun set(state: String, value: Boolean): Pair<String, String>? {
        if (state == YtStateNames.IDLE || state == YtStateNames.ANY) return null
        val changed = if (value) on.add(state) else on.remove(state)
        if (!changed) return null
        val before = primary
        primary = YtStateNames.priority.firstOrNull { it in on } ?: custom.firstOrNull { it in on } ?: YtStateNames.IDLE
        return if (primary != before) before to primary else null
    }

    fun apply(action: YtStateAction, state: String): Pair<String, String>? = set(
        state,
        when (action) {
            YtStateAction.SET -> true
            YtStateAction.CLEAR -> false
            YtStateAction.TOGGLE -> state !in on
        },
    )

    /** The states as the renderer's bits (state patches of the nodes). */
    fun bits(): Int = YtRenderStates.bitsOf(on, custom)
}

/**
 * Turns YT's events and state changes of one component instance into starts on [engine].
 * Order within one input: the state change's transition first, then the event's own
 * animations, so the event's win where both move the same property.
 */
class YtAnimController(index: YtAnimIndex, val engine: YtAnimEngine) {
    var index: YtAnimIndex = index
        private set
    var machine = YtStateMachine(index.anims.customStates)
        private set

    /** The SCROLL animations already started as driven runs. */
    private val scrollStarted = HashSet<String>()

    fun update(next: YtAnimIndex) {
        if (next === index) return
        val states = machine.states.toList()
        index = next
        machine = YtStateMachine(next.anims.customStates)
        for (s in states) machine.set(s, true)
        scrollStarted.clear()
        engine.clear()
    }

    /** States the host knew before the first frame: set without playing anything. */
    fun initial(state: String, value: Boolean) {
        machine.set(state, value)
    }

    fun event(t: YtTriggerKind) {
        for (r in index.rules) if (r.on == t) machine.apply(r.action, r.state)?.let { (f, to) -> play(index.transitions(f, to)) }
        play(index.on(t))
    }

    /** A state the host drives (pressed, selected, loading, active). */
    fun state(state: String, value: Boolean) {
        machine.set(state, value)?.let { (f, to) -> play(index.transitions(f, to)) }
    }

    /** Told of every start (the runtime's measurements). */
    var onStart: ((YtAnim, Int) -> Unit)? = null

    fun play(list: List<YtAnim>) {
        if (list.isEmpty()) return
        for ((a, offset) in YtAnimCombine.schedule(list, index.anims.groups)) {
            engine.start(a, offset)
            onStart?.invoke(a, offset)
        }
    }

    /** The feed scrolled to [dp]: every SCROLL animation shows its point along its range. */
    fun scroll(dp: Float) {
        for (a in index.on(YtTriggerKind.SCROLL)) {
            if (engine.snap) continue
            if (scrollStarted.add(a.id)) engine.start(a, 0, driven = true)
            val f = (dp / a.trigger.scrollRange.coerceAtLeast(1f)).coerceIn(0f, 1f)
            engine.drive(a.id, f.toDouble() * (a.delayMs + YtAnimEval.playMs(a)))
        }
    }
}
