package com.github.libretube.ui.yt.archive

/**
 * YT: the pure rules of the archive of vanished videos: which feed videos become candidates, the
 * capacity slider, what tier each entry gets within the budget, which entries fall back first,
 * which streams and stills are saved, and which entries get a light "still there?" check. No
 * Android, so all of it is unit-tested.
 *
 * Budget, in short:
 * - The slider sets the soft budget ([CAPACITY_STEPS_MB], 5 MB … 15 GB). Below
 *   [TEXT_ONLY_BELOW_MB] only the text tier is kept (≈ 50 KB per video).
 * - Every candidate gets TEXT first (best ranked first). A TEXT entry is only dropped for room
 *   once it is older than [TEXT_MIN_AGE_MS]; younger ones stay even past the budget (they are
 *   tiny, and "text for all" is the promise of tier 1).
 * - High-risk videos get at least AUDIO even past the soft budget, up to the hard cap
 *   ([hardCap]: budget + a quarter, at least 50 MB, at most 1 GB extra): they are what the
 *   archive is for, also on the smallest setting.
 * - The rest is filled in rank order (high risk, then risk, then newest): AUDIO for as many as
 *   fit, then VIDEO (144p) for as many as fit, if the user allows tier 3.
 * - Protected entries (vanished or saved by hand) keep their tier and count first; they are
 *   never downgraded or dropped automatically.
 * - When room is needed, entries fall back one tier at a time in [steps] order: every
 *   VIDEO→AUDIO first, then AUDIO→TEXT, then TEXT→drop, each lowest ranked (oldest, lowest
 *   risk) first.
 */
object YtArchiveRules {
    const val MB = 1024L * 1024L
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** The slider's steps in MB: few MB (text only) to 15 GB, denser at the small end. */
    val CAPACITY_STEPS_MB = intArrayOf(5, 10, 25, 50, 100, 250, 500, 1024, 2048, 3072, 5120, 7680, 10240, 15360)
    const val DEFAULT_CAPACITY_MB = 250

    /** Below this budget only text is kept (except high-risk audio within the hard cap). */
    const val TEXT_ONLY_BELOW_MB = 50

    /** Planning estimates: text part, audio per second (~48 kbit/s opus), stills, 144p video per second (~80 kbit/s). */
    const val TEXT_EST_BYTES = 50L * 1024
    const val AUDIO_BYTES_PER_SEC = 6_000L
    const val STORYBOARD_EST_BYTES = 400L * 1024
    const val VIDEO_BYTES_PER_SEC = 10_000L

    /** A video of unknown length is planned as 10 minutes. */
    const val DEFAULT_DURATION_SEC = 600L

    /** Audio and video are only kept for videos up to this length (a 2 h stream is ~45 MB of audio). */
    const val MAX_MEDIA_DURATION_SEC = 2L * 60 * 60

    /** A single stream file larger than this is not saved (a wrong length or a very long video). */
    const val MAX_STREAM_BYTES = 200L * MB

    /** TEXT entries are dropped for room only after this age. */
    const val TEXT_MIN_AGE_MS = 30L * DAY_MS

    /** Feed videos of the last two weeks are candidates. */
    const val CANDIDATE_WINDOW_MS = 14L * DAY_MS

    /**
     * The archive window of the risk estimator: a video still there this long after its upload
     * counts as "did not vanish" (one observe(false)).
     */
    const val ARCHIVE_WINDOW_MS = 30L * DAY_MS

    /** An entry in the window is checked again at most this often. */
    const val CHECK_EVERY_MS = 2L * DAY_MS

    /** Stills: frames at most this wide, and at most this many sprite sheets. */
    const val STORYBOARD_MAX_WIDTH = 160
    const val STORYBOARD_MAX_SHEETS = 12

    // ---- capacity slider ----

