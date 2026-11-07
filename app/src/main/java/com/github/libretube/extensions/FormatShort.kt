package com.github.libretube.extensions

import com.github.libretube.ui.yt.YtCountFormat

/** YT: compact counts ("327", "~27k", "~1,2 Mio."), see [YtCountFormat]. */
fun Long?.formatShort(): String = YtCountFormat.format(this ?: 0)
