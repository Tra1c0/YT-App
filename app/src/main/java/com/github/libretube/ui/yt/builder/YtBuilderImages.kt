package com.github.libretube.ui.yt.builder

import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Shader
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ShaderBrush
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * YT: the theme's images for image fills, decoded off the main thread and kept (a few, by file
 * and its change time). A draw that finds its image not loaded yet draws the fill's placeholder
 * colour and reads [version]; when the image arrives the version changes and the fill is built
 * again (drawWithCache observes the read), so nothing is decoded while drawing.
 */
object YtBuilderImages {
    /** Bumped when an image finished loading. */
    var version by mutableIntStateOf(0)
        private set

    private val cache = object : LruCache<String, android.graphics.Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap): Int = value.allocationByteCount
    }
    private val loading = HashSet<String>()
    private val failed = HashSet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun key(file: File) = file.path + "@" + file.lastModified()

    /** The image of [file], or null while it loads (or when it cannot be read). Main thread. */
    fun get(file: File): android.graphics.Bitmap? {
        val k = key(file)
        cache.get(k)?.let { return it }
        if (k in failed || !loading.add(k)) return null
        scope.launch {
            // stored images are already scaled down at import (YtImageImport), but a theme file
            // from elsewhere may hold larger ones: bound what is decoded
            val bitmap = runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                val (tw, th) = YtImageImport.targetSize(bounds.outWidth, bounds.outHeight)
                val opts = BitmapFactory.Options().apply { inSampleSize = YtImageImport.sampleSize(bounds.outWidth, bounds.outHeight, tw, th) }
                BitmapFactory.decodeFile(file.path, opts)
            }.getOrNull()
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                loading.remove(k)
                if (bitmap == null) failed += k else cache.put(k, bitmap)
                version++
            }
        }
        return null
    }

    /** [bitmap] covering a box of [size] (scaled to fill, centred, the rest cropped). */
    fun cover(bitmap: android.graphics.Bitmap, size: Size): Brush {
        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        shader.setLocalMatrix(coverMatrix(bitmap.width, bitmap.height, size.width, size.height))
        return ShaderBrush(shader)
    }

    fun coverMatrix(bw: Int, bh: Int, w: Float, h: Float): Matrix {
        val m = Matrix()
        if (bw <= 0 || bh <= 0) return m
        val s = maxOf(w / bw, h / bh)
        m.setScale(s, s)
        m.postTranslate((w - bw * s) / 2f, (h - bh * s) / 2f)
        return m
    }

    /** Forgets the images of a theme folder (deleted or replaced). */
    fun forget(dir: File) {
        val prefix = dir.path
        cache.snapshot().keys.filter { it.startsWith(prefix) }.forEach { cache.remove(it) }
        failed.removeAll { it.startsWith(prefix) }
    }
}
