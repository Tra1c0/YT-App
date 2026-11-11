package com.github.libretube.ui.yt.news

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.libretube.api.JsonHelper
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.ui.yt.YtTestHooks

/**
 * YT: the stories of the News overlay on the device: the user's edits (merged cards, videos
 * taken out of a story; YtNewsStoryEdits under [EDITS_KEY]) and what was seen at the last look
 * (YtNewsSeenList under [SEEN_KEY]), both JSON, pruned to about a week. The cards themselves are
 * grouped by YtNewsStory whenever a deck's cards are read (YtNewsDeck.cards), so an edit shows at
 * once, also on the deck the open overlay holds.
 */
object YtNewsStories {
    const val EDITS_KEY = "yt_news_story_edits"
    const val SEEN_KEY = "yt_news_seen"

    /** The user's edits; snapshot state, so the cards read in composition follow them. */
    var edits by mutableStateOf(loadEdits())
        private set

    /** The deck the open overlay shows (YtNewsLayer sets it), for the videos behind a story. */
    @Volatile
    var showing: YtNewsDeck? = null

    /** The video of [id] on the deck shown (or the current one). */
    fun video(id: String): StreamItem? = showing?.videos?.get(id) ?: YtNews.deck.videos[id]

    /** The videos of [cards] become one story. */
    fun merge(vararg cards: YtNewsCard) {
        val ids = cards.flatMap { card -> card.items.map { it.id } }
        storeEdits(YtNewsStoryEditRules.merge(edits, ids, System.currentTimeMillis()))
        YtTestHooks.log("YtNews") { "story merge ids=${ids.joinToString(",")}" }
    }

    /** [id] leaves its story. */
    fun detach(id: String) {
        storeEdits(YtNewsStoryEditRules.detach(edits, id, System.currentTimeMillis()))
        YtTestHooks.log("YtNews") { "story detach id=$id" }
    }

    private fun storeEdits(next: YtNewsStoryEdits) {
        edits = next
        runCatching {
            if (next.isEmpty()) {
                PreferenceHelper.remove(EDITS_KEY)
            } else {
                PreferenceHelper.putString(EDITS_KEY, JsonHelper.json.encodeToString(YtNewsStoryEdits.serializer(), next))
            }
        }
    }

    private fun loadEdits(): YtNewsStoryEdits = runCatching {
        PreferenceHelper.getString(EDITS_KEY, "").takeIf { it.isNotBlank() }
            ?.let { JsonHelper.json.decodeFromString(YtNewsStoryEdits.serializer(), it) }
            ?.let { YtNewsStoryEditRules.prune(it, System.currentTimeMillis()) }
    }.getOrNull() ?: YtNewsStoryEdits()

    // "Neu seit deinem letzten Blick": the seen videos as they were when this opening began

    private var seen: YtNewsSeenList? = null
    private var session = -1
    private var baseline: Set<String> = emptySet()

    /** The videos of [ids] new since the last look, for the opening [opens] (YtNews.opens). */
    fun fresh(opens: Int, ids: List<String>): Set<String> {
        if (opens != session) {
            session = opens
            baseline = seenList().seen.keys.toHashSet()
        }
        return YtNewsSeenRules.fresh(baseline, ids)
    }

    /** The user looked at a card with [ids]. */
    fun markSeen(ids: List<String>) {
        val before = seenList()
        val next = YtNewsSeenRules.record(before, ids, System.currentTimeMillis())
        if (next === before) return
        seen = next
        runCatching { PreferenceHelper.putString(SEEN_KEY, JsonHelper.json.encodeToString(YtNewsSeenList.serializer(), next)) }
    }

    private fun seenList(): YtNewsSeenList = seen ?: (
        runCatching {
            PreferenceHelper.getString(SEEN_KEY, "").takeIf { it.isNotBlank() }
                ?.let { JsonHelper.json.decodeFromString(YtNewsSeenList.serializer(), it) }
        }.getOrNull() ?: YtNewsSeenList()
        ).also { seen = it }
}
