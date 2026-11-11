package com.github.libretube.ui.yt.feed

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.github.libretube.api.JsonHelper
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.TrendingCategory
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.YtChannelPause
import com.github.libretube.helpers.YtHearts
import com.github.libretube.ui.yt.YtTestHooks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/**
 * The lists the home already has, handed to the feed: the subscription feed, the charts by
 * category (YtDiscovery), the chart of TRENDING_CATEGORY (HomeViewModel.trending) and the videos
 * to go on with.
 */
data class YtFeedInput(
    val subscriptions: List<StreamItem>?,
    val charts: Map<TrendingCategory, List<StreamItem>>,
    val trending: List<StreamItem>?,
    val continueWatching: List<StreamItem>?,
)

/**
 * What a pull to refresh changes in the ranking: the top of the last refreshes ([seen], the new
 * top gets other videos) and a number that nudges near-equal places ([shuffle], 0: none).
 */
data class YtFeedFreshness(val seen: YtSeenMemory = YtSeenMemory(), val shuffle: Long = 0L)

/**
 * The ranked feed: the videos in order and, by video id, why each one is there and which of the
 * user's own topics it counts for.
 */
data class YtFeedResult(
    val videos: List<StreamItem>,
    val reasons: Map<String, List<YtReason>>,
    val focus: Map<String, YtTopicMark> = emptyMap(),
)

/** What the feed knows locally, loaded in the background (watch positions, history, groups). */
data class YtLocalSignals(
    val positionsMs: Map<String, Long> = emptyMap(),
    val channelViews: Map<String, Int> = emptyMap(),
    val groups: Map<String, Set<String>> = emptyMap(),
)

/**
 * YT: the home feed after the user's own rules ("Feed-Regler") and the intent picked on opening.
 * Everything runs on the phone: there is no server-side personalization, the sources are the
 * ones the app always had (subscriptions, charts, live) plus searches for the user's topics and
 * the related videos of what was watched last. Stored as JSON under [KEY] and [INTENT_KEY].
 */
object YtFeed {
    const val KEY = "yt_feed_rules"
    const val INTENT_KEY = "yt_intent"
    const val SEEN_KEY = "yt_feed_seen"

    // the extra sources: how many videos each search or related list gives, and how long they keep
    private const val PER_TOPIC = 12
    private const val RELATED_FROM = 2
    private const val RELATED_POOL = 10
    private const val PER_RELATED = 10
    private const val EXTRAS_TTL_MS = 30L * 60 * 1000
    private const val LOAD_TIMEOUT_MS = 15_000L

    // the user's own topics: results per keyword search, keywords searched per topic and load,
    // and searches at a time over all topics
    private const val PER_KEYWORD = 10
    private const val SEARCHES_PER_TOPIC = 4
    private const val PARALLEL_SEARCHES = 4

    private val scope = MainScope()

    private val profileFlow = MutableStateFlow(loadProfile())
    val profile: StateFlow<YtFeedProfile> get() = profileFlow

    private val intentFlow = MutableStateFlow(loadIntent())

    /** The intent picked last; null before the first pick (the home is then "Einfach stöbern"). */
    val intent: StateFlow<YtIntent?> get() = intentFlow

    private val extrasFlow = MutableStateFlow<Map<YtFeedSource, List<YtFeedItem>>>(emptyMap())

    /** Topic searches, related videos and the live chart, by source, ready for the engine. */
    val extras: StateFlow<Map<YtFeedSource, List<YtFeedItem>>> get() = extrasFlow

    /** The videos of [extras], by id. */
    private val extraVideos = HashMap<String, StreamItem>()

    private val signalsFlow = MutableStateFlow(YtLocalSignals())
    val signals: StateFlow<YtLocalSignals> get() = signalsFlow

    private val reasonsFlow = MutableStateFlow<Map<String, List<YtReason>>>(emptyMap())

    /** Why each video of the last ranked feed is there ("Warum sehe ich das?"). */
    val reasons: StateFlow<Map<String, List<YtReason>>> get() = reasonsFlow

