package com.github.libretube.ui.yt.player

import android.graphics.Color
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import com.github.libretube.R
import com.github.libretube.databinding.DoubleTapOverlayBinding

/**
 * YT: the chapter name under the double tap's seek icon ("› Name") while the double tap jumps
 * chapters ([YtChapterTap]); the icon's "10" hides meanwhile. Made on first use, so the upstream
 * overlay layout stays as it is. A plain seek step hides the name and brings the "10" back.
 */
class YtChapterTapLabel(
    private val overlay: DoubleTapOverlayBinding,
    /** The chapter title at a position, null or empty without one. */
    private val title: (Long) -> String?,
    /** The position as a time, for chapters without a title. */
    private val time: (Long) -> String,
) {
    private var forwardLabel: TextView? = null
    private var rewindLabel: TextView? = null

    /** Shows the chapter at [targetMs] on the [forward] side, or (null) the plain seek step. */
    fun show(forward: Boolean, targetMs: Long?) {
        val seconds = if (forward) overlay.forwardLayout.forwardTV else overlay.rewindLayout.rewindTV
        seconds.isInvisible = targetMs != null
        if (targetMs == null) {
            hide(forward)
            return
        }
        val context = overlay.root.context
        val name = title(targetMs)?.takeIf { it.isNotBlank() } ?: time(targetMs)
        val label = label(forward)
        label.text = context.getString(
            if (forward) R.string.yt_r18_chapter_tap_next else R.string.yt_r18_chapter_tap_prev,
            name,
        )
        place(forward, label)
        label.isGone = false
        label.contentDescription = name
    }

    /** Hides the name on the [forward] side (with the overlay's own hide). */
    fun hide(forward: Boolean) {
        (if (forward) forwardLabel else rewindLabel)?.isGone = true
    }

    private fun label(forward: Boolean): TextView {
        (if (forward) forwardLabel else rewindLabel)?.let { return it }
        val frame = if (forward) overlay.forwardFL else overlay.rewindFL
        val context = frame.context
        val density = context.resources.displayMetrics.density
        val label = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setShadowLayer(4f * density, 0f, 0f, Color.argb(0xB0, 0, 0, 0))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            isGone = true
        }
        frame.addView(label, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        if (forward) forwardLabel = label else rewindLabel = label
        return label
    }

    // under the icon, which sits in the middle of its side; as wide as the side allows
    private fun place(forward: Boolean, label: TextView) {
        val frame = if (forward) overlay.forwardFL else overlay.rewindFL
        val icon = if (forward) overlay.forwardLayout.forwardBTN else overlay.rewindLayout.rewindBTN
        val image = if (forward) overlay.forwardLayout.forwardIV else overlay.rewindLayout.rewindIV
        val density = frame.resources.displayMetrics.density
        val gap = 6f * density
        val iconHeight = icon.height.takeIf { it > 0 } ?: image.drawable?.intrinsicHeight ?: (48 * density).toInt()
        val width = frame.width.takeIf { it > 0 } ?: (frame.resources.displayMetrics.widthPixels * 0.4f).toInt()
        label.maxWidth = (width - 16f * density).toInt().coerceAtLeast(0)
        label.translationY = iconHeight / 2f + gap + label.lineHeight / 2f
    }
}
