package com.github.libretube.ui.yt.builder

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.YtDesign
import java.io.File
import java.io.FileOutputStream

/**
 * YT: the themes on disk: filesDir/yt_themes/<id>/theme.json (+ assets/, versions/). Every write
 * is atomic (AtomicFile: a half-written file is never read, the previous one stays until the new
 * one is complete). Nothing here runs on every finger move: the editor saves debounced
 * (YtEditorState) and on leaving. All functions do file work; call them off the main thread,
 * except [loadActiveAtStart], which reads one small file once at app start.
 */
object YtBuilderStore {
    const val PREF_ACTIVE = "yt_custom_theme"
    private const val DIR = "yt_themes"
    private const val THEME = "theme.json"
    private const val ASSETS = "assets"
    private const val VERSIONS = "versions"
    const val MAX_VERSIONS = 20

    fun root(context: Context) = File(context.filesDir, DIR)

    fun dir(context: Context, id: String): File? = if (YtThemeIds.valid(id)) File(root(context), id) else null

    fun assets(context: Context, id: String): File? = dir(context, id)?.let { File(it, ASSETS) }

    data class Entry(val id: String, val name: String, val base: String, val modified: Long)

    fun activeId(): String? = runCatching { PreferenceHelper.getString(PREF_ACTIVE, "") }.getOrNull()?.takeIf { YtThemeIds.valid(it) }

    fun setActive(context: Context, doc: YtThemeDoc) {
        PreferenceHelper.putString(PREF_ACTIVE, doc.id)
        YtBuilderActive.set(doc, assets(context, doc.id))
    }

    fun list(context: Context): List<Entry> = root(context).listFiles { f -> f.isDirectory && YtThemeIds.valid(f.name) }.orEmpty()
        .mapNotNull { dir ->
            (load(context, dir.name) as? YtThemeCodec.Read.Ok)?.doc?.let { Entry(it.id, it.name, it.base, it.meta.modified) }
        }
        .sortedByDescending { it.modified }

    fun load(context: Context, id: String): YtThemeCodec.Read {
        val dir = dir(context, id) ?: return YtThemeCodec.Read.Failed("bad id")
        val file = AtomicFile(File(dir, THEME))
        val text = try {
            String(file.readFully(), Charsets.UTF_8)
        } catch (e: Exception) {
            return YtThemeCodec.Read.Failed("cannot read: ${e.javaClass.simpleName}")
        }
        val read = YtThemeCodec.decode(text)
        // the folder is the truth for the id: a copied folder must not save over another theme
        return if (read is YtThemeCodec.Read.Ok && read.doc.id != id) read.copy(doc = read.doc.copy(id = id)) else read
    }

    fun save(context: Context, doc: YtThemeDoc) {
        val dir = dir(context, doc.id) ?: return
        dir.mkdirs()
        writeAtomic(File(dir, THEME), YtThemeCodec.encode(doc).toByteArray(Charsets.UTF_8))
    }

    private fun writeAtomic(target: File, bytes: ByteArray) {
        val file = AtomicFile(target)
        var out: FileOutputStream? = null
        try {
            out = file.startWrite()
            out.write(bytes)
            file.finishWrite(out)
        } catch (e: Exception) {
            out?.let { file.failWrite(it) }
            throw e
        }
    }

    /** A new theme from [design], saved. */
    fun create(context: Context, design: YtDesign, name: String): YtThemeDoc {
        val doc = YtBuilderActive.create(design, name)
        save(context, doc)
        return doc
    }

    fun duplicate(context: Context, id: String, name: String): YtThemeDoc? {
        val doc = (load(context, id) as? YtThemeCodec.Read.Ok)?.doc ?: return null
        val now = System.currentTimeMillis()
        val copy = doc.copy(id = YtThemeIds.fresh(now), name = name.take(80), meta = doc.meta.copy(created = now, modified = now))
        save(context, copy)
        assets(context, id)?.takeIf { it.isDirectory }?.copyRecursively(assets(context, copy.id)!!, overwrite = true)
        return copy
    }

    fun delete(context: Context, id: String) {
        val dir = dir(context, id) ?: return
        assets(context, id)?.let {
            YtBuilderFonts.forget(it)
            YtBuilderImages.forget(it)
        }
        dir.deleteRecursively()
        if (activeId() == id) PreferenceHelper.putString(PREF_ACTIVE, "")
    }

    // ---- versions ----

    data class Version(val file: String, val at: Long, val name: String)

    /** Keeps the theme as it is now as a version; the oldest beyond [MAX_VERSIONS] go. */
    fun snapshot(context: Context, doc: YtThemeDoc, now: Long = System.currentTimeMillis()) {
        val dir = dir(context, doc.id)?.let { File(it, VERSIONS) } ?: return
        dir.mkdirs()
        writeAtomic(File(dir, "v$now.json"), YtThemeCodec.encode(doc).toByteArray(Charsets.UTF_8))
        val all = dir.listFiles { f -> f.name.startsWith("v") && f.name.endsWith(".json") }.orEmpty().sortedByDescending { it.name }
        all.drop(MAX_VERSIONS).forEach { it.delete() }
    }

