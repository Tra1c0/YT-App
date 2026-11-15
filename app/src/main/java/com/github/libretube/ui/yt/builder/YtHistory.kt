package com.github.libretube.ui.yt.builder

/**
 * YT: undo and redo of the theme editor (pure). Every change is a [YtCommand]: a label and a
 * function from one document to the next. The history keeps the document before and after each
 * step (immutable documents share their unchanged parts, so this is cheap). Steps with the same
 * coalescing key within [coalesceMs] merge into one, so a slider dragged through 40 values or a
 * colour typed letter by letter is one undo step; a drag commits once on release anyway.
 */
data class YtCommand(
    val label: String,
    /** Steps with the same key that follow each other quickly merge; null never merges. */
    val coalesce: String? = null,
    val change: (YtThemeDoc) -> YtThemeDoc,
)

class YtHistory(initial: YtThemeDoc, private val limit: Int = 200, private val coalesceMs: Long = 1200L) {
    private class Step(val label: String, val key: String?, val before: YtThemeDoc, var after: YtThemeDoc, var at: Long)

    private val done = ArrayDeque<Step>()
    private val undone = ArrayDeque<Step>()

    var current: YtThemeDoc = initial
        private set

    val canUndo: Boolean get() = done.isNotEmpty()
    val canRedo: Boolean get() = undone.isNotEmpty()
    val undoLabel: String? get() = done.lastOrNull()?.label
    val redoLabel: String? get() = undone.lastOrNull()?.label
    val size: Int get() = done.size

    /** Applies [command]; returns the new document (the same one when it changed nothing). */
    fun run(command: YtCommand, now: Long = System.currentTimeMillis()): YtThemeDoc {
        val next = command.change(current)
        if (next == current) return current
        val last = done.lastOrNull()
        if (last != null && command.coalesce != null && last.key == command.coalesce && now - last.at <= coalesceMs && undone.isEmpty()) {
            last.after = next
            last.at = now
            // merged back to where it started: the step did nothing after all
            if (last.after == last.before) done.removeLast()
        } else {
            done.addLast(Step(command.label, command.coalesce, current, next, now))
            while (done.size > limit) done.removeFirst()
        }
        undone.clear()
        current = next
        return next
    }

    /** Ends merging: the next step starts a new undo step even with the same key. */
    fun seal() {
        done.lastOrNull()?.at = Long.MIN_VALUE / 2
    }

    fun undo(): YtThemeDoc? {
        val step = done.removeLastOrNull() ?: return null
        undone.addLast(step)
        current = step.before
        return current
    }

    fun redo(): YtThemeDoc? {
        val step = undone.removeLastOrNull() ?: return null
        done.addLast(step)
        step.at = Long.MIN_VALUE / 2
        current = step.after
        return current
    }

    /** Starts over at [doc] (another theme was opened, or a version restored). */
    fun reset(doc: YtThemeDoc) {
        done.clear()
        undone.clear()
        current = doc
    }
}
