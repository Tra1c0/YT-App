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

        /** Battery saver is on: Android does not vibrate. */
        POWER_SAVE,

        /** The ringer is on silent: no vibration. */
        SILENT,

        /** Notification vibration is off in the system settings. */
        SYSTEM_OFF,

        /** The vibrator cannot vary its strength: the 5 % swell plays as pulses. */
        SWELL_AS_PULSES,
    }

    /**
     * How long the picture part of a cue runs ([motion] false = the colour frame only; [strong]
     * = the cue cannot vibrate and holds longer, as in the player).
     */
    fun visualMs(motion: Boolean, strong: Boolean = false): Long =
        if (motion) YtBatteryCues.totalMs(strong) else YtBatteryCues.colorFadeMs(strong)

    /** How long [cue]'s vibration runs, with ([amplitude]) or without amplitude control. */
    fun vibrationMs(cue: YtBatteryCue, amplitude: Boolean): Long =
        if (amplitude) cue.timings.sum() else cue.plainTimings.sum()

    /**
     * How long [cue] lasts in the preview: the picture part or, when it vibrates ([vibrates]),
     * the vibration if that is longer (the 5 % swell outlasts the picture). Without a vibration
     * the picture is the strong one ([YtBatteryVibeRules.strongVisual]).
     */
    fun lengthMs(cue: YtBatteryCue, motion: Boolean, vibrates: Boolean, amplitude: Boolean): Long {
        val visual = visualMs(motion, strong = !vibrates)
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
     * The notes for the current settings ([motion] = "Animations"), why the cue would not vibrate
     * ([blocked], [YtBatteryVibeRules.blocked]; null = it vibrates) and whether the vibrator can
     * vary its strength ([amplitude], only mentioned when it vibrates).
     */
    fun notes(motion: Boolean, blocked: YtBatteryVibeRules.Block?, amplitude: Boolean): List<Note> = buildList {
        if (!motion) add(Note.MOTION_OFF)
        when (blocked) {
            YtBatteryVibeRules.Block.HAPTICS_OFF -> add(Note.HAPTICS_OFF)
            YtBatteryVibeRules.Block.NO_VIBRATOR -> add(Note.NO_VIBRATOR)
            YtBatteryVibeRules.Block.POWER_SAVE -> add(Note.POWER_SAVE)
            YtBatteryVibeRules.Block.SILENT -> add(Note.SILENT)
            YtBatteryVibeRules.Block.SYSTEM_OFF -> add(Note.SYSTEM_OFF)
            null -> if (!amplitude) add(Note.SWELL_AS_PULSES)
        }
    }

    /** Whether the preview vibrates at all. */
    fun vibrates(blocked: YtBatteryVibeRules.Block?): Boolean = blocked == null
}
