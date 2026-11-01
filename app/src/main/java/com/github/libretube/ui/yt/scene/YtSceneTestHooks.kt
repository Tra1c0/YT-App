package com.github.libretube.ui.yt.scene

import android.os.Bundle
import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import com.github.libretube.api.JsonHelper
import com.github.libretube.api.obj.CommentsPage
import com.github.libretube.helpers.SceneMode
import com.github.libretube.ui.activities.AbstractPlayerHostActivity
import com.github.libretube.ui.models.CommentPageCache
import com.github.libretube.ui.models.CommentsViewModel
import com.github.libretube.ui.yt.YtTestHandler
import com.github.libretube.ui.yt.YtTestHooks

/**
 * YT (package E): the emulator start parameters of the comment checks (scripts/gestures/e.sh),
 * read by YtTestHooks. The test video is a download without comments, so the checks bring their own:
 * - `--es yt_comments_fixture <json or base64 of it>`: a list of comment pages (CommentsPage)
 *   that the comment cache returns for every video instead of the network; their nextpage keys
 *   are made here, so the pages chain in the order given. The pages read so far are dropped and
 *   nothing counts as seen past the position now.
 * - `--es yt_comments_scene off|spoiler|scene`: the mode, as the chips set it.
 * - `--ez yt_comments_open true`: opens the classic comment sheet (the designs hide its button).
 */
object YtSceneTestHooks : YtTestHandler {
    private const val TAG = "YtScene"
    private const val EXTRA_FIXTURE = "yt_comments_fixture"
    private const val EXTRA_MODE = "yt_comments_scene"
    private const val EXTRA_OPEN = "yt_comments_open"
    private const val KEY_PREFIX = "fixture:"

    override val keys = listOf(EXTRA_FIXTURE, EXTRA_MODE, EXTRA_OPEN)

    override suspend fun test(host: AbstractPlayerHostActivity, extras: Bundle) {
        val scene = ViewModelProvider(host)[CommentsViewModel::class.java].scene
        extras.getString(EXTRA_MODE)?.let { key ->
            scene.setMode(SceneMode.of(key))
            Log.i(TAG, "test mode=${scene.chosenMode.value.key}")
        }
        extras.getString(EXTRA_FIXTURE)?.let { value ->
            val pages = runCatching { parse(value) }
                .onFailure { Log.w(TAG, "comments fixture not read", it) }
                .getOrNull()
            if (pages != null) {
                CommentPageCache.fixture = { _, key -> pageAt(pages, key) }
                scene.restartForTest()
                Log.i(TAG, "comments fixture: ${pages.size} pages, ${pages.sumOf { it.comments.size }} comments")
            }
        }
        if (extras.getBoolean(EXTRA_OPEN, false)) {
            val opened = host.runOnPlayerFragment {
                if (view == null) return@runOnPlayerFragment false
                binding.commentsToggle.performClick()
                true
            }
            Log.i(TAG, "comments sheet opened=$opened")
        }
        YtTestHooks.log(TAG) { "test unlocked=${scene.playback().unlockedUpToMs} pos=${scene.playback().positionMs}" }
    }

    private fun parse(value: String): List<CommentsPage> {
        val text = value.trim().let { raw ->
            if (raw.startsWith("[") || raw.startsWith("{")) raw
            else String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8)
        }.trim()
        return if (text.startsWith("{")) {
            listOf(JsonHelper.json.decodeFromString(CommentsPage.serializer(), text))
        } else {
            JsonHelper.json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(CommentsPage.serializer()),
                text,
            )
        }
    }

    // page 0 for no key, then the keys made here
    private fun pageAt(pages: List<CommentsPage>, key: String?): CommentsPage? {
        val index = if (key == null) 0 else key.removePrefix(KEY_PREFIX).toIntOrNull() ?: return null
        val page = pages.getOrNull(index) ?: return null
        return page.copy(nextpage = if (index + 1 < pages.size) "$KEY_PREFIX${index + 1}" else null)
    }
}
