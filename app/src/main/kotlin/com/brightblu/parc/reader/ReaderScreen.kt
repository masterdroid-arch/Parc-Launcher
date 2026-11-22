package com.brightblu.parc.reader

import android.content.Intent
import android.Manifest
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.brightblu.parc.DemoMode
import com.brightblu.parc.Quote
import com.brightblu.parc.QuoteDraft
import com.brightblu.parc.QuoteEditorSheet
import com.brightblu.parc.QuotesStore
import com.brightblu.parc.bookCoverStyle
import com.brightblu.parc.core.data.library.Book
import com.brightblu.parc.core.data.library.BookFormat
import com.brightblu.parc.core.data.library.BookMetadataLoader
import com.brightblu.parc.core.data.library.BookPosition
import com.brightblu.parc.core.data.library.FoundBook
import com.brightblu.parc.core.data.library.LibraryStore
import com.brightblu.parc.core.data.library.ReaderBookIdentity
import com.brightblu.parc.core.data.library.ReaderBookMarks
import com.brightblu.parc.core.data.library.ReaderBookmark
import com.brightblu.parc.core.data.library.ReaderHighlight
import com.brightblu.parc.core.data.library.ReaderHighlightTone
import com.brightblu.parc.core.data.library.ReaderMarksJson
import com.brightblu.parc.core.data.library.ReaderMarksLimits
import com.brightblu.parc.core.data.library.ReaderMarksMath
import com.brightblu.parc.core.data.library.ReaderSearch
import com.brightblu.parc.core.data.library.ReaderPositionSession
import com.brightblu.parc.core.data.library.ReaderBookDoc
import com.brightblu.parc.core.data.library.ReaderBookException
import com.brightblu.parc.core.data.library.ReaderBookProblem
import com.brightblu.parc.core.data.library.PdfLayout
import com.brightblu.parc.core.data.library.ReaderFamily
import com.brightblu.parc.core.data.library.ReaderImageTone
import com.brightblu.parc.core.data.library.ReaderPageColor
import com.brightblu.parc.core.data.library.ReaderPdfMath
import com.brightblu.parc.core.data.library.ReaderPositionMath
import com.brightblu.parc.core.data.library.ReaderSettings
import com.brightblu.parc.core.data.library.ReaderSettingsStore
import com.brightblu.parc.core.data.library.ReaderTtsRules
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.Springs
import com.brightblu.parc.model.textFamily
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

internal data class ReaderColors(
    val paper: Long,
    val ink: Long,
    val secondary: Long,
    val accent: Long,
    val light: Boolean,
)

internal fun readerColors(page: ReaderPageColor, skin: Skin): ReaderColors {
    val base = when (page) {
        ReaderPageColor.Light -> ReaderColors(Metrics.readerPageLightPaper, Metrics.readerPageLightInk,
            Metrics.readerPageLightSecondary, skin.colors.accent, light = true)
        ReaderPageColor.Sepia -> ReaderColors(Metrics.readerPageSepiaPaper, Metrics.readerPageSepiaInk,
            Metrics.readerPageSepiaSecondary, skin.colors.accent, light = true)
        ReaderPageColor.Dark -> ReaderColors(Metrics.readerPageDarkPaper, Metrics.readerPageDarkInk,
            Metrics.readerPageDarkSecondary, skin.colors.accent, light = false)
        ReaderPageColor.Black -> ReaderColors(Metrics.readerPageBlackPaper, Metrics.readerPageBlackInk,
            Metrics.readerPageBlackSecondary, skin.colors.accent, light = false)
        ReaderPageColor.BlackOnWhite -> ReaderColors(Metrics.readerPageBlackOnWhitePaper,
            Metrics.readerPageBlackOnWhiteInk, Metrics.readerPageBlackOnWhiteSecondary,
            skin.colors.accent, light = true)
        ReaderPageColor.WhiteOnBlack -> ReaderColors(Metrics.readerPageWhiteOnBlackPaper,
            Metrics.readerPageWhiteOnBlackInk, Metrics.readerPageWhiteOnBlackSecondary,
            skin.colors.accent, light = false)
    }
    return if (page == ReaderPageColor.Dark) base.copy(paper = skin.colors.bg) else base
}

/**
 * T255: Night is a reader-only dark page, separate from the skin and from the Dark page colour: a very
 * dark paper with warm, dimmed ink that is easy on the eyes in bed. It wins over the page colour.
 */
internal fun readerColors(settings: ReaderSettings, skin: Skin): ReaderColors =
    if (settings.night) ReaderColors(Metrics.readerNightPaper, Metrics.readerNightInk,
        Metrics.readerNightSecondary, skin.colors.accent, light = false)
    else readerColors(settings.pageColor, skin)

internal enum class ReaderSheet { None, Contents, Marks, Bookmarks, Notes, Position, Aa, Search, Listen }

/** A failed open, with the kind that decides which recovery action can help. */
internal data class ReaderFailure(val message: String, val problem: ReaderBookProblem)

internal data class ReaderErrorActions(val retry: Boolean, val locate: Boolean)

/** T347: retrying only helps a file that may have regained access. A complete copy replaces a broken archive. */
internal fun readerErrorActions(problem: ReaderBookProblem, busy: Boolean = false): ReaderErrorActions = when {
    busy -> ReaderErrorActions(retry = false, locate = false)
    problem == ReaderBookProblem.LostAccess -> ReaderErrorActions(retry = true, locate = true)
    problem == ReaderBookProblem.BrokenArchive -> ReaderErrorActions(retry = false, locate = true)
    else -> ReaderErrorActions(retry = false, locate = false)
}

/** Loads the book and its Aa settings; the WebView itself only exists inside [ReaderPage]. */
@Composable
internal fun ReaderHost(
    request: ReaderRequest,
    skin: Skin,
    onClose: () -> Unit,
    onPosition: (BookPosition) -> Unit,
) {
    val context = LocalContext.current
    val settingsStore = remember(context) { ReaderSettingsStore(context) }
    val library = remember(context) { LibraryStore(context) }
    val openPositions = remember(context) { ReaderOpenPositionsStore(context) }
    var resolved by remember(request) { mutableStateOf<ReaderRequest?>(null) }
    var initialSettings by remember(request) { mutableStateOf<ReaderSettings?>(null) }
    var initialMarks by remember(request) { mutableStateOf<ReaderBookMarks?>(null) }
    LaunchedEffect(request) {
        val stored = ReaderBookIdentity.find(request.book, library.books.first())
        val position = stored?.position ?: request.openHash?.let(openPositions::position)
            ?: request.initialPosition
        val resolvedBook = stored ?: request.book
        initialSettings = settingsStore.settings.first()
        val storedMarks = library.marks(resolvedBook.id).first()
        initialMarks = if (DemoMode.enabled && ReaderDemo.isDemoBook(resolvedBook)) {
            ReaderDemo.withHighlight(storedMarks)
        } else storedMarks
        resolved = request.copy(book = resolvedBook, initialPosition = position,
            unadded = request.unadded && stored == null)
    }
    val readyRequest = resolved
    val readySettings = initialSettings
    val readyMarks = initialMarks
    // T352: the motion shell wraps the whole open, including the first frames before the library
    // row and settings resolve. The cover therefore grows from its tile exactly once and stays up
    // until the positioned page is drawn, instead of a plain title card appearing first.
    val closeFlush = remember { ReaderCloseFlush() }
    val shellColors = remember(readySettings, skin) {
        readerColors(readySettings ?: ReaderSettings(), skin)
    }
    var contentReady by remember(readyRequest?.book?.uri ?: request.book.uri) {
        mutableStateOf(false)
    }
    ReaderMotionShell(
        request = readyRequest ?: request,
        skin = skin,
        onClose = { closeFlush.run(); onClose() },
        colors = shellColors,
        contentReady = contentReady,
    ) {
        if (readyRequest == null || readySettings == null || readyMarks == null) {
            // T352: a book opened from a tile shows only its growing cover while the row resolves;
            // a quiet title card would flash around the cover. Open-with has no tile, so it rises
            // with the quiet title as before.
            if (request.coverRect != null && !request.unadded) {
                Box(Modifier.fillMaxSize())
            } else {
                ReaderLoading(request.book.title, skin)
            }
        } else {
            androidx.compose.runtime.key(readyRequest.book.uri) {
                ReaderSessionHost(readyRequest, skin, onClose, onPosition, library, openPositions,
                    settingsStore, readySettings, readyMarks,
                    onContentReady = { contentReady = true },
                    closeFlush = closeFlush,
                    onRelocated = { book -> resolved = readyRequest.copy(book = book) })
            }
        }
    }
}

/** T352: the shell lives above the session, so closing it must still flush the session's place. */
private class ReaderCloseFlush {
    var action: () -> Unit = {}
    fun run() {
        runCatching { action() }
    }
}

