package com.github.libretube.ui.yt

import android.util.LruCache
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.util.YtTitleCalm

/**
 * YT: video titles as the YT screens show them (Gesten & Interaktion → Startseite): capitals and
 * clickbait marks calmed by [YtTitleCalm]. Display only: what is stored, shared, saved to a
 * playlist or searched keeps the title as YouTube gave it, so callers pass the result straight
 * to a Text and nowhere else.
 */
object YtTitles {
    /** "Titel in normaler Schreibung" (capitals), on by default. */
    const val KEY_CASE = "yt_title_case"

    /** "Clickbait-Zeichen glätten" (!!!, ???, emoji runs), on by default. */
    const val KEY_MARKS = "yt_title_marks"

    // titles repeat across recompositions and screens; the work is small, but a list scrolls
    private val cache = LruCache<String, String>(512)

    /** [raw] as the YT screens show it; an empty text for null. */
    fun show(raw: String?): String {
        if (raw.isNullOrBlank()) return raw.orEmpty()
        val case = PreferenceHelper.getBoolean(KEY_CASE, true)
        val marks = PreferenceHelper.getBoolean(KEY_MARKS, true)
        if (!case && !marks) return raw
        val key = (if (case) "c" else "-") + (if (marks) "m" else "-") + raw
        cache.get(key)?.let { return it }
        return YtTitleCalm.calm(raw, fixCase = case, fixMarks = marks).also { cache.put(key, it) }
    }
}

/** This video's title as the YT screens show it (display only, see [YtTitles]). */
val StreamItem.ytTitle: String get() = YtTitles.show(title)

/** [raw] as the YT screens show a title (display only, see [YtTitles]). */
fun ytTitle(raw: String?): String = YtTitles.show(raw)
