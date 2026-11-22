package com.brightblu.parc.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.brightblu.parc.core.data.library.Book
import com.brightblu.parc.core.data.library.BookFiles
import com.brightblu.parc.core.data.library.BookPosition
import com.brightblu.parc.core.data.library.ComicPages
import com.brightblu.parc.core.data.library.ComicZoom
import com.brightblu.parc.core.data.library.ReaderBookException
import com.brightblu.parc.core.data.library.ReaderBookMarks
import com.brightblu.parc.core.data.library.ReaderPdfMath
import com.brightblu.parc.core.data.library.ReaderSettings
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.Springs
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** T253: plain messages for a comic that cannot be shown. */
internal object ReaderComicMessages {
    const val Missing = "This comic could not be opened. The file may have moved."
    const val Damaged = "This comic is damaged or not a valid CBZ."
    const val Empty = "This comic has no pages Parc can show."
    const val TooLarge = "This comic is too large to open."
    const val PageFailed = "This page could not be shown."
}

/**
 * One open CBZ. A zip is read in place; a book that lives behind a content URI is copied once into
 * the cache and the copy is deleted when the reader leaves. Pages are decoded one at a time, shrunk
 * to the screen, so a large comic never sits in memory.
 */
internal class ComicBook(
    private val zip: ZipFile,
    private val pageNames: List<String>,
    private val temporary: File?,
) {
    val pageCount: Int get() = pageNames.size
    private val lock = Mutex()

    /** [maxWidth] and [maxHeight] are the sharpest size worth keeping; a zoomed page uses 2x the screen. */
    suspend fun page(index: Int, maxWidth: Int, maxHeight: Int): Bitmap? = withContext(Dispatchers.IO) {
        val name = pageNames.getOrNull(index) ?: return@withContext null
        lock.withLock {
            runCatching {
                val entry = zip.getEntry(name) ?: return@runCatching null
                if (entry.size > MaxPageBytes) return@runCatching null
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= maxWidth && bounds.outHeight / (sample * 2) >= maxHeight) {
                    sample *= 2
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()
        }
    }

    fun close() {
        runCatching { zip.close() }
        temporary?.delete()
    }

    companion object {
        const val MaxPageBytes = 40L * 1024 * 1024
    }
}

internal object ReaderComicLoader {
    private const val MaxSourceBytes = 700L * 1024 * 1024

    suspend fun load(context: Context, book: Book): ComicBook = withContext(Dispatchers.IO) {
        var temp: File? = null
        try {
            val direct = book.uri.takeIf { it.startsWith("/") }?.let(::File)?.takeIf { it.isFile }
            val source = direct ?: run {
                val dir = File(context.cacheDir, "comic-work").apply { mkdirs() }
                val copy = File.createTempFile("comic-", ".cbz", dir)
                temp = copy
                BookFiles(context).open(book.uri).use { input ->
                    copy.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            total += read
                            if (total > MaxSourceBytes) throw ReaderBookException(ReaderComicMessages.TooLarge)
                            output.write(buffer, 0, read)
                        }
                    }
                }
                copy
            }
            val zip = try { ZipFile(source) } catch (e: Exception) {
                throw ReaderBookException(ReaderComicMessages.Damaged, e)
            }
            val names = ComicPages.pages(zip.entries().asSequence().map { it.name }.toList())
            if (names.isEmpty()) {
                zip.close()
                throw ReaderBookException(ReaderComicMessages.Empty)
            }
            ComicBook(zip, names, temp)
        } catch (e: ReaderBookException) {
            temp?.delete()
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            temp?.delete()
            throw e
        } catch (e: Exception) {
            temp?.delete()
            throw ReaderBookException(ReaderComicMessages.Missing, e)
        }
    }
}

/**
 * The comic reader body: swipe between pages, pinch to zoom, double-tap to zoom in and back to fit,
 * tap for the reading bar. The place is remembered as the page number. Bookmarks are pages, so the
 * same Marks list as the PDF reader works.
 */