    private val freshFlow = MutableStateFlow(YtFeedFreshness(loadSeen()))

    /** The seen-on-top memory and the shuffle of the last pull to refresh. */
    val freshness: StateFlow<YtFeedFreshness> get() = freshFlow

    private val focusFlow = MutableStateFlow<Map<String, YtTopicMark>>(emptyMap())

    /** The videos of the last ranked feed that count for one of the user's topics (chip on the card, suggestion). */
    val focus: StateFlow<Map<String, YtTopicMark>> get() = focusFlow

    private val topicPoolsFlow = MutableStateFlow<Map<String, List<YtTopicCandidate>>>(emptyMap())

    /** What the keyword searches found per topic id, kept for the session (until a pull or new keywords). */
    val topicPools: StateFlow<Map<String, List<YtTopicCandidate>>> get() = topicPoolsFlow

    /** The videos of [topicPools], by id. */
    private val topicVideos = HashMap<String, StreamItem>()

    /** A video a topic search found ([topicPools]), by its id; null when it is no longer kept. */
    fun topicVideo(id: String): StreamItem? = topicVideos[id]

    /** Per topic id: the keywords (folded) and the turn its pool was loaded for. */
    private val topicLoaded = HashMap<String, Pair<String, Int>>()
    private val topicJobs = HashMap<String, Job>()
    private val searchGate = Semaphore(PARALLEL_SEARCHES)

    /** The top of the feed as last ranked for the home: what a refresh remembers. */
    private var lastTop: List<YtSeen> = emptyList()

    /** Counts the forced reloads: which part of each search and of the history the extras use. */
    private var turn = 0

    private var extrasJob: Job? = null
    private var extrasKey: String? = null
    private var extrasAt = 0L
    private var signalsJob: Job? = null

    fun current(): YtIntent = intentFlow.value ?: YtIntent.BROWSE

    fun rules(): YtFeedRules = profileFlow.value.rulesFor(current())

    fun setIntent(intent: YtIntent) {
        intentFlow.value = intent
        PreferenceHelper.putString(INTENT_KEY, intent.name)
        YtTestHooks.log("YtIntent") { "intent=$intent" }
    }

    fun updateRules(intent: YtIntent = current(), transform: (YtFeedRules) -> YtFeedRules) {
        val profile = profileFlow.value
        save(profile.withRules(intent, transform(profile.rulesFor(intent))))
    }

    fun updatePersonal(transform: (YtFeedPersonal) -> YtFeedPersonal) {
        val profile = profileFlow.value
        save(profile.copy(personal = transform(profile.personal)))
    }

    /** "Zurücksetzen": the intent's preset again. */
    fun reset(intent: YtIntent = current()) = save(profileFlow.value.reset(intent))

    /** Sets the rule of [video]'s channel (null: normal again). */
    fun setChannel(video: StreamItem, rule: YtChannelRule?) {
        val key = channelKey(video) ?: return
        setChannel(key, video.uploaderName, rule)
    }

    fun setChannel(key: String, name: String?, rule: YtChannelRule?) {
        updatePersonal { personal ->
            if (rule == null) {
                personal.copy(channels = personal.channels - key, channelNames = personal.channelNames - key)
            } else {
                personal.copy(
                    channels = personal.channels + (key to rule),
                    channelNames = if (name.isNullOrBlank()) personal.channelNames else personal.channelNames + (key to name),
                )
            }
        }
    }

    fun channelRule(video: StreamItem): YtChannelRule? = channelKey(video)?.let { profileFlow.value.personal.channels[it] }

    /** How the per-channel rules know a channel: the id in its URL, else its name. */
    fun channelKey(video: StreamItem): String? =
        YtChannelPause.channelId(video.uploaderUrl) ?: video.uploaderName?.takeIf { it.isNotBlank() }

    /** The user's topics that are on and not expired now. */
    fun activeTopics(
        personal: YtFeedPersonal = profileFlow.value.personal,
        session: Long = YtTopicSession.current.value,
        nowMs: Long = System.currentTimeMillis(),
    ): List<YtFeedTopic> = personal.feedTopics.filter { YtFeedTopics.active(it, nowMs, session) }

