package com.github.libretube.ui.yt.builder

import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * YT: a theme as a ".yttheme" file (pure): a zip with theme.json and assets/<file> (fonts,
 * images). Reading trusts nothing: entry count, sizes (counted while reading, not taken from the
 * headers), names (no paths, known types only), file signatures and the JSON are all checked,
 * and whatever is wrong is reported instead of thrown.
 */
object YtThemeZip {
    const val EXTENSION = "yttheme"
    const val MIME = "application/zip"
    const val THEME = "theme.json"
    const val ASSETS = "assets/"
    const val MAX_ENTRIES = 64
    const val MAX_ENTRY_BYTES = 8L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 24L * 1024 * 1024

    private val NAME = Regex("[A-Za-z0-9._-]{1,80}")
    private val FONT_EXT = setOf("ttf", "otf")
    private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp")

    /** A file name that is safe inside the theme folder and of a type the theme can use. */
    fun safeAssetName(name: String): Boolean {
        if (!NAME.matches(name) || name.startsWith(".")) return false
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in FONT_EXT || ext in IMAGE_EXT
    }

    fun kindOf(name: String): YtAssetKind? = when (name.substringAfterLast('.', "").lowercase()) {
        in FONT_EXT -> YtAssetKind.FONT
        in IMAGE_EXT -> YtAssetKind.IMAGE
        else -> null
    }

    /** Whether [bytes] start like a file of [name]'s type (a renamed file is refused). */
    fun signatureOk(name: String, bytes: ByteArray): Boolean {
        fun at(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
        return when (name.substringAfterLast('.', "").lowercase()) {
            "ttf", "otf" -> (at(0) == 0 && at(1) == 1 && at(2) == 0 && at(3) == 0) ||
                String(bytes.copyOfRange(0, minOf(4, bytes.size)), Charsets.ISO_8859_1) in setOf("OTTO", "true", "ttcf")
            "png" -> at(0) == 0x89 && at(1) == 'P'.code && at(2) == 'N'.code && at(3) == 'G'.code
            "jpg", "jpeg" -> at(0) == 0xFF && at(1) == 0xD8
            "webp" -> String(bytes.copyOfRange(0, minOf(4, bytes.size)), Charsets.ISO_8859_1) == "RIFF" &&
                bytes.size >= 12 && String(bytes.copyOfRange(8, 12), Charsets.ISO_8859_1) == "WEBP"
            else -> false
        }
    }

    /** Writes [doc] and its [assets] (file name to bytes; only those the document lists). */
    fun write(doc: YtThemeDoc, assets: Map<String, ByteArray>, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(THEME))
            zip.write(YtThemeCodec.encode(doc).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for (asset in doc.assets) {
                val bytes = assets[asset.file] ?: continue
                if (!safeAssetName(asset.file)) continue
                zip.putNextEntry(ZipEntry(ASSETS + asset.file))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    sealed interface Import {
        data class Ok(val doc: YtThemeDoc, val assets: Map<String, ByteArray>, val notes: List<String>) : Import
        data class Failed(val reason: String) : Import
    }

    fun read(input: InputStream): Import {
        var theme: String? = null
        val assets = LinkedHashMap<String, ByteArray>()
        val notes = mutableListOf<String>()
        var total = 0L
        var entries = 0
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    if (entries > MAX_ENTRIES) return Import.Failed("too many files")
                    val name = entry.name
                    if (entry.isDirectory) continue
                    val bytes = readCapped(zip) ?: return Import.Failed("file too large: ${name.take(60)}")
                    total += bytes.size
                    if (total > MAX_TOTAL_BYTES) return Import.Failed("theme too large")
                    when {
                        name == THEME -> theme = String(bytes, Charsets.UTF_8)
                        name.startsWith(ASSETS) -> {
                            val file = name.removePrefix(ASSETS)
                            when {
                                !safeAssetName(file) -> notes += "skipped: ${file.take(60)}"
                                !signatureOk(file, bytes) -> notes += "damaged: $file"
                                else -> assets[file] = bytes
                            }
                        }
                        else -> notes += "ignored: ${name.take(60)}"
                    }
                }
            }
        } catch (e: Exception) {
            return Import.Failed("not a theme file: ${e.message?.take(120)}")
        }
        val text = theme ?: return Import.Failed("no theme.json")
        return when (val r = YtThemeCodec.decode(text)) {
            is YtThemeCodec.Read.Failed -> Import.Failed(r.reason)
            is YtThemeCodec.Read.Ok -> {
                notes += r.issues.take(20).map { "repaired: ${it.path} (${it.what})" }
                // what the document lists but the file does not hold is dropped and reported
                val present = r.doc.assets.filter { asset ->
                    (asset.file in assets).also { if (!it) notes += "missing: ${asset.file}" }
                }
                val listed = present.map { it.file }.toSet()
                // files the document does not list are of no use
                val kept = assets.filterKeys { it in listed }
                Import.Ok(r.doc.copy(assets = present), kept, notes)
            }
        }
    }

    private fun readCapped(input: InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var n = 0L
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            n += r
            if (n > MAX_ENTRY_BYTES) return null
            out.write(buf, 0, r)
        }
        return out.toByteArray()
    }
}
