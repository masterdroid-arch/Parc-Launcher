package com.brightblu.parc.reader

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.animate
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.brightblu.parc.bookCoverStyle
import com.brightblu.parc.core.data.library.Book
import com.brightblu.parc.core.data.library.BookMetadataLoader
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.Springs
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * T210: the animated shell around the reader. A shelf cover grows into the page; a top-edge pull or
 * Back shrinks the page back into that cover (or slides an open-with page down); the whole surface
 * is interruptible because both the enter and the close are one spring-driven value.
 *
 * The content is composed from the first frame, so a cached book is already drawn when the cover
 * lands. The window itself is translucent (Theme.Parc.Reader), so the launcher shelf stays visible
 * around the moving cover instead of a black or white flash.
 */
@Composable
internal fun ReaderMotionShell(
    request: ReaderRequest,
    skin: Skin,
    onClose: () -> Unit,
    colors: ReaderColors,
    contentReady: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screen = ReaderMotion.full(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        if (!screen.isValid) {
            content()
            return@BoxWithConstraints
        }
        val cover = request.coverRect?.takeIf { !request.unadded && it.isValid } ?: screen
        val hasCover = request.coverRect != null && !request.unadded && request.coverRect.isValid
        val density = LocalDensity.current
        val scope = rememberCoroutineScope()
        val enter = remember { Animatable(0f) }
        val coverAlpha = remember { Animatable(if (hasCover) 1f else 0f) }
        var pull by remember { mutableFloatStateOf(0f) }
        var pullJob by remember { mutableStateOf<Job?>(null) }
        var closing by remember { mutableStateOf(false) }
        var finished by remember { mutableStateOf(false) }
        var entered by remember { mutableStateOf(false) }
        val coverShape = remember { RoundedCornerShape(Metrics.bookShelfCornerDp.dp) }
        val coverRadiusPx = remember(density) { with(density) { Metrics.bookShelfCornerDp.dp.toPx() } }
        val titleArt = remember(request.book.id, skin, colors, screen) {
            if (hasCover) ReaderCoverArt.title(context, request.book, skin, colors,
                screen.width.toInt(), screen.height.toInt()) else null
        }
        var coverArt by remember(request.book.id, screen, colors) { mutableStateOf(titleArt) }
        LaunchedEffect(request.book.id, screen, colors) {
            if (hasCover) {
                val bitmap = BookMetadataLoader(context).displayCover(request.book,
                    screen.width.toInt(), screen.height.toInt())
                if (bitmap != null) coverArt = ReaderCoverArt(bitmap, colors.paper.toInt())
            }
        }

        fun finish() {
            if (finished) return
            finished = true
            onClose()
        }

        fun beginClose() {
            if (closing) return
            closing = true
            scope.launch {
                enter.snapTo(1f)
                coverAlpha.snapTo(0f)
            }
        }

        fun animatePull(target: Float, velocityPxPerSec: Float = 0f) {
            pullJob?.cancel()
            pullJob = scope.launch {
                animate(
                    initialValue = pull,
                    targetValue = target,
                    initialVelocity = velocityPxPerSec /
                        (screen.height * Metrics.readerPullDistanceFraction).coerceAtLeast(1f),
                    animationSpec = spring(
                        if (target >= 1f) Springs.open.damping else Springs.sheet.damping,
                        if (target >= 1f) Springs.open.stiffness else Springs.sheet.stiffness,
                    ),
                ) { value, _ -> pull = value }
                if (target >= 1f) {
                    withFrameNanos { }
                    finish()
                } else {
                    closing = false
                }
            }
        }

        fun closeWithSpring() {
            if (finished) return
            beginClose()
            animatePull(1f)
        }

        fun settlePull(velocityPxPerSec: Float) {
            if (ReaderMotion.pullTarget(pull, velocityPxPerSec) >= 1f) {
                animatePull(1f, velocityPxPerSec.coerceAtLeast(0f))
            } else {
                animatePull(0f, velocityPxPerSec.coerceAtMost(0f))
            }
        }

        LaunchedEffect(Unit) {
            enter.animateTo(1f, spring(Springs.open.damping, Springs.open.stiffness))
            entered = true
        }

        // The cover stays full-screen until the page has actually drawn its first frame (the JS
        // page reports ready, or the first PDF page is in the cache). This is what keeps a cached
        // book from showing a blank page between the cover landing and the text. A safety hold
        // releases the cover if a reader never reports ready.
        LaunchedEffect(entered, contentReady, closing, hasCover) {
            if (!hasCover || !entered || closing) return@LaunchedEffect
            if (!contentReady) delay(Metrics.readerCoverHoldMaxMs)
            if (!closing) {
                coverAlpha.animateTo(0f, spring(Springs.sheet.damping, Springs.sheet.stiffness))
            }
        }

        BackHandler(enabled = !finished) { closeWithSpring() }

        Box(
            Modifier.fillMaxSize().pointerInput(hasCover, finished) {
                if (finished) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (closing || !ReaderMotion.startsInPullZone(down.position.y, screen.height)) {
                        return@awaitEachGesture
                    }
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var total = 0f
                    var dragging = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        tracker.addPosition(change.uptimeMillis, change.position)
                        val dx = change.position.x - down.position.x
                        val dy = change.position.y - down.position.y
                        if (!dragging) {
                            if (ReaderMotion.isHorizontalGesture(dx, dy, viewConfiguration.touchSlop)) break
                            if (dy > viewConfiguration.touchSlop) {
                                dragging = true
                                beginClose()
                            }
                        }
                        if (dragging) {
                            total += change.position.y - change.previousPosition.y
                            pullJob?.cancel(); pull = ReaderMotion.pullProgress(total, screen.height)
                            change.consume()
                        }
                    }
                    if (dragging) settlePull(tracker.calculateVelocity().y)
                }
            }.graphicsLayer {
                val progress = pull.coerceIn(0f, 1f)
                if (hasCover) {
                    if (closing) {
                        val rect = ReaderMotion.lerp(screen, cover, progress)
                        scaleX = rect.width / screen.width
                        scaleY = rect.height / screen.height
                        translationX = rect.centerX - screen.centerX
                        translationY = rect.centerY - screen.centerY
                        clip = true
                        shape = coverShape
                        alpha = 1f
                    } else {
                        alpha = enter.value
                    }
                } else {
                    translationY = if (closing) {
                        progress * screen.height
                    } else {
                        ReaderMotion.riseOffsetPx(screen.height, enter.value)
                    }
                    alpha = if (closing) 1f - progress else enter.value
                }
            },
        ) {
            content()
        }

        if (hasCover) {
            Canvas(Modifier.fillMaxSize()) {
                val progress = pull.coerceIn(0f, 1f)
                val alpha = if (progress > 0f) {
                    ReaderMotion.closeCoverAlpha(progress)
                } else {
                    coverAlpha.value
                }
                if (alpha <= 0.001f) return@Canvas
                val rect = if (progress > 0f) {
                    ReaderMotion.lerp(screen, cover, progress)
                } else {
                    ReaderMotion.coverToPage(cover, screen, enter.value)
                }
                coverArt?.draw(this, rect, alpha, coverRadiusPx)
            }
        }
    }
}

