package com.github.libretube.ui.yt

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.libretube.R
import com.github.libretube.api.obj.Streams
import com.github.libretube.ui.yt.feed.YtFeed
import com.github.libretube.ui.yt.feed.YtFeedTopics
import com.github.libretube.ui.yt.feed.YtTopicSession
import com.github.libretube.ui.yt.feed.YtTopicWatchLog
import com.github.libretube.ui.yt.feed.YtTopicWords
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One keyword offered for a topic while [videoId] plays. */
class YtTopicOffer internal constructor(
    val key: Long,
    val topicId: String,
    val topicName: String,
    val word: String,
    val videoId: String,
)

/**
 * YT: "Schlagwort „X“ zu <Thema> hinzufügen?" while a video of one of the user's topics plays.
 * The video page reports each video ([watch]); once it has played [COUNT_AFTER_MS] it counts for
 * its topics (the one the home marked it with, and each whose keyword its title or tags name,
 * YtFeedTopics.topicsOf). From the second counted video of a topic in this session on
 * (YtTopicWatchLog) it offers the best new keyword of the video (YtTopicWords), at most once
 * per video, in a pill on the video page and, with the player docked, above the mini player.
 * Never a dialog, nothing touches the playback. "×" turns the word down for good (stored in the
 * topic), letting the pill go by or swiping it away only skips it for this session.
 * Main thread only.
 */
object YtTopicSuggest {
    /** Played this long (ms, counted while it runs) a video counts as watched. */
    const val COUNT_AFTER_MS = 10_000L

    /** How long the pill stays when nothing is done (longer when the system asks for it). */
    const val SHOW_MS = 20_000L

    var offer by mutableStateOf<YtTopicOffer?>(null)
        private set

    /** The keyword just added, for a short confirmation in the same place. */
    var added by mutableStateOf<YtTopicOffer?>(null)
        private set

    private val scope = MainScope()
    private var job: Job? = null
    private var keys = 0L
    private var log = YtTopicWatchLog()

    /** Words not taken in this session per topic (let go or swiped away): not offered again until a new session. */
    private val skipped = HashMap<String, MutableSet<String>>()

    /** The page that reported last; only it ends the count when it goes ([leave]). */
    private var owner: Any? = null

    /** The video page [page] shows [videoId]; [position] reads the play position in ms. */
    fun watch(page: Any, videoId: String, streams: Streams, position: () -> Long) {
        owner = page
        job?.cancel()
        offer?.let { if (it.videoId != videoId) offer = null }
        if (YtFeed.activeTopics().isEmpty()) return
        job = scope.launch {
            // counted once it really played a little (a resumed video starts far in, so the
            // time it runs is counted, not where it stands); paused, it simply waits
            var played = 0L
            var last = runCatching(position).getOrDefault(0L)
            while (played < COUNT_AFTER_MS) {
                delay(1_000)
                val now = runCatching(position).getOrDefault(last)
                played += (now - last).coerceIn(0L, 2_500L)
                last = now
            }
            count(videoId, streams)
        }
    }

    private fun count(videoId: String, streams: Streams) {
        val session = YtTopicSession.current.value
        if (log.session != session) skipped.clear()
        log = log.inSession(session)
        val marked = YtFeed.focus.value[videoId]?.topicId
        val topics = YtFeedTopics.topicsOf(YtFeed.activeTopics(), streams.title, streams.tags, marked)
            .sortedByDescending { it.id == marked }
        if (topics.isEmpty()) return
        topics.forEach { log = log.watched(it.id, videoId) }
        for (topic in topics) {
            if (!log.mayAsk(topic.id, videoId)) continue
            val word = YtTopicWords.suggest(
                title = streams.title,
                tags = streams.tags,
                topic = topic,
                corpus = YtFeed.recentTitles(),
                channel = streams.uploader,
                skip = skipped[topic.id].orEmpty(),
            ) ?: continue
            log = log.asked(videoId)
            offer = YtTopicOffer(++keys, topic.id, topic.name, word, videoId)
            YtTestHooks.log("YtTopic") { "suggest topic=${topic.id} word=$word count=${log.count(topic.id)}" }
            return
        }
    }

