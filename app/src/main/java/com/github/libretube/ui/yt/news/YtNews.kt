package com.github.libretube.ui.yt.news

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.YtChannelPause
import com.github.libretube.ui.yt.YtTestHooks
import com.github.libretube.ui.yt.feed.YtFeed
import com.github.libretube.ui.yt.feed.YtTopicSession
import com.github.libretube.ui.yt.rememberYtVisible

/**
 * What the News overlay shows: the [items] in order (YtNewsMerge) and the videos behind them by
 * id. [ready] is false until the home has built it once.
 */
class YtNewsDeck(
    val items: List<YtNewsItem>,
    val videos: Map<String, StreamItem>,
    val ready: Boolean = true,
) {
    private var grouped: Pair<YtNewsStoryEdits, List<YtNewsCard>>? = null

    /**
     * The cards: the items grouped into stories (YtNewsStory) with the user's edits. Reads the
     * edits as snapshot state, so a composition that shows the cards follows an edit at once.
     */
    val cards: List<YtNewsCard>
        get() {
            val edits = YtNewsStories.edits
            grouped?.let { (by, cards) -> if (by === edits) return cards }
            return YtNewsStory.cards(items, edits).also { grouped = edits to it }
        }

    fun video(card: YtNewsCard): StreamItem? = videos[card.lead.id]

    companion object {
        val NONE = YtNewsDeck(emptyList(), emptyMap(), ready = false)
    }
}

/**
 * YT: the state of the News overlay, shared by the home (which builds the deck and detects the
 * pinch, YtNewsGestures) and the overlay's layer above everything (YtNewsLayer). Snapshot state:
 * read it in composition or in snapshot flows.
 */
object YtNews {
    /** The one-time hint pill on the home ("Neu: … aufziehen"), seen once. */
    const val HINT_KEY = "yt_news_hint_seen"

    /** Whether the overlay is open (or opening). */
    var shown by mutableStateOf(false)
        private set

    /** While a pinch on the home is claimed but not committed: how far it is, 0..1 (live preview). */
    var preview by mutableFloatStateOf(0f)
        private set

    /** Where the window grows from: the fingers' centre, in window px (null: the screen's centre). */
    var origin by mutableStateOf<Offset?>(null)
        private set

    /** True while the overlay's layer takes the touches (open, and while it closes). */
    var layerUp by mutableStateOf(false)
        internal set

    /** What the overlay shows; the home keeps it current. */
    var deck by mutableStateOf(YtNewsDeck.NONE)
        internal set

    /** Counts the openings, so the pager starts at the first card each time. */
    var opens by mutableIntStateOf(0)
        private set

    /** A pinch on the home is claimed and at [progress] towards opening. */
    fun pinching(progress: Float, at: Offset?) {
        if (shown) return
        if (at != null) origin = at
        preview = progress.coerceIn(0f, 1f)
    }

    /** The pinch on the home ended without opening. */
    fun pinchEnded() {
        preview = 0f
    }

    fun open(at: Offset?) {
        if (shown) return
        origin = at
        preview = 0f
        opens++
        shown = true
        layerUp = true
        hintDone()
        YtTestHooks.log("YtNews") { "open cards=${deck.cards.size}" }
    }

    fun close() {
        if (!shown) return
        shown = false
        preview = 0f
        YtTestHooks.log("YtNews") { "close" }
    }

    /** Everything closed at once, without animation (a new activity window). */
    internal fun reset() {
        shown = false
        preview = 0f
        layerUp = false
    }

    /** Whether the hint pill is still due. */
    fun hintDue(): Boolean = !PreferenceHelper.getBoolean(HINT_KEY, false)

    fun hintDone() {
        if (hintDue()) PreferenceHelper.putBoolean(HINT_KEY, true)
    }

    /** A video as the merge rules see it; null without an id. */
    fun itemOf(video: StreamItem, topics: List<String> = emptyList()): YtNewsItem? {
        val id = video.url?.toID() ?: return null
        return YtNewsItem(
            id = id,
            title = video.title.orEmpty(),
            channelId = YtChannelPause.channelId(video.uploaderUrl),
            channelName = video.uploaderName,
            uploadedMs = video.uploaded,
            description = video.shortDescription.orEmpty(),
            durationSec = video.duration ?: 0L,
            isLive = video.isLive,
            isShort = video.isShort,
            isUpcoming = video.isUpcoming,
            topics = topics,
        )
    }
}

/**
 * Keeps [YtNews.deck] current from what the home has: the subscription feed ([subscriptions],
 * the local feed cache) and what the user's active topics' searches found for the home
 * (YtFeed.topicPools). Hidden videos, paused channels and "Gesehenes ausblenden" apply as on the
 * home (rememberYtVisible), the never-words of the Feed-Regler as in the feed. No network call.
 */
@Composable
fun YtNewsSource(subscriptions: List<StreamItem>?) {
    val feed = rememberYtVisible(subscriptions)
    val channels by YtNewsChannels.list.collectAsState()
    val profile by YtFeed.profile.collectAsState()
    val pools by YtFeed.topicPools.collectAsState()
    val session by YtTopicSession.current.collectAsState()
    val personal = profile.personal
    // the topics' finds, each with the names of the topics that found it
    val finds = remember(pools, personal.feedTopics, session) {
        val names = LinkedHashMap<String, MutableList<String>>()
        val videos = LinkedHashMap<String, StreamItem>()
        for (topic in YtFeed.activeTopics(personal, session)) {
            for (candidate in pools[topic.id].orEmpty()) {
                val id = candidate.item.id
                val video = YtFeed.topicVideo(id) ?: continue
                videos.putIfAbsent(id, video)
                names.getOrPut(id) { ArrayList() } += topic.name
            }
        }
        videos.values.toList() to names
    }
    val foundVisible = rememberYtVisible(finds.first)
    val deck = remember(feed, foundVisible, finds.second, channels, personal.neverWords, personal.feedTopics, session) {
        val videos = HashMap<String, StreamItem>()
        val subscriptionItems = feed.orEmpty().mapNotNull { video ->
            YtNews.itemOf(video)?.also { videos.putIfAbsent(it.id, video) }
        }
        val findItems = foundVisible.orEmpty().mapNotNull { video ->
            val id = video.url?.toID() ?: return@mapNotNull null
            YtNews.itemOf(video, finds.second[id].orEmpty())?.also { videos.putIfAbsent(it.id, video) }
        }
        val topics = YtFeed.activeTopics(personal, session)
            .filter { it.keywords.isNotEmpty() }
            .map { YtNewsTopic(it.name, it.keywords) }
        val items = YtNewsMerge.collect(
            subscriptions = subscriptionItems,
            isNewsChannel = { YtNewsChannelRules.isNews(channels, it.channelId, it.channelName) },
            topics = topics,
            topicFinds = findItems,
            never = personal.neverWords,
            nowMs = System.currentTimeMillis(),
        )
        // until the subscription feed loaded the overlay waits instead of saying "no news"
        YtNewsDeck(items, items.associate { it.id to videos.getValue(it.id) }, ready = feed != null)
    }
    LaunchedEffect(deck) { YtNews.deck = deck }
}
