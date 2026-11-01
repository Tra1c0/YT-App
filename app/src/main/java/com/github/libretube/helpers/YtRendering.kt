package com.github.libretube.helpers

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.github.libretube.ui.yt.YtRenderingPolicy
import com.kyant.backdrop.BackdropHardwareEffects
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Probe graphics once, away from startup drawing; retain the glass layout on every backend. */
object YtRendering {
    private var initialized = false
    @Volatile private var renderer: String? = null

    fun initialize(context: Context) {
        if (initialized || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        initialized = true
        val app = context.applicationContext
        val lowRam = app.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
        val power = app.getSystemService(PowerManager::class.java)
        fun update() {
            BackdropHardwareEffects.enabled = YtRenderingPolicy.hardwareEffects(renderer, lowRam, power?.isPowerSaveMode == true)
        }
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = update()
        }, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            renderer = runCatching { probeRenderer() }
                .onFailure { Log.d("YtRendering", "Using tinted glass: ${it.javaClass.simpleName}") }
                .getOrNull()
            Handler(Looper.getMainLooper()).post {
                update()
                Log.d("YtRendering", "renderer=$renderer hardwareEffects=${BackdropHardwareEffects.enabled}")
            }
        }
    }

    private fun probeRenderer(): String? {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1)) return null
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        var context = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        try {
            val attrs = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
            if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) || count[0] == 0) return null
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            if (context == EGL14.EGL_NO_CONTEXT) return null
            surface = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            if (surface == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(display, surface, surface, context)) return null
            return GLES20.glGetString(GLES20.GL_RENDERER)
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            EGL14.eglReleaseThread()
        }
    }
}