    /** Changes the topic [id] (nothing if it is gone). */
    fun updateTopic(id: String, transform: (YtFeedTopic) -> YtFeedTopic) {
        updatePersonal { personal -> personal.copy(feedTopics = personal.feedTopics.map { if (it.id == id) transform(it) else it }) }
    }

    /** The titles of the feed as last ranked, for how rare a suggested keyword is. */
    fun recentTitles(): List<String> = lastTop.map { it.title }

    /** Whether videos open as audio now (music intent with "Musik als Audio"). */
    fun audioNow(): Boolean = current() == YtIntent.MUSIC && rules().musicAsAudio

    private fun save(profile: YtFeedProfile) {
        profileFlow.value = profile
        runCatching { PreferenceHelper.putString(KEY, JsonHelper.json.encodeToString(YtFeedProfile.serializer(), profile)) }
        YtTestHooks.log("YtFeed") { "rules saved intent=${current()}" }
    }

    private fun loadProfile(): YtFeedProfile = runCatching {
        PreferenceHelper.getString(KEY, "").takeIf { it.isNotBlank() }
            ?.let { JsonHelper.json.decodeFromString(YtFeedProfile.serializer(), it) }
    }.getOrNull() ?: YtFeedProfile()

    private fun loadIntent(): YtIntent? =
        PreferenceHelper.getString(INTENT_KEY, "").let { name -> YtIntent.entries.firstOrNull { it.name == name } }

    private fun loadSeen(): YtSeenMemory = runCatching {
        YtSeenMemory.decode(PreferenceHelper.getString(SEEN_KEY, "")).within(System.currentTimeMillis())
    }.getOrNull() ?: YtSeenMemory()

    /**
     * A pull to refresh: remembers channel, title and id of the first 50 videos the home showed
     * (with the two refreshes before, for half a day), so the new top is made of other videos,
     * and picks a new shuffle. The home ranks again at once with what it has; the sources that
     * load again ([loadExtras] with force, the charts, the subscriptions) follow.
     */
    fun markRefresh() {
        val now = System.currentTimeMillis()
        val top = lastTop
        val memory = freshFlow.value.seen.remember(top, now)
        var shuffle = Random.nextLong()
        if (shuffle == 0L) shuffle = 1L
        freshFlow.value = YtFeedFreshness(memory, shuffle)
        turn++
        runCatching { PreferenceHelper.putString(SEEN_KEY, memory.encode()) }
        YtTestHooks.log("YtFeed") {
            "refresh remembered=${top.size} refreshes=${memory.refreshes.size} top=" +
                top.take(10).joinToString(" | ") { "${it.channel}: ${it.title}" }
        }
    }

    /** The chart the YouTube design's topic row picked: the engine lifts it a little instead of fighting it. */
    fun chosenChart(): YtFeedSource? =
        sourceOf(PreferenceHelper.getString(PreferenceKeys.TRENDING_CATEGORY, TrendingCategory.GAMING.name))

    fun sourceOf(category: String?): YtFeedSource? = when (category) {
        TrendingCategory.MUSIC.name -> YtFeedSource.MUSIC
        TrendingCategory.PODCASTS.name -> YtFeedSource.PODCASTS
        TrendingCategory.TRAILERS.name -> YtFeedSource.TRAILERS
        TrendingCategory.GAMING.name -> YtFeedSource.GAMING
        TrendingCategory.LIVE.name -> YtFeedSource.LIVE
        else -> null
    }

    // ---------------------------------------------------------------------------------------------
    // Loading

