package com.github.libretube.ui.yt.player

import android.content.Context
import com.github.libretube.R
import com.github.libretube.obj.BottomSheetItem
import com.github.libretube.services.YtBatteryQualityModule

/**
 * YT: the player's quality sheet shows what was asked for, which the low-battery cap may
 * lower. While the cap holds ([YtBatteryQualityModule.capNow]), the selected entry carries it,
 * e.g. "Auto (Battery limit: 480p · 30 fps)"; otherwise the sheet is upstream's plain list.
 */
object YtBatteryCapLabel {
    fun items(context: Context, titles: List<String>, preselectedItem: String?): List<BottomSheetItem> {
        val cap = YtBatteryQualityModule.capNow()
        val note = cap?.let { (height, fps) -> context.getString(R.string.yt_battery_cap_now, height, fps) }
        return titles.map { title ->
            val selected = title == preselectedItem
            BottomSheetItem(
                title,
                isSelected = selected,
                getCurrent = { if (selected) note else null },
            )
        }
    }
}