@Composable
private fun ReaderSessionHost(
    request: ReaderRequest, skin: Skin, onClose: () -> Unit, onPosition: (BookPosition) -> Unit,
    library: LibraryStore, openPositions: ReaderOpenPositionsStore,
    settingsStore: ReaderSettingsStore, initialSettings: ReaderSettings,
    initialMarks: ReaderBookMarks, onContentReady: () -> Unit, closeFlush: ReaderCloseFlush,
    onRelocated: (Book) -> Unit,
) {
    val context = LocalContext.current
    val settings by settingsStore.settings.collectAsState(initial = initialSettings)
    val quotesStore = remember(context) { QuotesStore.create(context) }
    val scope = rememberCoroutineScope()
    // T255: brightness is the reader window's own; it ends with this activity.
    LaunchedEffect(settings.brightness) { (context as? ReaderActivity)?.setReaderBrightness(settings.brightness) }
    // T345: keep the screen awake only while the reader is open and the choice is on.
    LaunchedEffect(settings.keepAwake) { (context as? ReaderActivity)?.setKeepAwake(settings.keepAwake) }
    var marks by remember(request.book.id) { mutableStateOf(initialMarks) }
    var lightDraft by remember(request.book.id) { mutableStateOf<QuoteDraft?>(null) }
    val demoBook = DemoMode.enabled && ReaderDemo.isDemoBook(request.book)
    LaunchedEffect(request.book.id, demoBook) {
        library.marks(request.book.id).collect { stored -> marks = if (demoBook) ReaderDemo.withHighlight(stored) else stored }
    }
    var doc by remember(request.book.id) { mutableStateOf(if (request.book.format == BookFormat.EPUB) null else ReaderCache.get(request.book)) }
    var pdf by remember(request.book.id) { mutableStateOf<PdfBook?>(null) }
    var comic by remember(request.book.id) { mutableStateOf<ComicBook?>(null) }
    var error by remember(request.book.id) { mutableStateOf<ReaderFailure?>(null) }
    var lastPosition by remember(request.book.id) { mutableStateOf(request.initialPosition) }
    var savedPosition by remember(request.book.id) { mutableStateOf<BookPosition?>(null) }
    var inLibrary by remember(request.book.id) { mutableStateOf(!request.unadded) }
    var adding by remember(request.book.id) { mutableStateOf(false) }
    var addNotice by remember(request.book.id) { mutableStateOf<String?>(null) }
    val latestPosition = rememberUpdatedState(lastPosition)
    val latestDoc = rememberUpdatedState(doc)
    val latestPdf = rememberUpdatedState(pdf)
    val latestComic = rememberUpdatedState(comic)

    val positionSession = remember(request) {
        ReaderPositionSession { position, progress ->
            if (request.openHash != null && !inLibrary) {
                openPositions.save(request.openHash, position)
            } else {
                library.savePosition(request.book.id, position, progress, System.currentTimeMillis())
            }
        }
    }

    fun updatePosition(position: BookPosition) {
        val pdfBook = pdf
        val comicBook = comic
        val current = doc
        when {
            comicBook != null -> {
                val safe = ComicPositionMath.clamp(position, comicBook.pageCount)
                positionSession.update(safe, ComicPositionMath.progress(safe, comicBook.pageCount))
            }
            pdfBook != null -> {
                val safe = ReaderPdfMath.clamp(position, pdfBook.pageCount)
                positionSession.update(safe, ReaderPdfMath.progress(safe, pdfBook.pageCount))
            }
            current != null -> {
                val safe = ReaderPositionMath.clamp(current, position)
                positionSession.update(safe, ReaderPositionMath.progress(current, safe))
            }
        }
    }

    var attempt by remember { mutableIntStateOf(0) }
    var locating by remember { mutableStateOf(false) }
    var locateFailure by remember { mutableStateOf<ReaderFailure?>(null) }
    val locateFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            locating = true
            locateFailure = null
            try {
                val replacement = request.book.copy(uri = uri.toString())
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                ReaderLoader.load(context, replacement)
                if (inLibrary) library.relocate(replacement.id, replacement.uri)
                onRelocated(replacement)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ReaderBookException) {
                locateFailure = ReaderFailure(e.message ?: ReaderLoader.ReadFailed, e.problem)
            } catch (_: Exception) {
                locateFailure = ReaderFailure(
                    "Access to the selected file could not be saved. Choose the file again.", ReaderBookProblem.Other)
            } finally {
                locating = false
            }
        }
    }

    LaunchedEffect(request.book.id, attempt) {
        error = null
        if (demoBook) {
            if (doc == null) doc = ReaderDemo.doc(request.book)
        } else if (request.book.format == BookFormat.CBZ) {
            if (comic == null) {
                try {
                    comic = ReaderComicLoader.load(context, request.book)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ReaderBookException) {
                    error = ReaderFailure(e.message ?: ReaderComicMessages.Missing, e.problem)
                } catch (_: Exception) {
                    error = ReaderFailure(ReaderComicMessages.Missing, ReaderBookProblem.Other)
                }
            }
        } else if (request.book.format == BookFormat.PDF) {
            if (pdf == null) {
                try {
                    pdf = ReaderPdfLoader.load(context, request.book)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ReaderBookException) {
                    error = ReaderFailure(e.message ?: "This PDF could not be opened.", e.problem)
                } catch (_: Exception) {
                    error = ReaderFailure(ReaderPdfMessages.Missing, ReaderBookProblem.LostAccess)
                }
            }
        } else if (doc == null) {
            try {
                doc = ReaderLoader.load(context, request.book)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ReaderBookException) {
                error = ReaderFailure(e.message ?: "This book could not be opened.", e.problem)
            } catch (_: Exception) {
                error = ReaderFailure("This book could not be opened. The file may have moved.",
                    ReaderBookProblem.LostAccess)
            }
        }
    }

    DisposableEffect(request.book.id, pdf) {
        // T235: capture the instance this effect owns. Reading the delegated `pdf` from onDispose
        // would close the freshly loaded renderer when the null -> non-null key changes, so every
        // PDF page render returned null and the reader stayed on the "Page 1" placeholder.
        val opened = pdf
        onDispose { opened?.close() }
    }

    DisposableEffect(request.book.id, comic) {
        val opened = comic
        onDispose { opened?.close() }
    }

    LaunchedEffect(addNotice) {
        if (addNotice != null) {
            delay(Metrics.bookFindNoticeMs)
            addNotice = null
        }
    }

    val addToLibrary: () -> Unit = {
        if (request.unadded && !inLibrary && !adding) {
            adding = true
            addNotice = null
            scope.launch {
                try {
                    val now = System.currentTimeMillis()
                    val extension = request.book.format.name.lowercase()
                    val found = FoundBook(
                        uri = request.book.uri,
                        name = "${request.book.title}.$extension",
                        folderLabel = "",
                        sizeBytes = request.book.sizeBytes,
                        modifiedAt = 0,
                        format = request.book.format,
                    )
                    val style = bookCoverStyle(context, skin)
                    val loaded = runCatching {
                        BookMetadataLoader(context).load(found, style, addedAt = now, id = request.book.id)
                    }.getOrNull()
                    library.add(loaded?.book ?: request.book.copy(addedAt = now))
                    val pdfBook = latestPdf.value
                    val docBook = latestDoc.value
                    val comicBook = latestComic.value
                    val position = latestPosition.value
                    val progress = when {
                        comicBook != null -> ComicPositionMath.progress(position, comicBook.pageCount)
                        pdfBook != null -> ReaderPdfMath.progress(position, pdfBook.pageCount)
                        docBook != null -> ReaderPositionMath.progress(docBook, position)
                        else -> 0.0
                    }
                    library.savePosition(request.book.id, position, progress, now)
                    request.openHash?.let { openPositions.remove(it) }
                    inLibrary = true
                    addNotice = "Added to library"
                } catch (_: Exception) {
                    addNotice = "Could not add this book."
                } finally {
                    adding = false
                }
            }
        }
    }

    fun flushPosition() {
        runCatching { positionSession.flush() }.onFailure { addNotice = "Could not save your place." }
    }

    // T352: the shell above the session closes the reader, so it flushes through this hook.
    SideEffect { closeFlush.action = { flushPosition() } }
    // An open failure is a drawn state, so the cover may lift to show the message.
    LaunchedEffect(error) { if (error != null) onContentReady() }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, doc, pdf) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                flushPosition()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            flushPosition()
        }
    }

    // T251: remember the last page once per session, so the Library can ask its one-time question.
    var lastPageSaved by remember(request.book.id) { mutableStateOf(false) }
    val onLastPage: () -> Unit = {
        if (!lastPageSaved) {
            lastPageSaved = true
            scope.launch { runCatching { library.markReachedEnd(request.book.id) } }
        }
    }

    val onTurn: (BookPosition) -> Unit = { position ->
        lastPosition = position
        onPosition(position)
        val isPdf = latestPdf.value != null || latestComic.value != null
        // WebView checkpoints arrive on its bridge thread before these posted UI callbacks.
        // Replaying an older callback must not replace a newer checkpoint during pause.
        if (isPdf) updatePosition(position)
        val unchanged = if (isPdf) ReaderPdfMath.same(savedPosition, position)
        else ReaderPositionMath.same(savedPosition, position)
        if (!unchanged) {
            savedPosition = position
            scope.launch {
                try { positionSession.persist() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { addNotice = "Could not save your place." }
            }
        }
    }

    fun mutateMarks(change: (ReaderBookMarks) -> ReaderBookMarks) {
        scope.launch {
            try { marks = library.updateMarks(request.book.id, change) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { addNotice = "Could not save this mark." }
        }
    }

    val toggleBookmark: (BookPosition) -> Unit = { position ->
        val now = System.currentTimeMillis()
        val asPdf = (request.book.format == BookFormat.PDF || request.book.format == BookFormat.CBZ) && !demoBook
        val candidate = if (asPdf) {
            ReaderMarksMath.bookmarkForPdf("bookmark-${UUID.randomUUID()}", position.page, now)
        } else {
            val text = latestDoc.value?.chapters?.getOrNull(position.chapter)?.text.orEmpty()
            ReaderMarksMath.bookmarkForText("bookmark-${UUID.randomUUID()}", position, text, now)
        }
        mutateMarks { current ->
            current.copy(bookmarks = ReaderMarksMath.toggleBookmark(current.bookmarks, candidate))
        }
    }
    val addHighlight: (ReaderHighlight) -> Unit = { highlight ->
        mutateMarks { current ->
            val next = ReaderMarksMath.addHighlight(current.highlights, highlight)
            // A merge can grow the range past the freshly selected text. Take the merged snippet
            // from the chapter so Add to Commonplace on that row carries the whole marked passage.
            val merged = next.firstOrNull { it.id == highlight.id }
            val chapterText = merged?.let { latestDoc.value?.chapters?.getOrNull(it.chapter)?.text }
            val text = if (merged != null && chapterText != null) {
                val from = merged.start.coerceIn(0, chapterText.length)
                val to = merged.end.coerceIn(from, chapterText.length)
                chapterText.substring(from, to).take(ReaderMarksLimits.MaxSelectedText)
            } else ""
            current.copy(highlights = next.map {
                if (it.id == merged?.id) it.copy(text = text) else it
            })
        }
    }
    val deleteBookmark: (String) -> Unit = { id ->
        mutateMarks { it.copy(bookmarks = ReaderMarksMath.removeBookmark(it.bookmarks, id)) }
    }
    val deleteHighlight: (String) -> Unit = { id ->
        mutateMarks { it.copy(highlights = ReaderMarksMath.removeHighlight(it.highlights, id)) }
    }
    val setHighlightNote: (String, String) -> Unit = { id, note ->
        mutateMarks { it.copy(highlights = ReaderMarksMath.setNote(it.highlights, id, note)) }
    }
    val addToLightly: (String) -> Unit = { selected ->
        val fields = ReaderMarksMath.quoteFields(request.book.title, request.book.author, selected)
        if (fields.text.isNotBlank()) {
            lightDraft = QuoteDraft(Quote("own-${UUID.randomUUID()}", fields.text,
                author = fields.author, work = fields.work))
        }
    }

    val canAdd = request.unadded && !inLibrary
    val loaded = doc
    val pdfBook = pdf
    val comicBook = comic
    when {
        comicBook != null -> ComicReaderPage(
            book = request.book,
            comic = comicBook,
            settings = settings,
            skin = skin,
            initialPosition = request.initialPosition,
            onPosition = onTurn,
            onLastPage = onLastPage,
            onReady = onContentReady,
            canAddToLibrary = canAdd,
            adding = adding,
            addNotice = addNotice,
            onAddToLibrary = addToLibrary,
            marks = marks,
            onToggleBookmark = toggleBookmark,
            onDeleteBookmark = deleteBookmark,
            onDeleteHighlight = deleteHighlight,
            onAddToLightly = addToLightly,
        )
        pdfBook != null -> PdfReaderPage(
            book = request.book,
            pdf = pdfBook,
            settings = settings,
            skin = skin,
            initialPosition = request.initialPosition,
            onPosition = onTurn,
            onLastPage = onLastPage,
            onSettingsChange = { change -> scope.launch { runCatching { settingsStore.update(change) } } },
            onReady = onContentReady,
            canAddToLibrary = canAdd,
            adding = adding,
            addNotice = addNotice,
            onAddToLibrary = addToLibrary,
            marks = marks,
            onToggleBookmark = toggleBookmark,
            onDeleteBookmark = deleteBookmark,
            onDeleteHighlight = deleteHighlight,
            onAddToLightly = addToLightly,
        )
        loaded != null -> ReaderPage(
            doc = loaded,
            onCheckpoint = { updatePosition(it) },
            book = request.book,
            settings = settings,
            skin = skin,
            initialPosition = request.initialPosition,
            onPosition = onTurn,
            onLastPage = onLastPage,
            onSettingsChange = { change -> scope.launch { runCatching { settingsStore.update(change) } } },
            onClose = onClose,
            onReady = onContentReady,
            canAddToLibrary = canAdd,
            adding = adding,
            addNotice = addNotice,
            onAddToLibrary = addToLibrary,
            marks = marks,
            onToggleBookmark = toggleBookmark,
            onAddHighlight = addHighlight,
            onDeleteBookmark = deleteBookmark,
            onDeleteHighlight = deleteHighlight,
            onSetHighlightNote = setHighlightNote,
            onAddToLightly = addToLightly,
            unadded = request.unadded && !inLibrary,
            openHash = request.openHash,
        )
        error != null -> {
            val failure = locateFailure ?: error!!
            val actions = readerErrorActions(failure.problem, busy = locating)
            ReaderError(failure.message, skin, onClose,
                onRetry = if (actions.retry) ({ locateFailure = null; attempt++ }) else null,
                onLocate = if (actions.locate)
                    ({ locateFile.launch(arrayOf("application/epub+zip", "application/octet-stream")) }) else null)
        }
        else -> ReaderLoading(request.book.title, skin)
    }
    lightDraft?.let { draft ->
        QuoteEditorSheet(draft, skin, quotesStore) { lightDraft = null }
    }
}

@Composable
internal fun ReaderLoading(title: String, skin: Skin) {
    // T341: the quiet title is the whole surface while the saved place is laid out. After the
    // old cover-hold budget it says it is still working instead of pretending the page is open.
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(Metrics.readerCoverHoldMaxMs)
        slow = true
    }
    Box(Modifier.fillMaxSize().background(Color(skin.colors.bg.toInt()))
        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
        contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(title, style = readerTitleStyle(skin), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(Metrics.readerRowGapDp.dp))
            BasicText(if (slow) "Still opening…" else "Opening…", style = readerLabelStyle(skin))
        }
    }
}

@Composable
private fun ReaderError(message: String, skin: Skin, onClose: () -> Unit,
    onRetry: (() -> Unit)?, onLocate: (() -> Unit)?) {
    Box(Modifier.fillMaxSize().background(Color(skin.colors.bg.toInt())), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = Metrics.readerSheetMaxWidthDp.dp)
            .padding(horizontal = Metrics.readerSheetInsetDp.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Metrics.readerRowGapDp.dp)) {
            BasicText("Cannot open this book", style = readerTitleStyle(skin))
            BasicText(message, style = readerLabelStyle(skin))
            onLocate?.let { ReaderAction("Locate file", skin, onClick = it) }
            onRetry?.let { ReaderAction("Retry", skin, onClick = it) }
            ReaderAction("Close", skin, onClick = onClose)
        }
    }
}

