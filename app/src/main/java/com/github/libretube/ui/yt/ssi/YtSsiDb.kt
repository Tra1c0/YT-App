package com.github.libretube.ui.yt.ssi

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import android.util.LruCache
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * YT: SSI's moment index, an SQLite file of its own (`databases/yt_ssi.db`), apart from
 * LibreTube's Room database so it can be cleared or rebuilt alone.
 *
 * - `docs`: one row per video (meta, availability, how exact its times are).
 * - `segs`: the passages with times, kind, timing and the word/line marks; ids never repeat
 *   (AUTOINCREMENT), so a stored vector can never point at another passage.
 * - `segs_fts`, `docs_fts`: FTS4 tables over the folded text (docid = the row's id). FTS4,
 *   because Android's own SQLite is not guaranteed to have FTS5 on API 26; FTS4 has no rank
 *   function, so BM25 is computed from matchinfo('pcnalx') ([YtSsiFusion.bm25]). The tokenizer
 *   is unicode61 where the build has it, else "simple" (the text is folded before, so both
 *   split it the same way).
 * - `vecs`: one int8 vector per passage and model.
 * - `ai`: the AI plans per folded query, the AI re-rank's grades (key "rr|…") and, SSI-3, the
 *   raw relation answers per claim and passage (key "st|…", verified again on every read).
 * - `feedback` (SSI-2): the user's verdicts on hits; the learning state itself is a preference.
 */
class YtSsiDb(context: Context) : SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE docs (id INTEGER PRIMARY KEY AUTOINCREMENT, video_id TEXT NOT NULL UNIQUE, title TEXT NOT NULL,
               channel_id TEXT, channel_name TEXT, description TEXT, uploaded INTEGER, duration_sec INTEGER, language TEXT,
               auto INTEGER, timing TEXT NOT NULL, source TEXT NOT NULL, vanished INTEGER NOT NULL DEFAULT 0, chapters TEXT,
               indexed_at INTEGER NOT NULL, version INTEGER NOT NULL, seg TEXT NOT NULL DEFAULT 'RULES')""",
        )
        db.execSQL(
            """CREATE TABLE segs (id INTEGER PRIMARY KEY AUTOINCREMENT, video_id TEXT NOT NULL, ord INTEGER NOT NULL,
               start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, text TEXT NOT NULL, kind TEXT NOT NULL, timing TEXT NOT NULL, marks TEXT)""",
        )
        db.execSQL("CREATE INDEX segs_video ON segs (video_id, ord)")
        db.execSQL("CREATE TABLE vecs (seg_id INTEGER PRIMARY KEY, model TEXT NOT NULL, scale REAL NOT NULL, data BLOB NOT NULL)")
        db.execSQL("CREATE INDEX vecs_model ON vecs (model, seg_id)")
        db.execSQL("CREATE TABLE ai (query TEXT PRIMARY KEY, answer TEXT NOT NULL, at INTEGER NOT NULL)")
        createFeedback(db)
        val tokenizer = try {
            db.execSQL("CREATE VIRTUAL TABLE probe_fts USING fts4(text, tokenize=unicode61)")
            db.execSQL("DROP TABLE probe_fts")
            "unicode61"
        } catch (e: SQLiteException) {
            Log.i(TAG, "no unicode61 tokenizer: $e")
            "simple"
        }
        db.execSQL("CREATE VIRTUAL TABLE segs_fts USING fts4(text, tokenize=$tokenizer)")
        db.execSQL("CREATE VIRTUAL TABLE docs_fts USING fts4(title, channel, description, chapters, tokenize=$tokenizer)")
    }

    /** The user's verdicts on hits (SSI-2); kept when the index is cleared. */
    private fun createFeedback(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS feedback (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, query TEXT NOT NULL,
               kind TEXT NOT NULL, video_id TEXT NOT NULL, seg_id INTEGER, verdict TEXT NOT NULL, lex_rank INTEGER, sem_rank INTEGER,
               title_rank INTEGER, ai_grade INTEGER, semantic INTEGER NOT NULL, timing TEXT NOT NULL)""",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == 1) {
            // SSI-2: the passages stay (re-cut one video at a time in the background, see YtSsiIndexer.migrate)
            db.execSQL("ALTER TABLE docs ADD COLUMN seg TEXT NOT NULL DEFAULT 'RULES'")
            createFeedback(db)
            return
        }
        // the index can always be built again from its sources
        for (table in listOf("docs", "segs", "vecs", "ai", "segs_fts", "docs_fts", "feedback")) db.execSQL("DROP TABLE IF EXISTS $table")
        onCreate(db)
    }

    // ---- caches ----

    private val lock = Any()
    private var docCache: Map<String, YtSsiDoc>? = null
    private val segCache = LruCache<Long, YtSsiSegment>(2_000)

    /** Counts every change of the index, so a loaded vector index or a result knows it is old. */
    @Volatile
    var changes: Long = 0
        private set

    private fun changed() {
        synchronized(lock) { docCache = null }
        segCache.evictAll()
        changes++
    }

    // ---- writing ----

    /**
     * Stores [doc] with its [segments] in place of whatever the index held for the video (its
     * old passages and their vectors go). Returns the new passages with their ids.
     */
    fun putVideo(doc: YtSsiDoc, segments: List<YtSsiSegment>): List<YtSsiSegment> {
        val db = writableDatabase
        val out = ArrayList<YtSsiSegment>(segments.size)
        db.beginTransaction()
        try {
            deleteRows(db, doc.videoId)
            val docId = db.insertOrThrow("docs", null, ContentValues().apply {
                put("video_id", doc.videoId)
                put("title", doc.title)
                put("channel_id", doc.channelId)
                put("channel_name", doc.channelName)
                put("description", doc.description)
                put("uploaded", doc.uploaded)
                put("duration_sec", doc.durationSec)
                put("language", doc.language)
                put("auto", doc.autoCaptions?.let { if (it) 1 else 0 })
                put("timing", doc.timing.name)
                put("source", doc.source.name)
                put("vanished", if (doc.vanished) 1 else 0)
                put("chapters", encodeChapters(doc.chapters))
                put("indexed_at", doc.indexedAt)
                put("version", doc.version)
                put("seg", doc.segmentation.name)
            })
            db.insertOrThrow("docs_fts", null, ContentValues().apply {
                put("docid", docId)
                put("title", YtSsiText.fold(doc.title))
                put("channel", YtSsiText.fold(doc.channelName.orEmpty()))
                put("description", YtSsiText.fold(doc.description.orEmpty()))
                put("chapters", YtSsiText.fold(doc.chapters.joinToString(" ") { it.title }))
            })
            for (segment in segments) {
                val id = db.insertOrThrow("segs", null, ContentValues().apply {
                    put("video_id", doc.videoId)
                    put("ord", segment.ord)
                    put("start_ms", segment.startMs)
                    put("end_ms", segment.endMs)
                    put("text", segment.text)
                    put("kind", segment.kind.name)
                    put("timing", segment.timing.name)
                    put("marks", YtSsiSegmenter.encodeMarks(segment.marks, segment.startMs))
                })
                db.insertOrThrow("segs_fts", null, ContentValues().apply {
                    put("docid", id)
                    put("text", YtSsiText.fold(segment.text))
                })
                out += segment.copy(id = id)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changed()
        return out
    }

    private fun deleteRows(db: SQLiteDatabase, videoId: String) {
        db.execSQL("DELETE FROM vecs WHERE seg_id IN (SELECT id FROM segs WHERE video_id = ?)", arrayOf(videoId))
        db.execSQL("DELETE FROM segs_fts WHERE docid IN (SELECT id FROM segs WHERE video_id = ?)", arrayOf(videoId))
        db.execSQL("DELETE FROM segs WHERE video_id = ?", arrayOf(videoId))
        db.execSQL("DELETE FROM docs_fts WHERE docid IN (SELECT id FROM docs WHERE video_id = ?)", arrayOf(videoId))
        db.execSQL("DELETE FROM docs WHERE video_id = ?", arrayOf(videoId))
    }

    fun deleteVideo(videoId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            deleteRows(db, videoId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changed()
    }

    fun setVanished(videoId: String, vanished: Boolean) {
        writableDatabase.execSQL("UPDATE docs SET vanished = ? WHERE video_id = ?", arrayOf<Any>(if (vanished) 1 else 0, videoId))
        changed()
    }

    /** Marks [videoId]'s passages as cut by [segmentation] under [version] (they stay as they are). */
    fun setSegmentation(videoId: String, segmentation: YtSsiSegmentation, version: Int) {
        writableDatabase.execSQL("UPDATE docs SET seg = ?, version = ? WHERE video_id = ?", arrayOf<Any>(segmentation.name, version, videoId))
        changed()
    }

    /** Raises every video of analysis [from] ..< [to] to [to] without touching its passages; returns how many. */
    fun raiseVersion(from: Int, to: Int): Int {
        val count = readableDatabase.rawQuery("SELECT COUNT(*) FROM docs WHERE version >= ? AND version < ?", arrayOf(from.toString(), to.toString())).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
        if (count > 0) {
            writableDatabase.execSQL("UPDATE docs SET version = ? WHERE version >= ? AND version < ?", arrayOf<Any>(to, from, to))
            changed()
        }
        return count
    }

    /** Videos per segmentation (for Diagnose). */
    fun segmentationCounts(): Map<YtSsiSegmentation, Int> = readableDatabase.rawQuery("SELECT seg, COUNT(*) FROM docs GROUP BY seg", null).use { c ->
        buildMap { while (c.moveToNext()) put(enumOr(c.getString(0), YtSsiSegmentation.RULES), c.getInt(1)) }
    }

    /** Empties the whole index (the AI plans too). */
    fun clear() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (table in listOf("vecs", "segs_fts", "segs", "docs_fts", "docs", "ai")) db.execSQL("DELETE FROM $table")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changed()
    }

    // ---- reading ----

    /** Every video in the index by id (kept in memory until the next change). */
    fun docs(): Map<String, YtSsiDoc> {
        synchronized(lock) { docCache?.let { return it } }
        val map = HashMap<String, YtSsiDoc>()
        readableDatabase.rawQuery(
            "SELECT video_id, title, channel_id, channel_name, description, uploaded, duration_sec, language, auto, timing, source, vanished, chapters, indexed_at, version, seg FROM docs",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val doc = YtSsiDoc(
                    videoId = c.getString(0),
                    title = c.getString(1),
                    channelId = c.getString(2),
                    channelName = c.getString(3),
                    description = c.getString(4),
                    uploaded = if (c.isNull(5)) null else c.getLong(5),
                    durationSec = if (c.isNull(6)) null else c.getLong(6),
                    language = c.getString(7),
                    autoCaptions = if (c.isNull(8)) null else c.getInt(8) == 1,
                    timing = enumOr(c.getString(9), YtSsiTiming.APPROX),
                    source = enumOr(c.getString(10), YtSsiSource.CAPTIONS),
                    vanished = c.getInt(11) == 1,
                    chapters = decodeChapters(c.getString(12)),
                    indexedAt = c.getLong(13),
                    version = c.getInt(14),
                    segmentation = enumOr(c.getString(15), YtSsiSegmentation.RULES),
                )
                map[doc.videoId] = doc
            }
        }
        synchronized(lock) { docCache = map }
        return map
    }

    fun segment(id: Long): YtSsiSegment? {
        segCache.get(id)?.let { return it }
        return readableDatabase.rawQuery("SELECT $SEG_COLUMNS FROM segs WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) readSegment(c) else null
        }?.also { segCache.put(id, it) }
    }

    fun neighbour(videoId: String, ord: Int): YtSsiSegment? {
        if (ord < 0) return null
        return readableDatabase.rawQuery("SELECT $SEG_COLUMNS FROM segs WHERE video_id = ? AND ord = ?", arrayOf(videoId, ord.toString())).use { c ->
            if (c.moveToFirst()) readSegment(c) else null
        }?.also { segCache.put(it.id, it) }
    }

    fun segments(videoId: String): List<YtSsiSegment> =
        readableDatabase.rawQuery("SELECT $SEG_COLUMNS FROM segs WHERE video_id = ? ORDER BY ord", arrayOf(videoId)).use { c ->
            buildList { while (c.moveToNext()) add(readSegment(c)) }
        }

    private fun readSegment(c: android.database.Cursor): YtSsiSegment {
        val start = c.getLong(3)
        return YtSsiSegment(
            id = c.getLong(0),
            videoId = c.getString(1),
            ord = c.getInt(2),
            startMs = start,
            endMs = c.getLong(4),
            text = c.getString(5),
            kind = enumOr(c.getString(6), YtSsiSegmentKind.SPEECH),
            timing = enumOr(c.getString(7), YtSsiTiming.LINE),
            marks = YtSsiSegmenter.decodeMarks(c.getString(8), start),
        )
    }

    /** Passage ids matching [match] (FTS4 syntax), by BM25, best first. */
    fun lexicalSegments(match: String, limit: Int = 300): List<Long> = ranked(
        "SELECT docid, matchinfo(segs_fts, 'pcnalx') FROM segs_fts WHERE segs_fts MATCH ? LIMIT $SCAN",
        match,
        null,
        limit,
    ).map { it.first }

    /**
     * Video ids whose title, channel, description or chapters match, by BM25 (the title counts
     * most), best first, each with whether its title itself matched.
     */
    fun lexicalDocs(match: String, limit: Int = 100): List<Pair<String, Boolean>> {
        val rows = ranked(
            "SELECT d.video_id, matchinfo(docs_fts, 'pcnalx') FROM docs_fts JOIN docs d ON d.id = docs_fts.docid WHERE docs_fts MATCH ? LIMIT $SCAN",
            match,
            doubleArrayOf(2.0, 1.0, 0.4, 1.0),
            limit,
            key = { it.getString(0) },
        )
        return rows.map { (id, info) -> id to titleHit(info) }
    }

    private fun titleHit(info: IntArray): Boolean {
        val p = info.getOrElse(0) { 0 }
        val c = info.getOrElse(1) { 0 }
        for (phrase in 0 until p) {
            val x = 3 + 2 * c + 3 * (phrase * c)
            if (info.getOrElse(x) { 0 } > 0) return true
        }
        return false
    }

    private fun ranked(sql: String, match: String, weights: DoubleArray?, limit: Int): List<Pair<Long, IntArray>> =
        ranked(sql, match, weights, limit) { it.getLong(0) }

    private fun <K> ranked(sql: String, match: String, weights: DoubleArray?, limit: Int, key: (android.database.Cursor) -> K): List<Pair<K, IntArray>> {
        val rows = ArrayList<Triple<K, IntArray, Double>>()
        try {
            readableDatabase.rawQuery(sql, arrayOf(match)).use { c ->
                while (c.moveToNext()) {
                    val info = ints(c.getBlob(1))
                    rows += Triple(key(c), info, YtSsiFusion.bm25(info, weights))
                }
            }
        } catch (e: SQLiteException) {
            // a query the FTS parser does not take (should not happen: tokens are folded)
            Log.w(TAG, "fts query failed: $e")
            return emptyList()
        }
        return rows.sortedByDescending { it.third }.take(limit).map { it.first to it.second }
    }

    private fun ints(blob: ByteArray): IntArray {
        val buffer = ByteBuffer.wrap(blob).order(ByteOrder.nativeOrder())
        return IntArray(blob.size / 4) { buffer.getInt(it * 4) }
    }

    // ---- vectors ----

    /** Passages without a vector of [model], oldest first: (id, text). */
    fun unembedded(model: String, limit: Int): List<Pair<Long, String>> = readableDatabase.rawQuery(
        "SELECT s.id, s.text FROM segs s LEFT JOIN vecs v ON v.seg_id = s.id AND v.model = ? WHERE v.seg_id IS NULL ORDER BY s.id LIMIT $limit",
        arrayOf(model),
    ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0) to c.getString(1)) } }

    /** The same for the passages of [videoIds] only (a Deep search embeds what it just fetched). */
    fun unembeddedOf(model: String, videoIds: Collection<String>, limit: Int): List<Pair<Long, String>> {
        if (videoIds.isEmpty()) return emptyList()
        val marks = videoIds.joinToString(",") { "?" }
        return readableDatabase.rawQuery(
            "SELECT s.id, s.text FROM segs s LEFT JOIN vecs v ON v.seg_id = s.id AND v.model = ? WHERE v.seg_id IS NULL AND s.video_id IN ($marks) ORDER BY s.id LIMIT $limit",
            arrayOf(model) + videoIds.toTypedArray(),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0) to c.getString(1)) } }
    }

    fun unembeddedCount(model: String): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM segs s LEFT JOIN vecs v ON v.seg_id = s.id AND v.model = ? WHERE v.seg_id IS NULL",
        arrayOf(model),
    ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** Stores vectors (already int8 with their scale) of [model]; a passage deleted meanwhile is skipped. */
    fun putVectors(model: String, rows: List<Triple<Long, Float, ByteArray>>) {
        if (rows.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((id, scale, data) in rows) {
                db.execSQL(
                    "INSERT OR REPLACE INTO vecs (seg_id, model, scale, data) SELECT ?, ?, ?, ? WHERE EXISTS (SELECT 1 FROM segs WHERE id = ?)",
                    arrayOf<Any>(id, model, scale.toDouble(), data, id),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changes++
    }

    /** Adds the vectors of [model] after [index]'s [YtSsiVectorIndex.maxId] to it. */
    fun loadVectors(index: YtSsiVectorIndex) {
        var after = index.maxId
        while (true) {
            val ids = ArrayList<Long>()
            val scales = ArrayList<Float>()
            val bytes = java.io.ByteArrayOutputStream()
            readableDatabase.rawQuery(
                "SELECT seg_id, scale, data FROM vecs WHERE model = ? AND seg_id > ? ORDER BY seg_id LIMIT $LOAD_CHUNK",
                arrayOf(index.model, after.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    val data = c.getBlob(2)
                    if (data.size != index.dim) continue
                    ids += c.getLong(0)
                    scales += c.getFloat(1)
                    bytes.write(data)
                }
            }
            if (ids.isEmpty()) return
            index.add(ids.toLongArray(), scales.toFloatArray(), bytes.toByteArray())
            after = ids.last()
            if (ids.size < LOAD_CHUNK) return
        }
    }

    fun deleteVectors() {
        writableDatabase.execSQL("DELETE FROM vecs")
        changes++
    }

    // ---- AI plans ----

    fun aiPlan(query: String, now: Long): YtSsiAiPlan? = readableDatabase.rawQuery("SELECT answer, at FROM ai WHERE query = ?", arrayOf(query)).use { c ->
        if (c.moveToFirst() && YtSsiAiRules.fresh(c.getLong(1), now)) YtSsiAiRules.decode(c.getString(0)) else null
    }

    fun putAiPlan(query: String, plan: YtSsiAiPlan, now: Long) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO ai (query, answer, at) VALUES (?, ?, ?)", arrayOf<Any>(query, YtSsiAiRules.encode(plan), now))
    }

    /** The AI re-rank's grades stored under [key] ([YtSsiRerank.cacheKey], prefixed so it never meets a plan), when fresh. */
    fun rerank(key: String, now: Long): Map<Long, YtSsiAiGrade>? = readableDatabase.rawQuery("SELECT answer, at FROM ai WHERE query = ?", arrayOf(key)).use { c ->
        if (c.moveToFirst() && YtSsiRerank.fresh(c.getLong(1), now)) YtSsiRerank.decode(c.getString(0)) else null
    }

    fun putRerank(key: String, grades: Map<Long, YtSsiAiGrade>, now: Long) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO ai (query, answer, at) VALUES (?, ?, ?)", arrayOf<Any>(key, YtSsiRerank.encode(grades), now))
    }

    // ---- SSI-3 relations ----

    /** The raw stance answer stored under [key] ([YtSsiStanceRules.cacheKey], prefix "st|"), when fresh; verified again by the caller. */
    fun stance(key: String, now: Long): YtSsiStanceRules.Raw? = readableDatabase.rawQuery("SELECT answer, at FROM ai WHERE query = ?", arrayOf(key)).use { c ->
        if (c.moveToFirst() && YtSsiStanceRules.fresh(c.getLong(1), now)) YtSsiStanceRules.decode(c.getString(0)) else null
    }

    fun putStance(key: String, raw: YtSsiStanceRules.Raw, now: Long) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO ai (query, answer, at) VALUES (?, ?, ?)", arrayOf<Any>(key, YtSsiStanceRules.encode(raw), now))
    }

    /** The stored vectors of [model] for the passages [ids] (for near-duplicate passages, SSI-3). */
    fun vectorsOf(model: String, ids: Collection<Long>): Map<Long, FloatArray> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<Long, FloatArray>()
        for (chunk in ids.distinct().chunked(400)) {
            val marks = chunk.joinToString(",") { "?" }
            readableDatabase.rawQuery(
                "SELECT seg_id, scale, data FROM vecs WHERE model = ? AND seg_id IN ($marks)",
                arrayOf(model) + chunk.map { it.toString() }.toTypedArray(),
            ).use { c ->
                while (c.moveToNext()) out[c.getLong(0)] = YtSsiVectors.dequantize(c.getBlob(2), c.getFloat(1))
            }
        }
        return out
    }

    // ---- feedback ----

    fun putFeedback(f: YtSsiFeedback) {
        writableDatabase.insertOrThrow("feedback", null, ContentValues().apply {
            put("at", f.at)
            put("query", f.query)
            put("kind", f.kind.name)
            put("video_id", f.videoId)
            put("seg_id", f.segmentId)
            put("verdict", f.verdict.name)
            put("lex_rank", f.lexicalRank)
            put("sem_rank", f.semanticRank)
            put("title_rank", f.titleRank)
            put("ai_grade", f.aiGrade)
            put("semantic", if (f.semanticUsed) 1 else 0)
            put("timing", f.timing.name)
        })
    }

    /** The verdict last given on passage or video [key] for [query], or null. */
    fun lastVerdict(query: String, videoId: String, segmentId: Long?): YtSsiVerdict? = readableDatabase.rawQuery(
        "SELECT verdict FROM feedback WHERE query = ? AND video_id = ? AND " + (if (segmentId == null) "seg_id IS NULL" else "seg_id = ?") + " ORDER BY id DESC LIMIT 1",
        if (segmentId == null) arrayOf(query, videoId) else arrayOf(query, videoId, segmentId.toString()),
    ).use { c -> if (c.moveToFirst()) enumOr(c.getString(0), YtSsiVerdict.NOT_RELEVANT) else null }

    fun feedbackCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM feedback", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun clearFeedback() {
        writableDatabase.execSQL("DELETE FROM feedback")
    }

    // ---- numbers ----

    data class Stats(val videos: Int, val segments: Int, val vectors: Int, val bytes: Long)

    fun stats(context: Context, model: String?): Stats {
        val db = readableDatabase
        fun count(sql: String, args: Array<String>? = null) = db.rawQuery(sql, args).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val file = context.getDatabasePath(NAME)
        val bytes = listOf(file, java.io.File(file.path + "-wal")).sumOf { if (it.isFile) it.length() else 0L }
        return Stats(
            videos = count("SELECT COUNT(*) FROM docs"),
            segments = count("SELECT COUNT(*) FROM segs"),
            vectors = if (model == null) 0 else count("SELECT COUNT(*) FROM vecs WHERE model = ?", arrayOf(model)),
            bytes = bytes,
        )
    }

    companion object {
        const val NAME = "yt_ssi.db"
        /** 2 (SSI-2): `docs.seg`, table `feedback`. */
        private const val VERSION = 2
        private const val TAG = "YtSsiDb"
        private const val SEG_COLUMNS = "id, video_id, ord, start_ms, end_ms, text, kind, timing, marks"

        /** At most this many FTS rows are scored per query (then the best are kept). */
        private const val SCAN = 5_000
        private const val LOAD_CHUNK = 20_000

        private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
            runCatching { enumValueOf<E>(name ?: return default) }.getOrDefault(default)

        fun encodeChapters(chapters: List<YtSsiChapter>): String =
            chapters.joinToString("\n") { "${it.startMs}\t${it.title.replace('\n', ' ').replace('\t', ' ')}" }

        fun decodeChapters(text: String?): List<YtSsiChapter> = text.orEmpty().split('\n').mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) null else line.substring(0, tab).toLongOrNull()?.let { YtSsiChapter(it, line.substring(tab + 1)) }
        }
    }
}
