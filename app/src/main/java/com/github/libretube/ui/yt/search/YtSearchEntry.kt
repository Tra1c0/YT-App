package com.github.libretube.ui.yt.search

import android.content.Context
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.YtDrag
import com.github.libretube.ui.yt.YtPalette
import com.github.libretube.ui.yt.YtTestHooks
import com.github.libretube.ui.yt.YtTheme
import com.github.libretube.ui.yt.palette
import java.lang.ref.WeakReference

/*
 * YT: the search of the designs. Instead of the toolbar's search field and the separate
 * suggestions screen, every design (all but Standard) opens a search of its own over the
 * current screen, drawn in Compose: Glas grows a glass field out of the place that was tapped,
 * Frost thaws a panel open from the top, Editorial prints a search line, and so on
 * (YtSearchUi.kt). It shows the past searches and, as you type, the service's suggestions, and
 * hands the query to the same path as before (YtChrome.searchFor, MainActivity), so the results
 * screen, the history and links typed into the field work as they did.
 *
 * The layer sits in the activity's content above everything. While closed it lets every touch
 * through and only notes where the last one went down, so the field can grow out of the
 * button that was tapped, whichever screen drew it.
 */
object YtSearchEntry {
    /** The setting "Eigene Suche je Design" (yt_r12_search_prefs.xml). */
    const val KEY = "yt_search_entry"
    val enabled: Boolean get() = PreferenceHelper.getBoolean(KEY, true)

    private const val TAG = "yt_search_layer"

    /** The open search, or null. */
    internal var session by mutableStateOf<YtSearchSession?>(null)
        private set

    val isOpen: Boolean get() = session != null

    private var host: WeakReference<Host>? = null
    private var submitter: ((String) -> Unit)? = null
    private var down = Offset.Unspecified
    private var downAt = 0L
    private var filter: String? = null
    private var filterAt = 0L