    /**
     * Loads what the rules need beyond the home's own lists, at most every half hour unless the
     * topics changed or [force]: one search per topic, the related videos of the last watched
     * videos (one full lookup each) and the live chart. Each part that fails is just left out.
     */
    fun loadExtras(context: Context, force: Boolean = false) {
        val rules = rules()
        val topics = if (rules.weight(YtFeedSource.TOPICS) > 0) rules.topics.take(YtFeedRules.MAX_TOPICS) else emptyList()
        val related = rules.weight(YtFeedSource.RELATED) > 0
        val live = rules.weight(YtFeedSource.LIVE) > 0
        val key = "${topics.joinToString("|") { it.query }}#$related#$live"
        val now = System.currentTimeMillis()
        // the user's own topics keep their own cache: loaded again on a pull or new keywords
        loadTopics(force)
        if (!force && key == extrasKey && now - extrasAt < EXTRAS_TTL_MS) return
        extrasKey = key
        extrasAt = now
        extrasJob?.cancel()
        val app = context.applicationContext
        // a forced reload takes another part of each search and of the history than the last one
        val round = if (force) turn else 0
        extrasJob = scope.launch {
            val found = HashMap<String, StreamItem>()
            val lists = withContext(Dispatchers.IO) {
                coroutineScope {
                    val topicLists = topics.map { topic ->
                        async { withTimeoutOrNull(LOAD_TIMEOUT_MS) { searchTopic(topic, round) }.orEmpty() }
                    }
                    val relatedList = async { if (related) withTimeoutOrNull(LOAD_TIMEOUT_MS * 2) { loadRelated(round) }.orEmpty() else emptyList() }
                    val liveList = async {
                        if (!live) {
                            emptyList()
                        } else {
                            withTimeoutOrNull(LOAD_TIMEOUT_MS) {
                                runCatching {
                                    MediaServiceRepository.instance.getTrending(PreferenceHelper.getTrendingRegion(app), TrendingCategory.LIVE)
                                }.getOrNull()
                            }.orEmpty()
                        }
                    }
                    Triple(topicLists.awaitAll(), relatedList.await(), liveList.await())
                }
            }
            val (topicLists, relatedList, liveList) = lists
            val bySource = HashMap<YtFeedSource, List<YtFeedItem>>()
            // the topics one after the other, their weights as repeats of each turn
            val weighted = topics.zip(topicLists)
            val topicItems = ArrayList<YtFeedItem>()
            val maxLen = weighted.maxOfOrNull { it.second.size } ?: 0
            for (i in 0 until maxLen) {
                for ((topic, list) in weighted) {
                    val video = list.getOrNull(i) ?: continue
                    toItem(video, YtFeedSource.TOPICS, topicItems.size, topic = topic.query)?.let {
                        found[it.id] = video
                        // a heavier topic counts as further up its turn
                        topicItems += it.copy(rank = (topicItems.size * 3) / topic.weight.coerceIn(1, 5))
                    }
                }
            }
            bySource[YtFeedSource.TOPICS] = topicItems
            bySource[YtFeedSource.RELATED] = relatedList.mapIndexedNotNull { i, (video, title) ->
                toItem(video, YtFeedSource.RELATED, i, relatedTo = title)?.also { found[it.id] = video }
            }
            bySource[YtFeedSource.LIVE] = liveList.mapIndexedNotNull { i, video ->
                toItem(video, YtFeedSource.LIVE, i)?.also { found[it.id] = video }
            }
            extraVideos.clear()
            extraVideos.putAll(found)
            extrasFlow.value = bySource
            YtTestHooks.log("YtFeed") {
                "extras topics=${topicItems.size} related=${bySource[YtFeedSource.RELATED]?.size} live=${bySource[YtFeedSource.LIVE]?.size}"
            }
        }
    }

    /**
     * One topic search. Round 0 takes the first [PER_TOPIC] results; a forced reload (round 1, 2,
     * ...) also loads the second page and takes the next [PER_TOPIC] of both, round by round, so
     * a pull brings other videos of the same topic instead of the same first page again.
     */
    private suspend fun searchTopic(topic: YtTopic, round: Int): List<StreamItem> = searchVideos(topic.query, round, PER_TOPIC)

