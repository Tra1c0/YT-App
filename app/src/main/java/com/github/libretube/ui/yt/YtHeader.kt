package com.github.libretube.ui.yt

import android.view.View
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.navigation.NavController
import com.github.libretube.R
import com.github.libretube.helpers.YtDesign
import com.google.android.material.appbar.MaterialToolbar
import kotlin.math.roundToInt

/**
 * YT: the header row of each design as in the references: logo mark and wordmark on the left,
 * search and a profile circle on the right. The profile circle opens the app menu.
 */
object YtHeader {
    fun apply(toolbar: MaterialToolbar) {
        val design = YtDesign.current()
        if (design == YtDesign.STANDARD) return
        val context = toolbar.context
        val dp = context.resources.displayMetrics.density * ytScale(context.resources.configuration)

        logo(design)?.let { toolbar.logo = AppCompatResources.getDrawable(context, it) }
        toolbar.titleMarginStart = (10 * dp).roundToInt()
        val inset = when (design) {
            YtDesign.YOUTUBE -> 15
            YtDesign.SIDE -> 24
            else -> 22
        }
        toolbar.setContentInsetsRelative((inset * dp).roundToInt(), 0)
        toolbar.overflowIcon = AppCompatResources.getDrawable(context, avatar(design))
    }

    /**
     * "After": the home poster runs behind the header, as in the reference. The fragment area
     * starts at the top of the screen; every other destination keeps the room for the header
     * as top padding. Must run in onCreate, before MotionLayout reads the layout.
     */
    fun overlayAfterHome(appBar: View, fragment: View, navController: NavController) {
        if (YtDesign.current() != YtDesign.AFTER) return
        fragment.updateLayoutParams<ConstraintLayout.LayoutParams> {
            topToBottom = ConstraintLayout.LayoutParams.UNSET
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        }
        appBar.translationZ = appBar.resources.displayMetrics.density

        var overlay = false
        fun update() {
            val header = appBar.height
            fragment.updatePadding(top = if (overlay) 0 else header)
            YtChrome.headerOverlapPx = if (overlay) header else 0
        }
        navController.addOnDestinationChangedListener { _, destination, _ ->
            overlay = destination.id == R.id.homeFragment
            update()
        }
        appBar.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) update()
        }
    }

    /** Search icon of the header, or null for the classic one. */
    @DrawableRes
    fun searchIcon(): Int? = when (YtDesign.current()) {
        YtDesign.STANDARD -> null
        YtDesign.MOVO -> R.drawable.ic_yt_search_movo
        else -> R.drawable.ic_yt_search
    }

    @DrawableRes
    private fun logo(design: YtDesign): Int? = when (design) {
        YtDesign.YOUTUBE -> R.drawable.ic_yt_logo_youtube
        YtDesign.MOVO -> R.drawable.ic_yt_logo_movo
        YtDesign.SIDE -> R.drawable.ic_yt_logo_side
        else -> null
    }

    @DrawableRes
    private fun avatar(design: YtDesign): Int = when (design) {
        YtDesign.MOVO -> R.drawable.ic_yt_avatar_movo
        YtDesign.AFTER -> R.drawable.ic_yt_avatar_after
        YtDesign.SIDE -> R.drawable.ic_yt_avatar_side
        else -> R.drawable.ic_yt_avatar_youtube
    }
}

/** YT: state of the activity chrome that design screens lay out around. */
object YtChrome {
    /** Height in px of the header drawn over the current screen (After home), else 0. */
    var headerOverlapPx by mutableIntStateOf(0)
}
