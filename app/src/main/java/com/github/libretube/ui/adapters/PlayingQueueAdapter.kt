package com.github.libretube.ui.adapters

import android.annotation.SuppressLint
import android.graphics.Color
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.databinding.QueueRowBinding
import com.github.libretube.extensions.move
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.ThemeHelper
import com.github.libretube.ui.viewholders.PlayingQueueViewHolder
import com.github.libretube.util.PlayingQueue

class PlayingQueueAdapter(
    private val onQueueItemSelected: (String) -> Unit
) : RecyclerView.Adapter<PlayingQueueViewHolder>() {
    // YT: a copy of the queue, renewed by [refresh]. The queue changes while the sheet is open
    // (the next video starts, a playlist's next pages arrive from a background thread); reading
    // it live gave RecyclerView a count it was never told of, which throws (index out of bounds,
    // "Inconsistency detected") and closed the app with the sheet.
    private var items: List<StreamItem> = PlayingQueue.getStreams()
    private var currentIndex = PlayingQueue.currentIndex()

    /**
     * Takes the queue as it is now and redraws, unless nothing changed (the sheet's own swipes
     * and drags already updated the copy, so their animations stay). Main thread.
     */
    @SuppressLint("NotifyDataSetChanged")
    fun refresh() {
        val streams = PlayingQueue.getStreams()
        val current = PlayingQueue.currentIndex()
        if (streams == items && current == currentIndex) return
        items = streams
        currentIndex = current
        notifyDataSetChanged()
    }

    /** The video id at [position] of the copy, null outside it. */
    fun idAt(position: Int): String? = items.getOrNull(position)?.url?.toID()

    /** The sheet removed the item at [position] from the queue. */
    fun removed(position: Int) {
        if (position !in items.indices) return refresh()
        items = items.toMutableList().apply { removeAt(position) }
        currentIndex = PlayingQueue.currentIndex()
        notifyItemRemoved(position)
        notifyItemRangeChanged(position, items.size - position)
    }

    /** The sheet moved an item in the queue from [from] to [to]. */
    fun moved(from: Int, to: Int) {
        if (from !in items.indices || to !in items.indices) return refresh()
        items = items.toMutableList().apply { move(from, to) }
        currentIndex = PlayingQueue.currentIndex()
        notifyItemMoved(from, to)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlayingQueueViewHolder {
        val binding = QueueRowBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PlayingQueueViewHolder(binding)
    }

    override fun getItemCount() = items.size

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: PlayingQueueViewHolder, position: Int) {
        val streamItem = items[position]
        holder.binding.apply {
            ImageHelper.loadImage(streamItem.thumbnail, thumbnail)
            title.text = streamItem.title
            videoInfo.text = streamItem.uploaderName + "  •  " +
                DateUtils.formatElapsedTime(streamItem.duration ?: 0)

            root.isSelected = currentIndex == position

            root.setOnClickListener {
                val newVideoId = streamItem.url?.toID() ?: return@setOnClickListener

                val oldPosition = currentIndex
                // get the new position from the queue to work properly after reordering the queue
                val newPosition = items.indexOfFirst {
                    it.url?.toID() == newVideoId
                }.takeIf { it >= 0 } ?: return@setOnClickListener
                PlayingQueue.updateCurrent(streamItem)
                currentIndex = newPosition

                // select the new item in the queue and update the selected item in the UI
                onQueueItemSelected(newVideoId)
                notifyItemChanged(oldPosition)
                notifyItemChanged(newPosition)
            }
        }
    }
}
