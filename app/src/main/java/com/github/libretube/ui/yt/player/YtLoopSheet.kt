package com.github.libretube.ui.yt.player

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.github.libretube.R
import com.github.libretube.databinding.YtR12CLoopSheetBinding
import com.github.libretube.services.YtLoopModule
import com.github.libretube.ui.sheets.ExpandedBottomSheet
import com.github.libretube.ui.yt.YtHaptics
import com.google.android.material.chip.Chip
import kotlinx.coroutines.launch

/**
 * YT: the explanation loop's sheet ("Erklärschleife"), from the player options and the loop's
 * chip: A and B at the current position, the speed (1 / 0.75 / 0.5×), a pause to think at the end
 * (0 / 2 / 4 / 8 s), the current chapter as a loop, the loops used before on this video, and the
 * way to end it. While a loop runs, every change applies at once; otherwise "Schleife starten".
 * It talks to the open page's YtLoopController and closes if there is none.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class YtLoopSheet : ExpandedBottomSheet(R.layout.yt_r12_c_loop_sheet) {
    private var _binding: YtR12CLoopSheetBinding? = null
    private val binding get() = _binding!!

    // the loop being put together: its ends (-1: not set), speed and pause
    private var aMs = -1L
    private var bMs = -1L
    private var speed = 1f
    private var pauseMs = 0L

    // the chips are set from code too; their listeners then stay quiet
    private var setting = false

    private val speedChips get() = listOf(binding.loopSpeed100, binding.loopSpeed75, binding.loopSpeed50)
    private val pauseChips get() = listOf(binding.loopPause0, binding.loopPause2, binding.loopPause4, binding.loopPause8)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = YtR12CLoopSheetBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)
        val page = YtLoopController.current()
        if (page == null) {
            dismissAllowingStateLoss()
            return
        }

        val running = page.shown.loop
        if (running != null) {
            aMs = running.aMs
            bMs = running.bMs
            speed = YtLoopPlan.nearestSpeed(running.speed)
            pauseMs = YtLoopPlan.nearestPause(running.pauseMs)
        } else {
            aMs = page.draftAMs
            speed = YtLoopPlan.nearestSpeed(page.currentSpeed())
        }

        speedChips.forEachIndexed { i, chip -> chip.text = page.speedLabel(YtLoopPlan.SPEEDS[i]) }
        pauseChips.forEachIndexed { i, chip ->
            if (i > 0) chip.text = getString(R.string.yt_r12_c_loop_pause_seconds, (YtLoopPlan.PAUSES_MS[i] / 1000).toInt())
        }
        selectChips()

        binding.loopSpeeds.setOnCheckedStateChangeListener { _, ids ->
            if (setting) return@setOnCheckedStateChangeListener
            val i = speedChips.indexOfFirst { it.id == ids.firstOrNull() }
            if (i < 0) return@setOnCheckedStateChangeListener
            speed = YtLoopPlan.SPEEDS[i]
            applyIfRunning(page)
        }
        binding.loopPauses.setOnCheckedStateChangeListener { _, ids ->
            if (setting) return@setOnCheckedStateChangeListener
            val i = pauseChips.indexOfFirst { it.id == ids.firstOrNull() }
            if (i < 0) return@setOnCheckedStateChangeListener
            pauseMs = YtLoopPlan.PAUSES_MS[i]
            applyIfRunning(page)
        }

        binding.loopSetA.setOnClickListener {
            aMs = page.positionMs()
            if (bMs in 0L..aMs) bMs = -1L
            page.draftAMs = aMs
            YtHaptics.of(it).tick()
            applyIfRunning(page)
            render(page, page.shown)
        }
        binding.loopSetB.setOnClickListener {
            bMs = page.positionMs()
            if (aMs < 0L) aMs = 0L
            YtHaptics.of(it).tick()
            applyIfRunning(page)
            render(page, page.shown)
        }
        binding.loopChapter.setOnClickListener {
            val loop = YtLoopPlan.chapter(page.positionMs(), page.chapterStartsMs(), page.durationMs(), speed, pauseMs)
            if (loop == null) {
                toast(R.string.yt_r12_c_loop_no_chapter)
                return@setOnClickListener
            }
            aMs = loop.aMs
            bMs = loop.bMs
            page.start(loop)
            YtHaptics.of(it).confirm()
            render(page, page.shown)
        }
        binding.loopStart.setOnClickListener {
            val loop = draft(page)
            if (loop == null) {
                YtHaptics.of(it).reject()
                toast(R.string.yt_r12_c_loop_too_short)
                return@setOnClickListener
            }
            page.start(loop)
            YtHaptics.of(it).confirm()
            dismiss()
        }
        binding.loopEnd.setOnClickListener {
            page.end()
            dismiss()
        }

        val live = page.isLive()
        listOf(binding.loopSetA, binding.loopSetB, binding.loopChapter, binding.loopStart).forEach { it.isEnabled = !live }
        if (live) binding.loopHint.setText(R.string.yt_r12_c_loop_live)

        showSaved(page)
        render(page, page.shown)
        // the passes, the end of the loop and ends moved on the bar, as they happen
        viewLifecycleOwner.lifecycleScope.launch {
            YtLoopModule.status.collect { status ->
                val mine = page.mine(status)
                mine.loop?.let {
                    aMs = it.aMs
                    bMs = it.bMs
                }
                render(page, mine)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun draft(page: YtLoopController): YtLoop? {
        if (aMs < 0L || bMs < 0L) return null
        return YtLoopPlan.of(aMs, bMs, page.durationMs(), speed, pauseMs)
    }

    // while a loop runs, the sheet edits it directly
    private fun applyIfRunning(page: YtLoopController) {
        if (!page.shown.active) return
        val loop = draft(page) ?: return
        if (loop != page.shown.loop) page.start(loop)
    }

    private fun selectChips() {
        setting = true
        speedChips.getOrNull(YtLoopPlan.SPEEDS.indexOfFirst { it == speed })?.isChecked = true
        pauseChips.getOrNull(YtLoopPlan.PAUSES_MS.indexOfFirst { it == pauseMs })?.isChecked = true
        setting = false
    }

    private fun render(page: YtLoopController, status: YtLoopStatus) {
        val binding = _binding ?: return
        val unset = getString(R.string.yt_r12_c_loop_unset)
        val a = if (aMs >= 0L) page.time(aMs) else unset
        val b = if (bMs >= 0L) page.time(bMs) else unset
        binding.loopRange.text = getString(
            R.string.yt_r12_c_loop_range,
            getString(R.string.yt_r12_c_loop_mark_a) + " " + a,
            getString(R.string.yt_r12_c_loop_mark_b) + " " + b,
        )
        val loop = status.loop
        binding.loopState.isVisible = loop != null
        if (loop != null) {
            binding.loopState.text = getString(R.string.yt_r12_c_loop_chip, status.pass)
        }
        binding.loopStart.isVisible = loop == null
        binding.loopEnd.isVisible = loop != null
        binding.loopStart.isEnabled = !page.isLive() && draft(page) != null
    }

    // the loops used before, one tap starts one
    private fun showSaved(page: YtLoopController) {
        val saved = page.saved()
        binding.loopSavedTitle.isVisible = saved.isNotEmpty()
        binding.loopSaved.isVisible = saved.isNotEmpty()
        binding.loopSaved.removeAllViews()
        for (loop in saved) {
            val chip = layoutInflater.inflate(R.layout.assist_chip, binding.loopSaved, false) as Chip
            val range = getString(R.string.yt_r12_c_loop_range, page.time(loop.aMs), page.time(loop.bMs))
            chip.text = if (loop.speed == 1f) range else "$range · ${page.speedLabel(loop.speed)}"
            chip.contentDescription = getString(R.string.yt_r12_c_loop_started, range)
            chip.setOnClickListener {
                aMs = loop.aMs
                bMs = loop.bMs
                speed = YtLoopPlan.nearestSpeed(loop.speed)
                pauseMs = YtLoopPlan.nearestPause(loop.pauseMs)
                selectChips()
                page.start(YtLoopPlan.of(loop.aMs, loop.bMs, page.durationMs(), loop.speed, loop.pauseMs) ?: return@setOnClickListener)
                YtHaptics.of(it).confirm()
                render(page, page.shown)
            }
            binding.loopSaved.addView(chip)
        }
    }

    private fun toast(text: Int) {
        Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()
    }
}
