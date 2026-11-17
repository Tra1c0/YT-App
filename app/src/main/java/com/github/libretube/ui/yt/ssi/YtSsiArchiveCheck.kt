package com.github.libretube.ui.yt.ssi

import com.github.libretube.ui.yt.archive.YtArchive
import com.github.libretube.ui.yt.archive.YtArchiveMeta

/** YT: when SSI looks at the archive for new transcripts (rule) and the check itself. */
object YtSsiArchiveCheck {
    const val MIN_GAP_MS = 10L * 60 * 1000

    /** What a check saw of the archive: if it differs, the archive changed. */
    data class Signature(val count: Int = 0, val textBytes: Long = 0, val newest: Long = 0)

    fun signature(metas: List<YtArchiveMeta>) = Signature(
        metas.size,
        metas.sumOf { it.textBytes },
        metas.maxOfOrNull { maxOf(it.savedAt, it.tierAt, it.vanishedAt ?: 0L) } ?: 0L,
    )

    /** Due when never done, when the archive changed since, or when [MIN_GAP_MS] passed. */
    fun due(now: Long, lastAt: Long?, lastSignature: Signature?, current: Signature): Boolean =
        lastAt == null || lastSignature != current || now - lastAt >= MIN_GAP_MS

    @Volatile
    private var lastAt: Long? = null

    @Volatile
    private var lastSignature: Signature? = null

    /** Runs the check if due (or [force]); returns the passages added (0 when not due). Local only. */
    suspend fun check(budgetMs: Long, force: Boolean = false): Int {
        val now = System.currentTimeMillis()
        val current = signature(YtArchive.store.all())
        if (!force && !due(now, lastAt, lastSignature, current)) return 0
        val added = YtSsi.indexer.indexArchive(now + budgetMs)
        lastAt = System.currentTimeMillis()
        lastSignature = current
        return added
    }
}
