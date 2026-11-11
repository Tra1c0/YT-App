package com.github.libretube.ui.yt

import androidx.annotation.StringRes
import com.github.libretube.R

/** YT: a place a guide entry can open: a settings screen, a page of the settings or an action. */
enum class YtGuideTarget {
    INTERACTION, PLAYER, AUDIO_VIDEO, SPONSORBLOCK, GENERAL, DIAGNOSIS, STORAGE, UPDATE;

    /** Settings screens get "Go to setting"; pages and actions their own words. */
    val isScreen: Boolean get() = ordinal <= GENERAL.ordinal
}

/**
 * One entry of the guide: a short [title], one or two sentences in [body], optionally the path
 * in the settings as text ([where]) and a place to open ([target]); on a settings screen
 * optionally the preference key [setting], whose row is scrolled to and highlighted. [key] is
 * stable: it names the entry for saved state and for the later first-start screen.
 */
data class YtGuideItem(
    val key: String,
    @StringRes val title: Int,
    @StringRes val body: Int,
    @StringRes val where: Int? = null,
    val target: YtGuideTarget? = null,
    val setting: String? = null,
)

/** A section of the guide; [note] is a short line under its title (where it applies). */
data class YtGuideSection(
    val key: String,
    @StringRes val title: Int,
    val items: List<YtGuideItem>,
    @StringRes val note: Int? = null,
)

/**
 * YT: the content of the guide ("Anleitung"): what YT does differently from the official app and
 * how to use it. Only data, so the settings page ([YtGuideFragment]) and a later first-start
 * screen show the same entries. Every entry must describe something that exists (FEATURES.md).
 */
object YtGuide {
    private fun item(
        key: String,
        @StringRes title: Int,
        @StringRes body: Int,
        @StringRes where: Int? = null,
        target: YtGuideTarget? = null,
        setting: String? = null,
    ) = YtGuideItem(key, title, body, where, target, setting)