@Composable
private fun ReaderPage(
    doc: ReaderBookDoc,
    onCheckpoint: (BookPosition) -> Unit,
    book: Book,
    settings: ReaderSettings,
    skin: Skin,
    initialPosition: BookPosition,
    onPosition: (BookPosition) -> Unit,
    onLastPage: () -> Unit,
    onSettingsChange: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onClose: () -> Unit,
    onReady: () -> Unit,
    canAddToLibrary: Boolean,
    adding: Boolean,
    addNotice: String?,
    onAddToLibrary: () -> Unit,
    marks: ReaderBookMarks,
    onToggleBookmark: (BookPosition) -> Unit,
    onAddHighlight: (ReaderHighlight) -> Unit,
    onDeleteBookmark: (String) -> Unit,
    onDeleteHighlight: (String) -> Unit,
    onSetHighlightNote: (String, String) -> Unit,
    onAddToLightly: (String) -> Unit,
    unadded: Boolean,
    openHash: String?,
) {
    val context = LocalContext.current
    val colors = remember(settings.pageColor, settings.night, skin) { readerColors(settings, skin) }
    val clipboard = LocalClipboardManager.current
    var chrome by remember { mutableStateOf(ReaderChrome.Hidden) }
    val barsVisible = chrome != ReaderChrome.Hidden
    var sheet by remember { mutableStateOf(ReaderSheet.None) }
    var lastSheet by remember { mutableStateOf(ReaderSheet.None) }
    // T344: a jump from a list closes it to show the page; Back reopens the same list.
    var returnSheet by remember { mutableStateOf<ReaderSheet?>(null) }
    var previousPlace by remember(doc.id) { mutableStateOf<BookPosition?>(null) }
    val contentsState = rememberLazyListState()
    val marksState = rememberLazyListState()
    val searchState = rememberLazyListState()
    val searchSession = remember(doc.id) { ReaderSearchSession() }
    LaunchedEffect(sheet) { if (sheet != ReaderSheet.None) lastSheet = sheet }
    val displayedSheet = if (sheet != ReaderSheet.None) sheet else lastSheet
    var position by remember(doc.id) { mutableStateOf(ReaderPositionMath.clamp(doc, initialPosition)) }
    var pageCount by remember { mutableIntStateOf(1) }
    var currentPage by remember { mutableIntStateOf(0) }
    // T350: the whole-book column count is filled in short slices after the saved page is ready.
    var countingPages by remember { mutableStateOf(false) }
    var bookmarkPages by remember(doc.id) { mutableStateOf<Map<String, Int>>(emptyMap()) }
    val latestBookmarks = rememberUpdatedState(marks.bookmarks)
    var layoutReady by remember(doc.id) { mutableStateOf(false) }
    var selection by remember(doc.id) { mutableStateOf<ReaderSelection?>(null) }
    var noteTarget by remember(doc.id) { mutableStateOf<ReaderSelection?>(null) }
    var translateText by remember(doc.id) { mutableStateOf<String?>(null) }
    var lastTranslateText by remember(doc.id) { mutableStateOf<String?>(null) }
    var defineNotice by remember(doc.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(translateText) { if (translateText != null) lastTranslateText = translateText }
    val displayedTranslate = translateText ?: lastTranslateText
    var bookmarkSpot by remember(doc.id) { mutableStateOf<BookPosition?>(null) }
    val pageBookmark = marks.bookmarks.firstOrNull {
        it.chapter == bookmarkSpot?.chapter && it.characterOffset == bookmarkSpot?.characterOffset
    }
    // T257: playback lives in the foreground service; this page subscribes for the sheet and highlight.
    val ttsState by ReaderTtsStateHolder.state.collectAsState()
    val ttsMine = ttsState.bookId == doc.id && ttsState.phase != ReaderTtsPhase.Idle
    var notificationsAllowed by remember(context) { mutableStateOf(ReaderTtsNotifications.allowed(context)) }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsAllowed = ReaderTtsNotifications.allowed(context)
        ReaderTtsService.refresh(context)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) notificationsAllowed = ReaderTtsNotifications.allowed(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val keyboard = LocalSoftwareKeyboardController.current
    // T344: Back closes the keyboard, then a list, then the panel, and only then the book.
    val keyboardUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val backAction = ReaderJump.backAction(keyboardUp,
        sheet != ReaderSheet.None || translateText != null, returnSheet != null, chrome == ReaderChrome.Panel)
    BackHandler(enabled = backAction != ReaderBack.ExitReader) {
        when (backAction) {
            ReaderBack.HideKeyboard -> keyboard?.hide()
            ReaderBack.CloseSheet -> if (translateText != null) translateText = null else sheet = ReaderSheet.None
            ReaderBack.ReopenList -> { sheet = returnSheet ?: ReaderSheet.None; returnSheet = null }
            ReaderBack.HidePanel -> chrome = ReaderChrome.Toolbar
            ReaderBack.ExitReader -> Unit
        }
    }
    val webView = remember { ReaderWebView(context) }
    val latestOnPosition = rememberUpdatedState(onPosition)
    val latestPage = rememberUpdatedState(currentPage)
    val latestCount = rememberUpdatedState(pageCount)
    val dragState = remember(doc.id) { ReaderDragState() }
    LaunchedEffect(layoutReady, pageCount, currentPage, settings.continuous, countingPages) {
        if (layoutReady && !settings.continuous && !countingPages && currentPage >= pageCount - 1) onLastPage()
    }

    val generation = remember(webView) { AtomicInteger() }
    val drawnGeneration = remember(webView) { AtomicInteger(-1) }
    val checkpoint = rememberUpdatedState(onCheckpoint)
    val bridge = remember(webView, dragState, doc) {
        ReaderBridge(
            onSelection = { raw -> selection = ReaderSelection.parse(raw); defineNotice = null },
            onBookmarkPage = { chapter, offset, version ->
                if (version == generation.get()) {
                    bookmarkSpot = if (chapter >= 0) BookPosition(chapter, offset) else null
                }
            },
            onReady = { pages, page, version ->
                webView.post {
                    if (version != generation.get()) return@post
                    pageCount = pages.coerceAtLeast(1)
                    webView.postVisualStateCallback(version.toLong(), object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) {
                            if (version != generation.get()) return
                            // The visual-state callback precedes a draw that contains the positioned
                            // DOM. Wait for that draw, then one animation frame, so the compositor
                            // has presented it before the cover or the loading title can lift.
                            webView.afterDraw = Runnable {
                                webView.postOnAnimation {
                                    if (version != generation.get()) return@postOnAnimation
                                    drawnGeneration.set(version)
                                    currentPage = page.coerceAtLeast(0)
                                    layoutReady = true
                                    onReady()
                                    checkpoint.value(position)
                                    latestOnPosition.value(position)
                                }
                            }
                            webView.invalidate()
                        }
                    })
                }
            },
            onMoved = { page, chapter, offset, _, version ->
                if (version == generation.get() && version == drawnGeneration.get()) {
                    val next = ReaderPositionMath.clamp(doc, chapter, offset)
                    checkpoint.value(next)
                }
                webView.post {
                    if (version != generation.get()) return@post
                    currentPage = page.coerceAtLeast(0)
                    if (version == drawnGeneration.get()) {
                        position = ReaderPositionMath.clamp(doc, chapter, offset)
                    }
                }
            },
            onSettled = { page, chapter, offset, _, version ->
                // T343: persistence follows a settled page, not every frame of a drag or spring.
                webView.post {
                    if (version != generation.get() || version != drawnGeneration.get()) return@post
                    val next = ReaderPositionMath.clamp(doc, chapter, offset)
                    currentPage = page.coerceAtLeast(0)
                    position = next
                    checkpoint.value(next)
                    latestOnPosition.value(next)
                }
            },
            onChapter = { index -> doc.chapter(index).html },
            onOffset = { offset -> dragState.offset = offset },
            // T350: the column count arrives in slices so the reader is never blocked.
            onCounted = { page, pages, done, version ->
                webView.post {
                    if (version != generation.get()) return@post
                    pageCount = pages.coerceAtLeast(1)
                    countingPages = !done
                    currentPage = page.coerceAtLeast(0)
                }
            },
            onBookmarkPages = { raw, version ->
                webView.post {
                    if (version == generation.get()) {
                        val bookmarkIds = latestBookmarks.value.map { it.id }
                        val parsed = runCatching {
                            val array = JSONArray(raw)
                            (0 until array.length()).map { index -> array.optInt(index, -1) }
                        }.getOrDefault(emptyList())
                        bookmarkPages = bookmarkIds.mapIndexedNotNull { index, id ->
                            val page = parsed.getOrNull(index) ?: return@mapIndexedNotNull null
                            if (page > 0) id to (page - 1) else null
                        }.toMap()
                    }
                }
            },
        )
    }
    val client = remember(webView) {
        ReaderWebClient { chapter, offset ->
            webView.evaluateJavascript("window.reader.goTo($chapter,$offset)", null)
        }
    }

    fun clearSelection() {
        selection = null
        defineNotice = null
        webView.evaluateJavascript("if(window.reader)window.reader.clearSelection()", null)
    }

    // T344: every deliberate jump remembers where the reader was, so "Previous place" is one tap.
    // The slider and its steps keep the panel open so the new location can be read; a list jump
    // closes the list and the panel to show the page.
    fun placeText(chapter: Int, offset: Int) {
        previousPlace = position
        returnSheet = null
        webView.evaluateJavascript("window.reader.goTo($chapter,$offset)", null)
    }

    fun goToText(chapter: Int, offset: Int, from: ReaderSheet?) {
        previousPlace = position
        returnSheet = from
        sheet = ReaderSheet.None
        chrome = ReaderChrome.Hidden
        webView.evaluateJavascript("window.reader.goTo($chapter,$offset)", null)
    }

    fun seekText(fraction: Float) {
        val target = ReaderJump.positionAt(doc, fraction)
        placeText(target.chapter, target.characterOffset)
    }

    /** T350: a page is one measured column. The JS places that column and reports the first glyph. */
    fun goToPage(page: Int) {
        if (settings.continuous) return
        previousPlace = position
        returnSheet = null
        webView.evaluateJavascript(
            "if(window.reader)window.reader.goToPage(${page.coerceIn(0, pageCount.coerceAtLeast(1) - 1)})",
            null,
        )
    }

    fun returnToPreviousPlace() {
        val place = previousPlace ?: return
        previousPlace = null
        returnSheet = null
        sheet = ReaderSheet.None
        webView.evaluateJavascript("window.reader.goTo(${place.chapter},${place.characterOffset})", null)
    }

    fun addHighlightFor(target: ReaderSelection, tone: ReaderHighlightTone, note: String = "") {
        val text = ReaderMarksMath.highlightText(target.text)
        if (text.isBlank()) { clearSelection(); return }
        onAddHighlight(ReaderHighlight(
            id = "highlight-${UUID.randomUUID()}",
            chapter = target.chapter,
            start = target.start,
            end = target.end,
            tone = tone,
            text = text,
            note = note.take(com.brightblu.parc.core.data.library.ReaderMarksLimits.MaxNote),
            createdAt = System.currentTimeMillis(),
        ))
        clearSelection()
    }

    LaunchedEffect(marks.highlights) {
        val json = ReaderMarksJson.renderHighlights(marks.highlights)
        webView.evaluateJavascript("if(window.reader)window.reader.setHighlights($json)", null)
    }
    LaunchedEffect(marks.bookmarks) {
        val json = ReaderMarksJson.renderBookmarks(marks.bookmarks)
        webView.evaluateJavascript("if(window.reader)window.reader.setBookmarks($json)", null)
    }

    DisposableEffect(webView) {
        onDispose {
            generation.incrementAndGet()
            webView.afterDraw = null
            webView.stopLoading()
            webView.removeJavascriptInterface("Reader")
            webView.webViewClient = WebViewClient()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
    }

    // T257: keep the spoken text on the page and move the page only when a new chunk starts.
    LaunchedEffect(ttsMine, layoutReady, ttsState.phase, ttsState.chapter, ttsState.chunkStart) {
        if (ttsMine && layoutReady && ttsState.phase == ReaderTtsPhase.Playing) {
            webView.evaluateJavascript(
                "if(window.reader)window.reader.goTo(${ttsState.chapter},${ttsState.chunkStart})", null)
        }
    }
    LaunchedEffect(ttsMine, layoutReady, ttsState.phase, ttsState.chapter, ttsState.start, ttsState.end) {
        if (!layoutReady) return@LaunchedEffect
        if (ttsMine && (ttsState.phase == ReaderTtsPhase.Playing || ttsState.phase == ReaderTtsPhase.Paused)) {
            webView.evaluateJavascript(
                "if(window.reader)window.reader.setSpeaking(${ttsState.chapter},${ttsState.start},${ttsState.end})",
                null)
        } else {
            webView.evaluateJavascript("if(window.reader)window.reader.clearSpeaking()", null)
        }
    }

    LaunchedEffect(barsVisible, sheet, skin.colors.surface) {
        (context as? ReaderActivity)?.setReaderChrome(visible = barsVisible || sheet != ReaderSheet.None, light = readerChromeIsLight(skin))
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(colors.paper.toInt()))) {
        val width = maxWidth.value
        val height = maxHeight.value
        val highlightColors = remember(skin) { readerHighlightColorsJson(skin) }
        // T345: the embedded face is only needed when Figtree is the chosen family.
        val figtree = remember(context, settings.family) {
            if (settings.family == ReaderFamily.Figtree) ReaderFonts.figtreeBase64(context) else ""
        }
        // T343: the document is assembled off the UI thread. Only the opening window is included;
        // later chapters arrive through the bridge when the chunk window reaches them.
        val htmlState = produceState<String?>(null,
            doc, settings, width, height, highlightColors) {
            layoutReady = false
            val version = generation.incrementAndGet()
            val startPosition = position
            val built = withContext(Dispatchers.Default) {
                ReaderWebDocument.build(doc, settings, colors, width, height, startPosition, version,
                    marks.bookmarks, marks.highlights, highlightColors, figtree)
            }
            value = built
        }
        val html = htmlState.value
        AndroidView(
            factory = {
                webView.apply {
                    webView.settings.javaScriptEnabled = true
                    webView.settings.allowFileAccess = false
                    webView.settings.allowContentAccess = false
                    webView.settings.blockNetworkLoads = true
                    webView.settings.domStorageEnabled = false
                    webView.settings.databaseEnabled = false
                    webView.settings.loadsImagesAutomatically = true
                    webView.settings.setGeolocationEnabled(false)
                    webView.settings.mediaPlaybackRequiresUserGesture = true
                    webView.settings.setSupportZoom(false)
                    webView.settings.setBuiltInZoomControls(false)
                    webView.settings.setDisplayZoomControls(false)
                    webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    // T236: selection is drawn and handled by Compose, not the platform's
                    // contextual action bar, so a long press cannot open a native menu.
                    isLongClickable = false
                    setOnLongClickListener { true }
                    setBackgroundColor(colors.paper.toInt())
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = WebView.OVER_SCROLL_NEVER
                    addJavascriptInterface(bridge, "Reader")
                    webViewClient = client
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                val document = html
                if (document != null && view.tag != document) {
                    view.tag = document
                    view.setBackgroundColor(colors.paper.toInt())
                    view.loadDataWithBaseURL(null, document, "text/html", "utf-8", null)
                }
            },
        )

        fun settleOn(target: Int) {
            webView.evaluateJavascript(
                "window.reader.settle($target,${Springs.sheet.damping},${Springs.sheet.stiffness})",
                null,
            )
        }

        fun turn(direction: Int) {
            if (!layoutReady || sheet != ReaderSheet.None) return
            if (settings.continuous) {
                webView.evaluateJavascript("if(window.reader)window.reader.turn($direction,0,0)", null)
            } else {
                settleOn(ReaderMotion.turnTarget(latestPage.value, latestCount.value, direction))
            }
            chrome = ReaderChrome.Hidden
        }

        fun startListening() {
            if (!notificationsAllowed) {
                runCatching { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
            if (ttsMine && (ttsState.phase == ReaderTtsPhase.Paused ||
                    ttsState.phase == ReaderTtsPhase.Preparing)) {
                ReaderTtsService.play(context)
            } else {
                ReaderTtsService.start(context, ReaderTtsSession(book, doc, unadded, openHash), position,
                    settings.ttsSpeed, settings.ttsStopAtChapterEnd)
            }
        }

        DisposableEffect(settings.volumeKeys, webView) {
            val activity = context as? ReaderActivity
            if (settings.volumeKeys) activity?.volumeKeyHandler = { forward -> turn(if (forward) 1 else -1); true }
            onDispose { if (activity?.volumeKeyHandler != null) activity.volumeKeyHandler = null }
        }

        Box(Modifier.matchParentSize()
            .pointerInput(sheet, selection != null) {
                detectTapGestures(
                    onLongPress = { offset ->
                        if (sheet == ReaderSheet.None && selection == null && size.width > 0f && size.height > 0f) {
                            webView.evaluateJavascript(
                                "if(window.reader)window.reader.beginSelection(${offset.x / size.width},${offset.y / size.height})",
                                null,
                            )
                        }
                    },
                    onTap = { offset ->
                        if (sheet != ReaderSheet.None) return@detectTapGestures
                        if (selection != null) {
                            clearSelection()
                            return@detectTapGestures
                        }
                        val edge = size.width * Metrics.readerEdgeFraction
                        when {
                            offset.x < edge -> turn(-1)
                            offset.x > size.width - edge -> turn(1)
                            else -> chrome = chrome.toggled()
                        }
                    },
                )
            }
            .pointerInput(sheet, settings.continuous) {
                if (settings.continuous) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            if (selection != null) clearSelection()
                            webView.evaluateJavascript("window.reader.cancel()", null)
                        },
                        onVerticalDrag = { change, amount ->
                            if (sheet == ReaderSheet.None) {
                                webView.evaluateJavascript("window.reader.vscroll(${-amount})", null)
                                change.consume()
                            }
                        },
                    )
                } else {
                    var total = 0f
                    var base = 0f
                    val tracker = VelocityTracker()
                    detectHorizontalDragGestures(
                        onDragStart = {
                            if (selection != null) clearSelection()
                            total = 0f
                            base = dragState.offset
                            tracker.resetTracking()
                            webView.evaluateJavascript("window.reader.cancel()", null)
                        },
                        onHorizontalDrag = { change, amount ->
                            if (sheet == ReaderSheet.None) {
                                total += amount
                                tracker.addPosition(change.uptimeMillis, change.position)
                                val drag = base + total
                                val offset = ReaderMotion.pageDragOffset(
                                    drag, size.width.toFloat(),
                                    latestPage.value <= 0,
                                    latestPage.value >= latestCount.value - 1,
                                )
                                webView.evaluateJavascript("window.reader.drag($offset)", null)
                                change.consume()
                            }
                        },
                        onDragEnd = {
                            if (sheet == ReaderSheet.None) {
                                val velocity = tracker.calculateVelocity().x
                                settleOn(ReaderMotion.pageTarget(
                                    latestPage.value, latestCount.value, base + total, velocity, size.width.toFloat(),
                                ))
                            }
                            total = 0f
                            base = 0f
                        },
                        onDragCancel = {
                            if (sheet == ReaderSheet.None) settleOn(latestPage.value)
                            total = 0f
                            base = 0f
                        },
                    )
                }
            })

        if (sheet == ReaderSheet.None) ReaderControlsToggle(barsVisible) { chrome = chrome.toggled() }
        if (barsVisible) {
            ReaderControls(
                showPanel = chrome == ReaderChrome.Panel,
                title = book.title,
                skin = skin,
                bookmarked = pageBookmark != null,
                onBookmark = {
                    if (pageBookmark != null) onDeleteBookmark(pageBookmark.id) else onToggleBookmark(position)
                },
                menu = { dismiss ->
                    if (canAddToLibrary) ReaderMenuItem(if (adding) "Adding…" else "Add to library", skin, dismiss, onAddToLibrary, !adding)
                    ReaderMenuItem("Contents", skin, dismiss, action = { sheet = ReaderSheet.Contents })
                    ReaderMenuItem("Marks", skin, dismiss, action = { sheet = ReaderSheet.Marks })
                    ReaderMenuItem("Find", skin, dismiss, action = { sheet = ReaderSheet.Search })
                    ReaderMenuItem("Listen", skin, dismiss, action = { sheet = ReaderSheet.Listen })
                    ReaderMenuItem("Aa · Reading settings", skin, dismiss, action = { sheet = ReaderSheet.Aa })
                },
            ) {
                // T350: paged text counts real columns; continuous text has no columns and stays percent.
                val continuous = settings.continuous
                val kind = if (continuous) ReaderJumpKind.Percent else ReaderJumpKind.Page
                val fraction = if (continuous) ReaderJump.positionFraction(doc, position)
                    else ReaderJump.fractionForPage(currentPage, pageCount)
                val percent = ReaderPositionMath.progressPercent(doc, position)
                val label = addNotice ?: if (continuous) {
                    ReaderJump.panelLabel(ReaderJumpKind.Percent, doc, position, 1)
                } else {
                    ReaderJump.pagePanelLabel(doc, position, currentPage, pageCount, countingPages)
                }
                ReaderJumpPanel(
                    kind = kind,
                    fraction = fraction,
                    label = label,
                    entryText = if (continuous) "$percent%"
                        else ReaderJump.entryText(ReaderJumpKind.Page, fraction, pageCount),
                    singlePage = false,
                    doc = doc,
                    pageCount = if (continuous) 1 else pageCount,
                    skin = skin,
                    onSeek = { if (continuous) seekText(it)
                        else goToPage(ReaderJump.pageForFraction(it, pageCount)) },
                    onStep = { steps ->
                        if (continuous) seekText(ReaderJump.step(ReaderJumpKind.Percent, fraction, steps, 1))
                        else goToPage((currentPage + steps).coerceIn(0, pageCount.coerceAtLeast(1) - 1))
                    },
                    onEntry = { returnSheet = null; sheet = ReaderSheet.Position },
                    onContents = { sheet = ReaderSheet.Contents },
                    onBookmarks = { sheet = ReaderSheet.Bookmarks },
                    onNotes = { sheet = ReaderSheet.Notes },
                    onFind = { sheet = ReaderSheet.Search },
                    previousPlace = if (previousPlace != null) ({ returnToPreviousPlace() }) else null,
                    steps = ReaderJumpSteps(
                        previousLabel = "Previous chapter",
                        nextLabel = "Next chapter",
                        canPrevious = position.chapter > 0,
                        canNext = position.chapter < doc.chapters.lastIndex,
                        onPrevious = {
                            ReaderJump.chapterStep(position.chapter, -1, doc.chapters.size)
                                ?.let { placeText(it, 0) }
                        },
                        onNext = {
                            ReaderJump.chapterStep(position.chapter, 1, doc.chapters.size)
                                ?.let { placeText(it, 0) }
                        },
                    ),
                )
            }
        }

        if (!barsVisible && sheet == ReaderSheet.None && layoutReady && settings.showPageNumber &&
            !settings.continuous) {
            ReaderPageBadge(ReaderJump.pageBadgeLabel(currentPage, pageCount), skin) {
                returnSheet = null
                sheet = ReaderSheet.Position
            }
        }

        if (!barsVisible && pageBookmark != null) {
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
                ReaderSheet.Contents -> {
                    val entries = remember(doc) { doc.tocOrChapters() }
                    ReaderContentsSheet(
                        doc = doc,
                        selectedIndex = ReaderJump.contentsIndex(entries, position),
                        listState = contentsState,
                        skin = skin,
                        onDismiss = { sheet = ReaderSheet.None },
                        onEntry = { chapter, offset -> goToText(chapter, offset, ReaderSheet.Contents) },
                    )
                }
                ReaderSheet.Marks, ReaderSheet.Bookmarks, ReaderSheet.Notes -> {
                    val section = when (displayedSheet) {
                        ReaderSheet.Bookmarks -> MarkSection.Bookmarks
                        ReaderSheet.Notes -> MarkSection.Notes
                        else -> MarkSection.All
                    }
                    ReaderMarksSheet(
                        book = book,
                        doc = doc,
                        marks = marks,
                        isPdf = false,
                        pageCount = pageCount,
                        bookmarkPages = if (settings.continuous) emptyMap() else bookmarkPages,
                        section = section,
                        listState = marksState,
                        skin = skin,
                        onDismiss = { sheet = ReaderSheet.None },
                        onJump = { target ->
                            goToText(target.chapter, target.characterOffset, displayedSheet)
                        },
                        onDeleteBookmark = onDeleteBookmark,
                        onDeleteHighlight = onDeleteHighlight,
                        onAddToLightly = onAddToLightly,
                    )
                }
                ReaderSheet.Search -> ReaderSearchSheet(
                    skin = skin,
                    doc = doc,
                    session = searchSession,
                    listState = searchState,
                    unavailable = null,
                    emptyHint = null,
                    search = { query -> withContext(Dispatchers.Default) { ReaderSearch.inDoc(doc, query) } },
                    onDismiss = { sheet = ReaderSheet.None },
                    onJump = { hit -> goToText(hit.chapter, hit.offset, ReaderSheet.Search) },
                )
                ReaderSheet.Position -> if (settings.continuous) ReaderPositionEntrySheet(
                    kind = ReaderJumpKind.Percent,
                    fraction = ReaderJump.positionFraction(doc, position),
                    pageCount = 1,
                    skin = skin,
                    onDismiss = { sheet = ReaderSheet.None },
                    onGo = { target ->
                        returnSheet = null
                        sheet = ReaderSheet.None
                        seekText(target)
                    },
                ) else ReaderPositionEntrySheet(
                    kind = ReaderJumpKind.Page,
                    fraction = ReaderJump.fractionForPage(currentPage, pageCount),
                    pageCount = pageCount,
                    skin = skin,
                    onDismiss = { sheet = ReaderSheet.None },
                    onGo = { target ->
                        returnSheet = null
                        sheet = ReaderSheet.None
                        goToPage(ReaderJump.pageForFraction(target, pageCount))
                    },
                )
                ReaderSheet.Listen -> ReaderListenSheet(
                    book = book,
                    doc = doc,
                    settings = settings,
                    state = ttsState,
                    notificationsAllowed = notificationsAllowed,
                    skin = skin,
                    onPlay = { startListening() },
                    onPause = { ReaderTtsService.pause(context) },
                    onStop = { ReaderTtsService.stop(context) },
                    onSpeed = { value ->
                        val speed = ReaderTtsRules.nearestSpeed(value)
                        onSettingsChange { it.copy(ttsSpeed = speed) }
                        if (ttsMine) ReaderTtsService.setSpeed(context, speed)
                    },
                    onStopAtChapterEnd = { enabled ->
                        onSettingsChange { it.copy(ttsStopAtChapterEnd = enabled) }
                        if (ttsMine) ReaderTtsService.setStopAtChapterEnd(context, enabled)
                    },
                    onAllowNotifications = {
                        runCatching { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
                    },
                    onDismiss = { sheet = ReaderSheet.None },
                )
                ReaderSheet.Aa -> ReaderAaSheet(
                    settings = settings,
                    skin = skin,
                    isPdf = false,
                    onDismiss = { sheet = ReaderSheet.None },
                    onChange = onSettingsChange,
                )
                ReaderSheet.None -> Unit
            }
        }

        selection?.let { selected ->
            ReaderSelectionLayer(
                selection = selected,
                skin = skin,
                onDragHandle = { which, x, y ->
                    webView.evaluateJavascript("if(window.reader)window.reader.dragSelection('$which',$x,$y)", null)
                },
                onDismiss = { clearSelection() },
            )
            Box(Modifier.align(Alignment.BottomCenter)
                .padding(bottom = Metrics.readerSelectionMenuInsetDp.dp)) {
                ReaderSelectionMenu(
                    selection = selected,
                    skin = skin,
                    translateAvailable = ReaderTranslateVariant.Available,
                    defineNotice = defineNotice,
                    onHighlight = { tone -> addHighlightFor(selected, tone) },
                    onCopy = {
                        clipboard.setText(AnnotatedString(selected.text))
                        clearSelection()
                    },
                    onDefine = {
                        if (ReaderDefine.open(context, selected.text)) clearSelection()
                        else defineNotice = ReaderDefineRules.NoAppMessage
                    },
                    onTranslate = {
                        translateText = selected.text
                        clearSelection()
                    },
                    onAddToLightly = { onAddToLightly(selected.text) },
                    onNote = { noteTarget = selected },
                    onDismiss = { clearSelection() },
                )
            }
        }
        AnimatedVisibility(
            visible = translateText != null,
            modifier = Modifier.matchParentSize(),
            enter = fadeIn(spring(Springs.sheet.damping, Springs.sheet.stiffness)),
            exit = fadeOut(spring(Springs.sheet.damping, Springs.sheet.stiffness)),
        ) {
            displayedTranslate?.let { text ->
                ReaderTranslateVariant.Sheet(text, skin) { translateText = null }
            }
        }
        if (barsVisible || sheet != ReaderSheet.None) ReaderBackButton(skin)
        if (!layoutReady) ReaderLoading(book.title, skin)
        noteTarget?.let { target ->
            ReaderNoteDialog(
                initial = "",
                skin = skin,
                onDismiss = { noteTarget = null },
                onSave = { note ->
                    noteTarget = null
                    addHighlightFor(target, ReaderHighlightTone.Sand, note)
                },
            )
        }
    }
}