    /** [count] videos of a search for [query], part [round] of the first two pages (see [searchTopic]). */
    private suspend fun searchVideos(query: String, round: Int, count: Int): List<StreamItem> = runCatching {
        val repo = MediaServiceRepository.instance
        val first = repo.getSearchResults(query, "videos")
        val next = first.nextpage
        val items = if (round > 0 && next != null) {
            first.items + runCatching { repo.getSearchResultsNextPage(query, "videos", next).items }.getOrDefault(emptyList())
        } else {
            first.items
        }
        YtRotation.window(
            items.filter { it.type == StreamItem.TYPE_STREAM }.map { it.toStreamItem() }.distinctBy { it.url },
            round,
            count,
        )
    }.getOrDefault(emptyList())

    /**
     * Searches the keywords of each active topic with a share (at most [SEARCHES_PER_TOPIC] per
     * load, other ones on each pull when there are more), [PARALLEL_SEARCHES] at a time over all
     * topics. A topic's videos are kept for the session: loaded again only on [force] (a pull,
     * with another part of each search) or when its keywords changed. The keywords' results come
     * in turn, each video with the keyword its title names (else the one it was found by).
     */
    fun loadTopics(force: Boolean = false) {
        val wanted = activeTopics().filter { it.share > 0 && it.keywords.isNotEmpty() }
        for (topic in wanted) {
            val words = topic.keywords.map { YtFeedTopics.fold(it) }.sorted().joinToString("|")
            val loaded = topicLoaded[topic.id]
            if (loaded != null && loaded.first == words && (!force || loaded.second == turn)) continue
            val round = turn
            topicLoaded[topic.id] = words to round
            topicJobs[topic.id]?.cancel()
            topicJobs[topic.id] = scope.launch {
                val searched = YtRotation.window(topic.keywords, round, SEARCHES_PER_TOPIC)
                val lists = withContext(Dispatchers.IO) {
                    coroutineScope {
                        searched.map { keyword ->
                            async {
                                keyword to searchGate.withPermit {
                                    withTimeoutOrNull(LOAD_TIMEOUT_MS) { searchVideos(keyword, round, PER_KEYWORD) }.orEmpty()
                                }
                            }
                        }.awaitAll()
                    }
                }
                val candidates = ArrayList<YtTopicCandidate>()
                val ids = HashSet<String>()
                for (i in 0 until (lists.maxOfOrNull { it.second.size } ?: 0)) {
                    for ((keyword, list) in lists) {
                        val video = list.getOrNull(i) ?: continue
                        val item = toItem(video, YtFeedSource.TOPICS, candidates.size, topic = topic.name) ?: continue
                        if (!ids.add(item.id)) continue
                        topicVideos[item.id] = video
                        candidates += YtTopicCandidate(item, YtFeedTopics.keywordIn(item.title, topic.keywords) ?: keyword)
                    }
                }
                // nothing found (offline, a failed search): the next load tries again
                if (candidates.isEmpty()) topicLoaded.remove(topic.id)
                val pools = topicPoolsFlow.value + (topic.id to candidates)
                topicPoolsFlow.value = pools
                val kept = pools.values.flatMapTo(HashSet()) { list -> list.map { it.item.id } }
                topicVideos.keys.retainAll(kept)
                YtTestHooks.log("YtFeed") { "topic ${topic.id} keywords=${searched.size} videos=${candidates.size}" }
            }
        }
    }

    /**
     * The related videos of [RELATED_FROM] watched videos, with the title they belong to: the last
     * ones at first, on each forced reload ([round]) two others of the last [RELATED_POOL].
     */
    private suspend fun loadRelated(round: Int): List<Pair<StreamItem, String?>> {
        val recent = runCatching { Database.watchHistoryDao().getAll() }.getOrDefault(emptyList())
            .filter { !it.isShort }
            .distinctBy { it.videoId }
            .takeLast(RELATED_POOL)
            .reversed()
        val last = YtRotation.window(recent, round, RELATED_FROM)
        return coroutineScope {
            last.map { watched ->
                async {
                    runCatching {
                        MediaServiceRepository.instance.getStreams(watched.videoId).relatedStreams
                            .filter { it.url != null && !it.isShort }
                            .take(PER_RELATED)
                            .map { it to watched.title }
                    }.getOrDefault(emptyList())
                }
            }.awaitAll()
        }.let { lists ->
            // in turn, so the second video's related ones are not all at the end
            buildList {
                for (i in 0 until (lists.maxOfOrNull { it.size } ?: 0)) lists.forEach { list -> list.getOrNull(i)?.let(::add) }
            }
        }
    }