    fun stepIndexOf(mb: Int): Int {
        val exact = CAPACITY_STEPS_MB.indexOf(mb)
        if (exact >= 0) return exact
        // a value between steps snaps to the nearest one below (or the first)
        return CAPACITY_STEPS_MB.indexOfLast { it <= mb }.coerceAtLeast(0)
    }

    fun capacityMb(index: Int): Int = CAPACITY_STEPS_MB[index.coerceIn(0, CAPACITY_STEPS_MB.lastIndex)]

    fun budgetBytes(mb: Int): Long = mb.coerceAtLeast(1) * MB

    fun textOnly(budget: Long): Boolean = budget < TEXT_ONLY_BELOW_MB * MB

    /** The most the archive may use: the budget plus room for high-risk audio. */
    fun hardCap(budget: Long): Long = budget + (budget / 4).coerceIn(50 * MB, 1024 * MB)

    /** About how many 10-minute videos fit as text only, with audio, with 144p video. */
    data class Fits(val text: Long, val audio: Long, val video: Long, val textOnly: Boolean)

    fun fits(budget: Long): Fits {
        val audio = AUDIO_BYTES_PER_SEC * DEFAULT_DURATION_SEC + STORYBOARD_EST_BYTES + TEXT_EST_BYTES
        val video = audio + VIDEO_BYTES_PER_SEC * DEFAULT_DURATION_SEC
        val textOnly = textOnly(budget)
        return Fits(
            text = budget / TEXT_EST_BYTES,
            audio = if (textOnly) 0 else budget / audio,
            video = if (textOnly) 0 else budget / video,
            textOnly = textOnly,
        )
    }

    // ---- candidates ----

    /** A video of the feed as the filter sees it. */
    data class FeedVideo(
        val candidate: YtArchiveCandidate,
        val isShort: Boolean,
        val watched: Boolean,
        val hidden: Boolean = false,
    )

    /**
     * The feed videos the archive considers: uploaded within [CANDIDATE_WINDOW_MS], not Shorts,
     * not watched, not live (no length), newest first.
     */
    fun candidates(feed: List<FeedVideo>, now: Long): List<YtArchiveCandidate> = feed.asSequence()
        .filter { !it.isShort && !it.watched && !it.hidden }
        .filter { (it.candidate.durationSec ?: 0) > 0 }
        .filter { video -> video.candidate.uploaded?.let { now - it in 0..CANDIDATE_WINDOW_MS } ?: false }
        .map { it.candidate }
        .distinctBy { it.videoId }
        .sortedByDescending { it.uploaded ?: 0 }
        .toList()

    /** The YouTube video id shape; the id also names the entry's folder, so nothing else passes. */
    fun validId(id: String?): Boolean = id != null && id.length in 10..12 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    // ---- budget plan ----

    /** One entry (stored or not yet) as the planner sees it. Stored sizes are null when the part is not on disk. */
    data class PlanItem(
        val videoId: String,
        val current: YtArchiveTier?,
        val textBytes: Long? = null,
        val audioBytes: Long? = null,
        val videoBytes: Long? = null,
        val durationSec: Long? = null,
        val risk: Double = 0.0,
        val high: Boolean = false,
        val uploaded: Long? = null,
        val savedAt: Long? = null,
        val protected: Boolean = false,
    ) {
        val mediaAllowed: Boolean get() = (durationSec ?: DEFAULT_DURATION_SEC) in 1..MAX_MEDIA_DURATION_SEC
    }

    private fun duration(item: PlanItem) = (item.durationSec ?: DEFAULT_DURATION_SEC).coerceAtLeast(1)

    /** The bytes of one part: what is stored, else the estimate. */
    fun partBytes(item: PlanItem, tier: YtArchiveTier): Long = when (tier) {
        YtArchiveTier.TEXT -> item.textBytes ?: TEXT_EST_BYTES
        YtArchiveTier.AUDIO -> item.audioBytes ?: (AUDIO_BYTES_PER_SEC * duration(item) + STORYBOARD_EST_BYTES)
        YtArchiveTier.VIDEO -> item.videoBytes ?: (VIDEO_BYTES_PER_SEC * duration(item))
    }

