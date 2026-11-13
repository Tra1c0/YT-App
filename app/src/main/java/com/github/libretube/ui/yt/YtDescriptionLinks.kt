package com.github.libretube.ui.yt

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.text.method.LinkMovementMethodCompat
import com.github.libretube.NavDirections
import com.github.libretube.R
import com.github.libretube.api.obj.Streams
import com.github.libretube.enums.PlaylistType
import com.github.libretube.helpers.ContextHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.ui.activities.MainActivity
import com.github.libretube.ui.views.DescriptionLayout
import com.github.libretube.ui.yt.YtDescriptionLinkRules.Kind
import com.github.libretube.ui.yt.YtDescriptionLinkRules.Link
import com.github.libretube.ui.yt.YtDescriptionLinkRules.Overview

/*
 * YT: the link overview under a video description (timestamps, channels, videos and playlists,
 * external links, and the ads folded into a group of their own), and ad blocks folded in the
 * description text itself. One composable for every design (WatchDescription in YtWatchPages.kt)
 * and for the classic page (YtDescriptionClassic below). The sorting is the pure
 * YtDescriptionLinkRules; taps reuse the player page's link handling (PlayerFragment.handleLink,
 * which plays videos and seeks in the current one, and asks YtDescriptionNav for channels and
 * playlists).
 */

/** Seeks the playing video (milliseconds); provided by the page, null hides the timestamps. */
val LocalYtDescriptionSeek = staticCompositionLocalOf<((Long) -> Unit)?> { null }

/** Opens channel and playlist links inside the app instead of handing them to the system. */
object YtDescriptionNav {
    /** @return true when [link] was a channel or playlist and is now open. */
    fun openInApp(context: Context, link: String): Boolean {
        val target = runCatching { YtDescriptionLinkRules.classify(link) }.getOrNull() ?: return false
        val activity = ContextHelper.tryUnwrapActivity<MainActivity>(context) ?: return false
        return runCatching {
            when (target.kind) {
                Kind.CHANNEL -> {
                    val channel = target.channel ?: return false
                    if (channel.id != null) {
                        // the upstream way, it also minimizes the player
                        NavigationHelper.navigateChannel(context, channel.id)
                        return true
                    }
                    // a handle ("@name") or a custom name
                    activity.navController.navigate(NavDirections.openChannel(channelName = channel.name ?: return false))
                }
                Kind.PLAYLIST -> {
                    val id = target.playlistId ?: return false
                    NavigationHelper.navigatePlaylist(context, id, PlaylistType.PUBLIC)
                }
                else -> return false
            }
            minimizePlayer(activity)
            true
        }.getOrDefault(false)
    }

    private fun minimizePlayer(activity: MainActivity) {
        runCatching {
            activity.runOnPlayerFragment {
                binding.playerMotionLayout.transitionToEnd()
                true
            }
            activity.minimizePlayerContainerLayout()
        }
    }
}

/**
 * Folds the ad blocks of [text] (the description as rendered, with its link spans) into one
 * tappable line each, or, [open], marks them with a line above that folds them again.
 */