@Composable
internal fun ReaderBarAction(label: String, skin: Skin, onClick: () -> Unit, enabled: Boolean = true,
    description: String? = null) {
    Box(Modifier.heightIn(min = Metrics.readerTouchTargetDp.dp)
        .widthIn(min = Metrics.readerTouchTargetDp.dp)
        .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = Metrics.readerChipInsetDp.dp),
        contentAlignment = Alignment.Center) {
        BasicText(label, style = readerLabelStyle(skin).copy(
            color = Color((if (enabled) skin.colors.accent else skin.colors.textSecondary).toInt())))
    }
}

@Composable
private fun ReaderContentsSheet(
    doc: ReaderBookDoc,
    selectedIndex: Int,
    listState: LazyListState,
    skin: Skin,
    onDismiss: () -> Unit,
    onEntry: (Int, Int) -> Unit,
) {
    val entries = remember(doc) { doc.tocOrChapters() }
    // T344: the list keeps its own scroll. The first open starts near the current heading.
    LaunchedEffect(selectedIndex) {
        if (selectedIndex > 0 && listState.firstVisibleItemIndex == 0) listState.scrollToItem(selectedIndex)
    }
    ReaderSheetFrame("Contents", skin, onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(Metrics.readerContentsHeightFraction),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(Metrics.readerRowGapDp.dp)) {
            items(count = entries.size, key = { "${entries[it].chapter}:${entries[it].offset}:${it}" }) { index ->
                val entry = entries[index]
                val percent = ReaderPositionMath.progressPercent(doc, BookPosition(entry.chapter, entry.offset))
                val selected = index == selectedIndex
                Column(Modifier.fillMaxWidth()
                    .background(Color(skin.colors.surface.toInt()), RoundedCornerShape(Metrics.readerSheetCornerDp.dp))
                    .border(Metrics.readerHairlineDp.dp,
                        Color((if (selected) skin.colors.accent else skin.colors.lineBase).toInt()),
                        RoundedCornerShape(Metrics.readerSheetCornerDp.dp))
                    .clickable(role = Role.Button) { onEntry(entry.chapter, entry.offset) }
                    .padding(horizontal = Metrics.readerChipInsetDp.dp, vertical = Metrics.readerRowGapDp.dp)) {
                    BasicText(entry.title, style = readerValueStyle(skin), maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                    BasicText("$percent%", style = readerLabelStyle(skin))
                }
            }
        }
    }
}

@Composable
internal fun ReaderAaSheet(
    settings: ReaderSettings,
    skin: Skin,
    isPdf: Boolean,
    onDismiss: () -> Unit,
    onChange: ((ReaderSettings) -> ReaderSettings) -> Unit,
    showVolumeKeys: Boolean = true,
) {
    ReaderSheetFrame("Reading", skin, onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Metrics.readerRowGapDp.dp)) {
            if (isPdf) {
                ReaderChoiceRow("PDF layout", listOf(
                    PdfLayout.Page to "Page by page",
                    PdfLayout.Continuous to "Continuous",
                ), settings.pdfLayout, skin) { layout -> onChange { it.copy(pdfLayout = layout) } }
            } else {
                ReaderChoiceRow("Reading mode", listOf(
                    false to "Page by page",
                    true to "Continuous",
                ), settings.continuous, skin) { continuous -> onChange { it.copy(continuous = continuous) } }
                ReaderStepRow("Text size", "${settings.fontSp.roundToInt()}",
                    settings.fontSp > Metrics.readerMinFontSp, settings.fontSp < Metrics.readerMaxFontSp,
                    skin, { onChange { it.smallerText() } }, { onChange { it.largerText() } })
                ReaderStepRow("Line spacing", String.format(Locale.US, "%.2f", settings.lineSpacing),
                    settings.lineSpacing > Metrics.readerMinLineSpacing, settings.lineSpacing < Metrics.readerMaxLineSpacing,
                    skin, { onChange { it.lessLineSpacing() } }, { onChange { it.moreLineSpacing() } })
                ReaderStepRow("Margins", "${(settings.marginFraction * 100).roundToInt()}%",
                    settings.marginFraction > Metrics.readerMinMarginFraction,
                    settings.marginFraction < Metrics.readerMaxMarginFraction,
                    skin, { onChange { it.narrowerMargins() } }, { onChange { it.widerMargins() } })
                ReaderChoiceRow("Typeface", listOf(
                    ReaderFamily.Serif to "Serif",
                    ReaderFamily.Sans to "Sans",
                    ReaderFamily.Figtree to "Figtree",
                ), settings.family, skin) { family -> onChange { it.copy(family = family) } }
                ReaderChoiceRow("Heavier text", listOf(false to "Off", true to "On"),
                    settings.heavyText, skin) { heavy -> onChange { it.copy(heavyText = heavy) } }
            }
            ReaderToneSample(settings, skin)
            ReaderChoiceGrid(if (isPdf) "Background" else "Page colour", ReaderPageColors,
                settings.pageColor, skin) { page -> onChange { it.withPageColor(page) } }
            ReaderChoiceRow("Night", listOf(false to "Off", true to "On"),
                settings.night, skin) { night -> onChange { it.copy(night = night) } }
            ReaderChoiceRow(if (isPdf) "Page images" else "Illustrations", listOf(
                ReaderImageTone.Colour to "Colour",
                ReaderImageTone.Greyscale to "Greyscale",
            ), settings.imageTone, skin) { tone -> onChange { it.copy(imageTone = tone) } }
            if (isPdf) {
                ReaderChoiceRow("Invert pages", listOf(false to "Off", true to "On"),
                    settings.pdfInvert, skin) { invert -> onChange { it.copy(pdfInvert = invert) } }
            }
            ReaderBrightnessRow(settings.brightness, skin) { value -> onChange { it.withBrightness(value) } }
            ReaderChoiceRow("Keep screen awake", listOf(false to "Off", true to "On"),
                settings.keepAwake, skin) { on -> onChange { it.copy(keepAwake = on) } }
            ReaderChoiceRow("Show page number", listOf(false to "Off", true to "On"),
                settings.showPageNumber, skin) { on -> onChange { it.copy(showPageNumber = on) } }
            if (showVolumeKeys) {
                ReaderChoiceRow("Volume keys turn pages", listOf(false to "Off", true to "On"),
                    settings.volumeKeys, skin) { on -> onChange { it.copy(volumeKeys = on) } }
            }
        }
    }
}