/** One display-sized bitmap, fitted whole on paper; draw objects are reused during motion. */
internal class ReaderCoverArt(private val bitmap: Bitmap, private val paper: Int) {
    private val src = Rect(0, 0, bitmap.width, bitmap.height)
    private val dst = RectF()
    private val bounds = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    fun draw(scope: DrawScope, rect: ReaderMotion.Rect, alpha: Float, cornerPx: Float) {
        if (rect.width < 1f || rect.height < 1f || alpha <= 0f) return
        bounds.set(rect.left, rect.top, rect.right, rect.bottom)
        val scale = minOf(rect.width / bitmap.width, rect.height / bitmap.height)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        dst.set(rect.centerX - width / 2, rect.centerY - height / 2,
            rect.centerX + width / 2, rect.centerY + height / 2)
        paint.color = paper
        paint.alpha = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
        scope.drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            path.reset()
            path.addRoundRect(bounds, cornerPx, cornerPx, Path.Direction.CW)
            native.save()
            native.clipPath(path)
            native.drawRect(bounds, paint)
            native.drawBitmap(bitmap, src, dst, paint)
            native.restore()
        }
    }

    companion object {
        fun title(context: android.content.Context, book: Book, skin: Skin, colors: ReaderColors,
            width: Int, height: Int): ReaderCoverArt? {
            val bitmap = runCatching {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }.getOrNull() ?: return null
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(colors.paper.toInt())
            val style = bookCoverStyle(context, skin)
            val inset = width * Metrics.bookCoverInsetFraction
            val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colors.ink.toInt()
                typeface = style.typeface
                textSize = minOf(width, height) * Metrics.bookCoverTitleFraction
            }
            val authorPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colors.secondary.toInt()
                typeface = style.typeface
                textSize = minOf(width, height) * Metrics.bookCoverAuthorFraction
            }
            val titleLayout = StaticLayout.Builder.obtain(
                book.title, 0, book.title.length, titlePaint, (width - inset * 2f).coerceAtLeast(1f).toInt(),
            ).setMaxLines(Metrics.bookCoverTitleLines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(0f, Metrics.bookCoverLineSpacing)
                .build()
            canvas.save()
            canvas.translate(inset, inset)
            titleLayout.draw(canvas)
            canvas.restore()
            if (book.author.isNotBlank()) {
                val authorLayout = StaticLayout.Builder.obtain(
                    book.author, 0, book.author.length, authorPaint,
                    (width - inset * 2f).coerceAtLeast(1f).toInt(),
                ).setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).build()
                canvas.save()
                canvas.translate(inset, (height - inset - authorLayout.height).coerceAtLeast(inset))
                authorLayout.draw(canvas)
                canvas.restore()
            }
            return ReaderCoverArt(bitmap, colors.paper.toInt())
        }
    }
}
