package com.brightblu.parc.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.Choreographer
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.brightblu.parc.core.data.library.Book
import com.brightblu.parc.core.data.library.BookFiles
import com.brightblu.parc.core.data.library.BookPosition
import com.brightblu.parc.core.data.library.PdfLayout
import com.brightblu.parc.core.data.library.PdfRenderSize
import com.brightblu.parc.core.data.library.ReaderBookException
import com.brightblu.parc.core.data.library.ReaderBookMarks
import com.brightblu.parc.core.data.library.ReaderImageTone
import com.brightblu.parc.core.data.library.ReaderPdfMath
import com.brightblu.parc.core.data.library.ReaderSettings
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.Springs
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** T208: plain messages, no jargon and no exclamation marks. */
internal object ReaderPdfMessages {
    const val Password = "This PDF is password-protected. Remove the password to read it here."
    const val Damaged = "This PDF is damaged or not a valid PDF."
    const val Missing = "This PDF could not be opened. The file may have moved."
    const val Empty = "This PDF has no pages."
    const val TooLarge = "This PDF is too large to open."
    const val PageFailed = "This page could not be shown. The file may be damaged."
}

/**
 * One open [PdfRenderer] and its page sizes. Rendering is serialised because the platform
 * renderer is not thread-safe; close waits for an in-flight page and then releases everything.
 * The object is created only when a PDF opens and is closed when the reader leaves.
 */
internal class PdfBook(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    val pageSizes: List<PdfRenderSize>,
    private val temporary: File?,
) {
    private val lock = Any()
    private var closed = false

    val pageCount: Int get() = pageSizes.size

    /** Returns null when the page cannot be rendered (a damaged page) or the book is closed. */
    fun render(page: Int, viewportWidth: Int, maxHeight: Int, scale: Float = 1f): Bitmap? = synchronized(lock) {
        if (closed) return null
        val raw = pageSizes.getOrNull(page) ?: return null
        val size = ReaderPdfMath.renderSize(raw.width, raw.height, viewportWidth, maxHeight, scale)
        val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        try {
            renderer.openPage(page).use { pdfPage ->
                bitmap.eraseColor(android.graphics.Color.WHITE)
                pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
            bitmap
        } catch (_: Exception) {
            bitmap.recycle()
            null
        }
    }

    /**
     * T254: the page's text where the PDF has any. The platform reads it from Android 15 (SDK
     * extension 13); on older phones, or for a scanned page, this is null and the search says so.
     */
    @android.annotation.SuppressLint("NewApi")
    fun pageText(page: Int): String? = synchronized(lock) {
        if (closed || !textSearchAvailable) return null
        runCatching {
            renderer.openPage(page).use { pdfPage ->
                pdfPage.textContents.joinToString("\n") { it.text }.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { renderer.close() }
            runCatching { descriptor.close() }
            temporary?.delete()
        }
    }
}

/** True when this phone can read text out of a PDF page (Android 15 and its PDF extension). */
internal val textSearchAvailable: Boolean by lazy {
    android.os.Build.VERSION.SDK_INT >= 35 ||
        (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            android.os.ext.SdkExtensions.getExtensionVersion(android.os.Build.VERSION_CODES.S) >= 13)
}

/** Opens the document off the main thread, with a local copy only when a provider is not seekable. */
internal object ReaderPdfLoader {
    suspend fun load(context: Context, book: Book): PdfBook = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val files = BookFiles(app)
        try {
            try {
                // T235: PdfRenderer seeks in its descriptor. A provider pipe reports -1 here and
                // a few report 0; copying those first is what makes a content-URI PDF render
                // instead of failing page by page. `file:` descriptors stay zero-copy.
                val direct = files.descriptor(book.uri)
                if (direct.statSize > 0L) {
                    open(direct, temporary = null, copied = false)
                } else {
                    runCatching { direct.close() }
                    copyAndOpen(app, files, book)
                }
            } catch (e: SecurityException) {
                throw ReaderBookException(ReaderPdfMessages.Password, e)
            } catch (e: java.io.IOException) {
                copyAndOpen(app, files, book)
            } catch (e: IllegalArgumentException) {
                copyAndOpen(app, files, book)
            }
        } catch (e: ReaderBookException) {
            throw e
        } catch (e: Exception) {
            throw ReaderBookException(ReaderPdfMessages.Missing, e)
        }
    }

    private fun copyAndOpen(
        app: Context,
        files: BookFiles,
        book: Book,
    ): PdfBook {
        val copy = File.createTempFile("parc-reader-", ".pdf", app.cacheDir)
        try {
            files.open(book.uri).use { input ->
                copy.outputStream().use { output -> copyCapped(input, output, Metrics.readerMaxSourceBytes) }
            }
        } catch (e: ReaderBookException) {
            copy.delete()
            throw e
        } catch (e: Exception) {
            copy.delete()
            throw ReaderBookException(ReaderPdfMessages.Missing, e)
        }
        try {
            return open(ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY), copy, copied = true)
        } catch (e: SecurityException) {
            copy.delete()
            throw ReaderBookException(ReaderPdfMessages.Password, e)
        } catch (e: java.io.IOException) {
            copy.delete()
            throw ReaderBookException(ReaderPdfMessages.Damaged, e)
        } catch (e: IllegalArgumentException) {
            copy.delete()
            throw ReaderBookException(ReaderPdfMessages.Damaged, e)
        } catch (e: Exception) {
            copy.delete()
            throw ReaderBookException(ReaderPdfMessages.Damaged, e)
        }
    }

    private fun open(
        descriptor: ParcelFileDescriptor,
        temporary: File?,
        copied: Boolean,
    ): PdfBook {
        val renderer = try {
            PdfRenderer(descriptor)
        } catch (e: SecurityException) {
            runCatching { descriptor.close() }
            throw e
        } catch (e: java.io.IOException) {
            runCatching { descriptor.close() }
            throw e
        } catch (e: IllegalArgumentException) {
            runCatching { descriptor.close() }
            throw e
        } catch (e: Exception) {
            runCatching { descriptor.close() }
            throw ReaderBookException(if (copied) ReaderPdfMessages.Damaged else ReaderPdfMessages.Missing, e)
        }
        try {
            if (renderer.pageCount <= 0) throw ReaderBookException(ReaderPdfMessages.Empty)
            val sizes = ArrayList<PdfRenderSize>(renderer.pageCount)
            for (index in 0 until renderer.pageCount) {
                renderer.openPage(index).use { page ->
                    sizes += PdfRenderSize(page.width.coerceAtLeast(1), page.height.coerceAtLeast(1))
                }
            }
            return PdfBook(descriptor, renderer, sizes, temporary)
        } catch (e: SecurityException) {
            runCatching { renderer.close() }
            runCatching { descriptor.close() }
            throw e
        } catch (e: Exception) {
            runCatching { renderer.close() }
            runCatching { descriptor.close() }
            if (e is ReaderBookException) throw e
            throw ReaderBookException(if (copied) ReaderPdfMessages.Damaged else ReaderPdfMessages.Missing, e)
        }
    }

    private fun copyCapped(input: InputStream, output: OutputStream, max: Long) {
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > max) throw ReaderBookException(ReaderPdfMessages.TooLarge)
            output.write(buffer, 0, read)
        }
    }
}

