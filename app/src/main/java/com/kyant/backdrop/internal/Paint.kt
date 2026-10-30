package com.kyant.backdrop.internal

/*
 * Vendored from Kyant0/AndroidLiquidGlass (backdrop 2.0.1, commit 65ab177), Apache License 2.0,
 * see third_party/backdrop/LICENSE. YT: flattened to Android (no expect/actual),
 * without the kyant shapes and JetBrains annotations dependencies.
 */
import android.graphics.BlurMaskFilter
import androidx.compose.ui.graphics.Paint
import com.kyant.backdrop.RuntimeShader
import com.kyant.backdrop.asAndroidRuntimeShader

internal fun Paint.blur(radius: Float) {
    this.asFrameworkPaint().maskFilter =
        if (radius > 0f) BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
        else null
}

internal fun Paint.setRuntimeShader(runtimeShader: RuntimeShader?) {
    this.asFrameworkPaint().shader = runtimeShader?.asAndroidRuntimeShader()
}