@Composable
internal fun ComicReaderPage(
    book: Book,
    comic: ComicBook,
    settings: ReaderSettings,
    skin: Skin,
    initialPosition: BookPosition,
    onPosition: (BookPosition) -> Unit,
    onLastPage: () -> Unit,
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
    val pageCount = comic.pageCount.coerceAtLeast(1)
    val pager = rememberPagerState(initialPage = initialPosition.page.coerceIn(0, pageCount - 1)) { pageCount }
    var chrome by remember { mutableStateOf(ReaderChrome.Hidden) }
    val barsVisible = chrome != ReaderChrome.Hidden
    var sheet by remember { mutableStateOf(ReaderSheet.None) }
    var lastSheet by remember { mutableStateOf(ReaderSheet.None) }
    LaunchedEffect(sheet) { if (sheet != ReaderSheet.None) lastSheet = sheet }
    val displayedSheet = if (sheet != ReaderSheet.None) sheet else lastSheet
    var returnSheet by remember { mutableStateOf<ReaderSheet?>(null) }
    var previousPlace by remember(book.id) { mutableStateOf<BookPosition?>(null) }
    val marksState = rememberLazyListState()
    var zoomNow by remember { mutableFloatStateOf(1f) }
    val scope = rememberCoroutineScope()
    val latestPosition = rememberUpdatedState(onPosition)
    val latestLast = rememberUpdatedState(onLastPage)
    val latestReady = rememberUpdatedState(onReady)

    val keyboard = LocalSoftwareKeyboardController.current
    val keyboardUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val backAction = ReaderJump.backAction(keyboard = keyboardUp, sheet = sheet != ReaderSheet.None,
        returnList = returnSheet != null, panel = chrome == ReaderChrome.Panel)
    BackHandler(enabled = backAction != ReaderBack.ExitReader) {
        when (backAction) {
            ReaderBack.CloseSheet -> sheet = ReaderSheet.None
            ReaderBack.ReopenList -> { sheet = returnSheet ?: ReaderSheet.None; returnSheet = null }
            ReaderBack.HidePanel -> chrome = ReaderChrome.Toolbar
            ReaderBack.HideKeyboard -> keyboard?.hide()
            ReaderBack.ExitReader -> Unit
        }
    }
    LaunchedEffect(barsVisible, sheet, skin.colors.surface) {
        (context as? ReaderActivity)?.setReaderChrome(visible = barsVisible || sheet != ReaderSheet.None, light = readerChromeIsLight(skin))
    }
    DisposableEffect(settings.volumeKeys, pager) {
        val activity = context as? ReaderActivity
        if (settings.volumeKeys) {
            activity?.volumeKeyHandler = { forward ->
                scope.launch { pager.animateScrollToPage((pager.currentPage + if (forward) 1 else -1).coerceIn(0, pageCount - 1)) }
                true
            }
        }
        onDispose { if (activity?.volumeKeyHandler != null) activity.volumeKeyHandler = null }
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { page ->
            latestPosition.value(BookPosition(page = page))
            if (page >= pageCount - 1) latestLast.value()
        }
    }
    // A page turn always starts at fit zoom on the new page.
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { zoomNow = 1f } }

    // T344: a deliberate jump remembers the page left behind, just as the PDF reader does.
    fun movePage(target: Int) {
        previousPlace = BookPosition(page = pager.currentPage)
        scope.launch { pager.animateScrollToPage(target.coerceIn(0, pageCount - 1)) }
    }

    fun jumpPage(target: Int, from: ReaderSheet) {
        previousPlace = BookPosition(page = pager.currentPage)
        returnSheet = from
        sheet = ReaderSheet.None
        chrome = ReaderChrome.Hidden
        scope.launch { pager.animateScrollToPage(target.coerceIn(0, pageCount - 1)) }
    }

    fun returnToPreviousPlace() {
        val place = previousPlace ?: return
        previousPlace = null
        returnSheet = null
        sheet = ReaderSheet.None
        scope.launch { pager.scrollToPage(place.page.coerceIn(0, pageCount - 1)) }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(colors.paper.toInt()))) {
        val widthPx = constraints.maxWidth.coerceAtLeast(1)
        val heightPx = constraints.maxHeight.coerceAtLeast(1)
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            userScrollEnabled = zoomNow <= ComicZoom.Fit + 0.01f,
            key = { it },
        ) { index ->
            ComicPageView(
                comic = comic,
                index = index,
                widthPx = widthPx,
                heightPx = heightPx,
                skin = skin,
                isCurrent = index == pager.currentPage,
                onZoom = { zoomNow = it },
                onTap = { chrome = chrome.toggled() },
                onFirstDraw = { if (index == pager.currentPage) latestReady.value() },
            )
        }

        val page = pager.currentPage
        if (sheet == ReaderSheet.None) ReaderControlsToggle(barsVisible) { chrome = chrome.toggled() }
        if (barsVisible) {
            val singlePage = ReaderJump.isSinglePage(ReaderJumpKind.Page, pageCount)
            val fraction = ReaderJump.fractionForPage(page, pageCount)
            val currentPlace = BookPosition(page = page)
            ReaderControls(
                showPanel = chrome == ReaderChrome.Panel,
                title = book.title,
                skin = skin,
                bookmarked = marks.bookmarks.any { it.page == page },
                onBookmark = { onToggleBookmark(BookPosition(page = page)) },
                menu = { dismiss ->
                    if (canAddToLibrary) ReaderMenuItem(if (adding) "Adding…" else "Add to library", skin, dismiss, onAddToLibrary, !adding)
                    ReaderMenuItem("Marks", skin, dismiss, action = { sheet = ReaderSheet.Marks })
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
                    onStep = { steps -> movePage(page + steps) },
                    onEntry = { returnSheet = null; sheet = ReaderSheet.Position },
                    onContents = null,
                    onBookmarks = { sheet = ReaderSheet.Bookmarks },
                    onNotes = { sheet = ReaderSheet.Notes },
                    onFind = null,
                    previousPlace = if (previousPlace != null) ({ returnToPreviousPlace() }) else null,
                    steps = if (singlePage) null else ReaderJumpSteps(
                        previousLabel = "Previous page",
                        nextLabel = "Next page",
                        canPrevious = page > 0,
                        canNext = page < pageCount - 1,
                        onPrevious = { movePage(page - 1) },
                        onNext = { movePage(page + 1) },
                    ),
                )
            }
        }
        if (!barsVisible && sheet == ReaderSheet.None && settings.showPageNumber) {
            ReaderPageBadge(ReaderJump.pageBadgeLabel(page, pageCount), skin) {
                returnSheet = null
                sheet = ReaderSheet.Position
            }
        }
        if (!barsVisible && marks.bookmarks.any { it.page == page }) {
            ReaderBookmarkRibbon(skin, Modifier.align(Alignment.TopEnd).statusBarsPadding()
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
                        book = book, doc = null, marks = marks, isPdf = true, pageCount = pageCount,
                        section = section, listState = marksState, skin = skin,
                        onDismiss = { sheet = ReaderSheet.None },
                        onJump = { target -> jumpPage(target.page, displayedSheet) },
                        onDeleteBookmark = onDeleteBookmark,
                        onDeleteHighlight = onDeleteHighlight,
                        onAddToLightly = onAddToLightly,
                    )
                }
                ReaderSheet.Position -> ReaderPositionEntrySheet(
                    kind = ReaderJumpKind.Page,
                    fraction = ReaderJump.fractionForPage(pager.currentPage, pageCount),
                    pageCount = pageCount,
                    skin = skin,
                    onDismiss = { sheet = ReaderSheet.None },
                    onGo = { target ->
                        returnSheet = null
                        sheet = ReaderSheet.None
                        movePage(ReaderJump.pageForFraction(target, pageCount))
                    },
                )
                else -> Unit
            }
        }
        if (barsVisible || sheet != ReaderSheet.None) ReaderBackButton(skin)
    }
}