    /** The bytes of [item] at [tier] (all lower parts included); 0 for none. */
    fun sizeAt(item: PlanItem, tier: YtArchiveTier?): Long {
        if (tier == null) return 0
        return YtArchiveTier.entries.filter { it.level <= tier.level }.sumOf { partBytes(item, it) }
    }

    /** Best first: high risk, then higher risk, then newer, then the id (stable). */
    val rank: Comparator<PlanItem> = compareByDescending<PlanItem> { it.high }
        .thenByDescending { it.risk }
        .thenByDescending { it.uploaded ?: it.savedAt ?: 0 }
        .thenBy { it.videoId }

    private fun age(item: PlanItem, now: Long): Long = now - (item.savedAt ?: item.uploaded ?: now)

    /**
     * The tier every item should have under [budget] (null: not kept). [top] is the highest tier
     * the user allows (AUDIO or VIDEO). See the class comment for the order.
     */
    fun plan(items: List<PlanItem>, budget: Long, top: YtArchiveTier, now: Long): Map<String, YtArchiveTier?> {
        val hard = hardCap(budget)
        val targets = LinkedHashMap<String, YtArchiveTier?>()
        var used = 0L
        for (item in items.filter { it.protected }) {
            val tier = item.current ?: YtArchiveTier.TEXT
            targets[item.videoId] = tier
            used += sizeAt(item, tier)
        }
        val others = items.filter { !it.protected }.sortedWith(rank)
        for (item in others) {
            val cost = sizeAt(item, YtArchiveTier.TEXT)
            if (used + cost <= budget || age(item, now) < TEXT_MIN_AGE_MS) {
                targets[item.videoId] = YtArchiveTier.TEXT
                used += cost
            } else {
                targets[item.videoId] = null
            }
        }
        fun raise(item: PlanItem, to: YtArchiveTier, limit: Long) {
            val from = targets[item.videoId] ?: return
            if (from.level != to.level - 1 || !item.mediaAllowed) return
            val delta = sizeAt(item, to) - sizeAt(item, from)
            if (used + delta <= limit) {
                targets[item.videoId] = to
                used += delta
            }
        }
        for (item in others.filter { it.high }) raise(item, YtArchiveTier.AUDIO, hard)
        if (!textOnly(budget)) {
            for (item in others) raise(item, YtArchiveTier.AUDIO, budget)
            if (top == YtArchiveTier.VIDEO) for (item in others) raise(item, YtArchiveTier.VIDEO, budget)
        }
        return targets
    }

    /** One fall back of one entry: [to] null drops it. */
    data class Step(val videoId: String, val from: YtArchiveTier, val to: YtArchiveTier?, val frees: Long)

    /**
     * The fall backs the plan allows, in the order they are taken when room is needed: all
     * VIDEO→AUDIO, then AUDIO→TEXT, then TEXT→drop, lowest ranked first; only down to the planned
     * tier, never for protected entries.
     */
    fun steps(items: List<PlanItem>, targets: Map<String, YtArchiveTier?>): List<Step> {
        val lowestFirst = items.filter { !it.protected && it.current != null }.sortedWith(rank.reversed())
        val out = ArrayList<Step>()
        for (level in YtArchiveTier.VIDEO.level downTo YtArchiveTier.TEXT.level) {
            for (item in lowestFirst) {
                val current = item.current ?: continue
                val target = targets[item.videoId]
                if (current.level < level || (target?.level ?: 0) >= level) continue
                val from = YtArchiveTier.entries.first { it.level == level }
                val to = YtArchiveTier.entries.firstOrNull { it.level == level - 1 }
                out += Step(item.videoId, from, to, partBytes(item, from))
            }
        }
        return out
    }

