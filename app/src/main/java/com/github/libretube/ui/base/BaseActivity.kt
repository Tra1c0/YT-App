package com.github.libretube.ui.base

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.github.libretube.R
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.helpers.LocaleHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.ThemeHelper
import com.github.libretube.helpers.ThemeHelper.getThemeMode
import com.github.libretube.helpers.WindowHelper
import java.util.Locale

/**
 * Activity that applies the LibreTube theme and the in-app language
 */
open class BaseActivity : AppCompatActivity() {
    open val isDialogActivity: Boolean = false

    val screenOrientationPref by lazy {
        val orientationPref = PreferenceHelper.getString(
            PreferenceKeys.ORIENTATION,
            resources.getString(R.string.config_default_orientation_pref)
        )
        when (orientationPref) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
            "auto" -> ActivityInfo.SCREEN_ORIENTATION_USER
            else -> throw IllegalArgumentException()
        }
    }

    /**
     * Whether the phone of the user has a cutout like a notch or not
     */
    var hasCutout: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // YT: a start may choose the design, e.g. an emulator run: am start ... --es yt_design pop
        // only the key of a real design is taken; anything else from outside is ignored
        intent?.getStringExtra(com.github.libretube.helpers.YtDesign.PREF_KEY)
            ?.takeIf { key -> com.github.libretube.helpers.YtDesign.entries.any { it.key == key } }
            ?.let { PreferenceHelper.putString(com.github.libretube.helpers.YtDesign.PREF_KEY, it) }
        // set the app theme (e.g. Material You)
        ThemeHelper.updateTheme(this)
        if (isDialogActivity) ThemeHelper.applyDialogActivityTheme(this)

        // enable auto-rotation if enabled
        requestOrientationChange()

        // wait for the window decor view to be drawn before detecting display cutouts
        window.decorView.setOnApplyWindowInsetsListener { view, insets ->
            hasCutout = WindowHelper.hasCutout(view)
            window.decorView.onApplyWindowInsets(insets)
        }
        enableEdgeToEdge()

        super.onCreate(savedInstanceState)

        // YT: switching day/night, AppCompat applies the base theme again on top of everything;
        // put the design back on top (this was why light designs showed the base colors)
        ThemeHelper.applyOverlays(this)
    }

    override fun onNightModeChanged(mode: Int) {
        super.onNightModeChanged(mode)
        ThemeHelper.applyOverlays(this)
    }

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase)

        val configuration = Configuration().apply {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                // TODO: remove this case in the future
                @Suppress("DEPRECATION")
                val locale = LocaleHelper.getAppLocale()
                Locale.setDefault(locale)
                setLocale(locale)
            }

            // YT: dark by default; the designs are dark only
            // YT: each design fixes the theme mode, light or dark
            val ytDesign = com.github.libretube.helpers.YtDesign.current()
            val uiPref = if (ytDesign != com.github.libretube.helpers.YtDesign.STANDARD) {
                if (ytDesign.light) "L" else "D"
            } else {
                PreferenceHelper.getString(PreferenceKeys.THEME_MODE, "D")
            }
            AppCompatDelegate.setDefaultNightMode(getThemeMode(uiPref))
        }

        applyOverrideConfiguration(configuration)
    }

    /**
     * Rotate the screen according to the app orientation preference
     */
    open fun requestOrientationChange() {
        requestedOrientation = screenOrientationPref
    }
}
