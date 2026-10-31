package com.github.libretube.util

/**
 * YT: the list rules behind PlayingQueue.ytInsertNext, ytAppend, upcomingCount and
 * ytKeepQueue, kept apart from the queue (which needs Android) so they can be tested on the
 * JVM. Items are matched by [key] (the video id); the current item is the first one with
 * [currentKey], as in PlayingQueue.currentIndex.
 */
object YtQueueEdit {
    /** How many items follow the current one at [currentIndex]; never negative. */
    fun upcoming(size: Int, currentIndex: Int): Int = (size - currentIndex - 1).coerceAtLeast(0)

    /** The index of the current item, 0 if it is not in [list] (as PlayingQueue.currentIndex). */
    fun <T> currentIndex(list: List<T>, currentKey: String?, key: (T) -> String?): Int =
        list.indexOfFirst { key(it) == currentKey }.takeIf { it >= 0 } ?: 0

    /**
     * Puts [item] right after the current item, or first into an empty list. Another copy of it
     * goes first, so it is in the list once.
     * @return its index, or -1 if it has no key or is the current item
     */
    fun <T> insertNext(list: MutableList<T>, item: T, currentKey: String?, key: (T) -> String?): Int {
        val id = key(item) ?: return -1
        if (id == currentKey) return -1
        list.removeAll { key(it) == id }
        val at = if (list.isEmpty()) 0 else (currentIndex(list, currentKey, key) + 1).coerceAtMost(list.size)
        list.add(at, item)
        return at
    }

    /**
     * Puts [item] at the end. Another copy of it goes first, so it is in the list once.
     * @return its index, or -1 if it has no key or is the current item
     */
    fun <T> append(list: MutableList<T>, item: T, currentKey: String?, key: (T) -> String?): Int {
        val id = key(item) ?: return -1
        if (id == currentKey) return -1
        list.removeAll { key(it) == id }
        list.add(item)
        return list.size - 1
    }

    /**
     * Keeps each video once: of several items with the same key the last one stays (the one
     * added last, as PlayingQueue.add moves a video it adds again to the end). Items without a
     * key stay as they are.
     * @return true if an item went
     */
    fun <T> keepLastOfEach(list: MutableList<T>, key: (T) -> String?): Boolean {
        val seen = HashSet<String>()
        val keep = BooleanArray(list.size)
        for (i in list.indices.reversed()) {
            val id = key(list[i])
            keep[i] = id == null || seen.add(id)
        }
        if (keep.all { it }) return false
        val kept = list.filterIndexed { i, _ -> keep[i] }
        list.clear()
        list.addAll(kept)
        return true
    }
}
