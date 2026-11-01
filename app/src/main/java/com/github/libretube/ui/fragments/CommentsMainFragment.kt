package com.github.libretube.ui.fragments

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commit
import androidx.fragment.app.replace
import androidx.fragment.app.setFragmentResult
import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.R
import com.github.libretube.constants.IntentData
import com.github.libretube.databinding.FragmentCommentsBinding
import com.github.libretube.extensions.formatShort
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.ui.adapters.CommentSceneGate
import com.github.libretube.ui.adapters.CommentsPagingAdapter
import com.github.libretube.ui.models.CommentsViewModel
import com.github.libretube.ui.sheets.CommentsSheet
import com.github.libretube.ui.yt.scene.YtSceneSheet

class CommentsMainFragment : Fragment(R.layout.fragment_comments) {

    private val viewModel: CommentsViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = FragmentCommentsBinding.bind(view)
        val layoutManager = LinearLayoutManager(requireContext())
        binding.commentsRV.layoutManager = layoutManager

        val commentsSheet = parentFragment as? CommentsSheet
        commentsSheet?.binding?.btnScrollToTop?.setOnClickListener {
            // scroll back to the top / first comment
            layoutManager.startSmoothScroll(LinearSmoothScroller(view.context).also {
                it.targetPosition = POSITION_START
            })
            viewModel.setCommentsPosition(POSITION_START)
        }

        binding.commentsRV.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return

                val firstVisiblePosition = layoutManager.findFirstVisibleItemPosition()
                viewModel.setCommentsPosition(firstVisiblePosition)
            }
        })

        commentsSheet?.updateFragmentInfo(false, getString(R.string.comments))

        val commentPagingAdapter = CommentsPagingAdapter(
            false,
            requireArguments().getString(IntentData.channelAvatar),
            handleLink = {
                setFragmentResult(
                    CommentsSheet.HANDLE_LINK_REQUEST_KEY,
                    bundleOf(IntentData.url to it),
                )
            },
            saveToClipboard = { comment ->
                viewModel.saveToClipboard(view.context, comment)
            },
            navigateToChannel = { comment ->
                NavigationHelper.navigateChannel(view.context, comment.commentorUrl)
                setFragmentResult(CommentsSheet.DISMISS_SHEET_REQUEST_KEY, Bundle.EMPTY)
            },
            navigateToReplies = { comment, channelAvatar ->
                if (comment.repliesPage != null) {
                    val args = bundleOf(
                        IntentData.videoId to viewModel.videoIdLiveData.value,
                        IntentData.comment to comment,
                        IntentData.channelAvatar to channelAvatar
                    )
                    parentFragmentManager.commit {
                        viewModel.setLastOpenedCommentRepliesId(comment.commentId)
                        replace<CommentsRepliesFragment>(R.id.commentFragContainer, args = args)
                        addToBackStack(null)
                    }
                }
            },
            // YT: comments follow the scene
            gate = CommentSceneGate(viewModel.scene, replies = false),
        )
        binding.commentsRV.adapter = commentPagingAdapter
        // YT: the list of the scene stands in for the pages in the scene mode
        val sceneSheet = YtSceneSheet(this, binding, viewModel.scene, commentPagingAdapter)
        var lastLoadStates: CombinedLoadStates? = null

        fun showLoadStates(loadStates: CombinedLoadStates) {
            binding.progress.isVisible = loadStates.refresh is LoadState.Loading

            if (loadStates.append is LoadState.NotLoading && loadStates.append.endOfPaginationReached && commentPagingAdapter.itemCount == 0) {
                binding.errorTV.text = getString(R.string.no_comments_available)
                binding.errorTV.isVisible = true
            }
        }
        // YT: back from the scene, the pages show their loading and "no comments" again
        sceneSheet.onPagesBack = { lastLoadStates?.let(::showLoadStates) }
        sceneSheet.start()

        commentPagingAdapter.addLoadStateListener { loadStates ->
            lastLoadStates = loadStates
            if (sceneSheet.showsScene) return@addLoadStateListener
            showLoadStates(loadStates)
        }

        viewModel.currentCommentsPosition.observe(viewLifecycleOwner) {
            // hide or show the scroll to top button
            commentsSheet?.binding?.btnScrollToTop?.isVisible = it != 0
        }

        viewModel.commentsLiveData.observe(viewLifecycleOwner) {
            commentPagingAdapter.submitData(lifecycle, it)
        }

        viewModel.commentCountLiveData.observe(viewLifecycleOwner) { commentCount ->
            if (commentCount == null) return@observe

            commentsSheet?.updateFragmentInfo(
                false,
                getString(R.string.comments_count, commentCount.formatShort())
            )
        }
    }

    companion object {
        private const val POSITION_START = 0
    }
}