@Composable
private fun ComicPageView(
    comic: ComicBook,
    index: Int,
    widthPx: Int,
    heightPx: Int,
    skin: Skin,
    isCurrent: Boolean,
    onZoom: (Float) -> Unit,
    onTap: () -> Unit,
    onFirstDraw: () -> Unit,
) {
    // Twice the screen keeps a 2.5x zoom readable without ever decoding the full scan.
    val bitmap by produceState<Bitmap?>(null, comic, index, widthPx, heightPx) {
        value = comic.page(index, widthPx * 2, heightPx * 2)
    }
    var drawn by remember(bitmap, isCurrent) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val zoom = remember { Animatable(1f) }
    val panX = remember { Animatable(0f) }
    val panY = remember { Animatable(0f) }
    val latestZoom = rememberUpdatedState(onZoom)
    val settle = spring<Float>(Springs.sheet.damping, Springs.sheet.stiffness)
    LaunchedEffect(isCurrent) {
        if (!isCurrent && zoom.value != 1f) { zoom.snapTo(1f); panX.snapTo(0f); panY.snapTo(0f) }
    }
    Box(Modifier.fillMaxSize()
        .pointerInput(widthPx, heightPx) {
            detectTapGestures(
                onTap = { onTap() },
                onDoubleTap = { point ->
                    val target = ComicZoom.doubleTap(zoom.value)
                    scope.launch { zoom.animateTo(target, settle) }
                    // Zoom around the tapped point so the thing tapped stays under the finger.
                    val nx = if (target == ComicZoom.Fit) 0f
                        else ComicZoom.clampPan((size.width / 2f - point.x) * (target - 1f), size.width.toFloat(), target)
                    val ny = if (target == ComicZoom.Fit) 0f
                        else ComicZoom.clampPan((size.height / 2f - point.y) * (target - 1f), size.height.toFloat(), target)
                    scope.launch { panX.animateTo(nx, settle) }
                    scope.launch { panY.animateTo(ny, settle) }
                    latestZoom.value(target)
                },
            )
        }
        .pointerInput(widthPx, heightPx) {
            // Two fingers always zoom; one finger only pans once zoomed in. A single finger at fit
            // zoom is left alone, so the pager gets the swipe.
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val multi = event.changes.count { it.pressed } > 1
                    if (multi || zoom.value > ComicZoom.Fit + 0.01f) {
                        val newZoom = ComicZoom.clamp(zoom.value * event.calculateZoom())
                        val pan = event.calculatePan()
                        val nx = ComicZoom.clampPan(panX.value + pan.x, size.width.toFloat(), newZoom)
                        val ny = ComicZoom.clampPan(panY.value + pan.y, size.height.toFloat(), newZoom)
                        scope.launch { zoom.snapTo(newZoom); panX.snapTo(nx); panY.snapTo(ny) }
                        latestZoom.value(newZoom)
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                } while (event.changes.any { it.pressed })
                if (zoom.value < ComicZoom.Fit + 0.05f) {
                    scope.launch { zoom.animateTo(1f, settle); panX.animateTo(0f, settle); panY.animateTo(0f, settle) }
                    latestZoom.value(1f)
                }
            }
        },
        contentAlignment = Alignment.Center) {
        val shown = bitmap
        if (shown != null) {
            Image(
                bitmap = shown.asImageBitmap(),
                contentDescription = "Page ${index + 1}",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().drawWithContent {
                    drawContent()
                    if (isCurrent && !drawn) {
                        drawn = true
                        onFirstDraw()
                    }
                }.graphicsLayer {
                    scaleX = zoom.value; scaleY = zoom.value
                    translationX = panX.value; translationY = panY.value
                },
            )
        } else {
            BasicText("Page ${index + 1}", style = readerLabelStyle(skin))
        }
    }
}

/** Kept tiny: comic pages share the PDF position maths, so progress and "same place" agree. */
internal object ComicPositionMath {
    fun clamp(position: BookPosition, pageCount: Int): BookPosition = ReaderPdfMath.clamp(position, pageCount)
    fun progress(position: BookPosition, pageCount: Int): Double = ReaderPdfMath.progress(position, pageCount)
}
