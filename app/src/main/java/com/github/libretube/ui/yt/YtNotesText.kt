package com.github.libretube.ui.yt

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BulletSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import com.github.libretube.util.YtReleaseNotes.Block
import com.github.libretube.util.YtReleaseNotes.Span

/**
 * YT: turns the parsed release notes into one styled text for the update dialog's TextView.
 * Colours come from the dialog's theme ([accent] = colorPrimary for the build headers, [bullet]
 * = the text colour); body text keeps the TextView's own colour, so it stays readable on the
 * dialog surface in every design and in dark mode.
 */
object YtNotesText {
    fun build(
        blocks: List<Block>,
        buildLabel: (Int) -> String,
        accent: Int,
        bullet: Int,
        bulletGap: Int,
    ): CharSequence {
        val out = SpannableStringBuilder()
        blocks.forEachIndexed { index, block ->
            if (index > 0 && (block is Block.BuildHeader || block is Block.Heading)) gap(out, block)
            val start = out.length
            when (block) {
                is Block.BuildHeader -> {
                    out.append(buildLabel(block.build))
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, EXCL)
                    out.setSpan(ForegroundColorSpan(accent), start, out.length, EXCL)
                    out.setSpan(RelativeSizeSpan(0.85f), start, out.length, EXCL)
                    out.append('\n')
                }
                is Block.Heading -> {
                    out.append(block.text)
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, EXCL)
                    out.setSpan(RelativeSizeSpan(1.05f), start, out.length, EXCL)
                    out.append('\n')
                }
                is Block.Bullet -> {
                    spans(out, block.spans)
                    out.append('\n')
                    out.setSpan(BulletSpan(bulletGap, bullet), start, out.length, EXCL)
                }
                is Block.Text -> {
                    spans(out, block.spans)
                    out.append('\n')
                }
            }
        }
        // no empty line below the last block
        while (out.isNotEmpty() && out[out.length - 1] == '\n') out.delete(out.length - 1, out.length)
        return out
    }

    private const val EXCL = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

    /** A half-height empty line: more above a build than above a section. */
    private fun gap(out: SpannableStringBuilder, before: Block) {
        val start = out.length
        out.append('\n')
        out.setSpan(RelativeSizeSpan(if (before is Block.BuildHeader) 0.8f else 0.4f), start, out.length, EXCL)
    }

    private fun spans(out: SpannableStringBuilder, spans: List<Span>) {
        spans.forEach { span ->
            val start = out.length
            out.append(span.text)
            if (span.bold) out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, EXCL)
        }
    }
}
