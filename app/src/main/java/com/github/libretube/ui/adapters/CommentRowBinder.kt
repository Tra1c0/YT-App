package com.github.libretube.ui.adapters

import android.animation.ValueAnimator
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.text.method.LinkMovementMethodCompat
import androidx.core.text.parseAsHtml
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import com.github.libretube.R
import com.github.libretube.api.obj.Comment
import com.github.libretube.extensions.formatShort
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.SceneVerdict
import com.github.libretube.helpers.ThemeHelper
import com.github.libretube.helpers.YtCommentMoments
import com.github.libretube.ui.models.CommentScene
import com.github.libretube.ui.viewholders.CommentViewHolder
import com.github.libretube.ui.yt.YtHaptics
import com.github.libretube.ui.yt.YtMotionPrefs
import com.github.libretube.util.HtmlParser
import com.github.libretube.util.LinkHandler
import com.github.libretube.util.TextUtils

/**
 * YT: what the scene lets a comment row show (comments follow the scene). [replies]: the rows of
 * a reply list, which are hidden like comments but never left out.
 */
class CommentSceneGate(private val scene: CommentScene, private val replies: Boolean) {
    fun verdict(comment: Comment): SceneVerdict =
        if (replies) scene.replyVerdict(comment) else scene.verdict(comment)

    fun shownHtml(comment: Comment, verdict: SceneVerdict): String? =
        YtCommentMoments.shownHtml(scene.momentsOf(comment), verdict)

    fun reveal(comment: Comment) = scene.reveal(comment)
}

/**
 * The binding of one comment row, shared by the paged list ([CommentsPagingAdapter]) and the list
 * of the scene ([CommentsSceneAdapter]). The upstream binding plus the gate of the scene: a
 * comment naming a time not seen yet is blurred (Android 12 and later) or replaced by "Spoiler
 * bei 14:20 · tippen", can be neither selected nor copied, and TalkBack only hears that it is
 * hidden. A tap shows it. A timeline keeps the lines up to what was seen and folds the rest.
 * When the mark of what was seen passes a time, the lists rebind with [PAYLOAD_SCENE] and the row
 * clears its blur without a full rebind.
 */