private val ReaderPageColors = listOf(
    ReaderPageColor.Light to "Light",
    ReaderPageColor.Sepia to "Sepia",
    ReaderPageColor.Dark to "Dark",
    ReaderPageColor.Black to "Black",
    ReaderPageColor.BlackOnWhite to "Black on white",
    ReaderPageColor.WhiteOnBlack to "White on black",
)

private fun readerPageColorName(page: ReaderPageColor): String =
    ReaderPageColors.firstOrNull { it.first == page }?.second ?: page.name

/** T345: a live paper-and-ink sample, including the heavier body weight. */
@Composable
private fun ReaderToneSample(settings: ReaderSettings, skin: Skin) {
    val colors = readerColors(settings, skin)
    Column(Modifier.fillMaxWidth()) {
        BasicText("Sample${if (settings.night) " · Night" else ""}", style = readerLabelStyle(skin))
        Spacer(Modifier.height(Metrics.readerChipGapDp.dp))
        Box(Modifier.fillMaxWidth().heightIn(min = Metrics.readerChipHeightDp.dp)
            .background(Color(colors.paper.toInt()), RoundedCornerShape(Metrics.readerChipGapDp.dp))
            .border(Metrics.readerHairlineDp.dp, Color(colors.secondary.toInt()),
                RoundedCornerShape(Metrics.readerChipGapDp.dp))
            .padding(horizontal = Metrics.readerChipInsetDp.dp, vertical = Metrics.readerChipGapDp.dp),
            contentAlignment = Alignment.CenterStart) {
            BasicText("Aa  ${readerPageColorName(settings.pageColor)}",
                style = readerValueStyle(skin).copy(
                    color = Color(colors.ink.toInt()),
                    fontWeight = if (settings.heavyText) FontWeight.Medium else FontWeight.Normal,
                ))
        }
    }
}

/** T345: six page colours wrap into two rows of three, so long pure-paper names stay readable. */
@Composable
private fun <T> ReaderChoiceGrid(label: String, choices: List<Pair<T, String>>, chosen: T,
    skin: Skin, onChoose: (T) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        BasicText(label, style = readerLabelStyle(skin))
        Spacer(Modifier.height(Metrics.readerChipGapDp.dp))
        choices.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.readerChipGapDp.dp)) {
                row.forEach { (value, text) ->
                    val selected = value == chosen
                    Box(Modifier.weight(1f).heightIn(min = Metrics.readerChipHeightDp.dp)
                        .background(Color(skin.colors.surface.toInt()), RoundedCornerShape(Metrics.readerChipGapDp.dp))
                        .border(Metrics.readerHairlineDp.dp,
                            Color((if (selected) skin.colors.accent else skin.colors.lineBase).toInt()),
                            RoundedCornerShape(Metrics.readerChipGapDp.dp))
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { onChoose(value) })
                        .padding(horizontal = Metrics.readerChipGapDp.dp),
                        contentAlignment = Alignment.Center) {
                        BasicText(text, style = readerLabelStyle(skin).copy(
                            color = Color((if (selected) skin.colors.accent else skin.colors.textSecondary).toInt())))
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(Metrics.readerChipGapDp.dp))
        }
    }
}

/** T255: reader-only brightness. The far left is "Phone", which leaves the system brightness alone. */
@Composable
private fun ReaderBrightnessRow(brightness: Float, skin: Skin, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        BasicText("Brightness", style = readerLabelStyle(skin))
        BasicText(if (brightness == 0f) "Phone" else "${(brightness * 100).roundToInt()}%",
            style = readerValueStyle(skin))
        androidx.compose.material3.Slider(
            value = if (brightness == 0f) 0f else brightness,
            onValueChange = { onChange(it) },
            valueRange = 0f..1f,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = Color(skin.colors.accent.toInt()),
                activeTrackColor = Color(skin.colors.accent.toInt()),
                inactiveTrackColor = Color(skin.colors.lineBase.toInt()),
            ),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Reader brightness" },
        )
    }
}

@Composable
private fun ReaderStepRow(label: String, value: String, canLess: Boolean, canMore: Boolean,
    skin: Skin, less: () -> Unit, more: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            BasicText(label, style = readerLabelStyle(skin))
            BasicText(value, style = readerValueStyle(skin))
        }
        ReaderStepButton("−", "Decrease $label", skin, canLess, less)
        Spacer(Modifier.width(Metrics.readerChipGapDp.dp))
        ReaderStepButton("+", "Increase $label", skin, canMore, more)
    }
}

@Composable
private fun ReaderStepButton(label: String, description: String, skin: Skin, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(Metrics.readerTouchTargetDp.dp)
        .border(Metrics.readerHairlineDp.dp,
            Color((if (enabled) skin.colors.lineActive else skin.colors.lineBase).toInt()),
            RoundedCornerShape(Metrics.readerChipGapDp.dp))
        .semantics { contentDescription = description }
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center) {
        BasicText(label, style = readerValueStyle(skin).copy(
            color = Color((if (enabled) skin.colors.textPrimary else skin.colors.textSecondary).toInt())))
    }
}

@Composable
internal fun <T> ReaderChoiceRow(label: String, choices: List<Pair<T, String>>, chosen: T,
    skin: Skin, onChoose: (T) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        BasicText(label, style = readerLabelStyle(skin))
        Spacer(Modifier.height(Metrics.readerChipGapDp.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.readerChipGapDp.dp)) {
            choices.forEach { (value, text) ->
                val selected = value == chosen
                Box(Modifier.weight(1f).heightIn(min = Metrics.readerChipHeightDp.dp)
                    .background(Color(skin.colors.surface.toInt()), RoundedCornerShape(Metrics.readerChipGapDp.dp))
                    .border(Metrics.readerHairlineDp.dp,
                        Color((if (selected) skin.colors.accent else skin.colors.lineBase).toInt()),
                        RoundedCornerShape(Metrics.readerChipGapDp.dp))
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onChoose(value) }),
                    contentAlignment = Alignment.Center) {
                    BasicText(text, style = readerLabelStyle(skin).copy(
                        color = Color((if (selected) skin.colors.accent else skin.colors.textSecondary).toInt())))
                }
            }
        }
    }
}

@Composable
internal fun ReaderSheetFrame(title: String, skin: Skin, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize()
        .background(Color(skin.colors.scrim.toInt()))
        .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.fillMaxWidth()
            .widthIn(max = Metrics.readerSheetMaxWidthDp.dp)
            .fillMaxHeight(Metrics.readerSheetMaxHeightFraction)
            .background(Color(skin.colors.surface.toInt()),
                RoundedCornerShape(topStart = Metrics.readerSheetCornerDp.dp, topEnd = Metrics.readerSheetCornerDp.dp))
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(horizontal = Metrics.readerSheetInsetDp.dp, vertical = Metrics.readerSheetInsetDp.dp),
            verticalArrangement = Arrangement.spacedBy(Metrics.readerRowGapDp.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BasicText(title, Modifier.weight(1f), style = readerTitleStyle(skin))
                ReaderBarAction("Close", skin, onDismiss)
            }
            content()
        }
    }
}

@Composable
internal fun ReaderAction(label: String, skin: Skin, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = Metrics.readerTouchTargetDp.dp)
        .widthIn(min = Metrics.readerChipHeightDp.dp)
        .border(Metrics.readerHairlineDp.dp, Color(skin.colors.accent.toInt()),
            RoundedCornerShape(Metrics.readerChipGapDp.dp))
        .clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = Metrics.readerChipInsetDp.dp),
        contentAlignment = Alignment.Center) {
        BasicText(label, style = readerLabelStyle(skin).copy(color = Color(skin.colors.accent.toInt())))
    }
}

internal fun readerLabelStyle(skin: Skin): TextStyle = TextStyle(
    fontFamily = skin.textFamily,
    color = Color(skin.colors.textSecondary.toInt()),
    fontSize = skin.typography.label.sizeSp.sp,
    fontWeight = FontWeight(skin.typography.label.weight),
    letterSpacing = skin.typography.label.trackingSp.sp,
)

/** T350: the tiny corner page label uses the caption role, quieter than any control. */
internal fun readerCaptionStyle(skin: Skin): TextStyle = TextStyle(
    fontFamily = skin.textFamily,
    color = Color(skin.colors.textSecondary.toInt()),
    fontSize = skin.typography.caption.sizeSp.sp,
    fontWeight = FontWeight(skin.typography.caption.weight),
    letterSpacing = skin.typography.caption.trackingSp.sp,
)

internal fun readerValueStyle(skin: Skin): TextStyle = TextStyle(
    fontFamily = skin.textFamily,
    color = Color(skin.colors.textPrimary.toInt()),
    fontSize = skin.typography.menu.sizeSp.sp,
    fontWeight = FontWeight(skin.typography.menu.weight),
    letterSpacing = skin.typography.menu.trackingSp.sp,
)

internal fun readerTitleStyle(skin: Skin): TextStyle = TextStyle(
    fontFamily = skin.textFamily,
    color = Color(skin.colors.textPrimary.toInt()),
    fontSize = skin.typography.menu.sizeSp.sp,
    fontWeight = FontWeight(skin.typography.menu.weight),
    letterSpacing = skin.typography.menu.trackingSp.sp,
)

/** T210: the live horizontal offset of the page, shared with the JS spring so a new drag can grab it. */
internal class ReaderDragState {
    @Volatile var offset: Float = 0f
}

internal class ReaderBridge(
    private val onReady: (Int, Int, Int) -> Unit,
    private val onMoved: (Int, Int, Int, Float, Int) -> Unit,
    private val onOffset: (Float) -> Unit = {},
    private val onSelection: (String) -> Unit = {},
    private val onBookmarkPage: (Int, Int, Int) -> Unit = { _, _, _ -> },
    private val onChapter: (Int) -> String = { "" },
    private val onSettled: (Int, Int, Int, Float, Int) -> Unit = { _, _, _, _, _ -> },
    private val onCounted: (Int, Int, Boolean, Int) -> Unit = { _, _, _, _ -> },
    private val onBookmarkPages: (String, Int) -> Unit = { _, _ -> },
) {
    /** T341: pages, the laid-out page, and the document generation that produced them. */
    @JavascriptInterface fun ready(pages: Int, page: Int, generation: Int) = onReady(pages, page, generation)

    /** T350: a page count after one slice of the column count, and once when it is complete. */
    @JavascriptInterface fun counted(page: Int, pages: Int, done: Boolean, generation: Int) =
        onCounted(page, pages, done, generation)

    /** T350: bookmark pages in the same column space, in the order the marks were sent. */
    @JavascriptInterface fun bookmarkPages(json: String, generation: Int) = onBookmarkPages(json, generation)

    @JavascriptInterface fun moved(page: Int, chapter: Int, offset: Int, progress: Float, generation: Int) =
        onMoved(page, chapter, offset, progress, generation)

    /** T343: the settled page, sent only after a turn has finished, never during a drag. */
    @JavascriptInterface fun settled(page: Int, chapter: Int, offset: Int, progress: Float, generation: Int) =
        onSettled(page, chapter, offset, progress, generation)

    /** T343: chapter markup for the bounded layout window, fetched only when it is reached. */
    @JavascriptInterface fun chapter(index: Int): String = onChapter(index)

    @JavascriptInterface fun offset(offset: Float) = onOffset(offset)

    /** T236: the selected range, handle positions and snippets from the reading script. */
    @JavascriptInterface fun selection(raw: String) = onSelection(raw)

    /** The visible bookmark in this layout, or chapter -1 when the page has none. */
    @JavascriptInterface fun bookmarkPage(chapter: Int, offset: Int, version: Int) = onBookmarkPage(chapter, offset, version)
}

/** A visual-state callback guarantees the next draw contains the requested DOM state. */
private class ReaderWebView(context: android.content.Context) : WebView(context) {
    var afterDraw: Runnable? = null

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val complete = afterDraw ?: return
        afterDraw = null
        post(complete)
    }
}

private class ReaderWebClient(private val onInternalLink: (Int, Int) -> Unit) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        if (url.scheme == "parc-reader" && url.host == "chapter") {
            val chapter = url.pathSegments.firstOrNull()?.toIntOrNull() ?: 0
            val offset = url.getQueryParameter("offset")?.toIntOrNull() ?: 0
            onInternalLink(chapter, offset)
        }
        return true
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val scheme = request.url.scheme.orEmpty()
        if (scheme == "data" || scheme == "about") return null
        return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
    }
}

/** T345: the family choice maps to the embedded Figtree face or a platform generic. */
internal fun readerFontFamilyCss(family: ReaderFamily): String = when (family) {
    ReaderFamily.Serif -> "serif"
    ReaderFamily.Sans -> "sans-serif"
    ReaderFamily.Figtree -> "'ParcFigtree'"
}

/** T345: heavier reflowable text moves body to medium and headings/strong one step above it. */
internal fun readerBodyWeight(heavy: Boolean): Int = if (heavy) 500 else 400

internal fun readerHeadingWeight(heavy: Boolean): Int = if (heavy) 800 else 600

