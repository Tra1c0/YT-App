package com.github.libretube.ui.yt.scene

import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.R
import com.github.libretube.databinding.FragmentCommentsBinding
import com.github.libretube.extensions.formatShort
import com.github.libretube.helpers.SceneMode
import com.github.libretube.helpers.YtCommentMoments
import com.github.libretube.ui.adapters.CommentRowBinder
import com.github.libretube.ui.adapters.CommentsPagingAdapter
import com.github.libretube.ui.adapters.CommentsSceneAdapter
import com.github.libretube.ui.models.CommentScene
import com.github.libretube.ui.yt.YtTestHooks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * YT (comments follow the scene): the scene part of the classic comment list (CommentsMainFragment).
 * In the scene mode the list shows the comments of the current scene ([CommentsSceneAdapter])
 * instead of the pages, follows the position in steps of 5 s and reads further pages on request;
 * in every mode but off a line below the chips says what is hidden or which scene this is. The
 * rows rebind only when a verdict may have changed ([CommentScene.epoch]).
 */
class YtSceneSheet(
    private val fragment: Fragment,
    private val binding: FragmentCommentsBinding,
    private val scene: CommentScene,
    private val paged: CommentsPagingAdapter,
) {
    private val sceneAdapter = CommentsSceneAdapter(paged.binder)

    // where the paged list was when the scene took its place
    private var pagedPosition = 0

    /** True while the list of the scene stands in for the pages. */
    val showsScene: Boolean get() = binding.commentsRV.adapter === sceneAdapter

    fun start() {
        binding.sceneMore.setOnClickListener { scene.scanMore() }
        val owner = fragment.viewLifecycleOwner
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { scene.mode.collect { show(it) } }
                launch {
                    scene.epoch.collect {
                        rebind(binding.commentsRV)
                        caption()
                    }
                }
                launch {
                    combine(scene.indexed, scene.sceneStep, scene.mode) { _, _, mode -> mode }
                        .collectLatest { mode ->
                            if (mode != SceneMode.SCENE) return@collectLatest
                            val list = withContext(Dispatchers.Default) { scene.sceneList() }
                            sceneAdapter.submitList(list) { caption() }
                        }
                }
                launch { combine(scene.scanning, scene.exhausted) { _, _ -> Unit }.collect { caption() } }
            }
        }
    }

    private fun show(mode: SceneMode) {
        val list = binding.commentsRV
        val layoutManager = list.layoutManager as? LinearLayoutManager
        if (mode == SceneMode.SCENE) {
            if (list.adapter !== sceneAdapter) {
                pagedPosition = layoutManager?.findFirstVisibleItemPosition()?.coerceAtLeast(0) ?: 0
                list.adapter = sceneAdapter
                // the pages' loading and "no comments" do not belong to the scene
                binding.progress.isVisible = false
                binding.errorTV.isVisible = false
            }
            scene.autoScan()
        } else if (list.adapter !== paged) {
            list.adapter = paged
            layoutManager?.scrollToPosition(pagedPosition)
        }
        binding.sceneBar.isVisible = mode != SceneMode.OFF
        caption()
        YtTestHooks.log(TAG) { "sheet mode=${mode.key} rows=${list.adapter?.itemCount ?: 0}" }
    }

    private fun caption() {
        val context = binding.root.context
        val mode = scene.mode.value
        val now = scene.playback()
        val scanning = scene.scanning.value
        binding.sceneMore.isVisible = mode == SceneMode.SCENE && !scene.exhausted.value
        binding.sceneMore.isEnabled = !scanning
        binding.sceneMore.text = context.getString(
            if (scanning) R.string.yt_r12_e_searching else R.string.yt_r12_e_search_more,
        )
        binding.sceneCaption.text = when (mode) {
            SceneMode.OFF -> ""
            SceneMode.SPOILER -> context.getString(
                R.string.yt_r12_e_caption_spoiler,
                YtCommentMoments.formatTime(now.unlockedUpToMs),
            )
            SceneMode.SCENE -> {
                val window = YtCommentMoments.sceneWindow(now)
                val end = now.durationMs.takeIf { it > 0 }?.let { minOf(window.last, it) } ?: window.last
                val from = YtCommentMoments.formatTime(window.first)
                val to = YtCommentMoments.formatTime(end)
                when {
                    sceneAdapter.itemCount > 0 || scanning -> scene.currentChapter()
                        ?.let { context.getString(R.string.yt_r12_e_caption_chapter, it.title, from, to) }
                        ?: context.getString(
                            R.string.yt_r12_e_caption_around,
                            YtCommentMoments.formatTime(now.positionMs), from, to,
                        )
                    scene.exhausted.value -> context.getString(R.string.yt_r12_e_scene_empty_all, from, to)
                    else -> {
                        val read = scene.indexedCount()
                        context.resources.getQuantityString(
                            R.plurals.yt_r12_e_scene_empty, read, from, to, read.toLong().formatShort(),
                        )
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "YtScene"

        /** Rebinds what the scene shows of every row of [list], without a full rebind. */
        fun rebind(list: RecyclerView) {
            val adapter = list.adapter ?: return
            if (adapter.itemCount > 0) {
                adapter.notifyItemRangeChanged(0, adapter.itemCount, CommentRowBinder.PAYLOAD_SCENE)
            }
        }

        /** For a list with no scene of its own (the replies): its rows follow the verdicts. */
        fun follow(fragment: Fragment, list: RecyclerView, scene: CommentScene) {
            val owner = fragment.viewLifecycleOwner
            owner.lifecycleScope.launch {
                owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    scene.epoch.collect { rebind(list) }
                }
            }
        }
    }
}