    /** Watch positions, how often each channel was watched and the groups' channels. */
    fun loadSignals() {
        if (signalsJob?.isActive == true) return
        signalsJob = scope.launch {
            signalsFlow.value = withContext(Dispatchers.IO) {
                val positions = runCatching { Database.watchPositionDao().getAll() }.getOrDefault(emptyList())
                    .associate { it.videoId to it.position }
                val views = runCatching { Database.watchHistoryDao().getAll() }.getOrDefault(emptyList())
                    .mapNotNull { it.uploaderUrl?.takeIf { url -> url.isNotBlank() }?.toID() }
                    .groupingBy { it }.eachCount()
                val groups = runCatching { Database.subscriptionGroupsDao().getAll() }.getOrDefault(emptyList())
                    .associate { it.name to it.channels.toSet() }
                YtLocalSignals(positions, views, groups)
            }
        }
    }

    /** A video as the engine sees it; null without an id. */
    fun toItem(
        video: StreamItem,
        source: YtFeedSource,
        rank: Int,
        topic: String? = null,
        relatedTo: String? = null,
    ): YtFeedItem? {
        val id = video.url?.toID() ?: return null
        return YtFeedItem(
            id = id,
            source = source,
            rank = rank,
            channelId = YtChannelPause.channelId(video.uploaderUrl),
            channelName = video.uploaderName,
            title = video.title.orEmpty(),
            description = video.shortDescription.orEmpty(),
            durationSec = video.duration ?: 0L,
            uploadedMs = video.uploaded,
            views = video.views ?: 0L,
            isLive = video.isLive,
            isShort = video.isShort,
            isUpcoming = video.isUpcoming,
            topic = topic,
            relatedTo = relatedTo,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Ranking

    /** Ranks [input] by [rules] (the current intent's unless given), without touching [reasons]. */
    fun rank(
        input: YtFeedInput,
        rules: YtFeedRules = rules(),
        personal: YtFeedPersonal = profileFlow.value.personal,
        extras: Map<YtFeedSource, List<YtFeedItem>> = extrasFlow.value,
        local: YtLocalSignals = signalsFlow.value,
        fresh: YtFeedFreshness = freshFlow.value,
        pools: Map<String, List<YtTopicCandidate>> = topicPoolsFlow.value,
        session: Long = YtTopicSession.current.value,
    ): YtFeedResult {
        val videos = HashMap<String, StreamItem>(256)
        val items = ArrayList<YtFeedItem>(256)
        fun addAll(list: List<StreamItem>?, source: YtFeedSource) {
            list.orEmpty().forEachIndexed { i, video ->
                if (video.url == null) return@forEachIndexed
                val item = toItem(video, source, i) ?: return@forEachIndexed
                videos.putIfAbsent(item.id, video)
                items += item
            }
        }
        addAll(input.subscriptions, YtFeedSource.SUBSCRIPTIONS)
        val chosen = chosenChart()
        for ((category, list) in input.charts) {
            val source = sourceOf(category.name) ?: continue
            if (source != YtFeedSource.LIVE) addAll(list, source)
        }
        // the topic row's chart, where YtDiscovery has not loaded it (or has no list for it)
        if (chosen != null && chosen != YtFeedSource.LIVE && input.charts.keys.none { sourceOf(it.name) == chosen }) {
            addAll(input.trending, chosen)
        }
        addAll(input.continueWatching, YtFeedSource.CONTINUE)
        for (list in extras.values) {
            list.forEach { item ->
                val video = extraVideos[item.id] ?: return@forEach
                videos.putIfAbsent(item.id, video)
                items += item
            }
        }
        // the user's own topics: what their searches found (marks only with share 0)
        val now = System.currentTimeMillis()
        val topicPools = activeTopics(personal, session, now).map { topic ->
            val found = if (topic.share > 0) pools[topic.id].orEmpty() else emptyList()
            YtTopicPool(
                topic,
                found.filter { candidate ->
                    val video = topicVideos[candidate.item.id] ?: return@filter false
                    videos.putIfAbsent(candidate.item.id, video)
                    true
                },
            )
        }
        val watched = HashMap<String, Float>()
        val finished = HashSet<String>()
        for (item in items + topicPools.flatMap { pool -> pool.candidates.map { it.item } }) {
            val position = local.positionsMs[item.id] ?: continue
            if (item.durationSec > 0) watched[item.id] = (position / 1000f / item.durationSec).coerceIn(0f, 1f)
            if (YtSeenHide.finished(position, item.durationSec)) finished += item.id
        }
        val groupChannels = rules.groups.takeIf { it.isNotEmpty() }?.let { names ->
            names.flatMap { local.groups[it].orEmpty() }.toSet()
        }
        val signals = YtFeedSignals(
            nowMs = now,
            watched = watched,
            channelViews = local.channelViews,
            hearts = YtHearts.all(),
            groupChannels = groupChannels,
            chosenChart = chosen,
            seen = fresh.seen.within(now),
            shuffle = fresh.shuffle,
            finished = finished,
        )
        val ranked = YtFeedEngine.rank(items, rules, personal, signals, topicPools)
        return YtFeedResult(
            videos = ranked.mapNotNull { videos[it.item.id] },
            reasons = ranked.associate { it.item.id to it.reasons },
            focus = ranked.mapNotNull { r -> r.focus?.let { r.item.id to it } }.toMap(),
        )
    }

    /** Remembers [result]'s reasons for "Warum sehe ich das?". */
    fun publish(result: YtFeedResult) {
        reasonsFlow.value = result.reasons
        focusFlow.value = result.focus
        // read on every ranking, kept only in memory until a refresh remembers it
        lastTop = result.videos.asSequence()
            .mapNotNull { video -> video.url?.toID()?.let { YtSeen(it, video.uploaderName, video.title.orEmpty()) } }
            .take(YtSeenMemory.TOP)
            .toList()
    }
}

/**
 * The home feed of [input] after the current intent's rules, ranked again whenever the rules,
 * the intent or a list changes; null while nothing has loaded. Loads the extra sources and the
 * local signals the rules need.
 */
@Composable
fun rememberYtFeed(input: YtFeedInput): List<StreamItem>? {
    val context = LocalContext.current
    val profile by YtFeed.profile.collectAsState()
    val intent by YtFeed.intent.collectAsState()
    val extras by YtFeed.extras.collectAsState()
    val signals by YtFeed.signals.collectAsState()
    val fresh by YtFeed.freshness.collectAsState()
    val pools by YtFeed.topicPools.collectAsState()
    val session by YtTopicSession.current.collectAsState()
    val rules = profile.rulesFor(intent ?: YtIntent.BROWSE)
    LaunchedEffect(rules.topics, rules.weights) { YtFeed.loadExtras(context) }
    // the user's own topics: a new topic, keyword, share or session loads what is missing
    val topicKey = profile.personal.feedTopics.map { Triple(it.id, it.keywords, it.share > 0 && it.enabled) } to session
    LaunchedEffect(topicKey) { YtFeed.loadTopics() }
    LaunchedEffect(Unit) { YtFeed.loadSignals() }
    if (input.subscriptions == null && input.charts.isEmpty() && input.trending == null) return null
    // ranked again at once on new rules (the Feed-Regler) and on a pull to refresh (fresh)
    val result = remember(input, rules, profile.personal, extras, signals, fresh, pools, session) {
        YtFeed.rank(input, rules, profile.personal, extras, signals, fresh, pools, session)
    }
    LaunchedEffect(result) { YtFeed.publish(result) }
    return result.videos
}
