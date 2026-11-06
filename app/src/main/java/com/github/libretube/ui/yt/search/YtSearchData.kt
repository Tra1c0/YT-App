package com.github.libretube.ui.yt.search

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.db.obj.SearchHistoryItem
import com.github.libretube.helpers.PreferenceHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * YT: the data of the design search, the same as the old suggestions screen had: the past
 * searches from the database (newest first) and the service's suggestions for what is typed,
 * fetched once typing pauses (YtSearchRules.DEBOUNCE_MS).
 */

@Stable
internal class YtSearchData(private val scope: CoroutineScope) {
    var history by mutableStateOf<List<String>>(emptyList())
        internal set
    var suggestions by mutableStateOf<List<String>>(emptyList())
        internal set

    /** What the list shows for [typed]. */
    fun items(typed: String): List<YtSearchItem> =
        YtSearchRules.merge(YtSearchRules.history(history, typed), if (YtSearchRules.query(typed) == null) emptyList() else suggestions)

    /** Removes [query] from the past searches. */
    fun delete(query: String) {
        // shown without it at once; the database follows
        history = history.filterNot { it == query }
        scope.launch(Dispatchers.IO) {
            runCatching { DatabaseHolder.Database.searchHistoryDao().delete(SearchHistoryItem(query)) }
        }
    }
}

/** The data for the open search; [typed] is read for the suggestions. */
@Composable
internal fun rememberYtSearchData(typed: () -> String): YtSearchData {
    val scope = rememberCoroutineScope()
    val data = remember { YtSearchData(scope) }
    val flow = remember {
        DatabaseHolder.Database.searchHistoryDao().getAllNewestFirstFlow().map { list -> list.map { it.query } }
    }
    val stored by flow.collectAsState(initial = null)
    LaunchedEffect(stored) {
        stored?.let { data.history = it }
    }
    LaunchedEffect(Unit) {
        val suggestionsOn = PreferenceHelper.getBoolean(PreferenceKeys.SEARCH_SUGGESTIONS, true)
        snapshotFlow { typed().trim() }
            .distinctUntilChanged()
            .collectLatest { text ->
                if (!YtSearchRules.fetches(text, suggestionsOn)) {
                    data.suggestions = emptyList()
                    return@collectLatest
                }
                delay(YtSearchRules.DEBOUNCE_MS)
                val found = try {
                    withContext(Dispatchers.IO) { MediaServiceRepository.instance.getSuggestions(text) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("YtSearch", "suggestions failed: ${e.message}")
                    emptyList()
                }
                data.suggestions = found
            }
    }
    return data
}