class CommentRowBinder(
    private val isReplies: Boolean,
    private val channelAvatar: String?,
    private val handleLink: (url: String) -> Unit,
    private val saveToClipboard: (Comment) -> Unit,
    private val navigateToChannel: (Comment) -> Unit,
    private val navigateToReplies: ((Comment, String?) -> Unit)? = null,
    private val gate: CommentSceneGate? = null,
) {
    private var clickEventConsumedByLinkHandler = false

    // the comment the viewer just tapped open: it got its confirm, it needs no tick as well
    private var tappedOpen: String? = null
    private var lastTickAt = 0L

    /** What a row shows of the scene now, kept on the row to tell what changes. */
    private class SceneState {
        var hidden = false
        var folded = false
        var placeholder = false
        var unselectable = false
        var blurPx = 0f
        var animator: ValueAnimator? = null
    }

    fun bind(holder: CommentViewHolder, comment: Comment, position: Int) {
        holder.binding.apply {
            commentAuthor.text = comment.author
            commentAuthor.setBackgroundResource(
                if (comment.channelOwner) R.drawable.comment_channel_owner_bg else 0
            )
            commentInfos.text = dateOf(comment)

            commentText.movementMethod = LinkMovementMethodCompat.getInstance()
            commentText.text = fullText(comment)

            ImageHelper.loadImage(comment.thumbnail, commentorImage, true)
            likesTextView.text = comment.likeCount.formatShort()

            if (comment.creatorReplied && !channelAvatar.isNullOrBlank()) {
                ImageHelper.loadImage(channelAvatar, creatorReplyImageView, true)
                creatorReplyImageView.isVisible = true
            } else {
                creatorReplyImageView.setImageDrawable(null)
                creatorReplyImageView.isVisible = false
            }

            verifiedImageView.isVisible = comment.verified
            pinnedImageView.isVisible = comment.pinned
            heartedImageView.isVisible = comment.hearted
            repliesCount.isVisible = !isReplies && comment.repliesPage != null
            repliesCount.text = if (comment.replyCount > 0) comment.replyCount.formatShort() else null

            commentorImage.setOnClickListener {
                navigateToChannel(comment)
            }

            if (isReplies) {
                // highlight the comment that is being replied to
                if (position == 0) {
                    root.setBackgroundColor(
                        ThemeHelper.getThemeColor(
                            root.context,
                            com.google.android.material.R.attr.colorSurface
                        )
                    )
                } else {
                    root.background = AppCompatResources.getDrawable(
                        root.context,
                        R.drawable.rounded_ripple
                    )
                    commentorImage.updateLayoutParams<ViewGroup.MarginLayoutParams> { leftMargin = 58 }
                }
            } else {
                val onClickListener = View.OnClickListener {
                    if (clickEventConsumedByLinkHandler) {
                        clickEventConsumedByLinkHandler = false
                        return@OnClickListener
                    }
                    // YT: a hidden comment opens with the first tap instead of its replies
                    if (revealOnTap(holder, comment)) return@OnClickListener
                    navigateToReplies?.invoke(comment, channelAvatar)
                }
                root.setOnClickListener(onClickListener)
                commentText.setOnClickListener(onClickListener)
            }

            root.setOnLongClickListener {
                // YT: a hidden comment is never copied; the long press shows it instead
                if (revealOnTap(holder, comment)) return@setOnLongClickListener true
                saveToClipboard(comment)
                true
            }
        }
        applyScene(holder, comment, animate = false)
    }

    /** The mark of what was seen passed a time, or the mode changed: only the scene part anew. */
    fun rebindScene(holder: CommentViewHolder, comment: Comment) = applyScene(holder, comment, animate = true)

    private fun dateOf(comment: Comment): CharSequence =
        comment.commentedTimeMillis?.let { TextUtils.formatRelativeDate(it) } ?: comment.commentedTime

    private fun linkHandler() = LinkHandler {
        clickEventConsumedByLinkHandler = true
        handleLink.invoke(it)
    }

    private fun fullText(comment: Comment): CharSequence? = comment.commentText?.replace("</a>", "</a> ")
        ?.parseAsHtml(tagHandler = HtmlParser(linkHandler()))

    // true if the tap showed a hidden or folded comment (and did nothing else)
    private fun revealOnTap(holder: CommentViewHolder, comment: Comment): Boolean {
        val gate = gate ?: return false
        val verdict = gate.verdict(comment)
        if (!verdict.hidden && !verdict.folded) return false
        tappedOpen = comment.commentId
        YtHaptics.of(holder.binding.root).confirm()
        gate.reveal(comment)
        // the lists rebind on the reveal as well; this row answers at once
        applyScene(holder, comment, animate = true)
        return true
    }

    private fun applyScene(holder: CommentViewHolder, comment: Comment, animate: Boolean) {
        val binding = holder.binding
        val root = binding.root
        val text = binding.commentText
        val state = root.getTag(R.id.yt_r12_e_scene_state) as? SceneState
            ?: SceneState().also { root.setTag(R.id.yt_r12_e_scene_state, it) }
        val verdict = gate?.verdict(comment) ?: SceneVerdict.SHOW
        val context = root.context
        val motion = animate && YtMotionPrefs.enabled
        val wasHidden = state.hidden
        val wasFolded = state.folded
        state.animator?.cancel()
        state.animator = null
        text.animate().cancel()
        text.alpha = 1f

        when {
            verdict.hidden -> {
                val at = YtCommentMoments.formatTime(verdict.spoilerAtMs)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    blurTo(text, state, BLUR_PX, motion && !wasHidden)
                } else {
                    // no blur before Android 12: the text gives way to a pill naming the time
                    text.text = context.getString(R.string.yt_r12_e_spoiler_at, at)
                    if (!state.placeholder) {
                        text.background = AppCompatResources.getDrawable(context, R.drawable.yt_r12_e_spoiler_pill)
                        val h = (10 * context.resources.displayMetrics.density).toInt()
                        val v = (4 * context.resources.displayMetrics.density).toInt()
                        text.setPadding(h, v, h, v)
                        text.updateLayoutParams<ViewGroup.LayoutParams> { width = ViewGroup.LayoutParams.WRAP_CONTENT }
                        state.placeholder = true
                    }
                }
                setUnselectable(text, state, true)
                binding.commentInfos.text = context.getString(
                    R.string.yt_r12_e_info_from,
                    dateOf(comment),
                    context.getString(R.string.yt_r12_e_from, at),
                )
                text.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                root.contentDescription = context.getString(R.string.yt_r12_e_hidden_description, comment.author, at)
                if (isReplies) root.setOnClickListener { revealOnTap(holder, comment) }
            }

            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) blurTo(text, state, 0f, motion && wasHidden)
                if (state.placeholder) {
                    restoreText(text, comment, state)
                    if (motion) {
                        text.alpha = 0f
                        text.animate().alpha(1f).setDuration(REVEAL_MS).setInterpolator(EASE_OUT).start()
                    }
                }
                setUnselectable(text, state, false)
                binding.commentInfos.text = dateOf(comment)
                text.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                root.contentDescription = null
                if (isReplies && wasHidden) {
                    root.setOnClickListener(null)
                    root.isClickable = false
                }
                if (verdict.folded) {
                    // a timeline: the lines up to what was seen, then "+3 weitere Zeitmarken · tippen"
                    val shown = gate?.shownHtml(comment, verdict).orEmpty()
                        .replace("</a>", "</a> ").parseAsHtml(tagHandler = HtmlParser(linkHandler()))
                    val fold = context.resources.getQuantityString(
                        R.plurals.yt_r12_e_more_times, verdict.foldedTimes, verdict.foldedTimes,
                    )
                    val secondary = binding.commentInfos.currentTextColor
                    text.text = buildSpannedString {
                        append(shown.trimEnd())
                        if (shown.isNotBlank()) append("\n")
                        color(secondary) { append(fold) }
                    }
                    if (isReplies) root.setOnClickListener { revealOnTap(holder, comment) }
                } else if (wasFolded) {
                    text.text = fullText(comment)
                    if (isReplies) {
                        root.setOnClickListener(null)
                        root.isClickable = false
                    }
                }
            }
        }

        // a time on screen was just passed: a light tick, once for all rows that open together
        if (animate && wasHidden && !verdict.hidden && tappedOpen != comment.commentId && root.isAttachedToWindow) {
            val now = SystemClock.uptimeMillis()
            if (now - lastTickAt > TICK_GAP_MS) {
                lastTickAt = now
                YtHaptics.of(root).tick()
            }
        }
        if (tappedOpen == comment.commentId && !verdict.hidden && !verdict.folded) tappedOpen = null
        state.hidden = verdict.hidden
        state.folded = verdict.folded
    }

    private fun restoreText(text: TextView, comment: Comment, state: SceneState) {
        text.background = null
        text.setPadding(0, 0, 0, 0)
        text.updateLayoutParams<ViewGroup.LayoutParams> { width = ViewGroup.LayoutParams.MATCH_PARENT }
        text.text = fullText(comment)
        state.placeholder = false
    }

    // a hidden comment can be neither selected nor followed by its links; back as the layout has it
    private fun setUnselectable(text: TextView, state: SceneState, off: Boolean) {
        if (off) {
            // every time: a full bind sets the link movement again
            if (text.isTextSelectable) text.setTextIsSelectable(false)
            text.movementMethod = null
            state.unselectable = true
        } else if (state.unselectable) {
            text.setTextIsSelectable(true)
            text.movementMethod = LinkMovementMethodCompat.getInstance()
            state.unselectable = false
        }
    }

    private fun blurTo(text: TextView, state: SceneState, target: Float, animate: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (!animate || state.blurPx == target) {
            setBlur(text, state, target)
            return
        }
        state.animator = ValueAnimator.ofFloat(state.blurPx, target).apply {
            duration = if (target > 0f) HIDE_MS else REVEAL_MS
            interpolator = EASE_OUT
            addUpdateListener { setBlur(text, state, it.animatedValue as Float) }
            start()
        }
    }

    private fun setBlur(text: TextView, state: SceneState, px: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        state.blurPx = px
        // a radius of 0 is no blur: no effect at all then
        text.setRenderEffect(
            if (px < 0.5f) null else RenderEffect.createBlurEffect(px, px, Shader.TileMode.DECAL)
        )
    }

    companion object {
        /** The payload of a rebind for the scene only (see [rebindScene]). */
        const val PAYLOAD_SCENE = "yt_scene"

        private const val BLUR_PX = 14f
        private const val REVEAL_MS = 320L
        private const val HIDE_MS = 220L
        private const val TICK_GAP_MS = 150L

        // YtEaseOut of the Compose designs, for Views
        private val EASE_OUT = PathInterpolator(0.23f, 1f, 0.32f, 1f)
    }
}