    /** The video page [page] went away (closed): its count and offer end with it, unless a newer page took over. */
    fun leave(page: Any) {
        if (owner !== page) return
        owner = null
        job?.cancel()
        job = null
        offer = null
    }

    /** "Hinzufügen". */
    fun add(shown: YtTopicOffer) {
        if (offer !== shown) return
        offer = null
        YtFeed.updateTopic(shown.topicId) { YtFeedTopics.withKeyword(it, shown.word) }
        added = shown
        scope.launch {
            delay(2_500)
            if (added === shown) added = null
        }
    }

    /** "×": this word never again for this topic. */
    fun dismiss(shown: YtTopicOffer) {
        if (offer !== shown) return
        offer = null
        YtFeed.updateTopic(shown.topicId) { YtFeedTopics.dismiss(it, shown.word) }
    }

    /** Let go or swiped away: not again in this session. */
    fun skip(shown: YtTopicOffer) {
        if (offer !== shown) return
        offer = null
        skipped.getOrPut(shown.topicId) { HashSet() } += shown.word
    }
}

/**
 * The suggestion pill in the design's pill look (as the undo pill), with "Hinzufügen" and "×";
 * after adding, a short confirmation. Put it where it may push content down a little: it
 * expands in and out.
 */
@Composable
fun YtTopicSuggestPill(palette: YtPalette, modifier: Modifier = Modifier) {
    val offer = YtTopicSuggest.offer
    val added = YtTopicSuggest.added
    val look = remember(palette) { pillLook(palette) }
    val accessibility = LocalAccessibilityManager.current
    val timeout = remember(accessibility) {
        accessibility?.calculateRecommendedTimeoutMillis(YtTopicSuggest.SHOW_MS, containsIcons = true, containsText = true, containsControls = true)
            ?: YtTopicSuggest.SHOW_MS
    }
    // the last content stays while it folds away
    val shown = remember { arrayOfNulls<Pair<YtTopicOffer, Boolean>>(1) }
    if (offer != null) shown[0] = offer to false else if (added != null) shown[0] = added to true
    LaunchedEffect(offer?.key) {
        val current = offer ?: return@LaunchedEffect
        delay(timeout)
        YtTopicSuggest.skip(current)
    }
    AnimatedVisibility(
        visible = offer != null || added != null,
        enter = ytEnterTransition(expandVertically(spring(dampingRatio = 0.85f, stiffness = 420f)) + fadeIn(tween(160))),
        exit = ytExitTransition(shrinkVertically(tween(220, easing = YtEaseOut)) + fadeOut(tween(140))),
        modifier = modifier,
    ) {
        val (item, done) = shown[0] ?: return@AnimatedVisibility
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
            SwipeAway(onGone = { if (!done) YtTopicSuggest.skip(item) }) {
                PillRow(look, Modifier.testTag("yt_topic_suggest")) {
                    Text(
                        text = if (done) {
                            stringResource(R.string.yt_topic_suggest_added, item.word, item.topicName)
                        } else {
                            stringResource(R.string.yt_topic_suggest, item.word, item.topicName)
                        },
                        color = look.text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = look.style(FontWeight.Medium),
                        modifier = Modifier
                            .widthIn(max = 240.dp)
                            .padding(end = if (done) 10.dp else 4.dp, top = 6.dp, bottom = 6.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    if (!done) {
                        PillAction(look, stringResource(R.string.yt_topic_suggest_add), "yt_topic_suggest_add") { YtTopicSuggest.add(item) }
                        Icon(
                            painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.yt_topic_suggest_dismiss),
                            tint = look.text.copy(alpha = 0.8f),
                            modifier = Modifier
                                .testTag("yt_topic_suggest_dismiss")
                                .clip(CircleShape)
                                .clickable { YtTopicSuggest.dismiss(item) }
                                .padding(12.dp)
                                .size(16.dp),
                        )
                    }
                }
            }
        }
    }
}
