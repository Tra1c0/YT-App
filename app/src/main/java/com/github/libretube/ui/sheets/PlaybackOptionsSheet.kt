package com.github.libretube.ui.sheets

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.media3.common.PlaybackParameters
import androidx.media3.session.MediaController
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.libretube.R
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.databinding.PlaybackBottomSheetBinding
import com.github.libretube.enums.PlayerCommand
import com.github.libretube.extensions.round
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.YtChannelSpeed
import com.github.libretube.helpers.YtSpeedRules
import com.github.libretube.helpers.YtThrottle
import com.github.libretube.services.AbstractPlayerService
import com.github.libretube.ui.adapters.SliderLabelsAdapter
import kotlin.math.absoluteValue
import kotlin.math.log
import kotlin.math.pow

class PlaybackOptionsSheet(
    private val player: MediaController
) : ExpandedBottomSheet(R.layout.playback_bottom_sheet) {
    private var _binding: PlaybackBottomSheetBinding? = null
    private val binding get() = _binding!!

    // YT: slider drags are sent at most every 150 ms (the last value always arrives), so the
    // audio processors are not rebuilt for every pixel (see YtSpeedRules)
    private val handler = Handler(Looper.getMainLooper())
    private val throttle = YtThrottle()
    private var lastSent: PlaybackParameters? = null
    private var sendPending = false
    private val sendNow = Runnable { sendParameters() }

    // YT: the channel of the video, for "Für diesen Kanal merken" (null: no channel known)
    private var channelId: String? = null
    private var channelName: String = ""

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = PlaybackBottomSheetBinding.bind(view)

        binding.speedShortcuts.layoutManager =
            LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)

        binding.speedShortcuts.adapter = SliderLabelsAdapter(SUGGESTED_SPEEDS) {
            binding.speed.value = it
        }

        // YT: snapped to the sliders' grid and range, which throw for any other value
        binding.speed.value = YtSpeedRules.sliderSpeed(player.playbackParameters.speed)
        binding.pitch.value = YtSpeedRules.quantizeSemitone(playbackPitchToSemitone(player.playbackParameters.pitch))
        lastSent = player.playbackParameters
        setupChannelSpeed()

        val currentSemitone = binding.pitch.value.round(2)
        binding.semitoneEditText.setText(currentSemitone.toString())
        binding.pitchResetButton.isGone = currentSemitone == 0f

        binding.semitoneEditText.setOnEditorActionListener { editText, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_DONE) {
                return@setOnEditorActionListener false
            }

            if (editText.text.isEmpty()) {
                // reset to previous value
                binding.semitoneEditText.setText(binding.pitch.value.round(2).toString())
                clearEditTextFocusAndHideKeyboard()
                return@setOnEditorActionListener false
            }

            // YT: "-" or "." alone do not parse; treated like a value out of range
            val enteredSemitoneValue = editText.text.toString().toFloatOrNull() ?: Float.NaN
            if (enteredSemitoneValue.isNaN() || enteredSemitoneValue.absoluteValue > SEMITONES_IN_ONE_OCTAVE) {
                editText.error = context?.getString(
                    R.string.playback_pitch_semitone_error_input,
                    SEMITONES_IN_ONE_OCTAVE
                )

                // return true here to prevent the keyboard from closing when
                // the user entered a wrong value
                return@setOnEditorActionListener true
            }
            changePlaybackPitchInSemitone(YtSpeedRules.quantizeSemitone(enteredSemitoneValue))
            clearEditTextFocusAndHideKeyboard()

            true
        }

        binding.pitchResetButton.setOnClickListener {
            changePlaybackPitchInSemitone(0f)
            clearEditTextFocusAndHideKeyboard()
        }

        binding.semitoneDecrementButton.setOnClickListener {
            incrementDecrementSemitoneBy(-1.0f)
            clearEditTextFocusAndHideKeyboard()
        }

        binding.semitoneIncrementButton.setOnClickListener {
            incrementDecrementSemitoneBy(1.0f)
            clearEditTextFocusAndHideKeyboard()
        }

        PreferenceHelper.getBoolean(PreferenceKeys.SKIP_SILENCE, false).let {
            binding.skipSilence.isChecked = it
        }

        binding.speed.addOnChangeListener { _, _, _ ->
            onChange()
        }
        binding.speed.setLabelFormatter { value ->
            return@setLabelFormatter "${value}x"
        }

        binding.pitch.addOnChangeListener { _, _, _ ->
            onChange()
        }
        binding.pitch.setLabelFormatter { value ->
            return@setLabelFormatter "${value.round(2)}"
        }

        binding.skipSilence.setOnCheckedChangeListener { _, isChecked ->
            player.sendCustomCommand(
                AbstractPlayerService.runPlayerActionCommand,
                bundleOf(PlayerCommand.SKIP_SILENCE.name to isChecked)
            )
            PreferenceHelper.putBoolean(PreferenceKeys.SKIP_SILENCE, isChecked)
        }
    }

    override fun onDestroyView() {
        // YT: a change still waiting for its turn is sent before the sheet goes
        handler.removeCallbacks(sendNow)
        if (sendPending) sendParameters()
        super.onDestroyView()
        _binding = null
    }

    private fun onChange() {
        binding.semitoneEditText.setText((binding.pitch.value).round(2).toString())
        binding.pitchResetButton.isGone = binding.pitch.value == 0f

        // YT: throttled instead of one MediaController call per slider pixel
        sendPending = true
        handler.removeCallbacks(sendNow)
        val delay = throttle.delayAt(SystemClock.uptimeMillis())
        if (delay == 0L) sendParameters() else handler.postDelayed(sendNow, delay)
    }

    /** YT: sends what the sliders show now, clamped, unless it is what was sent last. */
    private fun sendParameters() {
        sendPending = false
        val b = _binding ?: return
        val params = PlaybackParameters(
            YtSpeedRules.clampSpeed(b.speed.value),
            YtSpeedRules.clampPitch(semitoneToPlaybackPitch(YtSpeedRules.quantizeSemitone(b.pitch.value)))
        )
        if (params == lastSent) return
        lastSent = params
        throttle.sent(SystemClock.uptimeMillis())
        player.playbackParameters = params

        // YT: with "Für diesen Kanal merken" on, the speed belongs to the channel, not to the
        // general setting
        val id = channelId
        if (id != null && b.ytChannelSpeed.isChecked) {
            YtChannelSpeed.put(id, channelName, params.speed)
        } else {
            PreferenceHelper.putString(PreferenceKeys.PLAYBACK_SPEED, params.speed.toString())
        }
    }

    /** YT: the "Für diesen Kanal merken" switch with the channel's name below it. */
    private fun setupChannelSpeed() {
        val metadata = player.mediaMetadata
        val id = metadata.composer?.toString()?.takeIf { it.isNotBlank() }
        channelId = id
        channelName = metadata.artist?.toString().orEmpty()
        binding.ytChannelSpeed.isGone = id == null
        binding.ytChannelSpeedName.isGone = id == null || channelName.isBlank()
        if (id == null) return
        binding.ytChannelSpeedName.text = channelName
        binding.ytChannelSpeed.isChecked = YtChannelSpeed.find(id) != null
        binding.ytChannelSpeed.setOnCheckedChangeListener { _, on ->
            if (on) {
                YtChannelSpeed.put(id, channelName, YtSpeedRules.clampSpeed(binding.speed.value))
            } else {
                // the channel has no speed of its own any more: back to the general one
                YtChannelSpeed.remove(id)
                val general = YtSpeedRules.sliderSpeed(YtChannelSpeed.generalSpeed())
                if (binding.speed.value != general) binding.speed.value = general
            }
        }
    }

    private fun clearEditTextFocusAndHideKeyboard() {
        val inputMethodManager =
            context?.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.hideSoftInputFromWindow(binding.semitoneEditText.windowToken, 0)
        binding.semitoneEditText.clearFocus()
        binding.semitoneEditText.error = null
    }

    private fun changePlaybackPitchInSemitone(semitone: Float) {
        binding.pitch.value = semitone
        onChange()
    }

    // Reference: https://en.wikipedia.org/wiki/Twelfth_root_of_two
    // From the wiki, "Each note has a frequency that is 2^(1/12) times that of the one below it.", so
    // to get the desired frequency (or in this case playback pitch), we multiply the original frequency
    // with the ratio: 2^(1/12). And for n semitone, we raise the ratio to the power of n and multiply
    // that to the original frequency. So, the final equation:
    //
    // desiredPlaybackPitch = defaultPlaybackPitch * (ratio^n)
    //
    // And the defaultPlaybackPitch value is 1.0f, so we can omit that
    private fun semitoneToPlaybackPitch(semitone: Float): Float {
        return SEMITONE_RATIO.pow(semitone).toFloat()
    }

    // Get the exponent(or in this case semitone) value from a known base (the semitone's ratio)
    private fun playbackPitchToSemitone(playbackPitch: Float): Float {
        return log(playbackPitch, SEMITONE_RATIO)
    }

    private fun incrementDecrementSemitoneBy(value: Float) {
        var currentSemitone = binding.pitch.value
        currentSemitone = currentSemitone + value
        currentSemitone =
            currentSemitone.coerceIn(-SEMITONES_IN_ONE_OCTAVE, SEMITONES_IN_ONE_OCTAVE)
        changePlaybackPitchInSemitone(currentSemitone)
    }

    companion object {
        private val SUGGESTED_SPEEDS = listOf(0.5f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

        // Semitone's ratio in equal temperament is the 12th root of 2, or 2^(1/12)
        // References:
        // -https://en.wikipedia.org/wiki/12_equal_temperament
        // -https://en.wikipedia.org/wiki/Twelfth_root_of_two
        private const val SEMITONE_RATIO = 1.059463f

        private const val SEMITONES_IN_ONE_OCTAVE = 12.0f
    }
}
