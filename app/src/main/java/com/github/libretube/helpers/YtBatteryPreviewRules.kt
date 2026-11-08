package com.github.libretube.helpers

/**
 * YT: the pure part of the battery cue preview (Player settings → Battery → Preview): when each
 * level plays in "all in a row", how long one level lasts, and which notes explain what the
 * current settings and the device leave out. The cue itself (colours, vibrations, timing) comes
 * from [YtBatteryCues] unchanged, so the preview is exactly what a real discharge shows.
 */
object YtBatteryPreviewRules {
    /** The quiet time between two levels in "all in a row". */
    const val PAUSE_MS = 700L

    /** One level of a preview run: [mark] starts [atMs] after the run starts and lasts [lengthMs]. */
    data class Step(val mark: Int, val atMs: Long, val lengthMs: Long)

    /** What the preview tells the user below the picture. */
    enum class Note {
        /** Animations are off: the video does not move, only the colour frame shows. */
        MOTION_OFF,

        /** "Custom haptics" is off: the cue does not vibrate. */
        HAPTICS_OFF,

        /** The device has no vibrator. */
        NO_VIBRATOR,

        /** The vibrator cannot vary its strength: the 5 % swell plays as pulses. */
        SWELL_AS_PULSES,
    }

    /** How long the picture part of a cue runs ([motion] false = the colour frame only). */
    fun visualMs(motion: Boolean): Long = if (motion) YtBatteryCues.TOTAL_MS else YtBatteryCues.COLOR_FADE_MS

    /** How long [cue]'s vibration runs, with ([amplitude]) or without amplitude control. */
    fun vibrationMs(cue: YtBatteryCue, amplitude: Boolean): Long =
        if (amplitude) cue.timings.sum() else cue.plainTimings.sum()

    /**
     * How long [cue] lasts in the preview: the picture part or, when it vibrates ([vibrates]),
     * the vibration if that is longer (the 5 % swell outlasts the picture).
     */
    fun lengthMs(cue: YtBatteryCue, motion: Boolean, vibrates: Boolean, amplitude: Boolean): Long {
        val visual = visualMs(motion)
        return if (vibrates) maxOf(visual, vibrationMs(cue, amplitude)) else visual
    }

    /**
     * "All in a row": [marks] (20 → 15 → 10 → 5) one after the other, each starting [pause] ms
     * after the one before ended, so no level cuts the one before short.
     */
    fun sequence(
        motion: Boolean,
        vibrates: Boolean,
        amplitude: Boolean,
        marks: IntArray = YtBatteryCues.MARKS,
        pause: Long = PAUSE_MS,
    ): List<Step> {
        var at = 0L
        return marks.toList().mapNotNull { mark ->
            val cue = YtBatteryCues.of(mark) ?: return@mapNotNull null
            val length = lengthMs(cue, motion, vibrates, amplitude)
            Step(mark, at, length).also { at += length + pause }
        }
    }

    /** When a run of [steps] has ended completely. */
    fun endMs(steps: List<Step>): Long = steps.lastOrNull()?.let { it.atMs + it.lengthMs } ?: 0L

    /**
     * The notes for the current settings ([motion] = "Animations", [haptics] = "Custom haptics")
     * and the device ([hasVibrator], [amplitude] = it can vary the strength). With haptics off
     * the device's abilities do not matter, so they are not mentioned.
     */
    fun notes(motion: Boolean, haptics: Boolean, hasVibrator: Boolean, amplitude: Boolean): List<Note> = buildList {
        if (!motion) add(Note.MOTION_OFF)
        when {
            !haptics -> add(Note.HAPTICS_OFF)
            !hasVibrator -> add(Note.NO_VIBRATOR)
            !amplitude -> add(Note.SWELL_AS_PULSES)
        }
    }

    /** Whether the preview vibrates at all. */
    fun vibrates(haptics: Boolean, hasVibrator: Boolean): Boolean = haptics && hasVibrator
}
