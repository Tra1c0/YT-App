package com.github.libretube.ui.models.sources

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.github.libretube.api.obj.Comment
import com.github.libretube.ui.models.CommentPageCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CommentPagingSource(
    private val videoId: String,
    // YT: the video's pages, fetched once for the list, the scene index and the design pages
    private val cache: CommentPageCache,
    private val onCommentCount: (Long) -> Unit
) : PagingSource<String, Comment>() {
    override fun getRefreshKey(state: PagingState<String, Comment>) = null

    override suspend fun load(params: LoadParams<String>): LoadResult<String, Comment> {
        return try {
            val result = cache.page(videoId, params.key)
            if (params.key == null) {
                // avoid negative comment counts, i.e. because they're disabled
                withContext(Dispatchers.Main) {
                    onCommentCount(maxOf(0, result.commentCount))
                }
            }

            LoadResult.Page(result.comments, null, result.nextpage)
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }
}