/**
 * T343: the pure cache order. The page being read (usually the restored page) is rendered first,
 * then its nearest neighbours. [fit] decides which already-known pages stay inside the byte
 * budget; the current page and at least one neighbour are always kept.
 */
internal object PdfCachePolicy {
    fun order(pages: List<Int>, priority: Int): List<Int> =
        pages.distinct().sortedWith(compareBy({ abs(it - priority) }, { it }))

    fun fit(pages: List<Int>, bytes: Map<Int, Int>, budgetBytes: Int, minPages: Int, priority: Int): List<Int> {
        val ordered = order(pages, priority)
        val minimum = minPages.coerceAtLeast(1)
        val kept = ArrayList<Int>(ordered.size)
        var total = 0L
        for (page in ordered) {
            val size = (bytes[page] ?: 0).toLong().coerceAtLeast(0L)
            if (kept.size < minimum || total + size <= budgetBytes.coerceAtLeast(0).toLong()) {
                kept += page
                total += size
            }
        }
        return kept
    }
}

/**
 * A small state holder for rendered page bitmaps; evicted bitmaps are recycled after the next
 * frame. T235 re-renders the window at the settled zoom scale so a zoomed page is sharp, and
 * remembers a failed page so the reader can say so instead of showing "Page n" forever.
 * T343 bounds the window by bytes, renders the reading page first and drops a stale target set
 * after a long jump.
 */
internal class PdfPageCache(private val book: PdfBook) {
    private val state: SnapshotStateMap<Int, ImageBitmap> = mutableStateMapOf()
    private val bitmaps = HashMap<Int, Bitmap>()
    private val failures: SnapshotStateMap<Int, Boolean> = mutableStateMapOf()
    private val mutex = Mutex()
    private var closed = false
    private var scale = 1f
    @Volatile private var wanted: Set<Int> = emptySet()

    val images: Map<Int, ImageBitmap> get() = state
    val failed: Map<Int, Boolean> get() = failures

    suspend fun ensure(pages: List<Int>, viewportWidth: Int, maxHeight: Int, maxPages: Int,
        renderScale: Float = 1f, priority: Int = pages.firstOrNull() ?: 0) {
        val safeScale = ReaderPdfMath.renderScaleForZoom(renderScale)
        val ordered = PdfCachePolicy.order(pages, priority)
        val keep = ordered.take(maxPages.coerceAtLeast(1))
        wanted = keep.toSet()
        if (abs(scale - safeScale) > 0.01f) {
            scale = safeScale
            // Existing bitmaps are at the old scale. Let the draw path keep them for this frame,
            // then recycle them and fetch a sharper window for the new zoom.
            retain(emptySet())
            failures.clear()
        } else {
            retain(keep.toSet())
        }
        for (page in keep) {
            if (state.containsKey(page) || failures.containsKey(page)) continue
            mutex.withLock {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (closed || page !in wanted || state.containsKey(page) || failures.containsKey(page)) return@withLock
                // T343: make room before allocating the next page so the peak bitmap total
                // stays near the budget, not one page above it.
                val raw = book.pageSizes.getOrNull(page)
                val extra = if (raw == null) 0 else ReaderPdfMath.bytes(
                    ReaderPdfMath.renderSize(raw.width, raw.height, viewportWidth, maxHeight, safeScale))
                trimToBudget(priority, extra)
                // NonCancellable keeps a finished bitmap returnable: a stale result is then
                // recycled below instead of being lost with the cancelled continuation.
                val bitmap = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    book.render(page, viewportWidth, maxHeight, safeScale)
                }
                if (bitmap == null) {
                    failures[page] = true
                    return@withLock
                }
                if (closed || page !in wanted) {
                    if (!closed) recycleSoon(bitmap) else bitmap.recycle()
                    return@withLock
                }
                bitmaps[page] = bitmap
                state[page] = bitmap.asImageBitmap()
                trimToBudget(priority)
            }
        }
    }

    private fun trimToBudget(priority: Int, reserveBytes: Int = 0) {
        if (bitmaps.isEmpty()) return
        val sizes = bitmaps.mapValues { it.value.byteCount }
        val budget = (Metrics.pdfCacheMaxBytes - reserveBytes.coerceAtLeast(0)).coerceAtLeast(0)
        val keep = PdfCachePolicy.fit(bitmaps.keys.toList(), sizes, budget,
            Metrics.pdfCacheMinPages, priority).toSet()
        for (page in bitmaps.keys.filter { it !in keep }) {
            val dropped = bitmaps.remove(page) ?: continue
            state.remove(page)
            recycleSoon(dropped)
        }
    }

    fun retain(keep: Set<Int>) {
        val drop = state.keys.filter { it !in keep }
        for (page in drop) {
            state.remove(page)
            bitmaps.remove(page)?.let(::recycleSoon)
        }
    }

    fun close() {
        closed = true
        wanted = emptySet()
        state.clear()
        bitmaps.values.forEach(::recycleSoon)
        bitmaps.clear()
        failures.clear()
    }

    private fun recycleSoon(bitmap: Bitmap) {
        Choreographer.getInstance().postFrameCallback { runCatching { bitmap.recycle() } }
    }
}