    val sections: List<YtGuideSection> = listOf(
        YtGuideSection(
            "basics", R.string.yt_guide_s_basics, listOf(
                item("no_ads", R.string.yt_guide_no_ads_t, R.string.yt_guide_no_ads_b),
                item("no_account", R.string.yt_guide_no_account_t, R.string.yt_guide_no_account_b),
                item("local", R.string.yt_guide_local_t, R.string.yt_guide_local_b),
                item("background", R.string.yt_guide_background_t, R.string.yt_guide_background_b),
                item("pip", R.string.yt_guide_pip_t, R.string.yt_guide_pip_b, R.string.yt_guide_where_player, YtGuideTarget.PLAYER, "yt_pip_visualizer"),
                item("downloads", R.string.yt_guide_downloads_t, R.string.yt_guide_downloads_b),
                item("sponsorblock", R.string.yt_guide_sponsorblock_t, R.string.yt_guide_sponsorblock_b, R.string.yt_guide_where_sponsorblock, YtGuideTarget.SPONSORBLOCK, "sb_enabled_key"),
                item("dearrow", R.string.yt_guide_dearrow_t, R.string.yt_guide_dearrow_b, R.string.yt_guide_where_dearrow, YtGuideTarget.SPONSORBLOCK, "dearrow"),
                item("ryd", R.string.yt_guide_ryd_t, R.string.yt_guide_ryd_b, R.string.yt_guide_where_ryd, YtGuideTarget.GENERAL, "local_return_youtube_dislikes"),
            )
        ),
        YtGuideSection(
            "home", R.string.yt_guide_s_home, listOf(
                item("intent", R.string.yt_guide_intent_t, R.string.yt_guide_intent_b, R.string.yt_guide_where_home, YtGuideTarget.INTERACTION, "yt_intent_ask"),
                item("feed", R.string.yt_guide_feed_t, R.string.yt_guide_feed_b, R.string.yt_guide_where_feed),
                item("topics", R.string.yt_guide_topics_t, R.string.yt_guide_topics_b, R.string.yt_guide_where_feed),
                item("timebox", R.string.yt_guide_timebox_t, R.string.yt_guide_timebox_b, R.string.yt_guide_where_home, YtGuideTarget.INTERACTION, "yt_timebox_chip"),
                item("swipe", R.string.yt_guide_swipe_t, R.string.yt_guide_swipe_b, R.string.yt_guide_where_home, YtGuideTarget.INTERACTION, "yt_card_swipe"),
                item("pause", R.string.yt_guide_pause_t, R.string.yt_guide_pause_b, R.string.yt_guide_where_home, YtGuideTarget.INTERACTION, "yt_paused_channels_list"),
                item("drag_mini", R.string.yt_guide_drag_mini_t, R.string.yt_guide_drag_mini_b, R.string.yt_guide_where_home, YtGuideTarget.INTERACTION, "yt_home_drag"),
                item("lens", R.string.yt_guide_lens_t, R.string.yt_guide_lens_b, R.string.yt_guide_where_home, YtGuideTarget.INTERACTION, "yt_home_drag"),
                item("hold_menu", R.string.yt_guide_hold_menu_t, R.string.yt_guide_hold_menu_b),
                item("seen", R.string.yt_guide_seen_t, R.string.yt_guide_seen_b, R.string.yt_guide_where_home, YtGuideTarget.INTERACTION, "yt_seen_marks"),
                item("calm", R.string.yt_guide_calm_t, R.string.yt_guide_calm_b, R.string.yt_guide_where_home, YtGuideTarget.INTERACTION, "yt_title_case"),
            ),
            note = R.string.yt_guide_s_home_note,
        ),
        YtGuideSection(
            "search", R.string.yt_guide_s_search, listOf(
                item("search_design", R.string.yt_guide_search_design_t, R.string.yt_guide_search_design_b, R.string.yt_guide_where_search, YtGuideTarget.INTERACTION, "yt_search_entry"),
                item("search_seen", R.string.yt_guide_search_seen_t, R.string.yt_guide_search_seen_b, R.string.yt_guide_where_home, YtGuideTarget.INTERACTION, "yt_seen_marks"),
                item("search_morph", R.string.yt_guide_search_morph_t, R.string.yt_guide_search_morph_b),
            )
        ),
        YtGuideSection(
            "player", R.string.yt_guide_s_player, listOf(
                item("hold", R.string.yt_guide_hold_t, R.string.yt_guide_hold_b, R.string.yt_guide_where_gestures, YtGuideTarget.INTERACTION, "yt_hold"),
                item("magnifier", R.string.yt_guide_magnifier_t, R.string.yt_guide_magnifier_b, R.string.yt_guide_where_gestures, YtGuideTarget.INTERACTION, "yt_scrub_magnifier"),
                item("return", R.string.yt_guide_return_t, R.string.yt_guide_return_b, R.string.yt_guide_where_gestures, YtGuideTarget.INTERACTION, "yt_return_chip"),
                item("loop", R.string.yt_guide_loop_t, R.string.yt_guide_loop_b, R.string.yt_guide_where_gestures, YtGuideTarget.INTERACTION, "yt_loop_hold"),
                item("chapter", R.string.yt_guide_chapter_t, R.string.yt_guide_chapter_b, R.string.yt_guide_where_gestures, YtGuideTarget.INTERACTION, "yt_double_tap_chapter"),
                item("share", R.string.yt_guide_share_t, R.string.yt_guide_share_b, R.string.yt_guide_where_gestures, YtGuideTarget.INTERACTION, "yt_share_drag"),
                item("like", R.string.yt_guide_like_t, R.string.yt_guide_like_b),
                item("comments", R.string.yt_guide_comments_t, R.string.yt_guide_comments_b, R.string.yt_guide_where_comments, YtGuideTarget.INTERACTION, "yt_comments_scene"),
                item("speed", R.string.yt_guide_speed_t, R.string.yt_guide_speed_b, R.string.yt_guide_where_speed, YtGuideTarget.PLAYER, "yt_channel_speeds_list"),
            )
        ),
        YtGuideSection(
            "mini", R.string.yt_guide_s_mini, listOf(
                item("follow", R.string.yt_guide_follow_t, R.string.yt_guide_follow_b, R.string.yt_guide_where_minimize, YtGuideTarget.INTERACTION, "yt_minimize_follow"),
                item("peek", R.string.yt_guide_peek_t, R.string.yt_guide_peek_b, R.string.yt_guide_where_mini, YtGuideTarget.INTERACTION, "yt_mini_peek"),
                item("restore", R.string.yt_guide_restore_t, R.string.yt_guide_restore_b),
                item("soft_close", R.string.yt_guide_soft_close_t, R.string.yt_guide_soft_close_b),
                item("float", R.string.yt_guide_float_t, R.string.yt_guide_float_b, R.string.yt_guide_where_float, YtGuideTarget.PLAYER, "yt_pip_visualizer"),
            )
        ),
        YtGuideSection(
            "playback", R.string.yt_guide_s_playback, listOf(
                item("positions", R.string.yt_guide_positions_t, R.string.yt_guide_positions_b),
                item("queue", R.string.yt_guide_queue_t, R.string.yt_guide_queue_b),
                item("queue_restart", R.string.yt_r25_guide_queue_restart_t, R.string.yt_r25_guide_queue_restart_b),
                item("audio_only", R.string.yt_guide_audio_only_t, R.string.yt_guide_audio_only_b, R.string.yt_guide_where_audio_only, YtGuideTarget.GENERAL, "audio_only_mode"),
                item("loud", R.string.yt_guide_loud_t, R.string.yt_guide_loud_b, R.string.yt_guide_where_loud, YtGuideTarget.AUDIO_VIDEO, "yt_loud"),
                item("music", R.string.yt_guide_music_t, R.string.yt_guide_music_b, R.string.yt_guide_where_music, YtGuideTarget.PLAYER, "yt_music_audio_background"),
            )
        ),
        YtGuideSection(
            "battery", R.string.yt_guide_s_battery, listOf(
                item("battery_quality", R.string.yt_guide_battery_quality_t, R.string.yt_guide_battery_quality_b, R.string.yt_guide_where_battery, YtGuideTarget.PLAYER, "yt_battery_quality"),
                item("battery_cue", R.string.yt_guide_battery_cue_t, R.string.yt_guide_battery_cue_b, R.string.yt_guide_where_battery, YtGuideTarget.PLAYER, "yt_battery_cue"),
            )
        ),
        YtGuideSection(
            "help", R.string.yt_guide_s_help, listOf(
                item("diagnosis", R.string.yt_guide_diagnosis_t, R.string.yt_guide_diagnosis_b, R.string.yt_guide_where_diagnosis, YtGuideTarget.DIAGNOSIS),
                item("crash", R.string.yt_guide_crash_t, R.string.yt_guide_crash_b),
                item("storage", R.string.yt_guide_storage_t, R.string.yt_guide_storage_b, R.string.yt_guide_where_storage, YtGuideTarget.STORAGE),
                item("updates", R.string.yt_guide_updates_t, R.string.yt_guide_updates_b, R.string.yt_guide_where_update, YtGuideTarget.UPDATE),
            )
        ),
    )

    /** The sections with their texts looked up by [text] (a string resource lookup). */
    fun resolve(text: (Int) -> String): List<YtGuideRules.Group> = sections.map { section ->
        YtGuideRules.Group(
            key = section.key,
            title = text(section.title),
            note = section.note?.let(text),
            entries = section.items.map { item ->
                YtGuideRules.Entry(
                    key = item.key,
                    title = text(item.title),
                    body = text(item.body),
                    where = item.where?.let(text),
                )
            },
        )
    }

    /** The item behind [key], for its target. */
    fun item(key: String): YtGuideItem? = sections.firstNotNullOfOrNull { s -> s.items.firstOrNull { it.key == key } }
}