    /** The upgrades the plan wants, best ranked first: (item, target). */
    fun upgrades(items: List<PlanItem>, targets: Map<String, YtArchiveTier?>): List<Pair<PlanItem, YtArchiveTier>> =
        items.sortedWith(rank).mapNotNull { item ->
            val target = targets[item.videoId] ?: return@mapNotNull null
            if ((item.current?.level ?: 0) < target.level) item to target else null
        }

    /**
     * The first steps of [steps] that free at least [need] bytes, or null when all of them
     * together do not (then nothing is given up for a download that would not fit anyway).
     */
    fun makeRoom(steps: List<Step>, need: Long, excluding: String? = null): List<Step>? {
        if (need <= 0) return emptyList()
        var freed = 0L
        val taken = ArrayList<Step>()
        val done = HashMap<String, Int>()
        for (step in steps) {
            if (step.videoId == excluding) continue
            // an entry's lower step only after its higher one (the order guarantees it, the map checks it)
            val at = done[step.videoId]
            if (at != null && at != step.from.level + 1) continue
            taken += step
            done[step.videoId] = step.from.level
            freed += step.frees
            if (freed >= need) return taken
        }
        return null
    }

    /** An entry whose audio/video save failed is tried again after 1, 2, 4, 8 days, then no more. */
    const val MAX_MEDIA_FAILURES = 5

    fun retryDue(failures: Int, failedAt: Long?, now: Long): Boolean {
        if (failures <= 0 || failedAt == null) return true
        if (failures >= MAX_MEDIA_FAILURES) return false
        return now - failedAt >= DAY_MS shl (failures - 1)
    }

    // ---- light checks and labels ----

    /** An entry as the check planner sees it. */
    data class CheckItem(
        val videoId: String,
        val uploaded: Long?,
        val savedAt: Long,
        val checkedAt: Long?,
        val vanished: Boolean,
        val vanishLabelled: Boolean,
    )

    private fun windowStart(item: CheckItem) = item.uploaded ?: item.savedAt

    fun inWindow(uploaded: Long?, savedAt: Long, now: Long): Boolean = now - (uploaded ?: savedAt) < ARCHIVE_WINDOW_MS

    /**
     * Which entries get a light check now, at most [max]: first vanished ones whose vanish is not
     * confirmed yet (no label sent), then entries still in the window not checked for
     * [CHECK_EVERY_MS], the longest unchecked first.
     */
    fun checks(items: List<CheckItem>, now: Long, max: Int): List<String> {
        val confirm = items.filter { it.vanished && !it.vanishLabelled }
            .sortedBy { it.checkedAt ?: 0 }
        val due = items.filter { !it.vanished && now - windowStart(it) < ARCHIVE_WINDOW_MS }
            .filter { it.checkedAt == null || now - it.checkedAt >= CHECK_EVERY_MS }
            .sortedBy { it.checkedAt ?: 0 }
        return (confirm + due).map { it.videoId }.distinct().take(max.coerceAtLeast(0))
    }

    /** True when an entry left the window still there and its "did not vanish" label is due. */
    fun windowLabelDue(meta: YtArchiveMeta, now: Long): Boolean =
        !meta.vanished && !meta.windowLabelled && !inWindow(meta.uploaded, meta.savedAt, now)

    // ---- what is saved ----

    /** A stream as the picker sees it. */
    data class StreamChoice(
        val url: String?,
        val mimeType: String?,
        val codec: String?,
        val bitrate: Int?,
        val height: Int?,
        val contentLength: Long,
        val drc: Boolean = false,
        val trackType: String? = null,
    )

    private fun usable(stream: StreamChoice, sabr: Boolean): Boolean {
        val url = stream.url ?: return false
        // a plain file needs its length (ranged requests); SABR streams need the SABR address
        return if (url.startsWith("http")) stream.contentLength in 1..MAX_STREAM_BYTES else sabr
    }

