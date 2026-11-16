package com.github.libretube.ui.yt.ssi

import android.content.Context
import android.content.ContextWrapper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.SearchView
import com.github.libretube.R
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.activities.MainActivity

/**
 * YT (SSI-1 gap): SSI's entry in the Standard design, whose search is LibreTube's own toolbar
 * search (the other designs open SSI from their search field, [YtSsiUi.open] in YtSearchUi). An
 * icon next to the expanded search field, shown only while the field is open on the search
 * screens (suggestions or results); a tap opens SSI with the typed text. Called from
 * YtHeader.onMenuCreated, which MainActivity calls after building its menu.
 */
object YtSsiClassicEntry {
    fun onMenuCreated(searchItem: MenuItem, menu: Menu) {
        if (YtDesign.current() != YtDesign.STANDARD) return
        val searchView = searchItem.actionView as? SearchView ?: return
        val activity = activity(searchView.context) ?: return
        menu.removeItem(R.id.yt_ssi_classic)
        val item = menu.add(Menu.NONE, R.id.yt_ssi_classic, Menu.NONE, R.string.yt_r36_ssi_classic_action)
            .setIcon(R.drawable.ic_yt_ssi_classic)
            .setOnMenuItemClickListener {
                YtSsiUi.open(activity, searchView.query?.toString().orEmpty())
                true
            }
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        fun update() {
            item.isVisible = searchItem.isActionViewExpanded && onSearchScreen(activity)
        }
        update()
        // the field is in the window exactly while it is expanded
        searchView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = update()
            override fun onViewDetachedFromWindow(v: View) {
                item.isVisible = false
            }
        })
    }

    private fun onSearchScreen(activity: MainActivity): Boolean {
        val id = runCatching { activity.navController.currentDestination?.id }.getOrNull()
        return id == R.id.searchFragment || id == R.id.searchResultFragment
    }

    private fun activity(context: Context): MainActivity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is MainActivity) return c
            c = c.baseContext
        }
        return null
    }
}