    fun versions(context: Context, id: String): List<Version> {
        val dir = dir(context, id)?.let { File(it, VERSIONS) } ?: return emptyList()
        return dir.listFiles { f -> f.name.matches(Regex("v\\d+\\.json")) }.orEmpty()
            .mapNotNull { f ->
                val at = f.name.removePrefix("v").removeSuffix(".json").toLongOrNull() ?: return@mapNotNull null
                val name = (YtThemeCodec.decode(f.readText()) as? YtThemeCodec.Read.Ok)?.doc?.name ?: return@mapNotNull null
                Version(f.name, at, name)
            }
            .sortedByDescending { it.at }
    }

    fun loadVersion(context: Context, id: String, file: String): YtThemeDoc? {
        if (!file.matches(Regex("v\\d+\\.json"))) return null
        val f = dir(context, id)?.let { File(File(it, VERSIONS), file) } ?: return null
        return (runCatching { YtThemeCodec.decode(f.readText()) }.getOrNull() as? YtThemeCodec.Read.Ok)?.doc?.copy(id = id)
    }

    // ---- assets ----

    sealed interface AssetResult {
        data class Ok(val doc: YtThemeDoc, val file: String) : AssetResult
        data class Failed(val reason: String) : AssetResult
    }

    /** Copies a font from [uri] into the theme and lists it; the file is checked before it is kept. */
    fun importFont(context: Context, doc: YtThemeDoc, uri: Uri, displayName: String?): AssetResult {
        val raw = (displayName ?: "font.ttf").substringAfterLast('/')
        val ext = raw.substringAfterLast('.', "ttf").lowercase().takeIf { it == "ttf" || it == "otf" } ?: return AssetResult.Failed("not a TTF or OTF font")
        val stem = raw.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_-]"), "_").take(40).ifEmpty { "font" }
        var name = "$stem.$ext"
        var i = 2
        while (doc.assets.any { it.file == name }) name = "${stem}_${i++}.$ext"
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > YtThemeZip.MAX_ENTRY_BYTES) return AssetResult.Failed("font larger than 8 MB")
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            } ?: return AssetResult.Failed("cannot open the file")
        } catch (e: Exception) {
            return AssetResult.Failed("cannot read the file")
        }
        if (!YtThemeZip.signatureOk(name, bytes)) return AssetResult.Failed("not a font file")
        val dir = assets(context, doc.id) ?: return AssetResult.Failed("bad theme")
        dir.mkdirs()
        val file = File(dir, name)
        writeAtomic(file, bytes)
        if (!YtBuilderFonts.readable(file)) {
            file.delete()
            return AssetResult.Failed("Android cannot read this font")
        }
        return AssetResult.Ok(doc.copy(assets = doc.assets + YtAsset(name, YtAssetKind.FONT)), name)
    }

    /** An image imported into a theme: the document with it listed, its file name and stored size. */
    data class ImageOk(val doc: YtThemeDoc, val file: String, val width: Int, val height: Int)

    /**
     * Copies an image from [uri] into the theme (YtImageImport's rules: PNG, JPEG or WebP by its
     * bytes, at most 20 MB, scaled down to 2048 px / 4 MP, stored as PNG with transparency or else
     * JPEG). Null and [refused] with the reason when it is not taken. Not on the main thread.
     */
    fun importImage(context: Context, doc: YtThemeDoc, uri: Uri, displayName: String?, refused: (YtImageImport.Refusal?) -> Unit): ImageOk? {
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > YtImageImport.MAX_SOURCE_BYTES) {
                        refused(YtImageImport.Refusal.TOO_LARGE_FILE)
                        return null
                    }
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } catch (e: Exception) {
            null
        } ?: run {
            refused(null)
            return null
        }
        val head = bytes.copyOfRange(0, minOf(16, bytes.size))
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) }
        YtImageImport.check(bytes.size.toLong(), head, bounds.outWidth, bounds.outHeight)?.let {
            refused(it)
            return null
        }
        val (tw, th) = YtImageImport.targetSize(bounds.outWidth, bounds.outHeight)
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = YtImageImport.sampleSize(bounds.outWidth, bounds.outHeight, tw, th) }
        val decoded = runCatching { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull() ?: run {
            refused(YtImageImport.Refusal.NOT_AN_IMAGE)
            return null
        }
        val bitmap = if (decoded.width == tw && decoded.height == th) decoded else android.graphics.Bitmap.createScaledBitmap(decoded, tw, th, true)
        fun encode(format: YtImageImport.Format): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            @Suppress("DEPRECATION")
            val f = when (format) {
                YtImageImport.Format.PNG -> android.graphics.Bitmap.CompressFormat.PNG
                YtImageImport.Format.JPEG -> android.graphics.Bitmap.CompressFormat.JPEG
                YtImageImport.Format.WEBP -> if (android.os.Build.VERSION.SDK_INT >= 30) android.graphics.Bitmap.CompressFormat.WEBP_LOSSY else android.graphics.Bitmap.CompressFormat.WEBP
            }
            bitmap.compress(f, if (format == YtImageImport.Format.PNG) 100 else YtImageImport.JPEG_QUALITY, out)
            return out.toByteArray()
        }
        var format = YtImageImport.outputFormat(bitmap.hasAlpha())
        var data = encode(format)
        if (!YtImageImport.resultOk(data.size)) {
            // a large picture with transparency: lossy WebP keeps the transparency and is far smaller
            YtImageImport.fallback(format)?.let { f ->
                format = f
                data = encode(f)
            }
        }
        if (!YtImageImport.resultOk(data.size)) {
            refused(YtImageImport.Refusal.TOO_LARGE_RESULT)
            return null
        }
        val name = YtImageImport.assetName(displayName, format, doc.assets.map { it.file })
        if (!YtThemeZip.safeAssetName(name) || !YtThemeZip.signatureOk(name, data)) {
            refused(YtImageImport.Refusal.NOT_AN_IMAGE)
            return null
        }
        val dir = assets(context, doc.id) ?: run {
            refused(null)
            return null
        }
        dir.mkdirs()
        writeAtomic(File(dir, name), data)
        return ImageOk(doc.copy(assets = doc.assets + YtAsset(name, YtAssetKind.IMAGE)), name, bitmap.width, bitmap.height)
    }

    // ---- export / import ----

    fun export(context: Context, doc: YtThemeDoc, uri: Uri): Boolean = runCatching {
        val dir = assets(context, doc.id)
        val assets = doc.assets.mapNotNull { a -> dir?.let { File(it, a.file) }?.takeIf { it.isFile }?.let { a.file to it.readBytes() } }.toMap()
        context.contentResolver.openOutputStream(uri, "wt")?.use { YtThemeZip.write(doc, assets, it) } ?: error("no stream")
    }.isSuccess

    sealed interface ImportResult {
        data class Ok(val doc: YtThemeDoc, val notes: List<String>) : ImportResult
        data class Failed(val reason: String) : ImportResult
    }

    /** Reads a .yttheme file as a new theme (a new id, so nothing existing is overwritten). */
    fun import(context: Context, uri: Uri): ImportResult {
        val read = try {
            context.contentResolver.openInputStream(uri)?.use { YtThemeZip.read(it) } ?: return ImportResult.Failed("cannot open the file")
        } catch (e: Exception) {
            return ImportResult.Failed("cannot read the file")
        }
        return when (read) {
            is YtThemeZip.Import.Failed -> ImportResult.Failed(read.reason)
            is YtThemeZip.Import.Ok -> {
                val now = System.currentTimeMillis()
                val doc = read.doc.copy(id = YtThemeIds.fresh(now), meta = read.doc.meta.copy(modified = now))
                val notes = read.notes.toMutableList()
                if (YtDesign.entries.none { it.key == doc.base }) notes += "unknown base design: ${doc.base.take(30)}"
                save(context, doc)
                val dir = assets(context, doc.id)!!
                dir.mkdirs()
                for ((name, bytes) in read.assets) writeAtomic(File(dir, name), bytes)
                // fonts Android cannot read are dropped, the text falls back to the default family
                val fonts = doc.assets.filter { it.kind == YtAssetKind.FONT }.filter { !YtBuilderFonts.readable(File(dir, it.file)) }
                val kept = if (fonts.isEmpty()) doc else doc.copy(assets = doc.assets - fonts.toSet()).also {
                    fonts.forEach { f -> File(dir, f.file).delete(); notes += "unreadable font: ${f.file}" }
                    save(context, it)
                }
                ImportResult.Ok(kept, notes)
            }
        }
    }

    /**
     * App start: reads the active theme into YtBuilderActive (one small file). Without an active
     * theme, the newest one is taken; without any, a first one from YouTube neu is made, so
     * picking "Eigenes" always shows a whole design. False if the theme could not be read.
     */
    fun loadActiveAtStart(context: Context): Boolean {
        val id = activeId() ?: list(context).firstOrNull()?.id
        val doc = if (id == null) {
            create(context, YtDesign.YOUTUBE, context.getString(com.github.libretube.R.string.yt_builder_default_name))
        } else {
            when (val r = load(context, id)) {
                is YtThemeCodec.Read.Ok -> r.doc
                is YtThemeCodec.Read.Failed -> return false
            }
        }
        setActive(context, doc)
        return true
    }
}
