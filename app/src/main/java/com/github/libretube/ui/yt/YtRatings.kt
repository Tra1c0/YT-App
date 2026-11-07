package com.github.libretube.ui.yt

import com.github.libretube.api.JsonHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.ui.yt.account.YtAccount
import com.github.libretube.ui.yt.account.YtInnerTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** How the user rated a video. */
enum class YtRate { NONE, LIKE, DISLIKE }

/**
 * YT: the like and dislike of the video page.
 *
 * Every rating is kept on the device (a small JSON map of video id to rating), so the button can
 * show it again. With a YouTube account signed in, it is also sent to YouTube through the same
 * like / dislike / removelike requests the web client makes, with the account's session. Without
 * one it stays on the device only, and the user is told so once.
 * YouTube shows no dislike counts any more, so none is shown or made up.
 */
object YtRatings {
    private const val KEY = "yt_ratings"
    private const val LOCAL_HINT = "yt_ratings_local_hint"

    private fun load(): Map<String, String> = runCatching {
        JsonHelper.json.decodeFromString<Map<String, String>>(PreferenceHelper.getString(KEY, "{}"))
    }.getOrDefault(emptyMap())

    fun local(videoId: String): YtRate = when (load()[videoId]) {
        "like" -> YtRate.LIKE
        "dislike" -> YtRate.DISLIKE
        else -> YtRate.NONE
    }

    fun storeLocal(videoId: String, rate: YtRate) {
        val map = load().toMutableMap()
        when (rate) {
            YtRate.LIKE -> map[videoId] = "like"
            YtRate.DISLIKE -> map[videoId] = "dislike"
            YtRate.NONE -> map.remove(videoId)
        }
        PreferenceHelper.putString(KEY, JsonHelper.json.encodeToString(map))
    }

    /** Whether the "only on this device" hint has been shown. */
    var localHintShown: Boolean
        get() = PreferenceHelper.getBoolean(LOCAL_HINT, false)
        set(value) = PreferenceHelper.putBoolean(LOCAL_HINT, value)

    /** Whether a rating can go to YouTube: an account is signed in and its session can be read. */
    val atYouTube: Boolean get() = YtAccount.signedIn && YtAccount.session() != null

    // one prepared connection per sign-in, so a rating does not load youtube.com's page every time
    private val lock = Mutex()
    private var api: YtInnerTube? = null
    private var apiEpoch = -1

    private suspend fun <T> withApi(block: (YtInnerTube) -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            val epoch = YtAccount.epoch
            val cookies = YtAccount.session() ?: throw IOException("not signed in")
            val current = api?.takeIf { apiEpoch == epoch } ?: YtInnerTube(cookies) {
                if (YtAccount.epoch == epoch) YtAccount.saveSession(it)
            }.also {
                if (!it.prepare()) throw IOException("YouTube does not see the sign-in")
                api = it
                apiEpoch = epoch
            }
            block(current)
        }
    }

    /** The account's rating of [videoId] at YouTube, or null when it cannot be read. */
    suspend fun remote(videoId: String): YtRate? = runCatching {
        when (withApi { it.likeStatus(videoId) }) {
            "LIKE" -> YtRate.LIKE
            "DISLIKE" -> YtRate.DISLIKE
            "INDIFFERENT" -> YtRate.NONE
            else -> null
        }
    }.getOrNull()

    /** Sends [rate] for [videoId] to YouTube. */
    suspend fun send(videoId: String, rate: YtRate): Result<Unit> = runCatching {
        val endpoint = when (rate) {
            YtRate.LIKE -> "like"
            YtRate.DISLIKE -> "dislike"
            YtRate.NONE -> "removelike"
        }
        withApi { it.rate(videoId, endpoint) }
    }
}