internal object ReaderWebDocument {
    fun build(doc: ReaderBookDoc, settings: ReaderSettings, colors: ReaderColors,
        width: Float, height: Float, position: BookPosition, generation: Int = 0,
        bookmarks: List<ReaderBookmark> = emptyList(), highlights: List<ReaderHighlight> = emptyList(),
        highlightColors: String = "{}", figtreeBase64: String = ""): String {
        if (width <= 1f || height <= 1f) return "<html><body></body></html>"
        val margin = (width * settings.marginFraction).coerceAtLeast(0f)
        val contentWidth = (width - margin * 2f).coerceAtLeast(1f)
        val gap = margin * 2f
        val paper = css(colors.paper)
        val ink = css(colors.ink)
        val secondary = css(colors.secondary)
        val accent = css(if (colors.light) colors.secondary else colors.accent)
        val speaking = rgba(colors.accent, Metrics.readerSpeakingAlpha)
        val start = ReaderPositionMath.clamp(doc, position)
        val chapterChars = doc.chapters.joinToString(",") { it.text.length.toString() }
        val continuous = settings.continuous
        val bodyWeight = readerBodyWeight(settings.heavyText)
        val headingWeight = readerHeadingWeight(settings.heavyText)
        val fontFamily = readerFontFamilyCss(settings.family)
        val grayscale = if (settings.imageTone == ReaderImageTone.Greyscale) "filter:grayscale(1);" else ""
        // T343: only the opening window is in the document. Other chapters are fetched through
        // the Reader.chapter bridge when the chunk window reaches them.
        val initialWindow = ReaderLayoutBudget.window(start.chapter, doc.chapters.size)
        val templates = buildString {
            for (index in initialWindow) {
                append("<template data-chapter=\"")
                append(index)
                append("\">")
                append(doc.chapters[index].html)
                append("</template>")
            }
        }
        return buildString {
            append("<!doctype html><html><head><meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no\">")
            append("<style>")
            if (figtreeBase64.isNotEmpty()) {
                append("@font-face{font-family:'ParcFigtree';font-weight:100 900;")
                append("src:url(data:font/ttf;base64,$figtreeBase64) format('truetype');}")
            }
            append("html,body{margin:0;padding:0;background:$paper;color:$ink;-webkit-text-size-adjust:100%;text-size-adjust:100%;}")
            // T345: continuous reading stacks chunks in normal document flow and lets the
            // viewport scroll vertically. Paged reading keeps the column layout below.
            if (continuous) {
                append("#viewport{position:fixed;left:0;top:0;width:${width}px;height:${height}px;overflow-y:auto;overflow-x:hidden;-webkit-overflow-scrolling:touch;}")
                append("#book{position:relative;left:0;top:0;width:${width}px;}")
                append("#book .chunk{position:relative;left:0;top:0;box-sizing:border-box;width:${width}px;")
                append("padding:${margin}px;font-family:$fontFamily;font-size:${settings.fontSp}px;font-weight:$bodyWeight;")
                append("line-height:${settings.lineSpacing};overflow-wrap:break-word;}")
            } else {
                append("#viewport{position:fixed;left:0;top:0;width:${width}px;height:${height}px;overflow:hidden;}")
                append("#book{position:absolute;left:0;top:0;width:${width}px;height:${height}px;overflow:hidden;}")
                // One pane per text chunk keeps a single multicol flow bounded. A pane is positioned
                // so its current page sits in the viewport; neighbours wait one page away.
                append("#book .chunk{position:absolute;left:0;top:0;box-sizing:border-box;width:${width}px;height:${height}px;")
                append("padding:${margin}px;column-width:${contentWidth}px;column-gap:${gap}px;column-fill:auto;")
                append("font-family:$fontFamily;font-size:${settings.fontSp}px;font-weight:$bodyWeight;line-height:${settings.lineSpacing};overflow-wrap:break-word;}")
            }
            append("#book p{margin:0 0 0.7em;}")
            append("#book h1,#book h2,#book h3,#book h4,#book h5,#book h6{margin:0 0 0.45em;line-height:1.22;font-weight:$headingWeight;break-after:avoid;}")
            append("#book strong,#book b{font-weight:${if (settings.heavyText) 800 else 700};}")
            append("#book h1{font-size:1.5em;}#book h2{font-size:1.3em;}#book h3{font-size:1.15em;}")
            append("#book img{max-width:100%;height:auto;break-inside:avoid;$grayscale}")
            append("#book a{color:$accent;text-decoration:underline;text-decoration-thickness:1px;}")
            append("#book blockquote{margin:0 0 0.7em;padding-left:0.8em;border-left:1px solid $secondary;color:$secondary;}")
            append("#book ul,#book ol{margin:0 0 0.7em 1.1em;padding:0;}")
            append("#book li{margin:0 0 0.25em;}")
            append("#book pre{white-space:pre-wrap;font-family:inherit;margin:0;}")
            append("#book table{border-collapse:collapse;max-width:100%;}")
            append("#book td,#book th{border:1px solid $secondary;padding:0.15em 0.3em;}")
            append("</style></head><body><div id=\"viewport\"><div id=\"book\"></div></div>$templates")
            append("<script>(function(){")
            append("var book=document.getElementById('book');")
            append("var viewport=document.getElementById('viewport');")
            append("var CONT=$continuous;")
            append("var W=$width,H=$height,M=$margin,MAXCHUNK=${Metrics.readerChunkChars};")
            append("var chapterChars=[$chapterChars];")
            append("var chapterCount=chapterChars.length,totalChars=0;for(var i=0;i<chapterCount;i++)totalChars+=chapterChars[i];")
            append("var page=0,pageCount=1,offset=0,raf=0,settling=false,velocity=0,lastFrame=0,targetOffset=0;")
            append("var current={chapter:${start.chapter},offset:${start.characterOffset}};")
            append("var start={chapter:${start.chapter},offset:${start.characterOffset}};")
            append("var chunkCache={},attached=[],currentChunk=null,incomingChunk=null,localPage=0;")
            append("var charsPerPage=${Metrics.readerCharsPerPageFallback},measuredChars=0,measuredPages=0;")
            // T350: the measured column counts survive chunk eviction, so the whole-book total stays
            // exact after a chapter's DOM has been dropped.
            append("var pageCache={},pageCacheFull={};")
            append("var counting=false,countDone=false,countChapter=0,countChunk=0;")
            append("var bookmarkPositions=${ReaderMarksJson.renderBookmarks(bookmarks)};")
            append("var highlightData=${ReaderMarksJson.renderHighlights(highlights)};")
            append("var highlightColors=$highlightColors;")
            append("var speakColor='$speaking';")
            append("var selection=null,speakMark=null,speakRange=null;")
            append("var templates={};var tpls=document.querySelectorAll('template[data-chapter]');")
            append("for(var ti=0;ti<tpls.length;ti++){templates[parseInt(tpls[ti].getAttribute('data-chapter'),10)]=tpls[ti];}")
            // T343: one index per attached chunk. Every character lookup is a binary search in
            // this index instead of a fresh tree walk from the first node of the chapter.
            append("function rangeAt(obj,off){var arr=obj.index||indexPane(obj),lo=0,hi=arr.length-1,found=-1;")
            append("while(lo<=hi){var mid=(lo+hi)>>1;if(arr[mid].s<=off){found=mid;lo=mid+1;}else{hi=mid-1;}}")
            append("var r=document.createRange();if(found<0){if(!arr.length){r.selectNodeContents(obj.pane);r.collapse(false);return r;}found=0;}")
            append("var e=arr[found];var local=Math.max(0,Math.min(e.l-1,off-e.s));r.setStart(e.n,local);r.setEnd(e.n,local+1);return r;}")
            append("function indexPane(obj){var walker=document.createTreeWalker(obj.pane,NodeFilter.SHOW_TEXT,null,false),arr=[],pos=0,node,maxRight=obj.textRight||0,paneLeft=obj.pane?obj.pane.getBoundingClientRect().left:0;")
            append("while((node=walker.nextNode())){var len=node.nodeValue.length;if(len>0){arr.push({n:node,s:pos,l:len});pos+=len;")
            append("var rr=document.createRange();rr.selectNodeContents(node);var list=rr.getClientRects();for(var k=0;k<list.length;k++){var q=list[k];if(q.width>0&&q.right-paneLeft>maxRight)maxRight=q.right-paneLeft;}}}")
            append("obj.index=arr;obj.indexLen=pos;obj.textRight=maxRight;return arr;}")
            append("function chunkOf(node){while(node&&node!==book){if(node._parcChunk)return node._parcChunk;node=node.parentNode;}return null;}")
            append("function offsetInPane(obj,node,nodeOffset){var r=document.createRange();r.selectNodeContents(obj.pane);r.setEnd(node,nodeOffset);return r.toString().length;}")
            append("function pointOffset(x,y){var r=null;if(document.caretRangeFromPoint){r=document.caretRangeFromPoint(x,y);}else if(document.caretPositionFromPoint){var p=document.caretPositionFromPoint(x,y);if(p){r=document.createRange();r.setStart(p.offsetNode,p.offset);r.collapse(true);}}")
            append("if(!r)return null;var obj=chunkOf(r.startContainer);if(!obj)return null;return {chapter:obj.ch,offset:obj.start+offsetInPane(obj,r.startContainer,r.startOffset)};}")
            append("function chunkAt(ch,off){var d=chunkCache[ch];if(!d||!d.chunks.length)return null;for(var i=0;i<d.chunks.length;i++){var c=d.chunks[i];if(off>=c.start&&(off<c.start+c.len||i===d.chunks.length-1))return c;}return d.chunks[d.chunks.length-1];}")
            append("function wordBounds(obj,off){var text=obj.pane.textContent;var local=Math.max(0,Math.min(text.length,off-obj.start));var s=local,e=local;while(s>0&&!/\\s/.test(text.charAt(s-1)))s--;while(e<text.length&&!/\\s/.test(text.charAt(e)))e++;if(e<=s&&e<text.length)e++;return {start:obj.start+s,end:obj.start+e};}")
            append("function rangeWithin(obj,s,e){var walker=document.createTreeWalker(obj.pane,NodeFilter.SHOW_TEXT,null,false),node,pos=0,startNode=null,startOffset=0,endNode=null,endOffset=0;")
            append("while((node=walker.nextNode())){var len=node.nodeValue.length;if(startNode===null&&s<=pos+len){startNode=node;startOffset=Math.max(0,s-pos);}if(endNode===null&&e<=pos+len){endNode=node;endOffset=Math.max(0,e-pos);break;}pos+=len;}")
            append("if(startNode===null){startNode=obj.pane;startOffset=obj.pane.childNodes.length;}if(endNode===null){endNode=obj.pane;endOffset=obj.pane.childNodes.length;}")
            append("var r=document.createRange();r.setStart(startNode,startOffset);r.setEnd(endNode,endOffset);return r;}")
            append("function publishSelection(){if(!selection){if(window.Reader&&Reader.selection)Reader.selection('');return;}")
            append("var obj=chunkAt(selection.chapter,selection.start);if(!obj||!obj.pane){clearSelection();return;}")
            append("var len=obj.pane.textContent.length;var ls=Math.max(0,Math.min(len,selection.start-obj.start));var le=Math.max(ls+1,Math.min(len,selection.end-obj.start));if(le<=ls){clearSelection();return;}")
            append("selection.start=obj.start+ls;selection.end=obj.start+le;var r=rangeWithin(obj,ls,le);var list=r.getClientRects(),rects=[],first=null,last=null;")
            append("for(var i=0;i<list.length;i++){var q=list[i];if(q.width<=0||q.height<=0)continue;rects.push([q.left/W,q.top/H,q.right/W,q.bottom/H]);if(!first)first=q;last=q;}")
            append("var payload={chapter:selection.chapter,startOffset:selection.start,endOffset:selection.end,text:obj.pane.textContent.substring(ls,le),rects:rects,start:first?[first.left/W,first.bottom/H]:[0,0],end:last?[last.right/W,last.bottom/H]:[0,0]};if(window.Reader&&Reader.selection)Reader.selection(JSON.stringify(payload));}")
            append("function beginSelection(rx,ry){var p=pointOffset(rx*W,ry*H);if(!p){clearSelection();return;}var obj=chunkAt(p.chapter,p.offset);if(!obj){clearSelection();return;}var b=wordBounds(obj,p.offset);selection={chapter:p.chapter,start:b.start,end:b.end};publishSelection();}")
            append("function dragSelection(which,rx,ry){if(!selection)return;var p=pointOffset(rx*W,ry*H);if(!p||p.chapter!==selection.chapter)return;if(which==='start')selection.start=Math.min(p.offset,selection.end-1);else selection.end=Math.max(p.offset,selection.start+1);publishSelection();}")
            append("function clearSelection(){selection=null;if(window.Reader&&Reader.selection)Reader.selection('');}")
            append("function removeSpeakMark(){if(!speakMark)return;var p=speakMark.parentNode;if(!p){speakMark=null;return;}while(speakMark.firstChild)p.insertBefore(speakMark.firstChild,speakMark);p.removeChild(speakMark);p.normalize();speakMark=null;}")
            append("function drawSpeaking(){if(!speakRange)return;var obj=chunkAt(speakRange.c,speakRange.s);if(!obj||!obj.pane)return;var len=obj.pane.textContent.length;var s=Math.max(0,Math.min(len,speakRange.s-obj.start)),e=Math.max(s+1,Math.min(len,speakRange.e-obj.start));if(e<=s)return;var r=rangeWithin(obj,s,e);var frag=r.extractContents();var mark=document.createElement('mark');mark.setAttribute('data-parc-speak','1');mark.style.backgroundColor=speakColor;mark.style.color='inherit';mark.style.padding='0';mark.style.borderRadius='2px';mark.appendChild(frag);r.insertNode(mark);speakMark=mark;obj.index=null;indexPane(obj);}")
            append("function setSpeaking(ch,start,end){speakRange={c:ch,s:start,e:end};removeSpeakMark();drawSpeaking();}")
            append("function clearSpeaking(){speakRange=null;removeSpeakMark();}")
            append("function unwrapMarks(root){var marks=root.querySelectorAll('mark[data-parc-mark]');for(var i=marks.length-1;i>=0;i--){var m=marks[i],p=m.parentNode;if(!p)continue;while(m.firstChild)p.insertBefore(m.firstChild,m);p.removeChild(m);p.normalize();}}")
            append("function paneOfChapter(ch){for(var i=0;i<attached.length;i++)if(attached[i].ch===ch&&attached[i].pane)return attached[i];return null;}")
            append("function applyHighlightsFor(ch){var list=[];for(var i=0;i<(highlightData||[]).length;i++)if(highlightData[i].c===ch)list.push(highlightData[i]);list.sort(function(a,b){return a.s-b.s;});")
            append("for(var a=0;a<attached.length;a++){var obj=attached[a];if(obj.ch!==ch||!obj.pane)continue;unwrapMarks(obj.pane);")
            append("for(var h=0;h<list.length;h++){var m=list[h],len=obj.indexLen||obj.pane.textContent.length,s=Math.max(0,m.s-obj.start),e=Math.min(len,m.e-obj.start);if(e<=s)continue;")
            append("var r=rangeWithin(obj,s,e),frag=r.extractContents(),mark=document.createElement('mark');mark.setAttribute('data-parc-mark','1');mark.style.backgroundColor=(highlightColors[m.t]||'rgba(231,211,162,0.38)');mark.style.color='inherit';mark.style.padding='0';mark.style.borderRadius='2px';mark.appendChild(frag);r.insertNode(mark);}")
            append("obj.index=null;indexPane(obj);}}")
            append("function applyHighlights(list){highlightData=list||[];removeSpeakMark();var seen={};for(var i=0;i<attached.length;i++){var ch=attached[i].ch;if(seen[ch])continue;seen[ch]=true;applyHighlightsFor(ch);}if(speakRange)drawSpeaking();}")
            append("function setHighlights(list){highlightData=list||[];applyHighlights(highlightData);if(initialized){var c=current.chapter,o=current.offset;measure();goTo(c,o);notify();}}")
            // Range bounds include the page transform. Compare against the viewport, and return the
            // actual mark so Remove works even after a font change moves the start of the page.
            append("function bookmarkOnPage(){var mark=null,obj=currentChunk;if(obj){for(var i=0;i<bookmarkPositions.length;i++){")
            append("var b=bookmarkPositions[i];if(b.p>=0||b.c!==obj.ch)continue;var local=b.o-obj.start;if(local<0||local>=(obj.len||0))continue;")
            append("var rect=rangeAt(obj,local).getBoundingClientRect();if(rect.left>=0&&rect.left<W){mark=b;break;}}}")
            append("if(window.Reader)Reader.bookmarkPage(mark?mark.c:-1,mark?mark.o:0,$generation);}")
            append("function setBookmarks(list){bookmarkPositions=list||[];if(initialized)bookmarkOnPage();if(countDone)sendBookmarkPages();}")
            // T343: chunking. Hold at most one neighbour each side in the DOM; evicted chunks keep
            // their measured page count in the cache, so a revisited chunk does not need layout.
            append("function collectBlocks(root,out){var n=root.firstChild;while(n){var next=n.nextSibling;if(n.nodeType===3){out.push(n);}else if(n.nodeType===1){var tag=n.tagName;var wrap=(tag==='DIV'||tag==='SECTION'||tag==='ARTICLE'||tag==='ASIDE'||tag==='HEADER'||tag==='FOOTER'||tag==='NAV'||tag==='MAIN');if(wrap){var before=out.length;collectBlocks(n,out);if(out.length===before)out.push(n);}else{out.push(n);}}n=next;}}")
            append("function chapterData(ch){var d=chunkCache[ch];if(d)return d;var holder=document.createElement('div');var tpl=templates[ch];")
            append("if(tpl){holder.appendChild(tpl.content.cloneNode(true));}else if(window.Reader&&Reader.chapter){holder.innerHTML=Reader.chapter(ch);}")
            append("var blocks=[];collectBlocks(holder,blocks);var chunks=[],cur=null,curLen=0,total=0;")
            append("for(var i=0;i<blocks.length;i++){var node=blocks[i],len=node.textContent?node.textContent.length:0;")
            append("if(node.nodeType===3){if(!cur)cur=document.createElement('div');cur.appendChild(node);curLen+=len;continue;}")
            append("var keepWhole=(node.tagName==='TABLE'||node.tagName==='FIGURE'||node.tagName==='PRE'||node.tagName==='BLOCKQUOTE'||node.tagName==='UL'||node.tagName==='OL');")
            append("if(cur&&cur.children.length>0&&!keepWhole&&curLen+len>MAXCHUNK){chunks.push({ch:ch,start:total,len:curLen,pages:0,measured:false,el:cur,pane:null,index:null,indexLen:0,idx:chunks.length});total+=curLen;cur=null;curLen=0;}")
            append("if(!cur)cur=document.createElement('div');cur.appendChild(node);curLen+=len;}")
            append("chunks.push({ch:ch,start:total,len:curLen,pages:0,measured:false,el:cur,pane:null,index:null,indexLen:0,idx:chunks.length});total+=curLen;")
            append("d={chunks:chunks,total:total};chunkCache[ch]=d;return d;}")
            append("function chunkEstimate(obj){return Math.max(1,Math.ceil(obj.len/charsPerPage));}")
            append("function chunkPages(obj){var pc=pageCache[obj.ch];if(pc&&pc[obj.idx]!=null)return Math.max(1,pc[obj.idx]|0);return obj.measured?obj.pages:chunkEstimate(obj);}")
            append("function chapterPages(ch){var d=chunkCache[ch];if(d){var n=0;for(var i=0;i<d.chunks.length;i++)n+=chunkPages(d.chunks[i]);return Math.max(1,n);}")
            append("var pc=pageCache[ch];if(pc&&pageCacheFull[ch]){var n=0;for(var i=0;i<pc.length;i++)n+=Math.max(1,pc[i]|0);return Math.max(1,n);}")
            append("return Math.max(1,Math.ceil((chapterChars[ch]||0)/charsPerPage));}")
            append("function recordPages(obj){var pc=pageCache[obj.ch];if(!pc){pc=[];pageCache[obj.ch]=pc;}pc[obj.idx]=obj.pages;}")
            append("function pagesBeforeChapter(ch){var n=0;for(var i=0;i<ch&&i<chapterCount;i++)n+=chapterPages(i);return n;}")
            append("function pagesBeforeChunk(d,idx){var n=0;for(var i=0;i<idx;i++)n+=chunkPages(d.chunks[i]);return n;}")
            append("function totalPageCount(){var n=0;for(var i=0;i<chapterCount;i++)n+=chapterPages(i);return Math.max(1,n);}")
            append("function pageOf(ch,obj,local){var d=chunkCache[ch];var idx=d?d.chunks.indexOf(obj):0;return pagesBeforeChapter(ch)+pagesBeforeChunk(d,idx)+local;}")
            append("function updateRate(obj){measuredChars+=obj.len;measuredPages+=obj.pages;if(measuredChars>0&&measuredPages>0)charsPerPage=measuredChars/measuredPages;}")
            append("function measurePane(obj){var el=obj.pane;el.style.transform='translate3d(0,0,0)';el.style.left='0px';var base=el.getBoundingClientRect().left,right=obj.textRight||0;")
            append("for(var i=0;i<el.children.length;i++){var br=el.children[i].getBoundingClientRect();if(br.width>0&&br.right-base>right)right=br.right-base;}")
            append("if(right<=M)right=el.scrollWidth;if(right<=M)right=M+1;return Math.max(1,Math.ceil((right-M+1)/W));}")
            append("function attachChunk(obj){if(obj.pane)return;var el=document.createElement('div');el.className='chunk';el.setAttribute('data-chapter',obj.ch);")
            append("while(obj.el.firstChild)el.appendChild(obj.el.firstChild);el._parcChunk=obj;el.style.visibility='hidden';book.appendChild(el);obj.pane=el;")
            append("var pi=parked.indexOf(obj.ch);if(pi>=0)parked.splice(pi,1);")
            append("applyHighlightsFor(obj.ch);if(!obj.index)indexPane(obj);obj.pages=measurePane(obj);recordPages(obj);if(!obj.measured){obj.measured=true;updateRate(obj);}}")
            append("var parked=[];function detach(obj){if(!obj.pane)return;while(obj.pane.firstChild)obj.el.appendChild(obj.pane.firstChild);book.removeChild(obj.pane);obj.pane=null;obj.index=null;")
            append("if(parked.indexOf(obj.ch)<0)parked.push(obj.ch);while(parked.length>4){var evicted=false;for(var pk=0;pk<parked.length&&!evicted;pk++){var old=parked[pk],live=false;for(var lv=0;lv<attached.length;lv++)if(attached[lv].ch===old){live=true;break;}if(!live&&old!==current.chapter){parked.splice(pk,1);delete chunkCache[old];evicted=true;}}if(!evicted)break;}}")
            append("function paneIndex(obj){var d=chunkCache[obj.ch];return d?d.chunks.indexOf(obj):-1;}")
            append("function attachWindow(ch,idx){var d=chapterData(ch),want=[];")
            append("for(var i=Math.max(0,idx-1);i<=Math.min(d.chunks.length-1,idx+1);i++)want.push(d.chunks[i]);")
            append("if(idx===0&&ch>0){var pd=chapterData(ch-1);want.unshift(pd.chunks[pd.chunks.length-1]);}")
            append("if(idx===d.chunks.length-1&&ch<chapterCount-1){var nd=chapterData(ch+1);want.push(nd.chunks[0]);}")
            append("for(var a=attached.length-1;a>=0;a--){if(want.indexOf(attached[a])<0)detach(attached[a]);}attached=want;")
            append("for(var w=0;w<want.length;w++){if(!want[w].pane)attachChunk(want[w]);}}")
            // T345: continuous reading keeps one chapter's chunks stacked in document flow and
            // scrolls the viewport. The saved character is placed at the top margin.
            append("var contChapter=-1,contTimer=0,contSettleTimer=0;")
            append("function contDetachAll(){for(var i=attached.length-1;i>=0;i--){var o=attached[i];if(!o.pane)continue;while(o.pane.firstChild)o.el.appendChild(o.pane.firstChild);book.removeChild(o.pane);o.pane=null;o.index=null;}attached=[];}")
            append("function contAttach(ch){if(contChapter===ch&&attached.length)return;contDetachAll();contChapter=ch;var d=chapterData(ch);attached=d.chunks;")
            append("for(var i=0;i<d.chunks.length;i++){var obj=d.chunks[i];var el=document.createElement('div');el.className='chunk';el.setAttribute('data-chapter',ch);")
            append("while(obj.el.firstChild)el.appendChild(obj.el.firstChild);el._parcChunk=obj;book.appendChild(el);obj.pane=el;if(!obj.index)indexPane(obj);}")
            append("applyHighlightsFor(ch);}")
            append("function contShow(ch,off){contAttach(ch);var f=chunkFor(ch,off);if(!f||!f.obj||!f.obj.pane)return;var obj=f.obj;")
            append("var local=Math.max(0,Math.min((obj.len||1)-1,(off|0)-obj.start));var r=rangeAt(obj,local);")
            append("var vr=viewport.getBoundingClientRect(),rr=r.getBoundingClientRect();")
            append("viewport.scrollTop=viewport.scrollTop+(rr.top-vr.top)-M;current={chapter:ch,offset:off|0};currentChunk=obj;}")
            append("function contRead(commit){var p=pointOffset(M+2,M+2);if(!p)p=pointOffset(M+2,Math.round(H*0.18));if(!p)return;")
            append("current=p;var f=chunkFor(p.chapter,p.offset);if(f)currentChunk=f.obj;pageCount=totalPageCount();")
            append("if(currentChunk)page=pageOf(currentChunk.ch,currentChunk,0);send();")
            append("if(commit)stable();}")
            append("function scrollPages(dir){var before=viewport.scrollTop,max=viewport.scrollHeight-viewport.clientHeight;")
            append("if(dir>0&&before>=max-2){if(current.chapter+1<chapterCount)goTo(current.chapter+1,0);return;}")
            append("if(dir<0&&before<=1){if(current.chapter>0){var pd=chapterData(current.chapter-1);goTo(current.chapter-1,Math.max(0,pd.total-1));}return;}")
            append("viewport.scrollTop=before+dir*Math.round(H*0.82);}")
            if (continuous) {
                append("viewport.addEventListener('scroll',function(){if(!initialized)return;if(contTimer)clearTimeout(contTimer);")
                append("contTimer=setTimeout(function(){contTimer=0;contRead(false);},120);")
                append("if(contSettleTimer)clearTimeout(contSettleTimer);contSettleTimer=setTimeout(function(){contSettleTimer=0;contRead(true);},420);});")
            }
            append("function placePanes(){if(!currentChunk||!currentChunk.pane)return;var cd=chunkCache[currentChunk.ch],ci=cd.chunks.indexOf(currentChunk);")
            append("for(var i=0;i<attached.length;i++){var o=attached[i];if(!o.pane)continue;")
            append("if(o===currentChunk){o.pane.style.left='0px';o.pane.style.transform='translate3d('+(-localPage*W)+'px,0,0)';o.pane.style.zIndex='2';o.pane.style.display='block';}")
            append("else{var od=chunkCache[o.ch];if(!od)continue;var oi=od.chunks.indexOf(o),after=(o.ch>currentChunk.ch)||(o.ch===currentChunk.ch&&oi>ci);")
            append("if(after){o.pane.style.left=((currentChunk.pages-localPage)*W)+'px';o.pane.style.transform='translate3d(0,0,0)';}")
            append("else{o.pane.style.left='-'+W+'px';o.pane.style.transform='translate3d('+(-(o.pages-1)*W)+'px,0,0)';}o.pane.style.zIndex=o===incomingChunk?'2':'1';o.pane.style.display=(o===incomingChunk?'block':'none');}o.pane.style.visibility='visible';}")
            append("}")
            append("function stepNext(){var d=chunkCache[currentChunk.ch];if(!d)return null;var i=d.chunks.indexOf(currentChunk);if(i+1<d.chunks.length)return d.chunks[i+1];if(currentChunk.ch+1<chapterCount){var nd=chapterData(currentChunk.ch+1);return nd.chunks[0];}return null;}")
            append("function stepPrev(){var d=chunkCache[currentChunk.ch];if(!d)return null;var i=d.chunks.indexOf(currentChunk);if(i>0)return d.chunks[i-1];if(currentChunk.ch>0){var pd=chapterData(currentChunk.ch-1);return pd.chunks[pd.chunks.length-1];}return null;}")
            append("function commitStep(dir){incomingChunk=null;if(dir>0){if(localPage+1<currentChunk.pages){localPage++;}else{var n=stepNext();if(!n)return;currentChunk=n;localPage=0;attachWindow(n.ch,paneIndex(n));}}")
            append("else{if(localPage>0){localPage--;}else{var p=stepPrev();if(!p)return;currentChunk=p;localPage=p.pages-1;attachWindow(p.ch,paneIndex(p));}}")
            append("page=pageOf(currentChunk.ch,currentChunk,localPage);pageCount=totalPageCount();placePanes();}")
            append("function apply(){book.style.transform='translate3d('+offset+'px,0,0)';}")
            append("function cancelMotion(){if(raf){cancelAnimationFrame(raf);raf=0;}settling=false;}")
            append("function sendOffset(){if(window.Reader){Reader.offset(offset);}}")
            append("function drag(value){cancelMotion();if(!currentChunk)return;offset=value;apply();sendOffset();}")
            append("function settle(target,damping,stiffness){if(!initialized||!currentChunk)return;cancelMotion();target=Math.max(0,Math.min(pageCount-1,target|0));")
            append("var dir=target>page?1:(target<page?-1:0);if(dir!==0){var can=dir>0?((localPage+1<currentChunk.pages)||stepNext()):((localPage>0)||stepPrev());if(!can){dir=0;target=page;}}")
            append("targetOffset=-dir*W;")
            append("if(dir===0&&Math.abs(offset)<0.5){offset=0;apply();sendOffset();return;}")
            append("incomingChunk=null;if(dir>0&&localPage+1>=currentChunk.pages){var ix=stepNext();if(ix){if(attached.indexOf(ix)<0)attachWindow(ix.ch,paneIndex(ix));incomingChunk=ix;}}else if(dir<0&&localPage<=0){var ip=stepPrev();if(ip){if(attached.indexOf(ip)<0)attachWindow(ip.ch,paneIndex(ip));incomingChunk=ip;}}placePanes();")
            append("settling=true;velocity=0;lastFrame=0;")
            append("function frame(now){if(!settling)return;if(lastFrame===0)lastFrame=now;")
            append("var dt=Math.min(0.032,(now-lastFrame)/1000);lastFrame=now;var steps=Math.max(1,Math.ceil(dt/0.004));var h=dt/steps;")
            append("var k=stiffness,c=2*damping*Math.sqrt(k);")
            append("for(var i=0;i<steps;i++){var a=-k*(offset-targetOffset)-c*velocity;velocity+=a*h;offset+=velocity*h;}")
            append("if(Math.abs(offset-targetOffset)<0.5&&Math.abs(velocity)<8){offset=0;if(dir!==0)commitStep(dir);apply();sendOffset();notify();settling=false;raf=0;return;}")
            append("apply();sendOffset();raf=requestAnimationFrame(frame);}raf=requestAnimationFrame(frame);}")
            append("function measure(){cancelMotion();offset=0;book.style.transform='translate3d(0,0,0)';")
            append("if(CONT){pageCount=totalPageCount();return;}")
            append("if(!currentChunk){var f=chunkFor(start.chapter,start.offset);attachWindow(start.chapter,f.index);currentChunk=f.obj;localPage=0;placePanes();}")
            append("pageCount=totalPageCount();}")
            append("function chunkFor(ch,off){var d=chapterData(ch);var idx=0;for(var i=0;i<d.chunks.length;i++){var c=d.chunks[i];if((off|0)>=c.start&&((off|0)<c.start+c.len||i===d.chunks.length-1)){idx=i;break;}}return {d:d,index:idx,obj:d.chunks[idx]};}")
            // Hit-testing the margin can return the book element (or the previous page), so
            // find the first visible glyph in column order instead of caretRangeFromPoint.
            append("function caretAtPage(){var obj=currentChunk;if(!obj||!obj.pane)return null;var arr=obj.index||indexPane(obj);")
            append("for(var n=0;n<arr.length;n++){var e=arr[n],lo=0,hi=e.l;")
            append("while(lo<hi){var mid=(lo+hi)>>1,r=document.createRange();r.setStart(e.n,mid);r.setEnd(e.n,mid+1);")
            append("var rect=r.getBoundingClientRect();if(rect.right<=M)lo=mid+1;else hi=mid;}")
            append("if(lo<e.l){var r=document.createRange();r.setStart(e.n,lo);r.setEnd(e.n,lo+1);var rect=r.getBoundingClientRect();")
            append("if(rect.right>M&&rect.left<W-M&&rect.bottom>M&&rect.top<H-M){return {chapter:obj.ch,offset:obj.start+e.s+lo};}}}return null;}")
            // T350: the same scan for a chunk that is not the visible one, used by page counting.
            append("function firstGlyphIn(obj){if(!obj||!obj.pane)return null;var arr=obj.index||indexPane(obj);")
            append("for(var n=0;n<arr.length;n++){var e=arr[n],lo=0,hi=e.l;")
            append("while(lo<hi){var mid=(lo+hi)>>1,r=document.createRange();r.setStart(e.n,mid);r.setEnd(e.n,mid+1);")
            append("var rect=r.getBoundingClientRect();if(rect.right<=M)lo=mid+1;else hi=mid;}")
            append("if(lo<e.l){var r=document.createRange();r.setStart(e.n,lo);r.setEnd(e.n,lo+1);var rect=r.getBoundingClientRect();")
            append("if(rect.right>M&&rect.left<W-M&&rect.bottom>M&&rect.top<H-M){return {chapter:obj.ch,offset:obj.start+e.s+lo};}}}return null;}")
            append("function progress(){var before=0;for(var i=0;i<current.chapter&&i<chapterChars.length;i++){before+=chapterChars[i];}")
            append("var value=totalChars>0?(before+current.offset)/totalChars:0;return Math.max(0,Math.min(1,value));}")
            append("function send(){if(window.Reader){Reader.moved(page,current.chapter,current.offset,progress(),$generation);}}")
            append("function stable(){if(window.Reader&&Reader.settled){Reader.settled(page,current.chapter,current.offset,progress(),$generation);}}")
            append("function notify(){var p=caretAtPage();if(p){current=p;}if(currentChunk){page=pageOf(currentChunk.ch,currentChunk,localPage);pageCount=totalPageCount();}send();stable();bookmarkOnPage();}")
            // T344: show the target chunk, then find the page whose first visible glyph starts at
            // or before the requested character. A long chunk can be 20 or more columns; walking
            // page starts is reliable where a single off-screen caret rectangle is not.
            append("function showCurrent(obj,page){if(!obj||!obj.pane)return;obj.pane.style.left='0px';obj.pane.style.display='block';obj.pane.style.visibility='visible';obj.pane.style.transform='translate3d('+(-page*W)+'px,0,0)';}")
            append("function pageAtOffset(obj,local){var lo=0,hi=(obj.pages||1)-1,best=0;")
            append("while(lo<=hi){var mid=(lo+hi)>>1;localPage=mid;placePanes();showCurrent(obj,mid);var c=caretAtPage();")
            append("if(!c)break;var start=c.offset-obj.start;")
            append("if(start<=local+4){best=mid;lo=mid+1;}else{hi=mid-1;}}return best;}")
            // T350: pages are the reader's own columns. Each chunk's measured column count is kept
            // even after its DOM is evicted. A hidden pane lets a bookmark or a page jump measure
            // one chunk without moving what is on screen.
            append("function withPane(obj,fn){var created=false,el=obj.pane;")
            append("if(!el){el=document.createElement('div');el.className='chunk';el.setAttribute('data-chapter',obj.ch);el._parcChunk=obj;")
            append("while(obj.el.firstChild)el.appendChild(obj.el.firstChild);book.appendChild(el);obj.pane=el;created=true;}")
            append("var old=el.getAttribute('style');el.style.left='0px';el.style.top='0px';el.style.transform='translate3d(0,0,0)';el.style.display='block';el.style.visibility='hidden';")
            append("try{fn(el);}finally{if(created){while(el.firstChild)obj.el.appendChild(el.firstChild);book.removeChild(el);obj.pane=null;obj.index=null;}else{el.setAttribute('style',old||'');}}}")
            append("function measureOffscreen(obj){if(obj.pane){recordPages(obj);return;}")
            append("withPane(obj,function(){if(!obj.index)indexPane(obj);obj.pages=measurePane(obj);obj.measured=true;updateRate(obj);recordPages(obj);});}")
            append("function pageForOffsetIn(obj,local){if(!obj.measured)measureOffscreen(obj);var lo=0,hi=(obj.pages||1)-1,best=0;")
            append("withPane(obj,function(){while(lo<=hi){var mid=(lo+hi)>>1;obj.pane.style.transform='translate3d('+(-mid*W)+'px,0,0)';var c=firstGlyphIn(obj);if(!c)break;")
            append("var start=c.offset-obj.start;if(start<=local+4){best=mid;lo=mid+1;}else{hi=mid-1;}}});return best;}")
            append("function firstOffsetOnPage(obj,p){if(!obj.measured)measureOffscreen(obj);var off=obj.start;")
            append("withPane(obj,function(){obj.pane.style.transform='translate3d('+(-p*W)+'px,0,0)';var c=firstGlyphIn(obj);if(c)off=c.offset;});return off;}")
            append("function pageOfPosition(ch,off){if(!chapterCount)return 0;ch=Math.max(0,Math.min(chapterCount-1,ch|0));")
            append("var d=chapterData(ch),f=chunkFor(ch,off|0),obj=f.obj;if(!obj.measured)measureOffscreen(obj);")
            append("var local=Math.max(0,Math.min((obj.len||1)-1,(off|0)-obj.start));")
            append("return pagesBeforeChapter(ch)+pagesBeforeChunk(d,f.index)+pageForOffsetIn(obj,local);}")
            append("function bookmarkPageList(){var out=[];for(var i=0;i<bookmarkPositions.length;i++){var b=bookmarkPositions[i];")
            append("out.push(b.c>=0?pageOfPosition(b.c,b.o)+1:b.p+1);}return out;}")
            append("function pruneChunks(){for(var k in chunkCache){if(Math.abs((k|0)-current.chapter)>1)delete chunkCache[k];}}")
            append("function sendBookmarkPages(){if(!countDone)return;var list=bookmarkPageList();pruneChunks();")
            append("if(window.Reader&&Reader.bookmarkPages)Reader.bookmarkPages(JSON.stringify(list),$generation);}")
            // The whole book is counted in slices of about 6 ms so a large book never blocks the
            // reader. The rate keeps improving, so the total converges until the final count.
            append("function countIssue(){if(window.Reader&&Reader.counted)Reader.counted(page,pageCount,false,$generation);}")
            append("function startCount(){if(CONT||counting||countDone)return;counting=true;countChapter=0;countChunk=0;countIssue();requestAnimationFrame(countStep);}")
            append("function countStep(){if(!counting)return;if(settling||incomingChunk){requestAnimationFrame(countStep);return;}")
            append("var deadline=(window.performance&&performance.now)?performance.now()+6:Date.now()+6;")
            append("while(counting){if(countChapter>=chapterCount){counting=false;countDone=true;")
            append("if(currentChunk)page=pageOf(currentChunk.ch,currentChunk,localPage);pageCount=totalPageCount();")
            append("if(window.Reader&&Reader.counted)Reader.counted(page,pageCount,true,$generation);sendBookmarkPages();return;}")
            append("var d=chapterData(countChapter);if(countChunk>=d.chunks.length){pageCacheFull[countChapter]=true;var finished=countChapter;countChapter++;countChunk=0;")
            append("if(Math.abs(finished-current.chapter)>1)delete chunkCache[finished];continue;}")
            append("var obj=d.chunks[countChunk++];if(!obj.measured)measureOffscreen(obj);else recordPages(obj);")
            append("if(currentChunk)page=pageOf(currentChunk.ch,currentChunk,localPage);pageCount=totalPageCount();")
            append("var now=(window.performance&&performance.now)?performance.now():Date.now();if(now>=deadline)break;}")
            append("countIssue();requestAnimationFrame(countStep);}")
            append("function chapterForPage(n){var acc=0;for(var i=0;i<chapterCount;i++){var c=chapterPages(i);if(n<acc+c)return {ch:i,local:n-acc};acc+=c;}")
            append("var last=Math.max(0,chapterCount-1);return {ch:last,local:Math.max(0,chapterPages(last)-1)};}")
            append("function chunkForLocal(d,local){var acc=0;for(var i=0;i<d.chunks.length;i++){var cp=chunkPages(d.chunks[i]);if(local<acc+cp)return {idx:i,local:local-acc};acc+=cp;}")
            append("var li=Math.max(0,d.chunks.length-1);return {idx:li,local:Math.max(0,chunkPages(d.chunks[li])-1)};}")
            append("function goToPage(n){if(!initialized||!chapterCount||CONT)return;cancelMotion();offset=0;incomingChunk=null;")
            append("n=Math.max(0,Math.min(pageCount-1,n|0));var t=chapterForPage(n),d=chapterData(t.ch),f=chunkForLocal(d,t.local),obj=d.chunks[f.idx];")
            append("if(!obj.measured)measureOffscreen(obj);var lp=Math.max(0,Math.min((obj.pages||1)-1,f.local));")
            append("attachWindow(t.ch,f.idx);currentChunk=obj;localPage=lp;placePanes();showCurrent(obj,lp);book.style.transform='translate3d(0,0,0)';apply();")
            append("page=pagesBeforeChapter(t.ch)+pagesBeforeChunk(d,f.idx)+lp;pageCount=totalPageCount();")
            append("var p=caretAtPage();current=p?{chapter:p.chapter,offset:p.offset}:{chapter:t.ch,offset:obj.start};")
            append("send();stable();sendOffset();bookmarkOnPage();}")
            append("function goTo(ch,off){if(!initialized||!chapterCount)return;cancelMotion();offset=0;incomingChunk=null;")
            append("ch=Math.max(0,Math.min(chapterCount-1,ch|0));")
            append("if(CONT){contShow(ch,off|0);page=pagesBeforeChapter(ch);pageCount=totalPageCount();send();stable();bookmarkOnPage();return;}")
            append("var f=chunkFor(ch,off|0);attachWindow(ch,f.index);currentChunk=f.obj;localPage=0;placePanes();showCurrent(f.obj,0);")
            append("book.style.transform='translate3d(0,0,0)';var local=Math.max(0,Math.min((f.obj.len||1)-1,(off|0)-f.obj.start));")
            append("var arr=f.obj.index||indexPane(f.obj),be=null,bo=0;for(var bi=0;bi<arr.length;bi++){var ae=arr[bi];if(ae.s+ae.l>local){be=ae;bo=Math.max(0,local-ae.s);break;}}")
            append("while(be){var at=be.n.nodeValue||'';while(bo<at.length&&/\\s/.test(at.charAt(bo)))bo++;if(bo<at.length)break;var ni=arr.indexOf(be)+1;if(ni<arr.length){be=arr[ni];bo=0;}else{be=null;}}")
            append("var bel=be?be.n.parentElement:null,bpage=bel?Math.round((bel.offsetLeft-M)/W):-1,bhead=bel?/^H[1-6]$/.test(bel.tagName):false;")
            append("var target=pageAtOffset(f.obj,local);")
            append("if(bhead&&bpage>=0&&bpage<f.obj.pages){target=bpage;showCurrent(f.obj,target);var br=bel.getBoundingClientRect();")
            append("if(br.top>=H&&target<f.obj.pages-1)target++;else if(br.bottom<=0&&target>0)target--;}")
            append("localPage=Math.max(0,Math.min((f.obj.pages||1)-1,target));showCurrent(f.obj,localPage);")
            append("page=pageOf(ch,f.obj,localPage);pageCount=totalPageCount();placePanes();showCurrent(f.obj,localPage);")
            append("apply();current={chapter:ch,offset:off|0};")
            append("send();stable();sendOffset();bookmarkOnPage();}")
            append("function turn(direction,damping,stiffness){if(CONT){scrollPages(direction);return;}settle(page+direction,damping,stiffness);}")
            append("window.reader={turn:turn,goTo:goTo,goToPage:goToPage,drag:drag,settle:settle,vscroll:function(dy){if(!CONT)return;var before=viewport.scrollTop,max=viewport.scrollHeight-viewport.clientHeight;if(dy>0&&before>=max-2){scrollPages(1);return;}if(dy<0&&before<=1){scrollPages(-1);return;}viewport.scrollTop=before+dy;},cancel:function(){cancelMotion();sendOffset();},layout:function(){counting=false;countDone=false;countChapter=0;countChunk=0;pageCache={};pageCacheFull={};chunkCache={};attached=[];parked=[];currentChunk=null;incomingChunk=null;book.innerHTML='';measuredChars=0;measuredPages=0;charsPerPage=${Metrics.readerCharsPerPageFallback};var c=current.chapter,o=current.offset;if(CONT){contChapter=-1;measure();contShow(c,o);pageCount=totalPageCount();return;}measure();goTo(c,o);requestAnimationFrame(startCount);},position:function(){return JSON.stringify(current);},pages:function(){return pageCount;},beginSelection:beginSelection,dragSelection:dragSelection,clearSelection:clearSelection,setSpeaking:setSpeaking,clearSpeaking:clearSpeaking,setHighlights:setHighlights,setBookmarks:setBookmarks};")
            append("var initialized=false,prepared=false,readySent=false;")
            append("function initialize(){if(initialized)return;if(prepared)return;prepared=true;applyHighlights(highlightData);tryInitialize(0);}")
            // T341: lay out the saved character, then report ready only once its column is on the
            // page. A late image or a second layout pass gets two more frames; the page is never
            // declared ready at the start of the book. `initialized` lets goTo run; `readySent` is
            // the one-time completion, so retries cannot report twice.
            append("function tryInitialize(attempt){if(readySent)return;initialized=true;measure();goTo(start.chapter,start.offset);")
            if (continuous) {
                append("var cr=(currentChunk&&currentChunk.pane)?rangeAt(currentChunk,start.offset-currentChunk.start).getBoundingClientRect():null;")
                append("if(cr&&cr.width>0&&(cr.bottom<=0||cr.top>=H)){if(attempt<2){requestAnimationFrame(function(){tryInitialize(attempt+1);});return;}}")
                append("readySent=true;if(window.Reader){bookmarkOnPage();Reader.ready(pageCount,page,$generation);}return;")
            }
            append("var section=currentChunk&&currentChunk.pane;")
            append("if(currentChunk&&currentChunk.len){var anchor=rangeAt(currentChunk,start.offset-currentChunk.start).getBoundingClientRect();")
            append("if(anchor.width>0&&(anchor.right<=0||anchor.left>=W)){if(attempt<2){requestAnimationFrame(function(){tryInitialize(attempt+1);});return;}}}")
            append("readySent=true;if(window.Reader){bookmarkOnPage();Reader.ready(pageCount,page,$generation);}requestAnimationFrame(startCount);}")
            append("function loaded(){Promise.resolve(document.fonts?document.fonts.ready:null).then(function(){requestAnimationFrame(initialize);});}")
            append("window.addEventListener('resize',function(){if(initialized)window.reader.layout();});")
            append("if(document.readyState==='complete')loaded();else window.addEventListener('load',loaded,{once:true});")
            append("})();</script></body></html>")
        }
    }

    private fun css(color: Long): String = String.format(Locale.US, "#%06X", color and 0xFFFFFFL)

    private fun rgba(color: Long, alpha: Float): String = String.format(Locale.US, "rgba(%d,%d,%d,%.3f)",
        (color ushr 16) and 0xFF, (color ushr 8) and 0xFF, color and 0xFF, alpha)
}
