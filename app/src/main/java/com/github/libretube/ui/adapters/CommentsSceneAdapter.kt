package com.github.libretube.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.ListAdapter
import com.github.libretube.databinding.CommentsRowBinding
import com.github.libretube.ui.adapters.callbacks.DiffUtilItemCallback
import com.github.libretube.ui.models.SceneComment
import com.github.libretube.ui.viewholders.CommentViewHolder

/**
 * YT (comments follow the scene): the comments of the current scene, a list of its own beside the
 * paged one, since a filter that moves with the position would make the pages load anew. Rows
 * bind as in the paged list ([CommentRowBinder]).
 */
class CommentsSceneAdapter(
    private val binder: CommentRowBinder,
) : ListAdapter<SceneComment, CommentViewHolder>(
    DiffUtilItemCallback(
        areItemsTheSame = { oldItem, newItem -> oldItem.comment.commentId == newItem.comment.commentId },
        // what the scene shows of a row changes through a rebind with PAYLOAD_SCENE
        areContentsTheSame = { oldItem, newItem -> oldItem.comment == newItem.comment },
    )
) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CommentViewHolder {
        val binding = CommentsRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return CommentViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CommentViewHolder, position: Int) {
        binder.bind(holder, getItem(position).comment, position)
    }

    override fun onBindViewHolder(holder: CommentViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == CommentRowBinder.PAYLOAD_SCENE }) {
            binder.rebindScene(holder, getItem(position).comment)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }
}