/**
 * T210: draws a cached page with a reused Paint and destination rectangle, so the page-follow draw
 * path allocates nothing per frame. T345: the colour filter is one matrix built when the settings
 * change: luminance for greyscale, then inversion for white-on-black.
 */
internal fun pdfColorMatrix(grey: Boolean, invert: Boolean): FloatArray? {
    if (!grey && !invert) return null
    val scale = if (invert) -1f else 1f
    val offset = if (invert) 255f else 0f
    return if (grey) {
        val lr = 0.2126f
        val lg = 0.7152f
        val lb = 0.0722f
        floatArrayOf(
            scale * lr, scale * lg, scale * lb, 0f, offset,
            scale * lr, scale * lg, scale * lb, 0f, offset,
            scale * lr, scale * lg, scale * lb, 0f, offset,
            0f, 0f, 0f, 1f, 0f,
        )
    } else {
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        )
    }
}

internal class PdfPagePainter(grey: Boolean, invert: Boolean) {
    private val destination = android.graphics.RectF()
    private val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
        val matrix = pdfColorMatrix(grey, invert)
        colorFilter = matrix?.let { android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(it)) }
    }

    fun draw(scope: DrawScope, image: ImageBitmap, left: Float, top: Float, width: Float, height: Float) {
        destination.set(left, top, left + width, top + height)
        scope.drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawBitmap(image.asAndroidBitmap(), null, destination, paint)
        }
    }
}

/**
 * The PDF reader body: one Canvas draws the pages the cache holds, a small window is pre-rendered
 * around the reading position, and gestures scroll, turn, pinch and double-tap with springs.
 */
