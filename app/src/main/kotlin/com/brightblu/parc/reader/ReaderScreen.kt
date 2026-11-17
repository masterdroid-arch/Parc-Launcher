package com.brightblu.parc.reader

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
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
import com.brightblu.parc.core.data.library.PdfLayout
import com.brightblu.parc.core.data.library.ReaderFamily
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

internal enum class ReaderSheet { None, Contents, Marks, Aa, Search, Listen }

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
    if (readyRequest == null || readySettings == null || readyMarks == null) {
        ReaderLoading(request.book.title, skin)
        return
    }
    ReaderSessionHost(readyRequest, skin, onClose, onPosition, library, openPositions,
        settingsStore, readySettings, readyMarks)
}

@Composable
private fun ReaderSessionHost(
    request: ReaderRequest, skin: Skin, onClose: () -> Unit, onPosition: (BookPosition) -> Unit,
    library: LibraryStore, openPositions: ReaderOpenPositionsStore,
    settingsStore: ReaderSettingsStore, initialSettings: ReaderSettings,
    initialMarks: ReaderBookMarks,
) {
    val context = LocalContext.current
    val settings by settingsStore.settings.collectAsState(initial = initialSettings)
    val quotesStore = remember(context) { QuotesStore.create(context) }
    val scope = rememberCoroutineScope()
    // T255: brightness is the reader window's own; it ends with this activity.
    LaunchedEffect(settings.brightness) { (context as? ReaderActivity)?.setReaderBrightness(settings.brightness) }
    var marks by remember(request.book.id) { mutableStateOf(initialMarks) }
    var lightDraft by remember(request.book.id) { mutableStateOf<QuoteDraft?>(null) }
    val demoBook = DemoMode.enabled && ReaderDemo.isDemoBook(request.book)
    LaunchedEffect(request.book.id, demoBook) {
        library.marks(request.book.id).collect { stored -> marks = if (demoBook) ReaderDemo.withHighlight(stored) else stored }
    }
    var doc by remember(request.book.id) { mutableStateOf(ReaderCache.get(request.book)) }
    var pdf by remember(request.book.id) { mutableStateOf<PdfBook?>(null) }
    var comic by remember(request.book.id) { mutableStateOf<ComicBook?>(null) }
    var error by remember(request.book.id) { mutableStateOf<String?>(null) }
    var lastPosition by remember(request.book.id) { mutableStateOf(request.initialPosition) }
    var savedPosition by remember(request.book.id) { mutableStateOf<BookPosition?>(null) }
    var inLibrary by remember(request.book.id) { mutableStateOf(!request.unadded) }
    var adding by remember(request.book.id) { mutableStateOf(false) }
    var addNotice by remember(request.book.id) { mutableStateOf<String?>(null) }
    var contentReady by remember(request.book.id) { mutableStateOf(false) }
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

    LaunchedEffect(request.book.id) {
        if (demoBook) {
            if (doc == null) doc = ReaderDemo.doc(request.book)
        } else if (request.book.format == BookFormat.CBZ) {
            if (comic == null) {
                try {
                    comic = ReaderComicLoader.load(context, request.book)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ReaderBookException) {
                    error = e.message ?: ReaderComicMessages.Missing
                } catch (_: Exception) {
                    error = ReaderComicMessages.Missing
                }
            }
        } else if (request.book.format == BookFormat.PDF) {
            if (pdf == null) {
                try {
                    pdf = ReaderPdfLoader.load(context, request.book)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ReaderBookException) {
                    error = e.message ?: "This PDF could not be opened."
                } catch (_: Exception) {
                    error = ReaderPdfMessages.Missing
                }
            }
        } else if (doc == null) {
            try {
                doc = ReaderLoader.load(context, request.book)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ReaderBookException) {
                error = e.message ?: "This book could not be opened."
            } catch (_: Exception) {
                error = "This book could not be opened. The file may have moved."
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
    ReaderMotionShell(
        request = request,
        skin = skin,
        onClose = { flushPosition(); onClose() },
        colors = readerColors(settings, skin),
        contentReady = contentReady || error != null,
    ) {
        when {
            comicBook != null -> ComicReaderPage(
                book = request.book,
                comic = comicBook,
                settings = settings,
                skin = skin,
                initialPosition = request.initialPosition,
                onPosition = onTurn,
                onLastPage = onLastPage,
                onReady = { contentReady = true },
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
                onReady = { contentReady = true },
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
                onReady = { contentReady = true },
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
            error != null -> ReaderError(error!!, skin, onClose)
            else -> ReaderLoading(request.book.title, skin)
        }
    }
    lightDraft?.let { draft ->
        QuoteEditorSheet(draft, skin, quotesStore) { lightDraft = null }
    }
}

@Composable
private fun ReaderLoading(title: String, skin: Skin) {
    Box(Modifier.fillMaxSize().background(Color(skin.colors.bg.toInt())), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(title, style = readerTitleStyle(skin), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(Metrics.readerRowGapDp.dp))
            BasicText("Opening…", style = readerLabelStyle(skin))
        }
    }
}

@Composable
private fun ReaderError(message: String, skin: Skin, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(skin.colors.bg.toInt())), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = Metrics.readerSheetMaxWidthDp.dp)
            .padding(horizontal = Metrics.readerSheetInsetDp.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Metrics.readerRowGapDp.dp)) {
            BasicText("Cannot open this book", style = readerTitleStyle(skin))
            BasicText(message, style = readerLabelStyle(skin))
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
    var barsVisible by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(ReaderSheet.None) }
    var lastSheet by remember { mutableStateOf(ReaderSheet.None) }
    LaunchedEffect(sheet) { if (sheet != ReaderSheet.None) lastSheet = sheet }
    val displayedSheet = if (sheet != ReaderSheet.None) sheet else lastSheet
    var position by remember(doc.id) { mutableStateOf(ReaderPositionMath.clamp(doc, initialPosition)) }
    var pageCount by remember { mutableIntStateOf(1) }
    var currentPage by remember { mutableIntStateOf(0) }
    var layoutReady by remember(doc.id) { mutableStateOf(false) }
    var selection by remember(doc.id) { mutableStateOf<ReaderSelection?>(null) }
    var noteTarget by remember(doc.id) { mutableStateOf<ReaderSelection?>(null) }
    var translateText by remember(doc.id) { mutableStateOf<String?>(null) }
    var lastTranslateText by remember(doc.id) { mutableStateOf<String?>(null) }
    var defineNotice by remember(doc.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(translateText) { if (translateText != null) lastTranslateText = translateText }
    val displayedTranslate = translateText ?: lastTranslateText
    var pageBookmarked by remember(doc.id) { mutableStateOf(false) }
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
    BackHandler(enabled = sheet != ReaderSheet.None || translateText != null) {
        if (translateText != null) translateText = null else sheet = ReaderSheet.None
    }
    val webView = remember { WebView(context) }
    val latestOnPosition = rememberUpdatedState(onPosition)
    val latestPage = rememberUpdatedState(currentPage)
    val latestCount = rememberUpdatedState(pageCount)
    val dragState = remember(doc.id) { ReaderDragState() }
    LaunchedEffect(layoutReady, pageCount, currentPage) {
        if (layoutReady && currentPage >= pageCount - 1) onLastPage()
    }

    val generation = remember(webView) { AtomicInteger() }
    val checkpoint = rememberUpdatedState(onCheckpoint)
    val bridge = remember(webView, dragState) {
        ReaderBridge(
            onSelection = { raw -> selection = ReaderSelection.parse(raw); defineNotice = null },
            onBookmarkPage = { value -> pageBookmarked = value },
            onReady = { pages, version ->
                webView.post {
                    if (version != generation.get()) return@post
                    pageCount = pages.coerceAtLeast(1)
                    layoutReady = true
                    onReady()
                }
            },
            onMoved = { page, chapter, offset, _, version ->
                if (version == generation.get()) {
                    val next = ReaderPositionMath.clamp(doc, chapter, offset)
                    checkpoint.value(next)
                }
                webView.post {
                    if (version != generation.get()) return@post
                    currentPage = page.coerceAtLeast(0)
                    val next = ReaderPositionMath.clamp(doc, chapter, offset)
                    position = next
                    latestOnPosition.value(next)
                }
            },
            onOffset = { offset -> dragState.offset = offset },
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

    LaunchedEffect(barsVisible, sheet, colors.light) {
        (context as? ReaderActivity)?.setReaderChrome(visible = barsVisible || sheet != ReaderSheet.None, light = colors.light)
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(colors.paper.toInt()))) {
        val width = maxWidth.value
        val height = maxHeight.value
        val highlightColors = remember(skin) { readerHighlightColorsJson(skin) }
        val figtree = remember(context) { ReaderFonts.figtreeBase64(context) }
        val html = remember(doc, settings, width, height, highlightColors) {
            ReaderWebDocument.build(doc, settings, colors, width, height, position, generation.incrementAndGet(),
                marks.bookmarks, marks.highlights, highlightColors, figtree)
        }
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
                if (view.tag != html) {
                    view.tag = html
                    view.setBackgroundColor(colors.paper.toInt())
                    view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
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
            if (sheet != ReaderSheet.None) return
            settleOn(ReaderMotion.turnTarget(latestPage.value, latestCount.value, direction))
            barsVisible = false
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
                            else -> barsVisible = !barsVisible
                        }
                    },
                )
            }
            .pointerInput(sheet) {
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
            })

        AnimatedVisibility(
            visible = barsVisible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(spring(Springs.sheet.damping, Springs.sheet.stiffness)),
            exit = fadeOut(spring(Springs.sheet.damping, Springs.sheet.stiffness)),
        ) {
            ReaderBar(
                title = book.title,
                doc = doc,
                position = position,
                currentPage = currentPage,
                pageCount = pageCount,
                colors = colors,
                skin = skin,
                onContents = { sheet = ReaderSheet.Contents },
                onMarks = { sheet = ReaderSheet.Marks },
                onAa = { sheet = ReaderSheet.Aa },
                bookmarked = pageBookmarked,
                onBookmark = { onToggleBookmark(position) },
                canAddToLibrary = canAddToLibrary,
                adding = adding,
                addNotice = addNotice,
                onAddToLibrary = onAddToLibrary,
                onSearch = { sheet = ReaderSheet.Search },
                onListen = { sheet = ReaderSheet.Listen },
            )
        }

        if (pageBookmarked) {
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
                ReaderSheet.Contents -> ReaderContentsSheet(
                    doc = doc,
                    currentChapter = position.chapter,
                    skin = skin,
                    onDismiss = { sheet = ReaderSheet.None },
                    onEntry = { chapter, offset ->
                        sheet = ReaderSheet.None
                        barsVisible = false
                        webView.evaluateJavascript("window.reader.goTo($chapter,$offset)", null)
                    },
                )
                ReaderSheet.Marks -> ReaderMarksSheet(
                    book = book,
                    doc = doc,
                    marks = marks,
                    isPdf = false,
                    pageCount = pageCount,
                    skin = skin,
                    onDismiss = { sheet = ReaderSheet.None },
                    onJump = { target ->
                        sheet = ReaderSheet.None
                        barsVisible = false
                        webView.evaluateJavascript("window.reader.goTo(${target.chapter},${target.characterOffset})", null)
                    },
                    onDeleteBookmark = onDeleteBookmark,
                    onDeleteHighlight = onDeleteHighlight,
                    onAddToLightly = onAddToLightly,
                )
                ReaderSheet.Search -> ReaderSearchSheet(
                    skin = skin,
                    doc = doc,
                    unavailable = null,
                    emptyHint = null,
                    search = { query -> withContext(Dispatchers.Default) { ReaderSearch.inDoc(doc, query) } },
                    onDismiss = { sheet = ReaderSheet.None },
                    onJump = { hit ->
                        sheet = ReaderSheet.None
                        barsVisible = false
                        webView.evaluateJavascript("window.reader.goTo(${hit.chapter},${hit.offset})", null)
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
private fun ReaderBar(
    title: String,
    doc: ReaderBookDoc,
    position: BookPosition,
    currentPage: Int,
    pageCount: Int,
    colors: ReaderColors,
    skin: Skin,
    onContents: () -> Unit,
    onMarks: () -> Unit,
    onAa: () -> Unit,
    bookmarked: Boolean,
    onBookmark: () -> Unit,
    canAddToLibrary: Boolean,
    adding: Boolean,
    addNotice: String?,
    onAddToLibrary: () -> Unit,
    onSearch: () -> Unit,
    onListen: () -> Unit,
) {
    val percent = ReaderPositionMath.progressPercent(doc, position)
    // T271: the status-bar strip is solid, so page text never shows behind the clock and icons.
    Column(Modifier.fillMaxWidth().background(Color(skin.colors.surface.toInt()).copy(alpha = Metrics.readerBarScrimAlpha))) {
    Spacer(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars)
        .background(Color(skin.colors.surface.toInt())))
    Column(Modifier.fillMaxWidth()
        .padding(horizontal = Metrics.readerBarSideInsetDp.dp, vertical = Metrics.readerBarTopInsetDp.dp)
        .semantics { contentDescription = "Reading bar" }) {
        Row(Modifier.fillMaxWidth().heightIn(min = Metrics.readerBarMinHeightDp.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                BasicText(title, style = readerTitleStyle(skin), maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicText(addNotice ?: "Page ${currentPage + 1} of ${pageCount.coerceAtLeast(1)} · $percent% read",
                    style = readerLabelStyle(skin).copy(
                        color = Color((if (addNotice != null) skin.colors.accent else skin.colors.textSecondary).toInt())),
                    maxLines = 1)
            }
            if (canAddToLibrary) {
                ReaderBarAction(if (adding) "Adding…" else "Add to library", skin, onAddToLibrary, enabled = !adding)
            }
            ReaderBarAction("Listen", skin, onListen)
            ReaderBarAction("Find", skin, onSearch)
            ReaderBarAction(if (bookmarked) "Bookmarked" else "Bookmark", skin, onBookmark)
            ReaderBarAction("Contents", skin, onContents)
            ReaderBarAction("Marks", skin, onMarks)
            ReaderBarAction("Aa", skin, onAa)
        }
        Spacer(Modifier.height(Metrics.readerProgressGapDp.dp))
        Box(Modifier.fillMaxWidth().height(Metrics.readerProgressHeightDp.dp)
            .background(Color(skin.colors.lineBase.toInt()))) {
            Box(Modifier.fillMaxWidth(percent / 100f).height(Metrics.readerProgressHeightDp.dp)
                .background(Color(skin.colors.accent.toInt())))
        }
    }
    }
}

@Composable
internal fun ReaderBarAction(label: String, skin: Skin, onClick: () -> Unit, enabled: Boolean = true) {
    Box(Modifier.heightIn(min = Metrics.readerTouchTargetDp.dp)
        .widthIn(min = Metrics.readerTouchTargetDp.dp)
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
    currentChapter: Int,
    skin: Skin,
    onDismiss: () -> Unit,
    onEntry: (Int, Int) -> Unit,
) {
    val entries = remember(doc) { doc.tocOrChapters() }
    ReaderSheetFrame("Contents", skin, onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(Metrics.readerContentsHeightFraction),
            verticalArrangement = Arrangement.spacedBy(Metrics.readerRowGapDp.dp)) {
            items(count = entries.size, key = { "${entries[it].chapter}:${entries[it].offset}:${it}" }) { index ->
                val entry = entries[index]
                val percent = ReaderPositionMath.progressPercent(doc, BookPosition(entry.chapter, entry.offset))
                val selected = entry.chapter == currentChapter
                Column(Modifier.fillMaxWidth()
                    .background(Color(skin.colors.surface.toInt()), RoundedCornerShape(Metrics.readerSheetCornerDp.dp))
                    .border(Metrics.readerHairlineDp.dp,
                        Color((if (selected) skin.colors.accent else skin.colors.lineBase).toInt()),
                        RoundedCornerShape(Metrics.readerSheetCornerDp.dp))
                    .clickable(role = Role.Button) { onEntry(entry.chapter, entry.offset) }
                    .padding(horizontal = Metrics.readerChipInsetDp.dp, vertical = Metrics.readerRowGapDp.dp)) {
                    BasicText(entry.title, style = readerValueStyle(skin), maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                    BasicText("Chapter ${entry.chapter + 1} · $percent%", style = readerLabelStyle(skin))
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
                ReaderChoiceRow("Invert pages", listOf(false to "Off", true to "On"),
                    settings.pdfInvert, skin) { invert -> onChange { it.copy(pdfInvert = invert) } }
            } else {
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
                ReaderChoiceRow("Font", listOf(ReaderFamily.Serif to "Serif", ReaderFamily.Sans to "Sans",
                    ReaderFamily.Figtree to "Figtree"),
                    settings.family, skin) { family -> onChange { it.copy(family = family) } }
            }
            ReaderChoiceRow(if (isPdf) "Background" else "Page colour", listOf(
                ReaderPageColor.Light to "Light", ReaderPageColor.Sepia to "Sepia",
                ReaderPageColor.Dark to "Dark", ReaderPageColor.Black to "Black",
            ), settings.pageColor, skin) { page -> onChange { it.copy(pageColor = page) } }
            ReaderChoiceRow("Night", listOf(false to "Off", true to "On"),
                settings.night, skin) { night -> onChange { it.copy(night = night) } }
            ReaderBrightnessRow(settings.brightness, skin) { value -> onChange { it.withBrightness(value) } }
            if (showVolumeKeys) {
                ReaderChoiceRow("Volume keys turn pages", listOf(false to "Off", true to "On"),
                    settings.volumeKeys, skin) { on -> onChange { it.copy(volumeKeys = on) } }
            }
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
    private val onReady: (Int, Int) -> Unit,
    private val onMoved: (Int, Int, Int, Float, Int) -> Unit,
    private val onOffset: (Float) -> Unit = {},
    private val onSelection: (String) -> Unit = {},
    private val onBookmarkPage: (Boolean) -> Unit = {},
) {
    @JavascriptInterface fun ready(pages: Int, generation: Int) = onReady(pages, generation)

    @JavascriptInterface fun moved(page: Int, chapter: Int, offset: Int, progress: Float, generation: Int) =
        onMoved(page, chapter, offset, progress, generation)

    @JavascriptInterface fun offset(offset: Float) = onOffset(offset)

    /** T236: the selected range, handle positions and snippets from the reading script. */
    @JavascriptInterface fun selection(raw: String) = onSelection(raw)

    /** T236: whether the current page already has a bookmark, for the quiet ribbon. */
    @JavascriptInterface fun bookmarkPage(value: Boolean) = onBookmarkPage(value)
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

internal object ReaderWebDocument {
    fun build(doc: ReaderBookDoc, settings: ReaderSettings, colors: ReaderColors,
        width: Float, height: Float, position: BookPosition, generation: Int = 0,
        bookmarks: List<ReaderBookmark> = emptyList(), highlights: List<ReaderHighlight> = emptyList(),
        highlightColors: String = "{}", figtreeBase64: String = ""): String {
        if (width <= 1f || height <= 1f) return "<html><body></body></html>"
        val margin = (width * settings.marginFraction).coerceAtLeast(0f)
        val contentWidth = (width - margin * 2f).coerceAtLeast(1f)
        val gap = margin * 2f
        val fontFamily = when (settings.family) {
            ReaderFamily.Serif -> "serif"
            ReaderFamily.Sans -> "sans-serif"
            ReaderFamily.Figtree -> "'ParcFigtree',sans-serif"
        }
        val paper = css(colors.paper)
        val ink = css(colors.ink)
        val secondary = css(colors.secondary)
        val accent = css(if (colors.light) colors.secondary else colors.accent)
        val speaking = rgba(colors.accent, Metrics.readerSpeakingAlpha)
        val start = ReaderPositionMath.clamp(doc, position)
        val sections = doc.chapters.joinToString("") { chapter ->
            "<section id=\"c${chapter.index}\" data-chapter=\"${chapter.index}\">${chapter.html}</section>"
        }
        return buildString {
            append("<!doctype html><html><head><meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no\">")
            append("<style>")
            if (settings.family == ReaderFamily.Figtree && figtreeBase64.isNotEmpty()) {
                append("@font-face{font-family:'ParcFigtree';font-weight:100 900;")
                append("src:url(data:font/ttf;base64,$figtreeBase64) format('truetype');}")
            }
            append("html,body{margin:0;padding:0;background:$paper;color:$ink;-webkit-text-size-adjust:100%;text-size-adjust:100%;}")
            append("#viewport{position:fixed;left:0;top:0;width:${width}px;height:${height}px;overflow:hidden;}")
            append("#book{box-sizing:border-box;width:${width}px;height:${height}px;padding:${margin}px;")
            append("column-width:${contentWidth}px;column-gap:${gap}px;column-fill:auto;")
            append("font-family:$fontFamily;font-size:${settings.fontSp}px;line-height:${settings.lineSpacing};overflow-wrap:break-word;}")
            append("#book section{display:block;}")
            append("#book p{margin:0 0 0.7em;}")
            append("#book h1,#book h2,#book h3,#book h4,#book h5,#book h6{margin:0 0 0.45em;line-height:1.22;font-weight:600;break-after:avoid;}")
            append("#book h1{font-size:1.5em;}#book h2{font-size:1.3em;}#book h3{font-size:1.15em;}")
            append("#book img{max-width:100%;height:auto;break-inside:avoid;}")
            append("#book a{color:$accent;text-decoration:underline;text-decoration-thickness:1px;}")
            append("#book blockquote{margin:0 0 0.7em;padding-left:0.8em;border-left:1px solid $secondary;color:$secondary;}")
            append("#book ul,#book ol{margin:0 0 0.7em 1.1em;padding:0;}")
            append("#book li{margin:0 0 0.25em;}")
            append("#book pre{white-space:pre-wrap;font-family:inherit;margin:0;}")
            append("#book table{border-collapse:collapse;max-width:100%;}")
            append("#book td,#book th{border:1px solid $secondary;padding:0.15em 0.3em;}")
            append("</style></head><body><div id=\"viewport\"><div id=\"book\">$sections</div></div>")
            append("<script>(function(){")
            append("var book=document.getElementById('book');")
            append("var W=$width,H=$height,M=$margin;")
            append("var page=0,pageCount=1,offset=0,raf=0,settling=false,velocity=0,lastFrame=0,targetOffset=0;")
            append("var current={chapter:${start.chapter},offset:${start.characterOffset}};")
            append("var start={chapter:${start.chapter},offset:${start.characterOffset}};")
            append("var chapterLengths=[];for(var i=0;i<book.children.length;i++){chapterLengths.push(book.children[i].textContent.length);}")
            append("var totalChars=0;for(var i=0;i<chapterLengths.length;i++){totalChars+=chapterLengths[i];}")
            append("var bookmarkPositions=${ReaderMarksJson.renderBookmarks(bookmarks)};")
            append("var highlightData=${ReaderMarksJson.renderHighlights(highlights)};")
            append("var highlightColors=$highlightColors;")
            append("var speakColor='$speaking';")
            append("var selection=null,speakMark=null,speakRange=null;")
            append("function rangeAt(section,offset){")
            append("var walker=document.createTreeWalker(section,NodeFilter.SHOW_TEXT,null,false);")
            append("var remaining=Math.max(0,offset),node,last=null;")
            append("while((node=walker.nextNode())){var len=node.nodeValue.length;")
            append("if(remaining<len){var r=document.createRange();r.setStart(node,remaining);r.setEnd(node,remaining+1);return r;}")
            append("remaining-=len;if(len>0)last=node;}")
            append("if(last){var r=document.createRange();r.setStart(last,last.nodeValue.length-1);r.setEnd(last,last.nodeValue.length);return r;}")
            append("var r=document.createRange();r.selectNodeContents(section);r.collapse(false);return r;}")
            // T236: map a point or a character range inside one chapter, then publish the selected
            // range and its handle positions back to Compose. All values are viewport fractions.
            append("function sectionOf(node){while(node&&node!==book){if(node.parentNode===book)return node;node=node.parentNode;}return null;}")
            append("function offsetInSection(section,node,nodeOffset){var r=document.createRange();r.selectNodeContents(section);r.setEnd(node,nodeOffset);return r.toString().length;}")
            append("function pointOffset(x,y){var r=null;if(document.caretRangeFromPoint){r=document.caretRangeFromPoint(x,y);}else if(document.caretPositionFromPoint){var p=document.caretPositionFromPoint(x,y);if(p){r=document.createRange();r.setStart(p.offsetNode,p.offset);r.collapse(true);}}")
            append("if(!r)return null;var section=sectionOf(r.startContainer);if(!section)return null;var ch=Array.prototype.indexOf.call(book.children,section);if(ch<0)return null;return {chapter:ch,offset:offsetInSection(section,r.startContainer,r.startOffset)};}")
            append("function wordBounds(ch,off){var text=book.children[ch].textContent;var s=Math.max(0,Math.min(text.length,off));var e=s;while(s>0&&!/\\s/.test(text.charAt(s-1)))s--;while(e<text.length&&!/\\s/.test(text.charAt(e)))e++;if(e<=s&&e<text.length)e++;return {start:s,end:e};}")
            append("function rangeWithin(section,s,e){var walker=document.createTreeWalker(section,NodeFilter.SHOW_TEXT,null,false),node,pos=0,startNode=null,startOffset=0,endNode=null,endOffset=0;")
            append("while((node=walker.nextNode())){var len=node.nodeValue.length;if(startNode===null&&s<=pos+len){startNode=node;startOffset=Math.max(0,s-pos);}if(endNode===null&&e<=pos+len){endNode=node;endOffset=Math.max(0,e-pos);break;}pos+=len;}")
            append("if(startNode===null){startNode=section;startOffset=section.childNodes.length;}if(endNode===null){endNode=section;endOffset=section.childNodes.length;}")
            append("var r=document.createRange();r.setStart(startNode,startOffset);r.setEnd(endNode,endOffset);return r;}")
            append("function publishSelection(){if(!selection){if(window.Reader&&Reader.selection)Reader.selection('');return;}var section=book.children[selection.chapter];if(!section){clearSelection();return;}")
            append("var len=section.textContent.length;selection.start=Math.max(0,Math.min(len,selection.start));selection.end=Math.max(selection.start+1,Math.min(len,selection.end));if(selection.end<=selection.start){clearSelection();return;}")
            append("var r=rangeWithin(section,selection.start,selection.end);var list=r.getClientRects(),rects=[],first=null,last=null;for(var i=0;i<list.length;i++){var q=list[i];if(q.width<=0||q.height<=0)continue;rects.push([q.left/W,q.top/H,q.right/W,q.bottom/H]);if(!first)first=q;last=q;}")
            append("var payload={chapter:selection.chapter,startOffset:selection.start,endOffset:selection.end,text:section.textContent.substring(selection.start,selection.end),rects:rects,start:first?[first.left/W,first.bottom/H]:[0,0],end:last?[last.right/W,last.bottom/H]:[0,0]};if(window.Reader&&Reader.selection)Reader.selection(JSON.stringify(payload));}")
            append("function beginSelection(rx,ry){var p=pointOffset(rx*W,ry*H);if(!p){clearSelection();return;}var b=wordBounds(p.chapter,p.offset);selection={chapter:p.chapter,start:b.start,end:b.end};publishSelection();}")
            append("function dragSelection(which,rx,ry){if(!selection)return;var p=pointOffset(rx*W,ry*H);if(!p||p.chapter!==selection.chapter)return;if(which==='start')selection.start=Math.min(p.offset,selection.end-1);else selection.end=Math.max(p.offset,selection.start+1);publishSelection();}")
            append("function clearSelection(){selection=null;if(window.Reader&&Reader.selection)Reader.selection('');}")
            append("function removeSpeakMark(){if(!speakMark)return;var p=speakMark.parentNode;if(!p){speakMark=null;return;}while(speakMark.firstChild)p.insertBefore(speakMark.firstChild,speakMark);p.removeChild(speakMark);p.normalize();speakMark=null;}")
            append("function drawSpeaking(){if(!speakRange)return;var ch=speakRange.c,start=speakRange.s,end=speakRange.e;var section=book.children[ch];if(!section)return;var len=section.textContent.length;var s=Math.max(0,Math.min(len,start));var e=Math.max(s+1,Math.min(len,end));if(e<=s)return;var r=rangeWithin(section,s,e);var frag=r.extractContents();var mark=document.createElement('mark');mark.setAttribute('data-parc-speak','1');mark.style.backgroundColor=speakColor;mark.style.color='inherit';mark.style.padding='0';mark.style.borderRadius='2px';mark.appendChild(frag);r.insertNode(mark);speakMark=mark;}")
            append("function setSpeaking(ch,start,end){speakRange={c:ch,s:start,e:end};removeSpeakMark();drawSpeaking();}")
            append("function clearSpeaking(){speakRange=null;removeSpeakMark();}")
            append("function clearMarks(){if(!book.querySelectorAll||!document.createElement)return;var marks=book.querySelectorAll('mark[data-parc-mark]');for(var i=marks.length-1;i>=0;i--){var m=marks[i],p=m.parentNode;while(m.firstChild)p.insertBefore(m.firstChild,m);p.removeChild(m);p.normalize();}}")
            append("function applyHighlights(list){removeSpeakMark();clearMarks();var sorted=(list||[]).slice().sort(function(a,b){return a.c-b.c||a.s-b.s;});for(var i=0;i<sorted.length;i++){var h=sorted[i],section=book.children[h.c];if(!section)continue;var len=section.textContent.length,s=Math.max(0,Math.min(len,h.s)),e=Math.max(s+1,Math.min(len,h.e));if(e<=s)continue;")
            append("var r=rangeWithin(section,s,e),frag=r.extractContents(),mark=document.createElement('mark');mark.setAttribute('data-parc-mark','1');mark.style.backgroundColor=(highlightColors[h.t]||'rgba(231,211,162,0.38)');mark.style.color='inherit';mark.style.padding='0';mark.style.borderRadius='2px';mark.appendChild(frag);r.insertNode(mark);}if(speakRange)drawSpeaking();}")
            append("function setHighlights(list){highlightData=list||[];applyHighlights(highlightData);if(initialized){measure();var p=current;goTo(p.chapter,p.offset);notify();}}")
            append("function bookmarkOnPage(){var section=book.children[current.chapter];if(!section)return false;for(var i=0;i<bookmarkPositions.length;i++){var b=bookmarkPositions[i];if(b.c!==current.chapter)continue;var r=rangeAt(section,b.o),rect=r.getBoundingClientRect();if(Math.floor((rect.left-M+1)/W)===page)return true;}return false;}")
            append("function setBookmarks(list){bookmarkPositions=list||[];if(initialized)notify();}")
            append("function apply(){book.style.transform='translate3d('+(-page*W+offset)+'px,0,0)';}")
            append("function cancelMotion(){if(raf){cancelAnimationFrame(raf);raf=0;}settling=false;}")
            append("function sendOffset(){if(window.Reader){Reader.offset(offset);}}")
            append("function drag(value){cancelMotion();offset=value;apply();sendOffset();}")
            append("function settle(target,damping,stiffness){if(!initialized)return;cancelMotion();target=Math.max(0,Math.min(pageCount-1,target|0));targetOffset=(page-target)*W;")
            append("if(target===page&&Math.abs(offset)<0.5){offset=0;apply();sendOffset();return;}")
            append("settling=true;velocity=0;lastFrame=0;")
            append("function frame(now){if(!settling)return;if(lastFrame===0)lastFrame=now;")
            append("var dt=Math.min(0.032,(now-lastFrame)/1000);lastFrame=now;var steps=Math.max(1,Math.ceil(dt/0.004));var h=dt/steps;")
            append("var k=stiffness,c=2*damping*Math.sqrt(k);")
            append("for(var i=0;i<steps;i++){var a=-k*(offset-targetOffset)-c*velocity;velocity+=a*h;offset+=velocity*h;}")
            append("if(Math.abs(offset-targetOffset)<0.5&&Math.abs(velocity)<8){offset=0;page=target;apply();sendOffset();notify();settling=false;raf=0;return;}")
            append("apply();sendOffset();raf=requestAnimationFrame(frame);}raf=requestAnimationFrame(frame);}")
            append("function measure(){cancelMotion();offset=0;book.style.transform='translate3d(0,0,0)';")
            append("var right=M;for(var i=0;i<book.children.length;i++){var br=book.children[i].getBoundingClientRect();if(br.width>0&&br.right>right)right=br.right;}")
            append("if(right<=M)right=book.scrollWidth;")
            append("pageCount=Math.max(1,Math.ceil((right-M+1)/W));page=Math.min(page,pageCount-1);}")
            // Hit-testing the margin can return the book element (or the previous page), so
            // find the first visible glyph in column order instead of caretRangeFromPoint.
            append("function caretAtPage(){")
            append("for(var ch=0;ch<book.children.length;ch++){var section=book.children[ch],count=0;")
            append("var bounds=section.getBoundingClientRect();if(bounds.right<=M||bounds.left>=W-M)continue;")
            append("if(!section.textContent.length)return {chapter:ch,offset:0};")
            append("var walker=document.createTreeWalker(section,NodeFilter.SHOW_TEXT,null,false),node;")
            append("while((node=walker.nextNode())){var len=node.nodeValue.length,lo=0,hi=len;")
            append("while(lo<hi){var mid=(lo+hi)>>1,r=document.createRange();r.setStart(node,mid);r.setEnd(node,mid+1);")
            append("var rect=r.getBoundingClientRect();if(rect.right<=M)lo=mid+1;else hi=mid;}")
            append("if(lo<len){var r=document.createRange();r.setStart(node,lo);r.setEnd(node,lo+1);var rect=r.getBoundingClientRect();")
            append("if(rect.right>M&&rect.left<W-M&&rect.bottom>M&&rect.top<H-M)return {chapter:ch,offset:count+lo};}")
            append("count+=len;}}return null;}")
            append("function progress(){var before=0;for(var i=0;i<current.chapter&&i<chapterLengths.length;i++){before+=chapterLengths[i];}")
            append("var value=totalChars>0?(before+current.offset)/totalChars:0;return Math.max(0,Math.min(1,value));}")
            append("function send(){if(window.Reader){Reader.moved(page,current.chapter,current.offset,progress(),$generation);}}")
            append("function notify(){var p=caretAtPage();if(p){current=p;}send();if(window.Reader&&Reader.bookmarkPage)Reader.bookmarkPage(bookmarkOnPage());}")
            append("function goTo(ch,off){if(!initialized||!book.children.length)return;cancelMotion();offset=0;")
            append("ch=Math.max(0,Math.min(book.children.length-1,ch|0));var section=book.children[ch];")
            append("book.style.transform='translate3d(0,0,0)';var r=rangeAt(section,off|0);var rect=r.getBoundingClientRect();if(!section.textContent.length)rect=section.getBoundingClientRect();")
            append("var target=Math.floor((rect.left-M+1)/W);page=Math.max(0,Math.min(pageCount-1,target));apply();")
            append("current={chapter:ch,offset:off|0};send();sendOffset();}")
            append("function turn(direction,damping,stiffness){var next=page+direction;if(next<0||next>=pageCount)return;settle(next,damping,stiffness);}")
            append("window.reader={turn:turn,goTo:goTo,drag:drag,settle:settle,cancel:function(){cancelMotion();sendOffset();},layout:function(){var p=current;measure();goTo(p.chapter,p.offset);},position:function(){return JSON.stringify(current);},pages:function(){return pageCount;},beginSelection:beginSelection,dragSelection:dragSelection,clearSelection:clearSelection,setSpeaking:setSpeaking,clearSpeaking:clearSpeaking,setHighlights:setHighlights,setBookmarks:setBookmarks};")
            append("var initialized=false;")
            append("function initialize(){if(initialized)return;initialized=true;applyHighlights(highlightData);measure();goTo(start.chapter,start.offset);notify();if(window.Reader){Reader.ready(pageCount,$generation);}}")
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
