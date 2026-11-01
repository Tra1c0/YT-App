package com.github.libretube.ui.yt

/** Keep glass legible and usable when its effects would be rendered on the CPU. */
object YtRenderingPolicy {
    fun hardwareEffects(renderer: String?, lowRam: Boolean, powerSave: Boolean): Boolean {
        if (renderer.isNullOrBlank() || lowRam || powerSave) return false
        val name = renderer.lowercase(java.util.Locale.ROOT)
        return listOf("swiftshader", "llvmpipe", "softpipe", "software renderer")
            .none(name::contains)
    }
}
