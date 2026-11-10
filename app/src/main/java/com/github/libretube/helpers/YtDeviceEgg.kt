package com.github.libretube.helpers

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.BitmapDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * YT: a one-time, local, on-device surprise for a single phone. It is completely inert unless the
 * device is that exact model AND a one-time flag has not been set yet; once it has run, the flag
 * stays set and nothing here ever does anything again. Nothing is read from or sent over the
 * network, and no user data (watch positions, player, prefs other than the single flag) is touched.
 *
 * All hooks in existing files call into here and are a no-op when not [armed].
 */
object YtDeviceEgg {
    /** The one-time flag: once set, the whole feature is dead for good. */
    const val DONE_KEY = "yt_device_egg_done"

    /** Build.MODEL of the target phone starts with this (case-insensitive). */
    internal const val MODEL_PREFIX = "SM-G998"

    private const val ASSET = "yt_device_egg.jpg"

    // Timeline.
    private const val LAG_START_DELAY_MS = 800L   // let the cold start finish before hitching
    private const val HITCH_SLEEP_MS = 230L       // one unresponsive block (well under the 5 s ANR)
    private const val HITCH_GAP_MS = 18L          // always yield to the looper between blocks
    private const val ROW_STAGGER_MS = 750L       // a new row turns roughly every 0.75 s
    private const val ROW_FADE_MS = 500L
    private const val BUILDUP_MS = 7200L          // staggered row takeover window
    private const val FINALE_HOLD_MS = 3000L      // fullscreen image + vibration + sound
    private const val VIBRATION_MS = 3000L

    // lazy so the object initializes on a plain JVM (the pure helpers are unit-tested there)
    private val main by lazy { Handler(Looper.getMainLooper()) }

    @Volatile private var fired = false         // finale begun or done in this process
    @Volatile private var lagging = false
    @Volatile private var finaleStarted = false

    /**
     * Pure gate: true for the target phone's model. "SM-G998B" / "sm-g998u" yes, the plain
     * "SM-G998" prefix yes, "SM-G991B" and "SM-G973F" no. Exposed for the unit test.
     */
    fun modelMatches(model: String?): Boolean =
        model != null && model.trim().uppercase(Locale.ROOT).startsWith(MODEL_PREFIX)

    private fun armed(): Boolean {
        if (fired) return false
        if (!modelMatches(Build.MODEL)) return false
        return !PreferenceHelper.getBoolean(DONE_KEY, false)
    }

    // ---- fake lag: hitch the main thread in short blocks, always yielding between them ----

    private val hitch = object : Runnable {
        override fun run() {
            if (!lagging) return
            try {
                Thread.sleep(HITCH_SLEEP_MS)
            } catch (_: InterruptedException) {
                // ignore: just means we stop early
            }
            // repost with a tiny delay so the looper processes input between blocks (no ANR)
            if (lagging) main.postDelayed(this, HITCH_GAP_MS)
        }
    }

    /** Hook: the main activity came to the foreground. No-op unless armed. */
    fun onActivityForeground() {
        if (lagging || !armed()) return
        lagging = true
        main.postDelayed(hitch, LAG_START_DELAY_MS)
    }

    private fun stopLag() {
        lagging = false
        main.removeCallbacks(hitch)
    }

    /**
     * Hook: the settings home screen ([com.github.libretube.ui.preferences.MainSettings], the root
     * settings list, not a sub-screen) became visible. No-op unless armed. Starts the staggered
     * row takeover, keeps the lag running during it, then runs the fullscreen finale and closes.
     */
    fun onSettingsHome(list: RecyclerView?, activity: Activity) {
        if (finaleStarted || !armed()) return
        finaleStarted = true
        val round = runCatching { roundFace(activity) }.getOrNull()
        if (list != null && round != null) startRowTakeover(list, round)
        main.postDelayed({ finale(activity) }, BUILDUP_MS)
    }

    // ---- staggered row takeover: each visible row's left area cross-fades into a round face ----

    private fun startRowTakeover(list: RecyclerView, face: Bitmap) {
        val rows = (0 until list.childCount)
            .map { list.getChildAt(it) }
            .sortedBy { it.top }
        rows.forEachIndexed { i, row ->
            main.postDelayed({ morphRow(row, face) }, i * ROW_STAGGER_MS)
        }
    }

    private fun morphRow(row: View, face: Bitmap) {
        if (!row.isAttachedToWindow || row.height <= 0) return
        val size = min(row.height - dp(row, 8f), dp(row, 44f)).coerceAtLeast(dp(row, 24f))
        val drawable = BitmapDrawable(row.resources, face)
        val left = dp(row, 16f)
        val top = (row.height - size) / 2
        drawable.setBounds(left, top, left + size, top + size)
        drawable.alpha = 0
        row.overlay.add(drawable)
        // choppy cross-fade: the ~4 FPS lag is still running, so this looks broken on purpose
        ValueAnimator.ofInt(0, 255).apply {
            duration = ROW_FADE_MS
            addUpdateListener {
                drawable.alpha = it.animatedValue as Int
                row.invalidate()
            }
            start()
        }
    }

