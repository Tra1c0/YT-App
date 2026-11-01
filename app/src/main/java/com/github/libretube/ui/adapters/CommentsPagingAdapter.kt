package com.github.libretube.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import com.github.libretube.api.obj.Comment
import com.github.libretube.databinding.CommentsRowBinding
import com.github.libretube.ui.adapters.callbacks.DiffUtilItemCallback
import com.github.libretube.ui.viewholders.CommentViewHolder

class CommentsPagingAdapter(
    isReplies: Boolean,
    channelAvatar: String?,
    handleLink: (url: String) -> Unit,
    saveToClipboard: (Comment) -> Unit,
    navigateToChannel: (Comment) -> Unit,
    navigateToReplies: ((Comment, String?) -> Unit)? = null,
    // YT: what the scene lets a row show (comments follow the scene); null: every row as always
    gate: CommentSceneGate? = null,
) : PagingDataAdapter<Comment, CommentViewHolder>(
    DiffUtilItemCallback(
        areItemsTheSame = { oldItem, newItem -> oldItem.commentId == newItem.commentId },
        areContentsTheSame = { _, _ -> true },
    )
) {
    // YT: the row binding lives in CommentRowBinder; the list of the scene binds with this one
    val binder = CommentRowBinder(
        isReplies, channelAvatar, handleLink, saveToClipboard, navigateToChannel, navigateToReplies, gate,
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CommentViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        val binding = CommentsRowBinding.inflate(layoutInflater, parent, false)
        return CommentViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CommentViewHolder, position: Int) {
        binder.bind(holder, getItem(position)!!, position)
    }

    override fun onBindViewHolder(holder: CommentViewHolder, position: Int, payloads: MutableList<Any>) {
        val comment = getItem(position)
        if (comment != null && payloads.isNotEmpty() && payloads.all { it == CommentRowBinder.PAYLOAD_SCENE }) {
            binder.rebindScene(holder, comment)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }
}