    /** Adds the layer to [activity]; [submit] runs a search (the three steps of YtChrome.searchFor). */
    fun attach(activity: ComponentActivity, submit: (String) -> Unit) {
        if (YtDesign.current().palette == null) return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        submitter = submit
        (content.findViewWithTag<View>(TAG) as? Host)?.let {
            host = WeakReference(it)
            return
        }
        val layer = Host(activity).apply {
            tag = TAG
            // above the player container (20 dp), below the drag layer (24 dp)
            elevation = 23f * resources.displayMetrics.density
            outlineProvider = null
            setViewTreeLifecycleOwner(activity)
            setViewTreeSavedStateRegistryOwner(activity)
            setViewTreeViewModelStoreOwner(activity)
            setOpen(false)
        }
        val compose = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val open = session
                if (open != null) {
                    key(open) {
                        YtTheme(open.palette) { YtSearchLayer(open) }
                    }
                }
            }
        }
        layer.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        layer.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit

            override fun onViewDetachedFromWindow(v: View) {
                if (host?.get() !== v) return
                session?.let { finish(it) }
                // the search of a newer activity may already be there; this one lets go of its own
                host = null
                submitter = null
            }
        })
        content.addView(layer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        host = WeakReference(layer)
    }

    /**
     * Opens the design's search with [query] in the field. False when the design has none (or
     * it is switched off), so the caller opens the toolbar's search field instead.
     */
    fun open(query: String = ""): Boolean {
        if (!enabled) return false
        val layer = host?.get()?.takeIf { it.isAttachedToWindow } ?: return false
        val palette = YtDesign.current().palette ?: return false
        if (session != null) return true
        // a card or the search lens on its way keeps the finger
        if (YtDrag.session != null) return false
        val activity = layer.context as? ComponentActivity ?: return false
        val now = SystemClock.uptimeMillis()
        val origin = if (YtSearchRules.originFresh(downAt, now)) down else null
        val insets = ViewCompat.getRootWindowInsets(layer)
        // the bars' room, less what the layer does not cover (a window that is not edge to edge)
        val at = IntArray(2).also { layer.getLocationInWindow(it) }
        val windowHeight = layer.rootView?.height ?: 0
        val below = if (windowHeight > 0) (windowHeight - at[1] - layer.height).coerceAtLeast(0) else 0
        val top = ((insets?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0) - at[1]).coerceAtLeast(0)
        val bottom = ((insets?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0) - below).coerceAtLeast(0)
        val back = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = close()
        }
        val opened = YtSearchSession(palette, origin, query, top, bottom, back)
        session = opened
        layer.setOpen(true)
        // added on every opening, so it comes before the screens' own callbacks
        activity.onBackPressedDispatcher.addCallback(activity, back)
        YtTestHooks.log("YtSearch") { "open design=${YtDesign.current().name.lowercase()} origin=${origin != null}" }
        return true
    }

    /** Whether a finger went down on the screen just now ([withinMs]): a focus that follows comes from a tap. */
    fun touchedRecently(withinMs: Long = 500L): Boolean {
        val now = SystemClock.uptimeMillis()
        return downAt > 0 && now - downAt in 0..withinMs
    }

    /** Starts the closing animation; the layer goes away at its end ([finish]). */
    fun close() {
        val open = session ?: return
        if (open.closing) return
        open.closing = true
        YtTestHooks.log("YtSearch") { "close" }
    }

    /** Runs the search for [text] with the filter [filterKey] of YouTube neu, and closes. */
    internal fun submit(open: YtSearchSession, text: String, filterKey: String?) {
        val query = YtSearchRules.query(text) ?: return
        if (open.closing) return
        filter = YtSearchRules.filterFor(filterKey)
        filterAt = SystemClock.uptimeMillis()
        open.submitted = true
        open.closing = true
        YtTestHooks.log("YtSearch") { "submit filter=${filter ?: "all"}" }
        submitter?.invoke(query)
    }

    /**
     * The filter to start the results screen with, once: the one picked under the field of
     * YouTube neu just before, or null.
     */
    fun takeFilter(): String? {
        val picked = filter
        filter = null
        if (picked == null || SystemClock.uptimeMillis() - filterAt > YtSearchRules.ORIGIN_MAX_AGE_MS) return null
        return picked
    }

    /** The end of the closing animation. */
    internal fun finish(open: YtSearchSession) {
        if (session !== open) return
        session = null
        open.back.remove()
        host?.get()?.let {
            it.setOpen(false)
            it.blurBehind(0f)
        }
    }

    /** Blurs the screens under the layer by [radiusPx] (Android 12 and later; 0 removes it). */
    internal fun blurBehind(radiusPx: Float) {
        host?.get()?.blurBehind(radiusPx)
    }

    /** The layer's view: closed it passes every touch on and notes where it went down. */
    private class Host(context: Context) : FrameLayout(context) {
        private var open = false
        private val location = IntArray(2)

        fun setOpen(value: Boolean) {
            open = value
            isClickable = value
            descendantFocusability = if (value) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
            importantForAccessibility =
                if (value) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            // closed, the field gives the focus back to the screens
            if (!value) findFocus()?.clearFocus()
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (open) {
                super.dispatchTouchEvent(ev)
                // the search is modal: nothing below gets this finger
                return true
            }
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                getLocationInWindow(location)
                YtSearchEntry.down = Offset(location[0] + ev.x, location[1] + ev.y)
                YtSearchEntry.downAt = SystemClock.uptimeMillis()
            }
            return false
        }

        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean = if (open) super.dispatchGenericMotionEvent(event) else false

        private var blurred = 0f

        fun blurBehind(radiusPx: Float) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            val radius = if (radiusPx < 1f) 0f else radiusPx
            if (radius == blurred) return
            blurred = radius
            val content = parent as? ViewGroup ?: return
            val effect = if (radius > 0f) RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP) else null
            for (i in 0 until content.childCount) {
                val child = content.getChildAt(i)
                if (child === this) continue
                child.setRenderEffect(effect)
            }
        }
    }
}

/** One opening of the search, until its closing animation ends. */
@Stable
internal class YtSearchSession(
    val palette: YtPalette,
    /** Where the finger went down that opened it, in window px; null when not known. */
    val origin: Offset?,
    val initial: String,
    /** The status bar and navigation bar, in px. */
    val topInset: Int,
    val bottomInset: Int,
    val back: OnBackPressedCallback,
) {
    /** 0 closed, 1 open; the design's opening runs along it, and back again on closing. */
    val progress = Animatable(0f)
    var closing by mutableStateOf(false)

    /** Closed by a search (not by back): some designs leave differently then. */
    var submitted by mutableStateOf(false)
}