    // ---- fullscreen finale ----

    private fun finale(activity: Activity) {
        stopLag()
        if (activity.isFinishing || activity.isDestroyed) {
            done(activity)
            return
        }
        runCatching {
            val root = FrameLayout(activity).apply {
                setBackgroundColor(Color.BLACK)
                isClickable = true // swallow touches behind the image
            }
            val image = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(loadAsset(activity))
            }
            root.addView(
                image,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            activity.window.addContentView(
                root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        vibrate(activity)
        playFart(activity)
        main.postDelayed({ done(activity) }, FINALE_HOLD_MS)
    }

    private fun done(activity: Activity) {
        fired = true
        stopLag()
        PreferenceHelper.putBoolean(DONE_KEY, true)
        runCatching { BackgroundHelper.stopBackgroundPlay(activity.applicationContext) }
        runCatching { activity.finishAffinity() }
        main.postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 150L)
    }

    private fun vibrate(ctx: Context) {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Vibrator::class.java)
            } ?: return
            vibrator.vibrate(
                VibrationEffect.createOneShot(VIBRATION_MS, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        }
    }

    private fun playFart(ctx: Context) {
        Thread {
            runCatching {
                val sampleRate = 44100
                val wave = fartWaveform(sampleRate)
                val pcm = ShortArray(wave.size) { (wave[it] * 32767f * 0.92f).toInt().toShort() }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(pcm, 0, pcm.size)
                track.play()
            }
        }.start()
    }

    /**
     * Pure, testable: a synthesized low, buzzy, wobbling tone with noise and a short sputter, with
     * a quick attack and a decay to silence. Returns mono samples in [-1, 1] (with headroom, so no
     * sample reaches the rail). Exposed for the unit test.
     */
    fun fartWaveform(sampleRate: Int, durationSec: Float = 1.7f): FloatArray {
        val n = (sampleRate * durationSec).toInt().coerceAtLeast(1)
        val out = FloatArray(n)
        val rnd = java.util.Random(0x6AE7)
        val twoPi = 2.0 * PI
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / sampleRate
            val p = t / durationSec // 0..1 progress
            // pitch drifts 115 -> 80 Hz, with a vibrato wobble and a faster flutter near the end
            val vibrato = sin(twoPi * 6.5 * t) * 18.0
            val sputter = sin(twoPi * 11.0 * t) * (6.0 * p)
            val freq = (115.0 - 35.0 * p) + vibrato + sputter
            phase += twoPi * freq / sampleRate
            if (phase > twoPi) phase -= twoPi
            val saw = (phase / PI) - 1.0                 // sawtooth, [-1, 1]
            val square = if (phase < PI) 0.4 else -0.4   // a little buzz on top
            val noise = (rnd.nextDouble() * 2.0 - 1.0) * 0.14
            var s = saw * 0.7 + square * 0.25 + noise
            // amplitude envelope: quick attack, body, decay to silence, tremolo sputter at the end
            val attack = (t / 0.03).coerceAtMost(1.0)
            val release = ((durationSec - t) / 0.25).coerceIn(0.0, 1.0)
            val tremolo = 1.0 - 0.5 * p * (0.5 + 0.5 * sin(twoPi * 9.0 * t))
            s *= attack * release * tremolo * 0.9
            out[i] = s.coerceIn(-1.0, 1.0).toFloat()
        }
        return out
    }

    private fun loadAsset(ctx: Context): Bitmap =
        ctx.assets.open(ASSET).use { BitmapFactory.decodeStream(it) }

    /** A small circular thumbnail cropped from the upper part of the bundled portrait photo. */
    private fun roundFace(ctx: Context): Bitmap {
        val src = loadAsset(ctx)
        val side = min(src.width, src.height)
        val x = (src.width - side) / 2
        // faces sit in the upper part of a portrait: crop a little below the top, not the centre
        val y = ((src.height - side) * 0.15f).toInt().coerceAtLeast(0)
        val square = Bitmap.createBitmap(src, x, y, side, side)
        val target = 160
        val scaled = Bitmap.createScaledBitmap(square, target, target, true)
        val out = Bitmap.createBitmap(target, target, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawCircle(target / 2f, target / 2f, target / 2f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(scaled, 0f, 0f, paint)
        return out
    }

    private fun dp(view: View, value: Float): Int =
        (value * view.resources.displayMetrics.density).toInt()
}