    /**
     * The smallest audio stream: the original track (no dubbed or described one), no
     * loudness-compressed copy, opus before m4a at the same bitrate. Index into [streams] or null.
     */
    fun pickAudio(streams: List<StreamChoice>, sabr: Boolean): Int? {
        val ok = streams.indices.filter { usable(streams[it], sabr) }
        if (ok.isEmpty()) return null
        val original = ok.filter { streams[it].trackType == null || streams[it].trackType.equals("ORIGINAL", true) }
            .ifEmpty { ok }
        val plain = original.filter { !streams[it].drc }.ifEmpty { original }
        return plain.minWithOrNull(
            compareBy<Int> { streams[it].bitrate ?: Int.MAX_VALUE }
                .thenBy { if (isOpus(streams[it])) 0 else 1 }
        )
    }

    private fun isOpus(stream: StreamChoice) =
        stream.codec?.contains("opus", true) == true || stream.mimeType?.contains("webm", true) == true

    /** The video stream of tier 3: the lowest height from 144p up, AV1 before VP9 before H.264, then the lowest bitrate. */
    fun pickVideo(streams: List<StreamChoice>, sabr: Boolean): Int? {
        val ok = streams.indices.filter { usable(streams[it], sabr) && (streams[it].height ?: 0) > 0 }
        if (ok.isEmpty()) return null
        val atLeast144 = ok.filter { (streams[it].height ?: 0) >= 144 }.ifEmpty { ok }
        return atLeast144.minWithOrNull(
            compareBy<Int> { streams[it].height ?: Int.MAX_VALUE }
                .thenBy { codecRank(streams[it].codec) }
                .thenBy { streams[it].bitrate ?: Int.MAX_VALUE }
        )
    }

    private fun codecRank(codec: String?): Int {
        val c = codec?.lowercase().orEmpty()
        return when {
            c.startsWith("av01") || c.startsWith("av1") -> 0
            c.startsWith("vp9") || c.startsWith("vp09") -> 1
            c.startsWith("avc") -> 2
            else -> 3
        }
    }

    /** One storyboard level (a set of sprite sheets) as the picker sees it. */
    data class Board(val frameWidth: Int, val frameHeight: Int, val sheets: Int)

    /** The level of stills to keep: the widest up to [STORYBOARD_MAX_WIDTH] with at most [STORYBOARD_MAX_SHEETS] sheets, else the smallest. */
    fun pickBoard(boards: List<Board>): Int? {
        if (boards.isEmpty()) return null
        val fitting = boards.indices.filter { boards[it].frameWidth in 1..STORYBOARD_MAX_WIDTH && boards[it].sheets in 1..STORYBOARD_MAX_SHEETS }
        if (fitting.isNotEmpty()) return fitting.maxBy { boards[it].frameWidth }
        val small = boards.indices.filter { boards[it].sheets > 0 }.minByOrNull { boards[it].frameWidth * boards[it].sheets }
        return small?.takeIf { boards[it].sheets <= STORYBOARD_MAX_SHEETS * 3 }
    }

    /** Where the still at [positionMs] sits: sheet index, column, row; null past the end. */
    data class Frame(val sheet: Int, val x: Int, val y: Int)

    fun frameAt(positionMs: Long, durationPerFrameMs: Long, perX: Int, perY: Int, total: Int, sheets: Int): Frame? {
        if (durationPerFrameMs <= 0 || perX <= 0 || perY <= 0 || sheets <= 0) return null
        val limit = if (total > 0) total else perX * perY * sheets
        val index = (positionMs.coerceAtLeast(0) / durationPerFrameMs).toInt().coerceAtMost(limit - 1)
        val perSheet = perX * perY
        val sheet = index / perSheet
        if (sheet >= sheets) return null
        val within = index % perSheet
        return Frame(sheet, within % perX, within / perX)
    }
}
