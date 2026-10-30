package com.github.libretube.ui.yt

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext

/** The tilt of the phone for the screen (see [rememberYtGravity]); upright (0, 1). */
val LocalYtTilt = staticCompositionLocalOf<State<Offset>> { mutableStateOf(Offset(0f, 1f)) }

/** The angle of the light on glass: 45° upright, leaning with the phone to the side. */
fun ytLightAngle(tilt: Offset): Float = 45f + tilt.x.coerceIn(-1f, 1f) * 50f

/**
 * YT: where gravity pulls on the screen (x right, y down, length up to 1), from the
 * accelerometer and softened. Upright the phone gives (0, 1); tilted, the pull leans to the
 * lower edge. Without [enabled] (motion switched off) or without a sensor it stays (0, 1).
 */
@Composable
fun rememberYtGravity(enabled: Boolean, fast: Boolean = false): State<Offset> {
    val context = LocalContext.current
    val gravity = remember { mutableStateOf(Offset(0f, 1f)) }
    DisposableEffect(enabled, fast) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (!enabled || manager == null || sensor == null) {
            gravity.value = Offset(0f, 1f)
            return@DisposableEffect onDispose { }
        }
        // smoothed, and only handed on when it changed noticeably, so a resting hand does not
        // redraw the screen all the time
        val threshold = if (fast) 0.004f else 0.03f
        var smoothX = gravity.value.x
        var smoothY = gravity.value.y
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                // the sensor reports the reaction to gravity: upright it reads +9.81 on y
                val x = -event.values[0] / SensorManager.GRAVITY_EARTH
                val y = event.values[1] / SensorManager.GRAVITY_EARTH
                smoothX += (x - smoothX) * 0.15f
                smoothY += (y - smoothY) * 0.15f
                val old = gravity.value
                if (kotlin.math.abs(smoothX - old.x) > threshold || kotlin.math.abs(smoothY - old.y) > threshold) {
                    gravity.value = Offset(smoothX, smoothY)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        manager.registerListener(listener, sensor, if (fast) SensorManager.SENSOR_DELAY_GAME else SensorManager.SENSOR_DELAY_UI)
        onDispose { manager.unregisterListener(listener) }
    }
    return gravity
}
