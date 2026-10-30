package com.github.libretube.ui.yt

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.text.HtmlCompat
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.github.libretube.R
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.obj.Comment
import com.github.libretube.api.obj.CommentsPage
import com.github.libretube.extensions.formatShort
import com.github.libretube.helpers.YtNote
import com.github.libretube.helpers.YtNotes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class SideSheetTab { CONVERSATION, NOTES }

/** Comments of the first page that the sheet shows; the rest opens in the classic sheet. */
private const val SIDE_COMMENTS_SHOWN = 20

/** A video time like 2:16 or 1:02:16 in a comment. */
private val TIMESTAMP = Regex("""(?<![\d:])(\d{1,2}:)?\d{1,2}:\d{2}(?![\d:])""")

private fun parseTimestamp(text: String): Long =
    text.split(":").fold(0L) { acc, part -> acc * 60 + part.toLong() } * 1000

/**
 * YT design "Side", video page below the player: title and creator, then a raised
 * conversation sheet with the tabs Gespräch (comments, readable only; times in comments jump
 * there) and Notizen (own notes at the current video time, kept on the device). The input
 * sits at the bottom of the sheet; without an account a thought becomes a note.
 */
@Composable
fun SideWatchDetails(
    palette: YtPalette,
    imageLoader: ImageLoader,
    controller: YtWatchController,
    actions: YtWatchActions,
) {
    val streams = controller.streams ?: return
    val videoId = controller.videoId

    var tab by remember(videoId) { mutableStateOf(SideSheetTab.CONVERSATION) }
    var open by remember { mutableStateOf(true) }
    var showDescription by remember(videoId) { mutableStateOf(false) }
    var comments by remember(videoId) { mutableStateOf<CommentsPage?>(null) }
    var notes by remember(videoId) { mutableStateOf(videoId?.let { YtNotes.get(it) }.orEmpty()) }
    var draft by remember(videoId) { mutableStateOf("") }
    var savedAsNote by remember(videoId) { mutableStateOf(false) }

    LaunchedEffect(videoId) {
        comments = null
        comments = videoId?.let { id ->
            withContext(Dispatchers.IO) {
                runCatching { MediaServiceRepository.instance.getComments(id) }.getOrNull()
            }
        } ?: CommentsPage(disabled = true)
    }

    fun saveDraft() {
        val id = videoId ?: return
        val text = draft.trim()
        if (text.isEmpty()) return
        notes = YtNotes.add(id, YtNote(actions.currentPosition(), text, System.currentTimeMillis()))
        draft = ""
        savedAsNote = tab == SideSheetTab.CONVERSATION
        tab = SideSheetTab.NOTES
        open = true
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(palette.background),
    ) {
        Text(
            text = streams.title,
            color = palette.text,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = YtFonts.figtree, fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 23.sp),
            modifier = Modifier.padding(start = 21.dp, end = 20.dp, top = 10.dp),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 21.dp, end = 12.dp, top = 8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = actions.onOpenChannel),
            ) {
                AsyncImage(
                    model = streams.uploaderAvatar,
                    contentDescription = null,
                    imageLoader = imageLoader,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(29.dp)
                        .clip(CircleShape)
                        .background(palette.surface),
                )
                Text(
                    text = streams.uploader,
                    color = palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = YtFonts.figtree, fontSize = 14.sp),
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            Text(
                text = stringResource(R.string.yt_side_description),
                color = if (showDescription) palette.accent else palette.textSecondary,
                style = TextStyle(fontFamily = YtFonts.figtree, fontSize = 13.sp),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable {
                        showDescription = !showDescription
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Icon(
                painterResource(R.drawable.ic_bookmark_outlined),
                contentDescription = stringResource(R.string.save),
                tint = palette.text,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = actions.onSave)
                    .padding(8.dp)
                    .size(20.dp),
            )
            Icon(
                painterResource(R.drawable.ic_share),
                contentDescription = stringResource(R.string.share),
                tint = palette.text,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = actions.onShare)
                    .padding(8.dp)
                    .size(20.dp),
            )
        }

        if (showDescription) {
            WatchDescription(
                streams, actions.onLink, palette.text, palette.accent, R.font.font_figtree_400, expanded = true,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
            )
        }

        // the conversation sheet slides up below the video
        val appear = remember(videoId) { MutableTransitionState(false).apply { targetState = true } }
        AnimatedVisibility(
            visibleState = appear,
            enter = ytEnterTransition(slideInVertically(tween(380)) { it / 4 } + fadeIn(tween(380))),
        ) {
            Column(
                Modifier
                    .padding(top = 10.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(palette.surface)
                    .animateContentSize(ytFinite(spring())),
            ) {
                // drag handle: opens and closes the sheet
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clickable { open = !open }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(41.dp, 4.dp)
                            .clip(RoundedCornerShape(50))
                            .background(palette.textSecondary.copy(alpha = 0.45f)),
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(horizontal = 22.dp),
                ) {
                    SideTab(palette, stringResource(R.string.yt_side_conversation), tab == SideSheetTab.CONVERSATION) {
                        tab = SideSheetTab.CONVERSATION
                        open = true
                    }
                    SideTab(palette, stringResource(R.string.yt_side_notes), tab == SideSheetTab.NOTES) {
                        tab = SideSheetTab.NOTES
                        open = true
                    }
                }
                SideDivider(palette.copy(surface = palette.raised), Modifier.padding(top = 5.dp))

                AnimatedVisibility(
                    visible = open,
                    enter = ytEnterTransition(expandVertically() + fadeIn()),
                    exit = ytExitTransition(shrinkVertically() + fadeOut()),
                ) {
                    Column {
                        when (tab) {
                            SideSheetTab.CONVERSATION -> Conversation(palette, imageLoader, comments, actions)
                            SideSheetTab.NOTES -> Notes(
                                palette = palette,
                                notes = notes,
                                savedAsNote = savedAsNote,
                                onSeek = actions.onSeek,
                                onDelete = { note -> videoId?.let { notes = YtNotes.remove(it, note) } },
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(palette.raised),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_yt_person),
                                    contentDescription = null,
                                    tint = palette.textSecondary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            BasicTextField(
                                value = draft,
                                onValueChange = { draft = it },
                                textStyle = TextStyle(color = palette.text, fontFamily = YtFonts.figtree, fontSize = 13.5.sp),
                                cursorBrush = SolidColor(palette.accent),
                                maxLines = 4,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 8.dp, end = 6.dp)
                                    .clip(RoundedCornerShape(21.dp))
                                    .background(Color(0xFF3A3B3D))
                                    .padding(horizontal = 17.dp, vertical = 12.dp),
                                decorationBox = { inner ->
                                    Box {
                                        if (draft.isEmpty()) {
                                            Text(
                                                text = stringResource(R.string.yt_side_input_hint),
                                                color = palette.textSecondary,
                                                style = TextStyle(fontFamily = YtFonts.figtree, fontSize = 13.5.sp),
                                            )
                                        }
                                        inner()
                                    }
                                },
                            )
                            Box(
                                Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(if (draft.isBlank()) palette.raised else palette.accent)
                                    .clickable(enabled = draft.isNotBlank()) { saveDraft() },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_yt_send),
                                    contentDescription = stringResource(R.string.yt_side_save_note),
                                    tint = if (draft.isBlank()) palette.textSecondary else palette.onAccent,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Conversation(
    palette: YtPalette,
    imageLoader: ImageLoader,
    page: CommentsPage?,
    actions: YtWatchActions,
) {
    when {
        page == null -> Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 28.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = palette.accent)
        }

        page.disabled || page.comments.isEmpty() -> SheetCaption(
            palette,
            stringResource(if (page.disabled) R.string.yt_side_comments_off else R.string.yt_side_comments_none),
        )

        else -> {
            val count = page.commentCount.takeIf { it > 0 } ?: page.comments.size.toLong()
            SheetCaption(
                palette,
                pluralStringResource(R.plurals.yt_side_answers, count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), count.formatShort()),
            )
            page.comments.take(SIDE_COMMENTS_SHOWN).forEachIndexed { index, comment ->
                if (index > 0) SideDivider(palette.copy(surface = palette.raised))
                CommentRow(palette, imageLoader, comment, actions)
            }
            if (page.comments.size > SIDE_COMMENTS_SHOWN || page.nextpage != null) {
                Text(
                    text = stringResource(R.string.yt_side_all_comments),
                    color = palette.accent,
                    style = TextStyle(fontFamily = YtFonts.figtree, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = actions.onComments)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SheetCaption(palette: YtPalette, text: String) {
    Text(
        text = text,
        color = palette.textSecondary,
        style = TextStyle(fontFamily = YtFonts.figtree, fontSize = 12.sp),
        modifier = Modifier.padding(start = 21.dp, end = 20.dp, top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun CommentRow(
    palette: YtPalette,
    imageLoader: ImageLoader,
    comment: Comment,
    actions: YtWatchActions,
) {
    val text = remember(comment.commentId) {
        HtmlCompat.fromHtml(comment.commentText.orEmpty(), HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
    }
    val timestamp = remember(comment.commentId) { TIMESTAMP.find(text)?.value }

    Row(Modifier.padding(start = 19.dp, end = 12.dp, top = 12.dp, bottom = 11.dp)) {
        AsyncImage(
            model = comment.thumbnail,
            contentDescription = null,
            imageLoader = imageLoader,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(43.dp)
                .clip(CircleShape)
                .background(palette.raised),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(start = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = comment.author.removePrefix("@"),
                    color = palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = YtFonts.figtree, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (comment.channelOwner) {
                    Text(
                        text = stringResource(R.string.yt_side_creator),
                        color = palette.onAccent,
                        style = TextStyle(fontFamily = YtFonts.figtree, fontWeight = FontWeight.SemiBold, fontSize = 11.sp),
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(palette.accent)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                timestamp?.let { time ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(palette.raised)
                            .clickable { actions.onSeek(parseTimestamp(time)) }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_play),
                            contentDescription = null,
                            tint = palette.accent,
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            text = time,
                            color = palette.text,
                            style = TextStyle(fontFamily = YtFonts.figtree, fontSize = 12.sp),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = comment.commentedTime,
                    color = palette.textSecondary,
                    maxLines = 1,
                    style = TextStyle(fontFamily = YtFonts.figtree, fontSize = 12.sp),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(
                text = text,
                color = palette.text,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = YtFonts.figtree, fontSize = 13.sp, lineHeight = 18.sp),
                modifier = Modifier.padding(top = 3.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                if (comment.replyCount > 0) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.yt_side_answers,
                            comment.replyCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                            comment.replyCount.formatShort(),
                        ),
                        color = palette.textSecondary,
                        style = TextStyle(fontFamily = YtFonts.figtree, fontSize = 13.sp),
                        modifier = Modifier
                            .padding(end = 16.dp)
                            .clickable(onClick = actions.onComments),
                    )
                }
                Icon(
                    painterResource(if (comment.hearted) R.drawable.ic_yt_heart_fill else R.drawable.ic_yt_heart),
                    contentDescription = null,
                    tint = if (comment.hearted) palette.accent else palette.textSecondary,
                    modifier = Modifier.size(16.dp),
                )
                if (comment.likeCount > 0) {
                    Text(
                        text = comment.likeCount.formatShort(),
                        color = palette.textSecondary,
                        style = TextStyle(fontFamily = YtFonts.figtree, fontSize = 13.sp),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Notes(
    palette: YtPalette,
    notes: List<YtNote>,
    savedAsNote: Boolean,
    onSeek: (Long) -> Unit,
    onDelete: (YtNote) -> Unit,
) {
    SheetCaption(
        palette,
        stringResource(if (savedAsNote) R.string.yt_side_saved_as_note else R.string.yt_side_notes_caption),
    )
    if (notes.isEmpty()) {
        Text(
            text = stringResource(R.string.yt_side_notes_empty),
            color = palette.text,
            style = TextStyle(fontFamily = YtFonts.figtree, fontSize = 15.sp, lineHeight = 21.sp),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
        return
    }
    notes.forEachIndexed { index, note ->
        if (index > 0) SideDivider(palette.copy(surface = palette.raised))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(palette.raised)
                    .clickable { onSeek(note.positionMillis) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_play),
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = DateUtils.formatElapsedTime(note.positionMillis / 1000),
                    color = palette.text,
                    style = TextStyle(fontFamily = YtFonts.figtree, fontSize = 13.sp),
                    modifier = Modifier.padding(start = 5.dp),
                )
            }
            Text(
                text = note.text,
                color = palette.text,
                style = TextStyle(fontFamily = YtFonts.figtree, fontSize = 15.sp, lineHeight = 21.sp),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            )
            Icon(
                painterResource(R.drawable.ic_trash),
                contentDescription = stringResource(R.string.yt_side_delete_note),
                tint = palette.textSecondary,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onDelete(note) }
                    .padding(8.dp)
                    .size(18.dp),
            )
        }
    }
    Spacer(Modifier.height(4.dp))
}
