package com.kyant.backdrop

/*
 * Vendored from Kyant0/AndroidLiquidGlass (backdrop 2.0.1, commit 65ab177), Apache License 2.0,
 * see third_party/backdrop/LICENSE. YT: flattened to Android (no expect/actual),
 * without the kyant shapes and JetBrains annotations dependencies.
 */
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast

@ChecksSdkIntAtLeast(Build.VERSION_CODES.S)
fun isRenderEffectSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@ChecksSdkIntAtLeast(Build.VERSION_CODES.TIRAMISU)
fun isRuntimeShaderSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