object YtSponsorFold {
    fun apply(
        context: Context,
        text: CharSequence,
        overview: Overview,
        open: Boolean,
        markColor: Int,
        onToggle: () -> Unit,
    ): CharSequence {
        val ranges = runCatching { YtDescriptionLinkRules.foldRanges(text.toString(), overview) }.getOrDefault(emptyList())
        if (ranges.isEmpty()) return text
        val label = context.getString(if (open) R.string.yt_r29_desc_ads_open else R.string.yt_r29_desc_ads_folded)
        val builder = SpannableStringBuilder(text)
        // from the end, so the earlier ranges keep their offsets
        for (range in ranges.sortedByDescending { it.first }) {
            val mark = if (open) "▾ $label\n" else "▸ $label"
            if (open) {
                builder.insert(range.first, mark)
            } else {
                builder.replace(range.first, range.last + 1, mark)
            }
            val end = range.first + mark.length - if (open) 1 else 0
            builder.setSpan(MarkSpan(markColor, onToggle), range.first, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(StyleSpan(Typeface.ITALIC), range.first, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(RelativeSizeSpan(0.88f), range.first, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return builder
    }

    private class MarkSpan(private val color: Int, private val onToggle: () -> Unit) : ClickableSpan() {
        override fun onClick(widget: View) = onToggle()

        override fun updateDrawState(ds: TextPaint) {
            ds.color = color
            ds.isUnderlineText = false
        }
    }
}

/**
 * The links of a description sorted by type, each group folded behind its title. Taps: a
 * timestamp seeks ([LocalYtDescriptionSeek]), everything else goes to [onLink] (the page's link
 * handling). Text in [color] and [accent] as chosen by the page for the surface the description
 * stands on (Pitfall P1: no palette text colour here).
 */
@Composable
fun YtDescriptionLinks(
    overview: Overview,
    onLink: (String) -> Unit,
    color: Color,
    accent: Color,
    font: FontFamily,
    modifier: Modifier = Modifier,
    sizeSp: Float = 14f,
) {
    val seek = LocalYtDescriptionSeek.current
    val timestamps = if (seek != null) overview.timestamps else emptyList()
    if (timestamps.isEmpty() && overview.channels.isEmpty() && overview.videos.isEmpty() &&
        overview.external.isEmpty() && overview.sponsored.isEmpty()
    ) {
        return
    }
    val tap: (Link) -> Unit = { link ->
        when {
            link.kind == Kind.TIMESTAMP && link.seconds != null -> seek?.invoke(link.seconds * 1000)
            link.url != null -> onLink(link.url)
        }
    }
    Column(modifier.fillMaxWidth().padding(top = 14.dp)) {
        Text(
            text = stringResource(R.string.yt_r29_desc_links_title),
            color = color.copy(alpha = 0.72f),
            style = TextStyle(fontFamily = font, fontWeight = FontWeight.SemiBold, fontSize = (sizeSp - 1.5f).sp),
            modifier = Modifier.padding(bottom = 2.dp),
        )
        LinkGroup(R.string.yt_r29_desc_group_timestamps, timestamps, color, accent, font, sizeSp, false, tap)
        LinkGroup(R.string.yt_r29_desc_group_channels, overview.channels, color, accent, font, sizeSp, false, tap)
        LinkGroup(R.string.yt_r29_desc_group_videos, overview.videos, color, accent, font, sizeSp, false, tap)
        LinkGroup(R.string.yt_r29_desc_group_external, overview.external, color, accent, font, sizeSp, false, tap)
        LinkGroup(R.string.yt_r29_desc_group_sponsored, overview.sponsored, color, accent, font, sizeSp, true, tap)
    }
}

@Composable
private fun LinkGroup(
    title: Int,
    links: List<Link>,
    color: Color,
    accent: Color,
    font: FontFamily,
    sizeSp: Float,
    ads: Boolean,
    onTap: (Link) -> Unit,
) {
    if (links.isEmpty()) return
    // small groups start open; ads always start folded
    var open by remember(links) { mutableStateOf(!ads && links.size <= 3) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { open = !open }
            .padding(vertical = 8.dp, horizontal = 2.dp),
    ) {
        Icon(
            painterResource(R.drawable.ic_yt_chevron_right),
            contentDescription = null,
            tint = if (ads) color.copy(alpha = 0.72f) else accent,
            modifier = Modifier.size(14.dp).rotate(if (open) 90f else 0f),
        )
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(
                text = stringResource(title) + "  ·  " + links.size,
                color = color,
                style = TextStyle(fontFamily = font, fontWeight = FontWeight.SemiBold, fontSize = sizeSp.sp),
            )
            if (ads && !open) {
                Text(
                    text = stringResource(R.string.yt_r29_desc_sponsored_hint),
                    color = color.copy(alpha = 0.7f),
                    style = TextStyle(fontFamily = font, fontSize = (sizeSp - 2f).sp),
                )
            }
        }
    }
    if (!open) return
    Column(Modifier.fillMaxWidth().padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        links.forEach { link -> LinkRow(link, color, accent, font, sizeSp, onTap) }
    }
}

@Composable
private fun LinkRow(link: Link, color: Color, accent: Color, font: FontFamily, sizeSp: Float, onTap: (Link) -> Unit) {
    val lead = when (link.kind) {
        Kind.TIMESTAMP -> clock(link.seconds ?: 0)
        Kind.CHANNEL -> "@"
        Kind.VIDEO -> "▶"
        Kind.PLAYLIST -> "≡"
        Kind.EXTERNAL -> "↗"
    }
    val playlist = stringResource(R.string.yt_r29_desc_playlist)
    val label = link.label.ifBlank { if (link.kind == Kind.PLAYLIST) playlist else link.detail.orEmpty() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onTap(link) }
            .padding(vertical = 6.dp, horizontal = 4.dp),
    ) {
        Text(
            text = lead,
            color = accent,
            maxLines = 1,
            style = TextStyle(fontFamily = font, fontWeight = FontWeight.SemiBold, fontSize = (sizeSp - 1f).sp),
            modifier = Modifier.widthIn(min = if (link.kind == Kind.TIMESTAMP) 52.dp else 22.dp),
        )
        Column(Modifier.weight(1f).padding(start = 6.dp)) {
            if (label.isNotEmpty()) {
                Text(
                    text = label,
                    color = color,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = font, fontSize = (sizeSp - 0.5f).sp),
                )
            }
            val detail = link.detail
            if (detail != null && detail != label) {
                Text(
                    text = detail,
                    color = color.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = font, fontSize = (sizeSp - 2.5f).sp),
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
    }
}

/** 83 -> "1:23", 3723 -> "1:02:03". */
internal fun clock(seconds: Long): String {
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * The classic page (STANDARD): ad blocks folded in the classic description text and the link
 * overview under it, inside the part that opens with the description.
 */
object YtDescriptionClassic {
    private const val TAG = "yt_description_links"

    fun bind(layout: DescriptionLayout, streams: Streams, onSeek: (Long) -> Unit) {
        val textView = layout.binding.playerDescription
        val context = layout.context
        val overview = runCatching { YtDescriptionLinkRules.overview(streams.description, streams.duration) }
            .getOrNull() ?: return
        val linkColor = textView.linkTextColors?.defaultColor ?: textView.currentTextColor

        // ads folded in the text: the text as the classic layout just set it is the source
        val original = textView.text
        var open = false
        fun show() {
            textView.text = YtSponsorFold.apply(context, original, overview, open, linkColor) {
                open = !open
                show()
            }
        }
        if (overview.sponsorLines.isNotEmpty()) {
            if (textView.movementMethod == null) textView.movementMethod = LinkMovementMethodCompat.getInstance()
            show()
        }

        val parent = textView.parent as? ViewGroup ?: return
        val compose = parent.findViewWithTag<ComposeView>(TAG) ?: ComposeView(context).also { view ->
            view.tag = TAG
            view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            parent.addView(
                view,
                parent.indexOfChild(textView) + 1,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        val textColor = Color(textView.currentTextColor)
        val accent = Color(linkColor)
        // the text size in sp (Compose applies the font scale again)
        val metrics = textView.resources
        val sizeSp = textView.textSize / (metrics.displayMetrics.density * metrics.configuration.fontScale)
        compose.setContent {
            CompositionLocalProvider(LocalYtDescriptionSeek provides onSeek) {
                YtDescriptionLinks(
                    overview = overview,
                    onLink = { link -> layout.handleLink(link) },
                    color = textColor,
                    accent = accent,
                    font = FontFamily.Default,
                    // the description's own side margin
                    modifier = Modifier.padding(horizontal = 12.dp),
                    sizeSp = sizeSp,
                )
            }
        }
    }
}
