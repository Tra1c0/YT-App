package com.github.libretube.ui.yt.builder

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import androidx.preference.Preference

/** YT: the settings row under the design picker that opens the theme builder's editor. */
class YtThemeEditorPreference @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : Preference(context, attrs) {
    override fun onClick() {
        context.startActivity(Intent(context, YtThemeEditorActivity::class.java))
    }
}
