package com.github.libretube.ui.yt.mini

import android.animation.Animator
import android.animation.ValueAnimator
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.BlobShape
import com.github.libretube.ui.yt.CookieShape
import com.github.libretube.ui.yt.YtDock
import com.github.libretube.ui.yt.YtMotionPrefs
import com.github.libretube.ui.yt.YtPalette
import com.github.libretube.ui.yt.liquidGlass
import com.github.libretube.ui.yt.rememberSpin
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** The shape behind the play button. */
enum class YtMiniMark { NONE, DISC, BLOB, COOKIE }

/** How the progress of the video shows. */
enum class YtMiniBar { SEAM, UNDERLINE, WAVE, RING }

/** What frames the picture: a black rule, a sticker shadow, a scalloped shape, a glint. */
enum class YtMiniFrame { NONE, RULE, STICKER, COOKIE, GLINT }

/** How the player lands on the bar. */
enum class YtMiniLanding { NONE, SETTLE, CURTAIN, RISE, SQUISH, RULE, SWELL, JELLY, MORPH, FROST, DROP }

/**
 * The look of a docked player of round 10 (one of a design's colors and type); its measures are
 * the design's [spec].
 */
class YtMiniLook(
    val spec: YtMiniSpec,
    val frame: YtMiniFrame = YtMiniFrame.NONE,
    val frameColor: Color = Color.Unspecified,
    val fill: Color,
    val fillTop: Color? = null,
    val border: Color? = null,
    val borderWidth: Float = 1f,
    val shadow: Color? = null,
    /** The tint of the glass for the glass designs; null for a solid player. */
    val glassTint: Color? = null,
    val title: TextStyle,
    val subtitle: TextStyle,
    val caps: Boolean = false,
    /** Editorial: a kicker ("Jetzt") and the channel above the title. */
    @StringRes val kicker: Int? = null,
    val icon: Color,
    val playIcon: Color = icon,
    val mark: YtMiniMark = YtMiniMark.NONE,
    val markColor: Color = Color.Unspecified,
    val markSize: Int = 40,
    val bar: YtMiniBar,
    val barColor: Color,
    val track: Color,
    val barWidth: Float = 2f,
    val trackWidth: Float = barWidth,
) {
    val joint: YtMiniJoint get() = spec.joint
    val height: Int get() = spec.height
    val radius: Int get() = spec.radius
    val fillet: Int get() = spec.fillet
    val body: Int get() = spec.body
}

/**
 * YT: the docked players of round 10 as design objects, drawn exactly as before: a body joined to
 * the bar, the picture's frame, the mark behind the play button, title and channel in the design's
 * type and the progress as seam, underline, wave or ring. On top of that the neutral preview and
 * drop target ([YtMiniParts]). A design of wave 2 overrides what it draws its own way.
 */
abstract class YtMiniLookDesign(design: YtDesign) : YtMiniDesign(design) {
    /** How the player lands on the bar. */
    abstract val landing: YtMiniLanding

    /** How far player and bar dip together when the player lands, in dp. */
    open val dip: Float get() = 0f

    /** The design's colors and type for its player. */
    abstract fun look(palette: YtPalette): YtMiniLook

    override fun usesBackdrop(palette: YtPalette): Boolean = look(palette).glassTint != null

    override fun buttonTints(palette: YtPalette): Pair<Color, Color> = look(palette).let { it.playIcon to it.icon }

    /** The colors and type of the neutral preview and drop label. */
    open fun partsStyle(palette: YtPalette, look: YtMiniLook): YtMiniParts.Style = YtMiniParts.Style(
        surface = look.title.color.takeOrElse { palette.text }.copy(alpha = 0.08f),
        onSurface = look.title.color.takeOrElse { palette.text },
        secondary = look.subtitle.color.takeOrElse { palette.textSecondary },
        pill = look.markColor.takeOrElse { palette.accent },
        onPill = if (look.mark == YtMiniMark.NONE) palette.onAccent else look.playIcon,
        accent = look.barColor,
        title = look.title,
        label = look.subtitle.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
        thumbRadius = (look.spec.thumbRadius / 2).coerceAtMost(10).dp,
    )

    @Composable
    override fun Under(scene: YtMiniScene) {
        val palette = scene.palette ?: return
        val miniLook = remember(palette) { look(palette) }
        val style = remember(palette, miniLook) { partsStyle(palette, miniLook) }
        Box(Modifier.fillMaxSize()) {
            YtMiniSurface(miniLook, landing, scene, style)
            YtMiniParts.Peek(scene, style)
        }
    }

