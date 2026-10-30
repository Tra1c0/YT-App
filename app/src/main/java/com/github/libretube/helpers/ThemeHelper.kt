package com.github.libretube.helpers

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.text.Spanned
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.text.HtmlCompat
import androidx.core.text.parseAsHtml
import com.github.libretube.R
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.ui.adapters.IconsSheetAdapter
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors

object ThemeHelper {
    /**
     * YT: package of the launcher activity aliases (".Default" etc. in the manifest). They resolve
     * against the namespace, which differs from the application id app.tra1c0.yt. A literal,
     * because R8 renames classes like BuildConfig in release builds.
     */
    private const val ALIAS_PACKAGE = "com.github.libretube"

    /**
     * Set the theme, including accent color and night mode
     */
    fun updateTheme(activity: AppCompatActivity) {
        var accentColor = PreferenceHelper.getString(PreferenceKeys.ACCENT_COLOR, "")
        if (accentColor.isEmpty()) {
            // YT: pink is the default accent
            accentColor = "pink"
            PreferenceHelper.putString(PreferenceKeys.ACCENT_COLOR, accentColor)
        }

        activity.setTheme(getTheme(accentColor))
        if (accentColor == "my") DynamicColors.applyToActivityIfAvailable(activity)

        applyOverlays(activity)
    }

    /** Pure theme and the selected design on top of the base theme. */
    fun applyOverlays(activity: AppCompatActivity) {
        val pureThemeEnabled = PreferenceHelper.getBoolean(
            PreferenceKeys.PURE_THEME,
            false
        )
        if (pureThemeEnabled) activity.theme.applyStyle(R.style.Pure, true)

        // YT: a selected design overrides accent and pure theme with its own palette and type
        YtDesign.current().overlay?.let { activity.theme.applyStyle(it, true) }
    }

    /**
     * Update the accent color of the app and apply dynamic colors if needed
     */
    private fun getTheme(accentColor: String): Int {
        return when (accentColor) {
            // set the accent color, use the pure black/white theme if enabled
            "my" -> R.style.BaseTheme
            "pink" -> R.style.Theme_Pink
            "red" -> R.style.Theme_Red
            "blue" -> R.style.Theme_Blue
            "yellow" -> R.style.Theme_Yellow
            "green" -> R.style.Theme_Green
            "purple" -> R.style.Theme_Purple
            "monochrome" -> R.style.Theme_Monochrome
            "violet" -> R.style.Theme_Violet
            else -> throw IllegalArgumentException()
        }
    }

    fun applyDialogActivityTheme(activity: Activity) {
        activity.theme.applyStyle(R.style.DialogActivity, true)
    }

    /**
     * set the theme mode (light, dark, auto)
     */
    fun getThemeMode(themeMode: String): Int {
        return when (themeMode) {
            "A" -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            "L" -> AppCompatDelegate.MODE_NIGHT_NO
            "D" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
    }

    /**
     * change the app icon
     */
    fun changeIcon(context: Context, newLogoActivityAlias: String) {
        // YT: enable the new icon first, so there is always one launcher entry
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context.packageName, "$ALIAS_PACKAGE.$newLogoActivityAlias"),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )

        // Disable Old Icon(s)
        for (appIcon in IconsSheetAdapter.availableIcons) {
            if (appIcon.activityAlias == newLogoActivityAlias) continue
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context.packageName, "$ALIAS_PACKAGE.${appIcon.activityAlias}"),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
    }

    /**
     * Get a color by a color resource attr
     */
    fun getThemeColor(context: Context, colorCode: Int) =
        MaterialColors.getColor(context, colorCode, Color.TRANSPARENT)

    /**
     * Get the styled app name
     */
    fun getStyledAppName(context: Context): Spanned {
        val colorPrimary = getThemeColor(context, androidx.appcompat.R.attr.colorPrimary)
        val hexColor = "#%06X".format(0xFFFFFF and colorPrimary)
        return YtDesign.current().wordmarkHtml(hexColor)
            .parseAsHtml(HtmlCompat.FROM_HTML_MODE_COMPACT)
    }

    fun isDarkMode(context: Context): Boolean {
        val darkModeFlag =
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return darkModeFlag == Configuration.UI_MODE_NIGHT_YES
    }
}
