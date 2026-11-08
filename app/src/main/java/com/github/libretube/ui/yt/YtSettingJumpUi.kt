package com.github.libretube.ui.yt

import android.animation.ValueAnimator
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors

/**
 * YT: opens a settings screen at one setting. A guide button passes the key as
 * [YtSettingJump.ARG_KEY]; the screen scrolls the row to the middle and tints it once in the
 * primary colour. The tint is a drawable in the row's overlay, so the row's own background
 * (the design's card) is never touched; removing the overlay restores it exactly.
 */
object YtSettingJumpUi {
    /** Peak opacity of the primary colour over the row. */
    private const val PEAK_ALPHA = 0.22f

    /** Frames to wait for the scrolled-to row to be laid out before giving up. */
    private const val MAX_FRAMES = 20

    /** Called from [com.github.libretube.ui.base.BasePreferenceFragment.onViewCreated]. */
    fun consume(fragment: PreferenceFragmentCompat, savedInstanceState: Bundle?) {
        val args = fragment.arguments ?: return
        val raw = args.getString(YtSettingJump.ARG_KEY) ?: return
        // read once: rotation and coming back to the screen do not light it up again
        args.remove(YtSettingJump.ARG_KEY)
        val key = YtSettingJump.keyToUse(raw, savedInstanceState != null) ?: return
        val pref = fragment.findPreference<Preference>(key) ?: return

        // the row itself, or the nearest parent that is shown (row hidden in this design)
        val chain = generateSequence(pref) { it.parent }.takeWhile { it != fragment.preferenceScreen }.toList()
        val index = YtSettingJump.scrollTarget(chain.map { it.isVisible }) ?: return
        val target = chain[index]
        val highlight = index == 0

        val list = fragment.listView ?: return
        list.postDelayed({
            if (!list.isAttachedToWindow) return@postDelayed
            val callback = list.adapter as? PreferenceGroup.PreferencePositionCallback ?: return@postDelayed
            val position = callback.getPreferenceAdapterPosition(target)
            if (position == RecyclerView.NO_POSITION) return@postDelayed
            (list.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(position, list.height / 3)
                ?: list.scrollToPosition(position)
            awaitRow(list, position, 0) { row ->
                // center the row now that its height is known; the list clamps at its ends
                val delta = (row.top + row.height / 2) - list.height / 2
                if (delta != 0) list.scrollBy(0, delta)
                if (highlight) pulse(row)
            }
        }, YtSettingJump.START_DELAY_MS)
    }

    private fun awaitRow(list: RecyclerView, position: Int, frame: Int, then: (View) -> Unit) {
        list.postOnAnimation {
            if (!list.isAttachedToWindow) return@postOnAnimation
            val row = list.findViewHolderForAdapterPosition(position)?.itemView
            when {
                row != null && row.isLaidOut && !list.isLayoutRequested -> then(row)
                frame < MAX_FRAMES -> awaitRow(list, position, frame + 1, then)
            }
        }
    }

    private fun pulse(row: View) {
        val primary = MaterialColors.getColor(row, androidx.appcompat.R.attr.colorPrimary)
        val tint = GradientDrawable().apply {
            setColor(ColorUtils.setAlphaComponent(primary, (255 * PEAK_ALPHA).toInt()))
            cornerRadius = cornerRadius(row)
            setBounds(0, 0, row.width, row.height)
            alpha = 0
        }
        // animator scale 0 (system "remove animations") counts as motion off as well
        val motion = YtMotionPrefs.enabled && ValueAnimator.areAnimatorsEnabled()
        val total = YtSettingJump.durationMs(motion)
        var animator: ValueAnimator? = null
        var ended = false

        lateinit var detach: View.OnAttachStateChangeListener
        val end = Runnable {
            if (ended) return@Runnable
            ended = true
            animator?.cancel()
            row.overlay.remove(tint)
            row.removeOnAttachStateChangeListener(detach)
        }
        // a row that scrolls away is recycled for another setting: never leave the tint on it
        detach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                v.removeCallbacks(end)
                end.run()
            }
        }
        row.addOnAttachStateChangeListener(detach)
        row.overlay.add(tint)

        if (motion) {
            animator = ValueAnimator.ofFloat(0f, total.toFloat()).apply {
                duration = total
                interpolator = LinearInterpolator()
                addUpdateListener {
                    val ms = (it.animatedValue as Float).toLong()
                    tint.alpha = (255 * YtSettingJump.alphaAt(ms, true)).toInt()
                    row.invalidate()
                }
                start()
            }
        } else {
            tint.alpha = (255 * YtSettingJump.alphaAt(0, false)).toInt()
            row.invalidate()
        }
        row.postDelayed(end, total)
    }

    /** The corner radius of the row's card (outline of its background), 0 for plain rows. */
    private fun cornerRadius(row: View): Float {
        val outline = Outline()
        row.outlineProvider?.getOutline(row, outline)
        return outline.radius.coerceAtLeast(0f)
    }
}