    /**
     * The gesture with which the player lands on the bar, in the character of the design: the
     * player alone (it falls, squashes, rises, frosts over) and together with the bar (both dip
     * a little, as if the bar takes the weight).
     */
    override fun land(views: YtMiniViews): Animator? {
        if (landing == YtMiniLanding.NONE && dip == 0f) return null
        val density = views.density
        val surface = views.under
        val duration = when (landing) {
            YtMiniLanding.DROP -> 620L
            YtMiniLanding.FROST -> 600L
            YtMiniLanding.JELLY -> 560L
            else -> 420L
        }
        surface.pivotX = surface.width / 2f
        surface.pivotY = surface.height.toFloat()
        return ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = null
            addUpdateListener { animator ->
                val t = animator.animatedValue as Float
                // the shared dip: down, back and a little past, settling; every landing starts and
                // ends at rest, so nothing jumps when it begins
                val shared = dip * density * 2.2f * if (landing == YtMiniLanding.DROP) wobble(t, 2f, 4f) else wobble(t, 1.4f, 4.5f)
                var own = 0f
                var scaleX = 1f
                var scaleY = 1f
                var alpha = 1f
                when (landing) {
                    // the weight comes down: the player sinks a little further than the bar
                    YtMiniLanding.DROP -> own = 2.5f * density * wobble(t, 2f, 4f)
                    YtMiniLanding.SQUISH -> {
                        val w = wobble(t, 1.2f, 5f)
                        scaleY = 1f - 0.16f * w
                        scaleX = 1f + 0.05f * w
                    }
                    YtMiniLanding.JELLY -> {
                        val w = wobble(t, 2.2f, 4f)
                        scaleX = 1f + 0.07f * w
                        scaleY = 1f - 0.07f * w
                    }
                    YtMiniLanding.RISE -> scaleY = 1f + 0.08f * wobble(t, 1.1f, 6f)
                    YtMiniLanding.SWELL -> scaleY = 1f + 0.05f * wobble(t, 0.8f, 5f)
                    // a breath of frost: the glass clouds a little and clears
                    YtMiniLanding.FROST -> alpha = 1f - 0.3f * sin(PI.toFloat() * t)
                    else -> Unit
                }
                views.all.forEach { it.translationY = own + shared }
                surface.scaleX = scaleX
                surface.scaleY = scaleY
                surface.alpha = alpha
                YtDock.dip = shared
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    views.all.forEach { it.translationY = 0f }
                    surface.scaleX = 1f
                    surface.scaleY = 1f
                    surface.alpha = 1f
                    YtDock.dip = 0f
                }
            })
            start()
        }
    }

    /**
     * The outline of the player's surface in px: joined to the bar at its foot. [grow] is how far
     * the box has grown above its docked height (the preview); the foot stays on the bar.
     */
    fun bodyPath(look: YtMiniLook, width: Float, height: Float, density: Float, open: Boolean, grow: Float): Path {
        val content = look.height * density + grow
        val r = look.radius * density
        val path = Path()
        when (look.joint) {
            YtMiniJoint.STACK -> {
                val inset = if (open) look.borderWidth * density / 2f else 0f
                path.moveTo(inset, height)
                path.lineTo(inset, r)
                if (r > 0f) path.arcTo(Rect(inset, inset, inset + 2 * r, inset + 2 * r), 180f, 90f, false) else path.lineTo(inset, inset)
                path.lineTo(width - inset - r, inset)
                if (r > 0f) path.arcTo(Rect(width - inset - 2 * r, inset, width - inset, inset + 2 * r), 270f, 90f, false) else path.lineTo(width - inset, inset)
                path.lineTo(width - inset, height)
                if (!open) path.close()
            }
            YtMiniJoint.BULGE -> {
                val b = look.body * density
                val f = look.fillet * density
                val inset = if (open) look.borderWidth * density / 2f else 0f
                // from the bar's surface, curving up into the body
                val foot = if (open) content + inset else content
                path.moveTo(b - f, foot)
                path.arcTo(Rect(b - 2 * f, content - 2 * f, b, content), 90f, -90f, false)
                path.lineTo(b + inset, r)
                path.arcTo(Rect(b + inset, inset, b + inset + 2 * r, inset + 2 * r), 180f, 90f, false)
                path.lineTo(width - b - inset - r, inset)
                path.arcTo(Rect(width - b - inset - 2 * r, inset, width - b - inset, inset + 2 * r), 270f, 90f, false)
                path.lineTo(width - b - inset, content - f)
                path.arcTo(Rect(width - b, content - 2 * f, width - b + 2 * f, content), 180f, -90f, false)
                if (!open) {
                    path.lineTo(width - b + f, height)
                    path.lineTo(b - f, height)
                    path.close()
                }
            }
            YtMiniJoint.TUCK -> {
                val inset = if (open) look.borderWidth * density / 2f else 0f
                path.addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        Rect(inset, inset, width - inset, height - inset),
                        CornerRadius(r),
                    ),
                )
            }
        }
        return path
    }

    /**
     * The player of round 10: (1) its body joined to the bar, with the seam of progress at the
     * joint; then the docked row (frame, mark, ring, title and channel), which gives way to the
     * preview as it grows. Positions come from [geometry], as the View constraints do.
     */
    @Composable
    private fun YtMiniSurface(look: YtMiniLook, landing: YtMiniLanding, scene: YtMiniScene, style: YtMiniParts.Style) {
        val data = scene.info
        val backdrop = scene.backdrop
        // the part of the landing drawn here: the rule draws in, titles come up, shapes turn in
        val settle = remember { Animatable(1f) }
        val motionOn = YtMotionPrefs.enabled
        val landings = scene.landings
        LaunchedEffect(landings) {
            if (landings > 0 && motionOn) {
                settle.snapTo(0f)
                settle.animateTo(1f, tween(if (landing == YtMiniLanding.CURTAIN) 620 else 420, easing = LinearOutSlowInEasing))
            }
        }
        // moving only while it can be seen: docked and playing
        val live = motionOn && data.playing && YtDock.docked
        val spin = if (live && (look.frame == YtMiniFrame.COOKIE || look.mark == YtMiniMark.COOKIE)) rememberSpin(14000) else null
        val breath = if (live && look.mark == YtMiniMark.BLOB) {
            rememberInfiniteTransition(label = "miniBreath").animateFloat(
                0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse), label = "miniBreathValue",
            )
        } else {
            null
        }
        val density = LocalDensity.current.density
        // right to left: the views swap sides, so the drawn frame and ring swap with them
        val mirror = LocalLayoutDirection.current == LayoutDirection.Rtl
        // the box's height when docked, as the constraints give it (whole px)
        val restBox = ((look.height + look.spec.overlap) * density).roundToInt().toFloat()
        // how far the preview has grown the box: the foot of the body and the seam stay on the bar
        fun grow(height: Float): Float = if (YtDock.peek > 0f) (height - restBox).coerceAtLeast(0f) else 0f

        Box(Modifier.fillMaxSize()) {
            // 1: the surface, joined to the bar
            val glass = look.glassTint != null && backdrop != null
            if (glass) {
                val shape = if (look.joint == YtMiniJoint.STACK) {
                    RoundedCornerShape(topStart = look.radius.dp, topEnd = look.radius.dp, bottomEnd = 0.dp, bottomStart = 0.dp)
                } else {
                    RoundedCornerShape(look.radius.dp)
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .liquidGlass(backdrop!!, shape, look.glassTint!!, look.shadow, blur = 14.dp, refraction = 10.dp),
                )
            } else {
                val shape = remember(look) {
                    GenericShape { size, _ -> addPath(bodyPath(look, size.width, size.height, density, open = false, grow = grow(size.height))) }
                }
                // before Android 10 a shadow takes only convex outlines and the joined body is
                // not one: there the shadow comes from the body's rounded rectangle behind it
                val convexShadow = look.shadow != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && look.joint == YtMiniJoint.BULGE
                if (convexShadow) {
                    Box(
                        Modifier
                            .padding(horizontal = look.body.dp)
                            .fillMaxWidth()
                            .height(look.height.dp)
                            .shadow(10.dp, RoundedCornerShape(look.radius.dp), clip = false, ambientColor = look.shadow ?: Color.Transparent, spotColor = look.shadow ?: Color.Transparent),
                    )
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(
                            if (look.shadow != null && !convexShadow) {
                                Modifier.shadow(10.dp, shape, clip = false, ambientColor = look.shadow, spotColor = look.shadow)
                            } else {
                                Modifier
                            },
                        )
                        .drawBehind {
                            val grown = grow(size.height)
                            val body = bodyPath(look, size.width, size.height, density, open = false, grow = grown)
                            drawPath(body, Brush.verticalGradient(listOf(look.fillTop ?: look.fill, look.fill), endY = look.height * density + grown))
                            look.border?.let { border ->
                                drawPath(
                                    bodyPath(look, size.width, size.height, density, open = true, grow = grown),
                                    border,
                                    style = Stroke(look.borderWidth * density),
                                )
                            }
                        },
                )
            }

            // the seam of progress at the joint
            if (look.bar == YtMiniBar.SEAM) {
                Canvas(Modifier.fillMaxSize().graphicsLayer { if (mirror) scaleX = -1f }) {
                    val w = size.width
                    val content = look.height * density + grow(size.height)
                    val bodyStart = look.body * density
                    val bodyEnd = w - look.body * density
                    val left = if (look.joint == YtMiniJoint.STACK) (look.border?.let { look.borderWidth * density } ?: 0f) else bodyStart
                    val right = if (look.joint == YtMiniJoint.STACK) w - left else bodyEnd
                    val reveal = if (landing == YtMiniLanding.RULE || landing == YtMiniLanding.CURTAIN) settle.value else 1f
                    val track = look.trackWidth * density
                    drawRect(look.track, Offset(left, content - track), Size((right - left) * reveal, track))
                    val bar = look.barWidth * density
                    drawRect(look.barColor, Offset(left, content - bar), Size((right - left) * scene.info.fraction * reveal, bar))
                }
            }

            // the docked row: at the top of the box, as tall as the docked box; it fades as the
            // preview grows
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .requiredHeight((restBox / density).dp)
                    .align(Alignment.TopStart)
                    .graphicsLayer { alpha = YtMiniParts.restFade(scene.peek()) },
            ) {
                val w = constraints.maxWidth.toFloat()
                val g = remember(w, density) { geometry(w, density) }
                val thumb = Rect(g.player.left, g.player.top, g.player.right, g.player.bottom)
                val playCenter = g.play?.let { Offset(it.centerX, it.centerY) }
                val textStart = g.text.left
                val textEnd = g.text.right

                // 2: the frame of the picture, the ring of progress
                Canvas(Modifier.fillMaxSize().graphicsLayer { if (mirror) scaleX = -1f }) {
                    when (look.frame) {
                        YtMiniFrame.RULE -> drawRect(
                            look.frameColor,
                            topLeft = thumb.topLeft - Offset(density, density),
                            size = Size(thumb.width + 2 * density, thumb.height + 2 * density),
                            style = Stroke(density),
                        )
                        YtMiniFrame.STICKER -> drawRoundRect(
                            look.frameColor,
                            topLeft = thumb.topLeft + Offset(3 * density, 3 * density),
                            size = thumb.size,
                            cornerRadius = CornerRadius(look.spec.thumbRadius * density),
                        )
                        YtMiniFrame.GLINT -> drawRoundRect(
                            look.frameColor,
                            topLeft = thumb.topLeft - Offset(1.5f * density, 1.5f * density),
                            size = Size(thumb.width + 3 * density, thumb.height + 3 * density),
                            cornerRadius = CornerRadius((look.spec.thumbRadius + 1.5f) * density),
                            style = Stroke(density),
                        )
                        else -> Unit
                    }
                    if (look.bar == YtMiniBar.RING && playCenter != null) {
                        val radius = (look.markSize / 2f + 4f) * density
                        val stroke = Stroke(look.barWidth * density, cap = StrokeCap.Round)
                        val topLeft = playCenter - Offset(radius, radius)
                        drawArc(look.track, 0f, 360f, false, topLeft, Size(2 * radius, 2 * radius), style = stroke)
                        drawArc(look.barColor, -90f, 360f * scene.info.fraction, false, topLeft, Size(2 * radius, 2 * radius), style = stroke)
                    }
                }

                // 3: Formen: a scalloped shape behind the picture, turning while the video plays
                if (look.frame == YtMiniFrame.COOKIE) {
                    val side = (look.spec.thumbHeight + 14) * density
                    Box(
                        Modifier
                            .offset { IntOffset((thumb.center.x - side / 2f).roundToInt(), (thumb.center.y - side / 2f).roundToInt()) }
                            .size((look.spec.thumbHeight + 14).dp)
                            .graphicsLayer {
                                rotationZ = (spin?.value ?: 0f) - 120f * (1f - settle.value)
                                scaleX = 0.4f + 0.6f * settle.value
                                scaleY = 0.4f + 0.6f * settle.value
                            }
                            .clip(CookieShape(9, 0.08f))
                            .background(look.frameColor),
                    )
                }

                // 4: the mark behind the play button
                if (look.mark != YtMiniMark.NONE && playCenter != null) {
                    val shape = when (look.mark) {
                        YtMiniMark.BLOB -> BlobShape(0.55f, 0.45f, 0.5f, 0.6f)
                        YtMiniMark.COOKIE -> CookieShape(6, 0.1f)
                        else -> CircleShape
                    }
                    Box(
                        Modifier
                            .offset {
                                IntOffset(
                                    (playCenter.x - look.markSize * density / 2f).roundToInt(),
                                    (playCenter.y - look.markSize * density / 2f).roundToInt(),
                                )
                            }
                            .size(look.markSize.dp)
                            .graphicsLayer {
                                val b = breath?.value ?: 0f
                                scaleX = 1f + 0.05f * b
                                scaleY = 1f - 0.03f * b
                                if (look.mark == YtMiniMark.COOKIE) rotationZ = (spin?.value ?: 0f) * 1.5f - 90f * (1f - settle.value)
                            }
                            .clip(shape)
                            .background(look.markColor),
                    )
                }

                // 5: title and channel in the type of the design
                Box(
                    Modifier
                        .offset { IntOffset(textStart.roundToInt(), 0) }
                        .width(((textEnd - textStart).coerceAtLeast(0f) / density).dp)
                        .height(look.height.dp)
                        .graphicsLayer {
                            if (landing == YtMiniLanding.CURTAIN) {
                                alpha = settle.value
                                translationY = 6f * density * (1f - settle.value)
                            }
                        },
                ) {
                    Column(Modifier.align(Alignment.CenterStart)) {
                        val channel = if (look.caps || look.kicker != null) data.channel.uppercase() else data.channel
                        if (look.kicker != null) {
                            val kicker = stringResource(look.kicker).uppercase()
                            Text(
                                text = if (channel.isBlank()) kicker else "$kicker — $channel",
                                style = look.subtitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                        }
                        Text(
                            text = if (look.caps) data.title.uppercase() else data.title,
                            style = look.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (look.kicker == null && channel.isNotBlank()) {
                            Spacer(Modifier.height(1.dp))
                            Text(text = channel, style = look.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        when (look.bar) {
                            YtMiniBar.UNDERLINE -> {
                                Spacer(Modifier.height(6.dp))
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(look.barWidth.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(look.track),
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxHeight()
                                            .fillMaxWidth(data.fraction)
                                            .clip(RoundedCornerShape(50))
                                            .background(look.barColor),
                                    )
                                }
                            }
                            YtMiniBar.WAVE -> {
                                Spacer(Modifier.height(4.dp))
                                Wave(look, data.fraction, live, mirror)
                            }
                            else -> Unit
                        }
                    }
                }

                // the drop target: the outline as a dragged video comes near, the label of a drop
                val outline: (Size) -> Path = remember(look, density) {
                    { size: Size -> bodyPath(look, size.width, size.height, density, open = true, grow = 0f) }
                }
                YtMiniParts.Drop(scene, style, g.text, outline)
            }
        }
    }

    /** Formen: the played part as a wave that flows while the video plays, the rest a line. */
    @Composable
    private fun Wave(look: YtMiniLook, fraction: Float, flowing: Boolean, mirror: Boolean) {
        // read while drawing, so the flow only redraws and does not compose again every frame
        val flow = if (flowing) {
            rememberInfiniteTransition(label = "miniWave").animateFloat(
                0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "miniWavePhase",
            )
        } else {
            null
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .graphicsLayer { if (mirror) scaleX = -1f },
        ) {
            val phase = flow?.value ?: 0f
            val mid = size.height / 2f
            val stroke = look.barWidth * density
            val split = size.width * fraction
            drawLine(look.track, Offset(split, mid), Offset(size.width, mid), stroke, StrokeCap.Round)
            if (split > 0f) {
                val wave = Path()
                val length = 12f * density
                val amplitude = 2.5f * density
                var x = 0f
                wave.moveTo(0f, mid)
                while (x <= split) {
                    wave.lineTo(x, mid + amplitude * sin(2f * PI.toFloat() * (x / length - phase)))
                    x += density
                }
                drawPath(wave, look.barColor, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
    }
}