@Composable
internal fun PdfReaderPage(
    book: Book,
    pdf: PdfBook,
    settings: ReaderSettings,
    skin: Skin,
    initialPosition: BookPosition,
    onPosition: (BookPosition) -> Unit,
    onLastPage: () -> Unit,
    onSettingsChange: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onReady: () -> Unit,
    canAddToLibrary: Boolean,
    adding: Boolean,
    addNotice: String?,
    onAddToLibrary: () -> Unit,
    marks: ReaderBookMarks,
    onToggleBookmark: (BookPosition) -> Unit,
    onDeleteBookmark: (String) -> Unit,
    onDeleteHighlight: (String) -> Unit,
    onAddToLightly: (String) -> Unit,
) {
    val context = LocalContext.current
    val colors = remember(settings.pageColor, settings.night, skin) { readerColors(settings, skin) }
    val painter = remember(settings.imageTone, settings.pdfInvert) {
        PdfPagePainter(settings.imageTone == ReaderImageTone.Greyscale, settings.pdfInvert)
    }
    var chrome by remember { mutableStateOf(ReaderChrome.Hidden) }
    val barsVisible = chrome != ReaderChrome.Hidden
    var sheet by remember { mutableStateOf(ReaderSheet.None) }
    var lastSheet by remember { mutableStateOf(ReaderSheet.None) }
    var returnSheet by remember { mutableStateOf<ReaderSheet?>(null) }
    var previousPlace by remember(book.id) { mutableStateOf<BookPosition?>(null) }
    val marksState = rememberLazyListState()
    val searchState = rememberLazyListState()
    val searchSession = remember(book.id) { ReaderSearchSession() }
    LaunchedEffect(sheet) { if (sheet != ReaderSheet.None) lastSheet = sheet }
    val displayedSheet = if (sheet != ReaderSheet.None) sheet else lastSheet
    val scope = rememberCoroutineScope()
    val latestOnPosition = rememberUpdatedState(onPosition)
    val pageCount = pdf.pageCount.coerceAtLeast(1)
    var currentPage by remember(book.id) { mutableIntStateOf(initialPosition.page.coerceIn(0, pageCount - 1)) }
    var settledOffset by remember(book.id) { mutableStateOf(initialPosition.pageOffset.coerceIn(0.0, 1.0)) }
    var pageDrag by remember(book.id) { mutableFloatStateOf(0f) }
    var scroll by remember(book.id) { mutableFloatStateOf(0f) }
    var zoom by remember(book.id) { mutableFloatStateOf(1f) }
    // T235: bitmaps are fetched at the settled zoom scale, not only at screen width.
    var renderZoom by remember(book.id) { mutableFloatStateOf(1f) }
    var panX by remember(book.id) { mutableFloatStateOf(0f) }
    var panY by remember(book.id) { mutableFloatStateOf(0f) }
    var navigationJob by remember(book.id) { mutableStateOf<Job?>(null) }

    val keyboard = LocalSoftwareKeyboardController.current
    val keyboardUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val backAction = ReaderJump.backAction(keyboardUp, sheet != ReaderSheet.None,
        returnSheet != null, chrome == ReaderChrome.Panel)
    BackHandler(enabled = backAction != ReaderBack.ExitReader) {
        when (backAction) {
            ReaderBack.HideKeyboard -> keyboard?.hide()
            ReaderBack.CloseSheet -> sheet = ReaderSheet.None
            ReaderBack.ReopenList -> { sheet = returnSheet ?: ReaderSheet.None; returnSheet = null }
            ReaderBack.HidePanel -> chrome = ReaderChrome.Toolbar
            ReaderBack.ExitReader -> Unit
        }
    }
    LaunchedEffect(barsVisible, sheet, skin.colors.surface) {
        (context as? ReaderActivity)?.setReaderChrome(visible = barsVisible || sheet != ReaderSheet.None, light = readerChromeIsLight(skin))
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(colors.paper.toInt()))) {
        val viewportWidth = constraints.maxWidth.coerceAtLeast(1)
        val viewportHeight = constraints.maxHeight.coerceAtLeast(1)
        val density = LocalDensity.current
        val gapPx = with(density) { Metrics.pdfPageGapDp.dp.toPx() }
        val baseHeights = remember(pdf, viewportWidth) {
            pdf.pageSizes.map { ReaderPdfMath.displayHeight(it.width, it.height, viewportWidth).toFloat() }
        }
        val baseTops = remember(baseHeights, gapPx) {
            val tops = ArrayList<Float>(baseHeights.size)
            var top = 0f
            for (height in baseHeights) {
                tops += top
                top += height + gapPx
            }
            tops
        }
        val totalHeight = remember(baseTops, baseHeights, gapPx) {
            if (baseTops.isEmpty()) 0f else baseTops.last() + baseHeights.last() + gapPx
        }
        val maxRenderHeight = ReaderPdfMath.maxRenderHeight(viewportHeight)
        val renderScale = ReaderPdfMath.renderScaleForZoom(renderZoom)
        val maxCachePages = remember(pdf, viewportWidth, viewportHeight, renderScale) {
            val largest = pdf.pageSizes.maxOfOrNull { raw ->
                val size = ReaderPdfMath.renderSize(raw.width, raw.height, viewportWidth,
                    maxRenderHeight, renderScale)
                ReaderPdfMath.bytes(size)
            } ?: 1
            ReaderPdfMath.cacheLimit(largest, Metrics.pdfCacheMaxBytes,
                Metrics.pdfCacheMinPages, Metrics.pdfCacheMaxPages)
        }
        val cache = remember(book.id, viewportWidth, viewportHeight) { PdfPageCache(pdf) }
        DisposableEffect(cache) { onDispose { cache.close() } }

        val scrollPosition by remember(baseTops, baseHeights) {
            derivedStateOf { ReaderPdfMath.positionAt(baseTops, baseHeights, scroll) }
        }
        var restored by remember(book.id) { mutableStateOf(false) }
        val restoredPage = initialPosition.page.coerceIn(0, pageCount - 1)
        val displayedPage = when {
            settings.pdfLayout == PdfLayout.Page -> currentPage
            !restored -> restoredPage
            else -> scrollPosition.page
        }
        val visiblePages by remember(baseTops, baseHeights, settings.pdfLayout, restored) {
            derivedStateOf {
                if (settings.pdfLayout != PdfLayout.Continuous || !restored) emptyList()
                else ReaderPdfMath.visiblePages(baseTops, baseHeights, scroll, viewportHeight / zoom,
                    overscan = 1)
            }
        }
        // T343: before the saved position is restored, ask the cache for the saved page, not page
        // one. A long jump cancels the old set because `wanted` changes on the next ensure call.
        val targets = if (settings.pdfLayout == PdfLayout.Page) {
            remember(currentPage, pageCount, maxCachePages) {
                ReaderPdfMath.pageTargets(currentPage, pageCount, maxCachePages)
            }
        } else {
            visiblePages.take(maxCachePages)
        }
        val targetPriority = if (settings.pdfLayout == PdfLayout.Page) currentPage else displayedPage

        fun maxScrollAt(atZoom: Float): Float =
            (totalHeight - viewportHeight / atZoom.coerceAtLeast(0.01f)).coerceAtLeast(0f)

        fun currentDisplayedPage(): Int =
            if (settings.pdfLayout == PdfLayout.Page) currentPage
            else ReaderPdfMath.positionAt(baseTops, baseHeights, scroll).page

        LaunchedEffect(targets, targetPriority, cache, viewportWidth, viewportHeight, renderScale, maxCachePages) {
            if (targets.isNotEmpty()) {
                cache.ensure(targets, viewportWidth, maxRenderHeight, maxCachePages, renderScale,
                    priority = targetPriority)
            }
        }

        var firstPageDrawn by remember(book.id) { mutableStateOf(false) }
        LaunchedEffect(firstPageDrawn, displayedPage) {
            if (firstPageDrawn && displayedPage >= pageCount - 1) onLastPage()
        }

        LaunchedEffect(baseTops, viewportWidth, viewportHeight, settings.pdfLayout) {
            if (baseTops.isEmpty()) return@LaunchedEffect
            val page = if (restored) currentPage else initialPosition.page.coerceIn(0, pageCount - 1)
            val offset = if (restored) settledOffset else initialPosition.pageOffset.coerceIn(0.0, 1.0)
            currentPage = page
            settledOffset = offset
            scroll = ReaderPdfMath.scrollFor(baseTops, baseHeights, page, offset)
                .coerceIn(0f, maxScrollAt(zoom))
            restored = true
            latestOnPosition.value(BookPosition(page = currentPage, pageOffset = settledOffset))
        }

        fun reportPosition(position: BookPosition) {
            val safe = ReaderPdfMath.clamp(position, pageCount)
            latestOnPosition.value(safe)
        }

        fun goToPage(page: Int, offset: Double = 0.0) {
            val targetPage = page.coerceIn(0, pageCount - 1)
            val targetOffset = offset.coerceIn(0.0, 1.0)
            pageDrag = 0f
            currentPage = targetPage
            settledOffset = targetOffset
            reportPosition(BookPosition(page = targetPage, pageOffset = targetOffset))
            navigationJob?.cancel()
            navigationJob = scope.launch {
                val target = ReaderPdfMath.scrollFor(baseTops, baseHeights, targetPage, targetOffset)
                    .coerceIn(0f, maxScrollAt(zoom))
                animate(scroll, target, animationSpec = spring(Springs.sheet.damping, Springs.sheet.stiffness)) { value, _ ->
                    scroll = value
                }
                animate(panX, 0f, animationSpec = spring(Springs.sheet.damping, Springs.sheet.stiffness)) { value, _ ->
                    panX = value
                }
                animate(panY, 0f, animationSpec = spring(Springs.sheet.damping, Springs.sheet.stiffness)) { value, _ ->
                    panY = value
                }
            }
        }

        /** The place the reader is at now, used by "Previous place". */
        fun here(): BookPosition =
            if (settings.pdfLayout == PdfLayout.Page) BookPosition(page = currentPage, pageOffset = settledOffset)
            else ReaderPdfMath.positionAt(baseTops, baseHeights, scroll)

        /** T344: the slider and its steps keep the panel open, so the new page can be read. */
        fun movePage(target: Int, offset: Double = 0.0) {
            previousPlace = here()
            goToPage(target, offset)
        }

        /** A list jump closes the list and the panel to show the page. */
        fun jumpPage(target: Int, offset: Double = 0.0, from: ReaderSheet) {
            previousPlace = here()
            returnSheet = from
            sheet = ReaderSheet.None
            chrome = ReaderChrome.Hidden
            goToPage(target, offset)
        }

        fun returnToPreviousPlace() {
            val place = previousPlace ?: return
            previousPlace = null
            returnSheet = null
            sheet = ReaderSheet.None
            goToPage(place.page, place.pageOffset)
        }

        /** T210: a PDF page turn follows the finger, then settles onto the target page with a spring. */
        fun settlePageTurn(targetPage: Int) {
            val target = targetPage.coerceIn(0, pageCount - 1)
            if (target == currentPage && abs(pageDrag) < 0.5f) {
                pageDrag = 0f
                return
            }
            navigationJob?.cancel()
            navigationJob = scope.launch {
                animate(
                    pageDrag,
                    ReaderMotion.pageSettleOffset(currentPage, target, viewportWidth.toFloat()),
                    animationSpec = spring(Springs.sheet.damping, Springs.sheet.stiffness),
                ) { value, _ -> pageDrag = value }
                pageDrag = 0f
                if (target != currentPage) {
                    currentPage = target
                    settledOffset = 0.0
                    reportPosition(BookPosition(page = target, pageOffset = 0.0))
                    // The new page starts centred at the same zoom.
                    if (panX != 0f) animate(panX, 0f,
                        animationSpec = spring(Springs.sheet.damping, Springs.sheet.stiffness)) { value, _ -> panX = value }
                    if (panY != 0f) animate(panY, 0f,
                        animationSpec = spring(Springs.sheet.damping, Springs.sheet.stiffness)) { value, _ -> panY = value }
                }
            }
        }

        DisposableEffect(settings.volumeKeys, settings.pdfLayout) {
            val activity = context as? ReaderActivity
            if (settings.volumeKeys && settings.pdfLayout == PdfLayout.Page) {
                activity?.volumeKeyHandler = { forward ->
                    settlePageTurn(currentPage + if (forward) 1 else -1)
                    true
                }
            }
            onDispose { if (activity?.volumeKeyHandler != null) activity.volumeKeyHandler = null }
        }

        fun reportCurrentPosition() {
            if (settings.pdfLayout == PdfLayout.Page) {
                reportPosition(BookPosition(page = currentPage, pageOffset = 0.0))
            } else {
                val position = ReaderPdfMath.positionAt(baseTops, baseHeights, scroll)
                currentPage = position.page
                settledOffset = position.pageOffset
                reportPosition(position)
            }
        }

        /** The height of the page as actually drawn in page mode (fitted, before zoom). */
        fun fittedPageHeight(): Float {
            val raw = pdf.pageSizes.getOrNull(currentPage) ?: return viewportHeight.toFloat()
            val aspect = raw.width.toFloat() / raw.height.toFloat()
            val fitWidth = minOf(viewportWidth.toFloat(), viewportHeight * aspect)
            return (fitWidth / aspect).coerceAtLeast(1f)
        }

        fun panLimit(atZoom: Float): ReaderPdfMath.PdfPan = ReaderPdfMath.panLimit(
            viewportWidth, viewportHeight, fittedPageHeight(), atZoom,
            vertical = settings.pdfLayout == PdfLayout.Page,
        )

        fun clampPan(value: Offset, atZoom: Float): Offset {
            if (atZoom <= Metrics.pdfZoomLabelThreshold) return Offset.Zero
            val limit = panLimit(atZoom)
            val safe = ReaderPdfMath.clampPan(value.x, value.y, limit)
            return Offset(safe.x, safe.y)
        }

        fun animatePan(target: Offset) {
            navigationJob?.cancel()
            navigationJob = scope.launch {
                animate(panX, target.x, animationSpec = spring(Springs.sheet.damping, Springs.sheet.stiffness)) { value, _ ->
                    panX = value
                }
                animate(panY, target.y, animationSpec = spring(Springs.sheet.damping, Springs.sheet.stiffness)) { value, _ ->
                    panY = value
                }
            }
        }

        fun animateZoom(target: Float) {
            navigationJob?.cancel()
            val safe = ReaderPdfMath.renderScaleForZoom(target)
            renderZoom = safe
            val targetPan = clampPan(Offset(panX, panY), safe)
            val targetScroll = scroll.coerceIn(0f, maxScrollAt(safe))
            navigationJob = scope.launch {
                animate(zoom, safe, animationSpec = spring(Springs.sheet.damping, Springs.sheet.stiffness)) { value, _ ->
                    zoom = value
                }
                animate(scroll, targetScroll, animationSpec = spring(Springs.sheet.damping, Springs.sheet.stiffness)) { value, _ ->
                    scroll = value
                }
                animate(panX, targetPan.x, animationSpec = spring(Springs.sheet.damping, Springs.sheet.stiffness)) { value, _ ->
                    panX = value
                }
                animate(panY, targetPan.y, animationSpec = spring(Springs.sheet.damping, Springs.sheet.stiffness)) { value, _ ->
                    panY = value
                }
            }
        }

        val tapTimeout = androidx.compose.ui.platform.LocalViewConfiguration.current.doubleTapTimeoutMillis
        Canvas(
            Modifier.fillMaxSize()
                .semantics { contentDescription = "PDF page ${displayedPage + 1} of $pageCount" }
                .pointerInput(settings.pdfLayout, pageCount, viewportWidth, viewportHeight, baseTops) {
                    var lastTapAt = 0L
                    var lastTapPosition = Offset.Zero
                    var pendingTap: Job? = null
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        navigationJob?.cancel()
                        down.consume()
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        var pinching = false
                        var moved = false
                        var total = Offset.Zero
                        var dragX = pageDrag
                        // T235: horizontal drag the pan clamp refuses at a zoomed page edge.
                        var dragPastEdge = 0f
                        var sawMultiple = false
                        var anyPressed = true
                        while (anyPressed) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            anyPressed = event.changes.any { it.pressed }
                            if (pressed.size >= 2) {
                                pinching = true
                                sawMultiple = true
                                val newZoom = (zoom * event.calculateZoom())
                                    .coerceIn(1f, Metrics.pdfMaxZoom)
                                zoom = newZoom
                                val panChange = event.calculatePan()
                                if (panChange != Offset.Zero) {
                                    val next = clampPan(Offset(panX + panChange.x, panY + panChange.y), newZoom)
                                    panX = next.x
                                    panY = next.y
                                }
                                event.changes.forEach { it.consume() }
                            } else if (pressed.size == 1) {
                                val change = pressed.first()
                                tracker.addPosition(change.uptimeMillis, change.position)
                                val delta = change.position - change.previousPosition
                                if (!sawMultiple && !pinching) {
                                    total += delta
                                    if (!moved && total.getDistance() > viewConfiguration.touchSlop) moved = true
                                    if (moved) {
                                        if (settings.pdfLayout == PdfLayout.Page) {
                                            if (zoom > Metrics.pdfZoomLabelThreshold) {
                                                val before = panX
                                                val limit = panLimit(zoom)
                                                val next = clampPan(Offset(panX + delta.x, panY + delta.y), zoom)
                                                panX = next.x
                                                panY = next.y
                                                val refused = delta.x - (panX - before)
                                                if (abs(refused) > 0.01f) {
                                                    dragPastEdge += refused
                                                } else if (abs(panX) < limit.x - Metrics.pdfPanEdgeSlopPx) {
                                                    dragPastEdge = 0f
                                                }
                                            } else {
                                                dragX += delta.x
                                                pageDrag = ReaderMotion.pageDragOffset(
                                                    dragX, viewportWidth.toFloat(),
                                                    currentPage <= 0,
                                                    currentPage >= pageCount - 1,
                                                )
                                            }
                                        } else {
                                            if (zoom > Metrics.pdfZoomLabelThreshold) {
                                                val next = clampPan(Offset(panX + delta.x, panY), zoom)
                                                panX = next.x
                                                panY = next.y
                                            }
                                            scroll = (scroll - delta.y / zoom).coerceIn(0f, maxScrollAt(zoom))
                                            reportCurrentPosition()
                                        }
                                        change.consume()
                                    }
                                }
                            }
                        }

                        if (pinching) {
                            animateZoom(zoom.coerceIn(1f, Metrics.pdfMaxZoom))
                        } else if (moved) {
                            if (settings.pdfLayout == PdfLayout.Page) {
                                if (zoom <= Metrics.pdfZoomLabelThreshold) {
                                    val target = ReaderMotion.pageTarget(
                                        currentPage, pageCount, dragX, tracker.calculateVelocity().x,
                                        viewportWidth.toFloat(),
                                    )
                                    settlePageTurn(target)
                                } else {
                                    val limit = panLimit(zoom)
                                    val target = ReaderPdfMath.zoomedEdgeTurn(
                                        currentPage, pageCount, dragPastEdge, panX, limit,
                                        tracker.calculateVelocity().x, viewportWidth,
                                    )
                                    if (target != null) settlePageTurn(target)
                                    else animatePan(clampPan(Offset(panX, panY), zoom))
                                }
                            } else {
                                val target = scroll.coerceIn(0f, maxScrollAt(zoom))
                                scope.launch {
                                    animate(scroll, target, animationSpec = spring(Springs.sheet.damping, Springs.sheet.stiffness)) { value, _ ->
                                        scroll = value
                                    }
                                    val targetPan = clampPan(Offset(panX, panY), zoom)
                                    animate(panX, targetPan.x, animationSpec = spring(Springs.sheet.damping, Springs.sheet.stiffness)) { value, _ ->
                                        panX = value
                                    }
                                    animate(panY, targetPan.y, animationSpec = spring(Springs.sheet.damping, Springs.sheet.stiffness)) { value, _ ->
                                        panY = value
                                    }
                                    reportCurrentPosition()
                                }
                            }
                        } else {
                            val now = SystemClock.uptimeMillis()
                            val edge = viewportWidth * Metrics.readerEdgeFraction
                            val inCentre = down.position.x >= edge && down.position.x <= viewportWidth - edge
                            val isDouble = inCentre && now - lastTapAt <= tapTimeout &&
                                (down.position - lastTapPosition).getDistance() <= viewConfiguration.touchSlop * 2f
                            if (isDouble) {
                                pendingTap?.cancel()
                                lastTapAt = 0L
                                val target = if (zoom > Metrics.pdfZoomLabelThreshold) 1f
                                else Metrics.pdfDoubleTapZoom
                                animateZoom(target)
                            } else {
                                lastTapAt = now
                                lastTapPosition = down.position
                                pendingTap?.cancel()
                                if (inCentre) {
                                    // Centre taps wait for a possible second tap; edge taps turn at once.
                                    pendingTap = scope.launch {
                                        delay(tapTimeout.toLong())
                                        chrome = chrome.toggled()
                                    }
                                } else {
                                    if (settings.pdfLayout == PdfLayout.Page) {
                                        settlePageTurn(ReaderMotion.turnTarget(
                                            currentPage, pageCount, if (down.position.x < edge) -1 else 1,
                                        ))
                                    } else {
                                        goToPage(currentDisplayedPage() + if (down.position.x < edge) -1 else 1)
                                    }
                                }
                            }
                        }
                    }
                },
        ) {
            drawRect(Color(colors.paper.toInt()))
            if (settings.pdfLayout == PdfLayout.Page) {
                val drag = pageDrag
                val image = cache.images[displayedPage]
                if (image != null) {
                    val raw = pdf.pageSizes.getOrNull(displayedPage)
                    if (raw != null) {
                        val aspect = raw.width.toFloat() / raw.height.toFloat()
                        val fitWidth = minOf(size.width, size.height * aspect)
                        val fitHeight = fitWidth / aspect
                        val drawWidth = (fitWidth * zoom).coerceAtLeast(1f)
                        val drawHeight = (fitHeight * zoom).coerceAtLeast(1f)
                        val left = (size.width - drawWidth) / 2f + panX + drag
                        val top = (size.height - drawHeight) / 2f + panY
                        painter.draw(this, image, left, top, drawWidth, drawHeight)
                        if (restored && !firstPageDrawn) {
                            firstPageDrawn = true
                            onReady()
                        }
                        if (drag != 0f) {
                            val neighbour = if (drag < 0f) displayedPage + 1 else displayedPage - 1
                            if (neighbour in 0 until pageCount) {
                                val neighbourImage = cache.images[neighbour]
                                val neighbourRaw = pdf.pageSizes.getOrNull(neighbour)
                                if (neighbourImage != null && neighbourRaw != null) {
                                    val neighbourAspect = neighbourRaw.width.toFloat() / neighbourRaw.height.toFloat()
                                    val neighbourFitWidth = minOf(size.width, size.height * neighbourAspect)
                                    val neighbourLeft = (size.width - neighbourFitWidth * zoom) / 2f + panX + drag +
                                        if (drag < 0f) size.width else -size.width
                                    val neighbourTop = (size.height - (neighbourFitWidth / neighbourAspect) * zoom) / 2f + panY
                                    painter.draw(this, neighbourImage, neighbourLeft, neighbourTop,
                                        neighbourFitWidth * zoom, (neighbourFitWidth / neighbourAspect) * zoom)
                                }
                            }
                        }
                    }
                }
            } else {
                val pages = visiblePages
                for (page in pages) {
                    val image = cache.images[page] ?: continue
                    val height = baseHeights.getOrNull(page) ?: continue
                    val top = baseTops.getOrNull(page) ?: continue
                    val drawWidth = (size.width * zoom).coerceAtLeast(1f)
                    val drawHeight = (height * zoom).coerceAtLeast(1f)
                    val y = (top - scroll) * zoom + panY
                    painter.draw(this, image, panX, y, drawWidth, drawHeight)
                    if (restored && !firstPageDrawn && page == currentPage) {
                        firstPageDrawn = true
                        onReady()
                    }
                }
            }
        }

        if (settings.pdfLayout == PdfLayout.Page && cache.images[displayedPage] == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BasicText(
                    if (cache.failed.containsKey(displayedPage)) ReaderPdfMessages.PageFailed
                    else "Page ${displayedPage + 1}",
                    style = readerLabelStyle(skin),
                )
            }
        }

        if (sheet == ReaderSheet.None) ReaderControlsToggle(barsVisible) { chrome = chrome.toggled() }
        if (barsVisible) {
            val singlePage = ReaderJump.isSinglePage(ReaderJumpKind.Page, pageCount)
            val fraction = ReaderJump.fractionForPage(displayedPage, pageCount)
            val currentPlace = BookPosition(page = displayedPage, pageOffset = settledOffset)
            ReaderControls(
                showPanel = chrome == ReaderChrome.Panel,
                title = book.title,
                skin = skin,
                bookmarked = marks.bookmarks.any { it.page == displayedPage },
                onBookmark = { onToggleBookmark(BookPosition(page = displayedPage, pageOffset = settledOffset)) },
                menu = { dismiss ->
                    if (canAddToLibrary) ReaderMenuItem(if (adding) "Adding…" else "Add to library", skin, dismiss, onAddToLibrary, !adding)
                    if (zoom > Metrics.pdfZoomLabelThreshold) ReaderMenuItem("Reset zoom (${(zoom * 100).roundToInt()}%)", skin, dismiss, action = { animateZoom(1f) })
                    ReaderMenuItem("Contents", skin, dismiss, action = { sheet = ReaderSheet.Position })
                    if (textSearchAvailable) ReaderMenuItem("Find", skin, dismiss, action = { sheet = ReaderSheet.Search })
                    ReaderMenuItem("Marks", skin, dismiss, action = { sheet = ReaderSheet.Marks })
                    ReaderMenuItem("Aa · Reading settings", skin, dismiss, action = { sheet = ReaderSheet.Aa })
                },
            ) {
                ReaderJumpPanel(
                    kind = ReaderJumpKind.Page,
                    fraction = fraction,
                    label = addNotice ?: ReaderJump.panelLabel(ReaderJumpKind.Page, null, currentPlace, pageCount),
                    entryText = ReaderJump.entryText(ReaderJumpKind.Page, fraction, pageCount),
                    singlePage = singlePage,
                    doc = null,
                    pageCount = pageCount,
                    skin = skin,
                    onSeek = { target -> movePage(ReaderJump.pageForFraction(target, pageCount)) },
                    onStep = { steps -> movePage((displayedPage + steps).coerceIn(0, pageCount - 1)) },
                    onEntry = { returnSheet = null; sheet = ReaderSheet.Position },
                    onContents = if (singlePage) null else ({ sheet = ReaderSheet.Position }),
                    onBookmarks = { sheet = ReaderSheet.Bookmarks },
                    onNotes = { sheet = ReaderSheet.Notes },
                    onFind = if (textSearchAvailable) ({ sheet = ReaderSheet.Search }) else null,
                    previousPlace = if (previousPlace != null) ({ returnToPreviousPlace() }) else null,
                    steps = if (singlePage) null else ReaderJumpSteps(
                        previousLabel = "Previous page",
                        nextLabel = "Next page",
                        canPrevious = displayedPage > 0,
                        canNext = displayedPage < pageCount - 1,
                        onPrevious = { movePage(displayedPage - 1) },
                        onNext = { movePage(displayedPage + 1) },
                    ),
                )
            }
        }

        if (!barsVisible && sheet == ReaderSheet.None && firstPageDrawn && settings.showPageNumber) {
            ReaderPageBadge(ReaderJump.pageBadgeLabel(displayedPage, pageCount), skin) {
                returnSheet = null
                sheet = ReaderSheet.Position
            }
        }

        if (!barsVisible && marks.bookmarks.any { it.page == displayedPage }) {
            ReaderBookmarkRibbon(skin, Modifier.align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = Metrics.readerMarkRibbonInsetDp.dp, end = Metrics.readerMarkRibbonInsetDp.dp))
        }

        AnimatedVisibility(
            visible = sheet != ReaderSheet.None,
            modifier = Modifier.matchParentSize(),
            enter = fadeIn(spring(Springs.sheet.damping, Springs.sheet.stiffness)),
            exit = fadeOut(spring(Springs.sheet.damping, Springs.sheet.stiffness)),
        ) {
            when (displayedSheet) {
                ReaderSheet.Marks, ReaderSheet.Bookmarks, ReaderSheet.Notes -> {
                    val section = when (displayedSheet) {
                        ReaderSheet.Bookmarks -> MarkSection.Bookmarks
                        ReaderSheet.Notes -> MarkSection.Notes
                        else -> MarkSection.All
                    }
                    ReaderMarksSheet(
                        book = book,
                        doc = null,
                        marks = marks,
                        isPdf = true,
                        pageCount = pageCount,
                        skin = skin,
                        section = section,
                        listState = marksState,
                        onDismiss = { sheet = ReaderSheet.None },
                        onJump = { target ->
                            jumpPage(target.page, target.pageOffset, displayedSheet)
                        },
                        onDeleteBookmark = onDeleteBookmark,
                        onDeleteHighlight = onDeleteHighlight,
                        onAddToLightly = onAddToLightly,
                    )
                }
                ReaderSheet.Position -> ReaderPositionEntrySheet(
                    kind = ReaderJumpKind.Page,
                    fraction = ReaderJump.fractionForPage(displayedPage, pageCount),
                    pageCount = pageCount,
                    skin = skin,
                    onDismiss = { sheet = ReaderSheet.None },
                    onGo = { target ->
                        returnSheet = null
                        sheet = ReaderSheet.None
                        movePage(ReaderJump.pageForFraction(target, pageCount))
                    },
                )
                ReaderSheet.Aa -> ReaderAaSheet(
                    settings = settings,
                    skin = skin,
                    isPdf = true,
                    onDismiss = { sheet = ReaderSheet.None },
                    onChange = onSettingsChange,
                )
                ReaderSheet.Search -> ReaderSearchSheet(
                    skin = skin,
                    doc = null,
                    session = searchSession,
                    listState = searchState,
                    unavailable = if (textSearchAvailable) null else ReaderSearchMessages.PdfNeedsNewerPhone,
                    emptyHint = ReaderSearchMessages.PdfNoText,
                    search = { query -> searchPdf(pdf, query) },
                    onDismiss = { sheet = ReaderSheet.None },
                    onJump = { hit -> jumpPage(hit.page, from = ReaderSheet.Search) },
                )
                ReaderSheet.Contents -> Unit
                // T257: read aloud is offered by the text reader, where there is a text offset to follow.
                ReaderSheet.Listen, ReaderSheet.None -> Unit
            }
        }
        if (barsVisible || sheet != ReaderSheet.None) ReaderBackButton(skin)
    }
}

/** Walks the pages one at a time so a long PDF never blocks rendering for more than one page. */
internal suspend fun searchPdf(pdf: PdfBook, query: String): List<com.brightblu.parc.core.data.library.ReaderSearchHit> =
    withContext(Dispatchers.Default) {
        val hits = ArrayList<com.brightblu.parc.core.data.library.ReaderSearchHit>()
        for (page in 0 until pdf.pageCount) {
            kotlin.coroutines.coroutineContext.ensureActive()
            val text = pdf.pageText(page) ?: continue
            hits += com.brightblu.parc.core.data.library.ReaderSearch.inText(text, query, 0, page,
                com.brightblu.parc.core.data.library.ReaderSearch.MaxResults - hits.size)
            if (hits.size >= com.brightblu.parc.core.data.library.ReaderSearch.MaxResults) break
        }
        hits
    }
