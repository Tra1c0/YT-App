package com.github.libretube.ui.sheets

import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.activity.addCallback
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commit
import androidx.fragment.app.replace
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.libretube.R
import com.github.libretube.databinding.CommentsSheetBinding
import com.github.libretube.helpers.SceneMode
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.fragments.CommentsMainFragment
import com.github.libretube.ui.models.CommentsViewModel
import com.github.libretube.ui.models.CommonPlayerViewModel
import com.github.libretube.ui.yt.YtHaptics
import com.github.libretube.ui.yt.YtTestHooks
import com.google.android.material.chip.Chip
import kotlinx.coroutines.launch

class CommentsSheet : ExpandablePlayerSheet(R.layout.comments_sheet) {
    private var _binding: CommentsSheetBinding? = null
    val binding get() = _binding!!

    private val commonPlayerViewModel: CommonPlayerViewModel by activityViewModels()
    private val commentsViewModel: CommentsViewModel by activityViewModels()

    // YT: the chips of the scene, hidden for live streams and in the replies
    private var sceneChipsAllowed = false

    private val backPressedCallback by lazy(LazyThreadSafetyMode.NONE) {
        (dialog as ComponentDialog?)?.onBackPressedDispatcher?.addCallback(owner = viewLifecycleOwner, enabled = false) {
            if (childFragmentManager.backStackEntryCount > 0) {
                childFragmentManager.popBackStack()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = CommentsSheetBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        childFragmentManager.setFragmentResultListener(DISMISS_SHEET_REQUEST_KEY, viewLifecycleOwner) { _, _ ->
            dismiss()
        }

        // forward requests to open links to the parent fragment
        childFragmentManager.setFragmentResultListener(HANDLE_LINK_REQUEST_KEY, viewLifecycleOwner) { key, result ->
            parentFragment?.setFragmentResult(key, result)
        }

        binding.btnBack.setOnClickListener {
            if (childFragmentManager.backStackEntryCount > 0) {
                childFragmentManager.popBackStack()
            }
        }

        binding.btnClose.setOnClickListener { dismiss() }

        setupSceneChips()

        childFragmentManager.commit {
            replace<CommentsMainFragment>(R.id.commentFragContainer, args = arguments)
        }
    }

    override fun onStart() {
        super.onStart()
        // remove internal padding from the bottomsheet
        // https://github.com/material-components/material-components-android/issues/3389#issuecomment-2049028605
        dialog?.window?.apply {
            setFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )
        }
    }


    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun getSheetMaxHeightPx() = commonPlayerViewModel.maxSheetHeightPx

    override fun getDragHandle() = binding.dragHandle

    override fun getBottomSheet() = binding.standardBottomSheet

    fun updateFragmentInfo(showBackButton: Boolean, title: String) {
        binding.btnBack.isVisible = showBackButton
        binding.commentsTitle.text = title
        backPressedCallback?.isEnabled = showBackButton
        binding.sceneChipsScroll.isVisible = sceneChipsAllowed && !showBackButton
    }

    /**
     * YT (comments follow the scene): Alle / Diese Stelle / Spoilerschutz above the comments, in
     * the design's chips. They set the same mode as the setting.
     */
    private fun setupSceneChips() {
        val scene = commentsViewModel.scene
        scene.reloadMode()
        sceneChipsAllowed = !scene.isLive
        binding.sceneChipsScroll.isVisible = sceneChipsAllowed
        if (!sceneChipsAllowed) return

        val group = binding.sceneChips
        // the classic look has no design overlay with its chip: a filter chip of its own then
        val chipContext = if (YtDesign.current() == YtDesign.STANDARD) {
            ContextThemeWrapper(group.context, R.style.ThemeOverlay_YT_R12e_SceneChips)
        } else {
            group.context
        }
        val chips = listOf(
            SceneMode.OFF to R.string.yt_r12_e_chip_all,
            SceneMode.SCENE to R.string.yt_r12_e_mode_scene,
            SceneMode.SPOILER to R.string.yt_r12_e_mode_spoiler,
        ).associate { (mode, label) ->
            val chip = Chip(chipContext).apply {
                id = View.generateViewId()
                text = getString(label)
                isCheckable = true
                isCheckedIconVisible = false
                tag = mode
            }
            group.addView(chip)
            mode to chip
        }
        chips[scene.chosenMode.value]?.let { group.check(it.id) }
        group.setOnCheckedStateChangeListener { chipGroup, checkedIds ->
            val mode = checkedIds.firstOrNull()
                ?.let { chipGroup.findViewById<Chip>(it)?.tag as? SceneMode }
                ?: return@setOnCheckedStateChangeListener
            if (mode == scene.chosenMode.value) return@setOnCheckedStateChangeListener
            YtHaptics.of(chipGroup).tick()
            scene.setMode(mode)
            YtTestHooks.log("YtScene") { "chip mode=${mode.key}" }
        }
        // the Side and Movo pages switch the mode as well
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                scene.chosenMode.collect { mode ->
                    val chip = chips[mode] ?: return@collect
                    if (!chip.isChecked) group.check(chip.id)
                }
            }
        }
    }

    companion object {
        const val HANDLE_LINK_REQUEST_KEY = "handle_link_request_key"
        const val DISMISS_SHEET_REQUEST_KEY = "dismiss_sheet_request_key"
    }
}
