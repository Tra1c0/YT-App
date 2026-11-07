package com.github.libretube.ui.base

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowCompat
import com.github.libretube.R
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.helpers.LocaleHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.ThemeHelper
import com.github.libretube.helpers.ThemeHelper.getThemeMode
import com.github.libretube.helpers.WindowHelper
import com.github.libretube.helpers.YtThemeRefresh
import com.github.libretube.ui.dialogs.RequireRestartDialog
import java.util.Locale

/**
 * Activity that applies the LibreTube theme and the in-app language
 */
open class BaseActivity : AppCompatActivity() {
    open val isDialogActivity: Boolean = false

    // YT: read each time (was once per activity), so the emulator check can turn the app
    val screenOrientationPref: Int get() {
        val orientationPref = PreferenceHelper.getString(
            PreferenceKeys.ORIENTATION,
            resources.getString(R.string.config_default_orientation_pref)
        )
        return when (orientationPref) {
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

    /** YT: the look this activity was built with (see [YtThemeRefresh]). */
    private var ytThemeGeneration = YtThemeRefresh.generation

    /** YT: the look for which this activity already asked for a restart, if any. */
    private var ytAskedRestartFor = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        // YT: a start may choose the design, e.g. an emulator run: am start ... --es yt_design pop
        // only in the emulator and only the key of a real design; on a phone no other app can
        // change the design this way
        intent?.takeIf { com.github.libretube.helpers.YtGuard.isEmulator }
            ?.getStringExtra(com.github.libretube.helpers.YtDesign.PREF_KEY)
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
        ytBuiltDark = com.github.libretube.helpers.YtDesignDark.active
        ytSyncBarIcons()
    }

    /** YT: whether this activity was built with the dark palette of a light design. */
    private var ytBuiltDark: Boolean? = null

    /**
     * YT: light designs pick dark or light system bar icons from their current mode; the
     * edge-to-edge setup above may run before the night mode is in the configuration.
     */
    private fun ytSyncBarIcons() {
        if (!com.github.libretube.helpers.YtDesign.current().light) return
        val light = !com.github.libretube.helpers.YtDesignDark.active
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    override fun onResume() {
        super.onResume()
        ytApplyNewLook()
    }

    /**
     * YT: a design, theme or animation setting changed while this activity waited in the
     * background; rebuild it so it shows the new look, instead of restarting the app. Where
     * that is not safe right now (see [ytCanRecreateNow]), ask once for the restart as before.
     */
    private fun ytApplyNewLook() {
        val now = YtThemeRefresh.generation
        if (!YtThemeRefresh.isStale(ytThemeGeneration)) return
        if (ytCanRecreateNow()) {
            ytThemeGeneration = now
            recreate()
        } else if (ytAskedRestartFor != now) {
            ytAskedRestartFor = now
            RequireRestartDialog().show(supportFragmentManager, RequireRestartDialog::class.java.name)
        }
    }

    /**
     * YT: whether a recreate keeps what the user is doing. Not while YT's floating window or
     * picture-in-picture shows the video: both hang on this activity's player.
     */
    protected open fun ytCanRecreateNow(): Boolean =
        !com.github.libretube.ui.yt.YtFloatingPlayer.isShowing && !isInPictureInPictureMode

    override fun onNightModeChanged(mode: Int) {
        super.onNightModeChanged(mode)
        ThemeHelper.applyOverlays(this)
        // YT: a light design on "system" whose mode flipped: the Compose screens took their
        // palette when they were built, so rebuild this activity with the other palette
        val built = ytBuiltDark ?: return
        if (built != com.github.libretube.helpers.YtDesignDark.active) {
            ytBuiltDark = com.github.libretube.helpers.YtDesignDark.active
            if (ytCanRecreateNow()) recreate() else ytSyncBarIcons()
        }
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

            // YT: dark designs fix the dark mode; light designs follow their own choice of
            // light, dark or system (YtDesignDark)
            val ytDesign = com.github.libretube.helpers.YtDesign.current()
            val uiPref = if (ytDesign != com.github.libretube.helpers.YtDesign.STANDARD) {
                com.github.libretube.helpers.YtDesignDark.themeMode(
                    ytDesign.light,
                    com.github.libretube.helpers.YtDesignDark.choice()
                )
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
