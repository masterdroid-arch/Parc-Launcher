package com.brightblu.parc

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.Image
import com.brightblu.parc.core.data.library.Book
import com.brightblu.parc.core.data.library.BookCoverStyle
import com.brightblu.parc.core.data.library.BookFiles
import com.brightblu.parc.core.data.library.BookFolder
import com.brightblu.parc.core.data.library.BookFolderStore
import com.brightblu.parc.core.data.library.BookFormat
import com.brightblu.parc.core.data.library.BookIndex
import com.brightblu.parc.core.data.library.BookIndexRules
import com.brightblu.parc.core.data.library.BookIndexStore
import com.brightblu.parc.core.data.library.BookMetadataLoader
import com.brightblu.parc.core.data.library.BookTreeScanner
import com.brightblu.parc.core.data.library.FoundBook
import com.brightblu.parc.core.data.library.LibraryShelf
import com.brightblu.parc.core.data.library.ShelfRuleKind
import com.brightblu.parc.core.data.library.ShelfRule
import com.brightblu.parc.core.data.library.LibraryShelfLimits
import com.brightblu.parc.core.data.library.LibraryAutoAdd
import com.brightblu.parc.core.data.library.LibraryShelfIds
import com.brightblu.parc.core.data.library.LibraryShelfRules
import com.brightblu.parc.core.data.library.LibraryShelfSort
import com.brightblu.parc.core.data.library.LibraryShelfTab
import com.brightblu.parc.core.data.library.LibraryShelvesDocument
import com.brightblu.parc.core.data.library.LibraryStore
import com.brightblu.parc.core.data.library.stableBookId
import com.brightblu.parc.reader.ReaderContract
import com.brightblu.parc.reader.ReaderMotion
import com.brightblu.parc.feature.settings.ui.SettingKind
import com.brightblu.parc.feature.settings.ui.SettingRow
import com.brightblu.parc.feature.settings.ui.SettingsCard
import com.brightblu.parc.feature.settings.ui.SettingsDivider
import com.brightblu.parc.feature.settings.ui.settingsText
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.ParcDialogArea
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.readableAccent
import com.brightblu.parc.model.Springs
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** T206: the Library page id. Lowercase and stable, because it is part of the saved hub format. */
internal object LibraryPage {
    const val Id = "library"
}

/** The selected books, provided where the hub is drawn. Null only in previews. */
internal val LocalLibraryStore = staticCompositionLocalOf<LibraryStore?> { null }

internal enum class LibrarySort(val label: String) {
    Newest("Newest"),
    Name("Name"),
    Size("Size"),
}

internal data class LibraryFindRow(val found: FoundBook, val inLibrary: Boolean)

/** T226: one folder's rows under a collapsible heading. */
internal data class LibraryFindGroup(val folder: String, val rows: List<LibraryFindRow>)

internal data class LibraryApplyPlan(val add: List<FoundBook>, val remove: List<Book>) {
    val changeCount: Int get() = add.size + remove.size
}

internal data class LibraryShelfPlan(
    val columns: Int,
    val rows: Int,
    val coverWidthDp: Float,
    val coverHeightDp: Float,
    val labelHeightDp: Float,
)

/** T227: the row-major place of the "+" tile in the shelf grid. */
internal data class LibraryShelfSlot(val index: Int, val row: Int, val column: Int)

/** The pure side of the shelf: its size from the panel, its order, and where the "+" tile sits. */
internal object LibraryLayout {
    /** A key that cannot collide with a book id (those are SHA-256 hex). */
    const val AddTileKey = "library-add-books"

    fun rows(size: HubSize): Int = when (size) {
        HubSize.Compact -> 1
        HubSize.Standard -> 1
        HubSize.Tall -> 2
    }

    /** The tile is the first free cell after the last book, so it moves as books are added. */
    fun addTileIndex(bookCount: Int): Int = bookCount.coerceAtLeast(0)

    fun addTileSlot(bookCount: Int, columns: Int): LibraryShelfSlot {
        val index = addTileIndex(bookCount)
        val cols = columns.coerceAtLeast(1)
        return LibraryShelfSlot(index, index / cols, index % cols)
    }

    /**
     * Covers keep the reader's 5:7 shape. The height comes from the panel, then the width follows it,
     * and a wider panel adds more columns instead of stretching the same cover.
     */
    fun plan(size: HubSize, contentHeightDp: Float, widthDp: Float, fontScale: Float = 1f): LibraryShelfPlan {
        val scale = fontScale.coerceAtLeast(1f)
        val label = Metrics.bookShelfLabelHeightDp * scale
        val gap = Metrics.bookShelfGapDp
        // T271: Tall asks for two rows, but never at the price of tiny covers; when the panel is too
        // short for two readable rows it keeps one row of the larger covers (the page scrolls).
        var rows = rows(size)
        if (rows > 1 && (contentHeightDp - gap * (rows - 1) - label * rows) / rows < Metrics.bookShelfTallMinCoverDp) rows = 1
        val available = (contentHeightDp - gap * (rows - 1) - label * rows)
            .coerceAtLeast(Metrics.bookShelfCoverMinDp * rows)
        var height = (available / rows).coerceIn(Metrics.bookShelfCoverMinDp, Metrics.bookShelfCoverMaxDp)
        var width = height * Metrics.bookShelfAspect
        if (width > widthDp.coerceAtLeast(1f)) {
            width = widthDp.coerceAtLeast(1f)
            height = width / Metrics.bookShelfAspect
        }
        val columns = ((widthDp + gap) / (width + gap)).toInt().coerceAtLeast(1)
        return LibraryShelfPlan(columns, rows, width, height, label)
    }
}

/** The pure side of Find books: type labels, filtering, sorting, and what Apply changes. */
internal object LibraryFind {
    val types: List<BookFormat> = listOf(BookFormat.PDF, BookFormat.EPUB, BookFormat.TXT, BookFormat.CBZ)

    fun typeLabel(format: BookFormat): String = when (format) {
        BookFormat.PDF -> "PDF"
        BookFormat.EPUB -> "EPUB"
        BookFormat.TXT -> "Text"
        BookFormat.CBZ -> "Comics"
    }

    /** T226: the tiny badge before a row's title; short enough for a narrow row. */
    fun badgeLabel(format: BookFormat): String = when (format) {
        BookFormat.PDF -> "PDF"
        BookFormat.EPUB -> "EPUB"
        BookFormat.TXT -> "TXT"
        BookFormat.CBZ -> "CBZ"
    }

    fun toggleType(types: Set<BookFormat>, format: BookFormat): Set<BookFormat> =
        if (format in types) types - format else types + format

    /**
     * T226: a selected row is never hidden by a filter change. [keepSelected] stays visible even when
     * its format is switched off or the search no longer matches; an ordinary row obeys both.
     */
    fun rows(found: List<FoundBook>, library: List<Book>, types: Set<BookFormat>, query: String,
        sort: LibrarySort, keepSelected: Set<String> = emptySet()): List<LibraryFindRow> {
        val inLibrary = library.mapTo(hashSetOf()) { it.uri }
        val needle = query.trim()
        return found.asSequence()
            .filter { it.format in types || it.uri in keepSelected }
            .filter { needle.isEmpty() || it.uri in keepSelected ||
                it.name.contains(needle, ignoreCase = true) || it.folderLabel.contains(needle, ignoreCase = true) }
            .sortedWith(comparator(sort))
            .map { LibraryFindRow(it, it.uri in inLibrary) }
            .toList()
    }

    /** Only the shown results may change: a library book outside this search is never removed by Apply. */
    fun plan(found: List<FoundBook>, library: List<Book>, types: Set<BookFormat>, query: String,
        sort: LibrarySort, selectedUris: Set<String>, keepSelected: Set<String> = emptySet()): LibraryApplyPlan {
        val rows = rows(found, library, types, query, sort, keepSelected)
        val byUri = library.associateBy { it.uri }
        val add = rows.filter { !it.inLibrary && it.found.uri in selectedUris }.map { it.found }
        val remove = rows.filter { it.inLibrary && it.found.uri !in selectedUris }.mapNotNull { byUri[it.found.uri] }
        return LibraryApplyPlan(add, remove)
    }

    /** T226: results grouped under their folder, with folders in a calm alphabetical order. */
    fun groups(rows: List<LibraryFindRow>): List<LibraryFindGroup> {
        val byFolder = linkedMapOf<String, MutableList<LibraryFindRow>>()
        rows.forEach { row ->
            val folder = row.found.folderLabel.ifBlank { "Shared storage" }
            byFolder.getOrPut(folder) { mutableListOf() }.add(row)
        }
        return byFolder.entries.sortedBy { it.key.lowercase(Locale.ROOT) }
            .map { LibraryFindGroup(it.key, it.value) }
    }

    fun toggleFolder(collapsed: Set<String>, folder: String): Set<String> =
        if (folder in collapsed) collapsed - folder else collapsed + folder

    fun addLabel(addCount: Int): String = if (addCount > 0) "Add $addCount" else "Add"

    fun selectedLabel(count: Int): String = when (count) {
        0 -> "Nothing selected"
        1 -> "1 selected"
        else -> "$count selected"
    }

    /** T271: the count says what the button will do, so "5 selected" never sits beside "Add 3". */
    fun summaryLabel(selected: Int, newCount: Int, removeCount: Int = 0): String {
        val already = (selected - newCount).coerceAtLeast(0)
        val parts = buildList {
            if (newCount > 0) add("$newCount new")
            if (already > 0) add("$already already in library")
            if (removeCount > 0) add("$removeCount to remove")
        }
        return if (parts.isEmpty()) selectedLabel(selected) else parts.joinToString(", ")
    }

    fun emptyMessage(foundCount: Int, shownCount: Int, types: Set<BookFormat>, query: String): String = when {
        types.isEmpty() -> "Pick a format to see your books."
        query.trim().isNotEmpty() && shownCount == 0 -> "No books match “${query.trim()}”."
        foundCount == 0 -> "No books here yet. Choose a folder or scan again."
        else -> "No books to show."
    }

    fun secondaryLine(found: FoundBook, zone: ZoneId = ZoneId.systemDefault()): String =
        "${LibraryFormat.size(found.sizeBytes)} · ${LibraryFormat.date(found.modifiedAt, zone)}"

    private fun comparator(sort: LibrarySort): Comparator<FoundBook> = when (sort) {
        LibrarySort.Newest -> compareByDescending<FoundBook> { it.modifiedAt }
            .thenBy { it.name.lowercase(Locale.ROOT) }
        LibrarySort.Name -> compareBy<FoundBook> { it.name.lowercase(Locale.ROOT) }
            .thenBy { it.folderLabel.lowercase(Locale.ROOT) }
        LibrarySort.Size -> compareByDescending<FoundBook> { it.sizeBytes }
            .thenBy { it.name.lowercase(Locale.ROOT) }
    }
}

/** The pure text of the shelf, the count line and Details. */
internal object LibraryFormat {
    fun size(bytes: Long): String {
        val value = bytes.coerceAtLeast(0)
        return when {
            value < 1_000L -> "$value B"
            value < 1_000_000L -> String.format(Locale.US, "%.1f KB", value / 1_000.0)
            value < 1_000_000_000L -> String.format(Locale.US, "%.1f MB", value / 1_000_000.0)
            else -> String.format(Locale.US, "%.1f GB", value / 1_000_000_000.0)
        }
    }

    fun formatLabel(format: BookFormat): String = LibraryFind.typeLabel(format)

    fun progressPercent(book: Book): Int = (book.progress.coerceIn(0.0, 1.0) * 100).roundToInt()

    fun progressLabel(book: Book): String = "${progressPercent(book)}% read"

    fun date(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
            .format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))

    fun order(books: List<Book>): List<Book> = books.sortedWith(
        compareByDescending<Book> { it.lastOpenedAt ?: Long.MIN_VALUE }
            .thenByDescending { it.addedAt }
            .thenBy { it.title.lowercase(Locale.ROOT) },
    )

    fun secondaryLine(book: Book, isContinue: Boolean, missing: Boolean): String = when {
        isContinue -> "Continue"
        missing -> "File not found"
        book.author.isNotBlank() -> "by ${book.author}"
        else -> "${formatLabel(book.format)} · ${size(book.sizeBytes)}"
    }

    fun cellDescription(book: Book, isContinue: Boolean, missing: Boolean): String = buildString {
        append(book.title)
        if (book.author.isNotBlank()) append(", by ").append(book.author)
        append(". ").append(formatLabel(book.format)).append(", ").append(size(book.sizeBytes))
        append(". ").append(progressLabel(book))
        if (isContinue) append(". Continue")
        if (missing) append(". File not found")
    }

    fun countLine(foundCount: Int, shownCount: Int, libraryCount: Int): String =
        if (shownCount == foundCount) "$foundCount found · $libraryCount in the library"
        else "$shownCount of $foundCount shown · $libraryCount in the library"

    fun details(book: Book, missing: Boolean, zone: ZoneId = ZoneId.systemDefault()): List<Pair<String, String>> = buildList {
        add("Title" to book.title)
        if (book.author.isNotBlank()) add("Author" to book.author)
        add("Format" to formatLabel(book.format))
        add("Size" to size(book.sizeBytes))
        add("Added" to date(book.addedAt, zone))
        add("Last opened" to (book.lastOpenedAt?.let { date(it, zone) } ?: "Not opened yet"))
        add("Progress" to progressLabel(book))
        if (missing) add("Status" to "File not found")
    }
}

/** The made-up shelf used by demo mode. No real file, title or person is read. */
internal val DemoLibraryBooks: List<Book> = listOf(
    Book("demo-quiet-coast", "demo://library/quiet-coast", "The Quiet Coast", "M. R. Vale",
        BookFormat.EPUB, 2_400_000, 1_759_000_000_000, 1_759_100_000_000, 0.42, shelves = listOf("shelf-1")),
    Book("demo-paper-moons", "demo://library/paper-moons", "Paper Moons", "Ada Wren",
        BookFormat.PDF, 8_100_000, 1_759_010_000_000, 1_759_090_000_000, 1.0,
        finished = true, shelves = listOf("shelf-2")),
    Book("demo-field-notes", "demo://library/field-notes", "Field Notes", "Sam Holt",
        BookFormat.TXT, 86_000, 1_759_020_000_000, 1_759_080_000_000, 0.73, shelves = listOf("shelf-1", "shelf-2")),
    Book("demo-short-rain", "demo://library/short-rain", "A Short History of Rain", "Nell Ives",
        BookFormat.EPUB, 3_900_000, 1_759_030_000_000, null, 0.0, shelves = listOf("shelf-2")),
    Book("demo-north", "demo://library/north", "North", "Ivo March",
        BookFormat.CBZ, 21_500_000, 1_759_040_000_000, 1_759_070_000_000, 0.55, shelves = listOf("shelf-1")),
    Book("demo-long-way", "demo://library/long-way", "The Long Way Home", "Clara Bend",
        BookFormat.EPUB, 5_200_000, 1_759_050_000_000, null, 0.07, shelves = listOf("shelf-2")),
)

/** T251: invented own shelves for demo mode, so the shelf row and Finished are reviewable. */
internal val DemoLibraryShelves = LibraryShelvesDocument(listOf(
    LibraryShelf("shelf-1", "Evenings"),
    LibraryShelf("shelf-2", "Short reads"),
))

/**
 * T262: made-up Find books results for demo screenshots. Every title, author and folder is invented,
 * plain English and ASCII; no real book, language or person is named. The one long title stays whole
 * in the two-line row.
 */
internal val DemoFoundBooks: List<FoundBook> = listOf(
    FoundBook("demo://found/orchard", "The Copper Orchard.pdf", "Books", 4_800_000, 1_794_100_000_000, BookFormat.PDF),
    FoundBook("demo://found/weather", "A Field Guide to Small Weather.epub", "Documents/Reading", 2_650_000, 1_794_000_000_000, BookFormat.EPUB),
    FoundBook("demo://found/tea", "The Cartographer's Guide to Quiet Afternoons.txt", "Downloads", 780_000, 1_793_950_000_000, BookFormat.TXT),
    FoundBook("demo://found/quiet-house", "Notes from a Quiet House.cbz", "Comics", 18_400_000, 1_793_900_000_000, BookFormat.CBZ),
    FoundBook("demo://found/lighthouse", "The Glass Lighthouse.epub", "Documents/Reading", 3_200_000, 1_793_850_000_000, BookFormat.EPUB),
    FoundBook("demo://found/walks", "Seven Short Walks.pdf", "Books", 6_100_000, 1_793_800_000_000, BookFormat.PDF),
    FoundBook("demo://found/aviary", "The Paper Aviary.txt", "Books", 420_000, 1_793_750_000_000, BookFormat.TXT),
    FoundBook("demo://found/sky", "Under the Same Sky.epub", "Downloads", 5_600_000, 1_793_700_000_000, BookFormat.EPUB),
    FoundBook("demo://found/green-room", "The Last Green Room.pdf", "Documents/Reading", 7_900_000, 1_793_650_000_000, BookFormat.PDF),
    FoundBook("demo://found/mornings", "A Year of Ordinary Mornings.cbz", "Comics", 24_200_000, 1_793_600_000_000, BookFormat.CBZ),
)

/** Two demo files are already on the made-up shelf so the selected state has both kinds of row. */
internal val DemoFindLibrary: List<Book> = listOf(
    Book("demo-orchard", "demo://found/orchard", "The Copper Orchard", "Iris Vell",
        BookFormat.PDF, 4_800_000, 1_793_000_000_000, null, 0.12),
    Book("demo-lighthouse", "demo://found/lighthouse", "The Glass Lighthouse", "June Marlow",
        BookFormat.EPUB, 3_200_000, 1_793_010_000_000, null, 0.31),
)

@Composable
internal fun LibraryHubPage(skin: Skin, active: Boolean) {
    val context = LocalContext.current
    val store = LocalLibraryStore.current
    val books by remember(store) { store?.books ?: flowOf(emptyList()) }.collectAsState(initial = emptyList())
    val shelves by remember(store) { store?.shelves ?: flowOf(LibraryShelvesDocument()) }
        .collectAsState(initial = LibraryShelvesDocument())
    val tabs = remember(shelves) { LibraryShelfRules.tabs(shelves) }
    var selectedShelf by rememberSaveable { mutableStateOf(LibraryShelfIds.All) }
    var sortName by rememberSaveable { mutableStateOf(LibraryShelfSort.Recent.name) }
    val sort = LibraryShelfSort.entries.firstOrNull { it.name == sortName } ?: LibraryShelfSort.Recent
    // A deleted shelf falls back to All without leaving the page blank.
    LaunchedEffect(tabs) { if (tabs.none { it.id == selectedShelf }) selectedShelf = LibraryShelfIds.All }
    val shelfBooks = remember(books, selectedShelf) { LibraryShelfRules.booksFor(selectedShelf, books) }
    val ordered = remember(shelfBooks, sort) { LibraryShelfRules.sorted(shelfBooks, sort) }
    val continueId = remember(books) {
        LibraryFormat.order(books).firstOrNull { it.lastOpenedAt != null && !it.finished }?.id
    }
    val scope = rememberCoroutineScope()
    val files = remember(context) { BookFiles(context) }
    var missing by remember { mutableStateOf<Set<String>>(emptySet()) }
    var findOpen by remember { mutableStateOf(false) }
    var newShelfOpen by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<String?>(null) }
    var shelfPickerFor by remember { mutableStateOf<String?>(null) }
    var detailsFor by remember { mutableStateOf<String?>(null) }
    var finishPromptFor by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var settleKey by remember { mutableIntStateOf(0) }
    // T210: when the page appears (first look or back from the reader) the covers settle into
    // place with the existing open spring instead of snapping.
    LaunchedEffect(active) { if (active) settleKey++ }
    val loader = remember(context, ordered.isNotEmpty()) {
        if (ordered.isNotEmpty()) BookMetadataLoader(context) else null
    }
    val coverStyle = remember(context, skin) { bookCoverStyle(context, skin) }
    // T235: the shelf remembers its own place across a tab switch, the reader, and the hub closing.
    val scrollMemory = LocalHubPageScrollMemory.current
    val gridState = rememberLazyGridState()
    RegisterHubScroll(gridState)
    val savedScroll = remember(scrollMemory) { scrollMemory?.get(LibraryPage.Id) }
    var scrollRestored by remember(scrollMemory) { mutableStateOf(savedScroll == null) }
    var shelfAtRestore by remember { mutableStateOf(selectedShelf) }
    LaunchedEffect(ordered.isNotEmpty(), gridState, scrollMemory) {
        if (!scrollRestored && ordered.isNotEmpty()) {
            val target = LibraryScroll.restore(savedScroll, ordered.size + 1)
            gridState.scrollToItem(target.index, target.offset)
            scrollRestored = true
        }
        if (scrollRestored) {
            snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
                .collect { (index, offset) -> scrollMemory?.put(LibraryPage.Id, index, offset) }
        }
    }
    DisposableEffect(gridState, scrollMemory) {
        onDispose {
            if (scrollRestored || savedScroll == null) {
                scrollMemory?.put(LibraryPage.Id, gridState.firstVisibleItemIndex,
                    gridState.firstVisibleItemScrollOffset)
            }
        }
    }
    // A different shelf starts at its own top; coming back from the reader keeps the saved place.
    LaunchedEffect(selectedShelf) {
        if (selectedShelf != shelfAtRestore) {
            gridState.scrollToItem(0)
            shelfAtRestore = selectedShelf
        }
    }

    LaunchedEffect(ordered, active) {
        if (!active || ordered.isEmpty()) return@LaunchedEffect
        missing = withContext(Dispatchers.IO) {
            ordered.filterNot { book -> runCatching { files.canRead(book.uri) }.getOrDefault(false) }.map { it.id }.toSet()
        }
    }
    LaunchedEffect(notice) {
        if (notice != null) { delay(Metrics.bookFindNoticeMs); notice = null }
    }
    // T252: first use gets the Comics shelf; then new files in the chosen folders join the library
    // and land on the shelves whose rules they match.
    LaunchedEffect(active, store) {
        if (!active || store == null) return@LaunchedEffect
        runCatching { store.ensureDefaultShelves() }
        val added = runCatching { autoAddNewBooks(context, store, coverStyle) }.getOrDefault(0)
        if (added > 0) notice = if (added == 1) "1 new book added from your folders."
            else "$added new books added from your folders."
    }
    // T251: a book that reached its last page asks once. Recording the ask first means a process
    // death cannot make Parc ask the same book again.
    LaunchedEffect(books, active, finishPromptFor) {
        if (!active || store == null || finishPromptFor != null) return@LaunchedEffect
        val candidate = books.filter(LibraryShelfRules::shouldAskFinished)
            .maxByOrNull { it.lastOpenedAt ?: it.addedAt } ?: return@LaunchedEffect
        finishPromptFor = candidate.id
        store.markFinishAsked(candidate.id)
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            LibraryShelfBar(tabs, selectedShelf, sort, skin,
                onSelect = { selectedShelf = it },
                onSort = { sortName = it.name },
                onNewShelf = if (store != null) ({ newShelfOpen = true }) else null)
            if (books.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = Metrics.cardInsetDp.dp, vertical = Metrics.rowGapDp.dp),
                    verticalArrangement = Arrangement.spacedBy(Metrics.rowGapDp.dp)) {
                    BasicText("No books yet", style = hudValueStyle(skin))
                    BasicText("Find PDF, EPUB, text or comic files on shared storage. The files stay where they are.",
                        style = hudLabelStyle(skin))
                }
            } else if (ordered.isEmpty()) {
                BasicText(LibraryShelfRules.emptyMessage(selectedShelf),
                    Modifier.fillMaxWidth().padding(horizontal = Metrics.cardInsetDp.dp, vertical = Metrics.rowGapDp.dp),
                    style = hudLabelStyle(skin), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val plan = LibraryLayout.plan(LocalHubSize.current, maxHeight.value, maxWidth.value,
                    LocalDensity.current.fontScale)
                LibraryShelfGrid(ordered, plan, skin, continueId, missing, settleKey,
                    gridState = gridState,
                    onOpen = { book, cover -> ReaderContract.open(context, book, cover) },
                    onLongPress = { actionsFor = it.id },
                    onDetails = { detailsFor = it.id },
                    onRemove = { book ->
                        scope.launch {
                            store?.remove(book.id)
                            notice = "Removed from library. The file was not deleted."
                        }
                    },
                    onAdd = { findOpen = true },
                    cover = { book, modifier -> RealBookCover(book, loader, coverStyle, skin, modifier) })
            }
        }
        notice?.let { line ->
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .padding(horizontal = Metrics.cardInsetDp.dp, vertical = Metrics.rowGapDp.dp)) {
                BasicText(line, style = hudLabelStyle(skin).copy(color = Color(skin.colors.accent.toInt())),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    if (findOpen) FindBooksDialog(skin, onDismiss = { findOpen = false }) { changed ->
        findOpen = false
        notice = when (changed) {
            0 -> "Library unchanged."
            1 -> "Library updated. 1 book changed."
            else -> "Library updated. $changed books changed."
        }
    }
    if (newShelfOpen) ShelfNameDialog("New shelf", "", shelves, null, skin,
        onDismiss = { newShelfOpen = false },
        onSave = { name ->
            newShelfOpen = false
            scope.launch {
                val added = store?.createShelf(name)
                if (added != null) {
                    selectedShelf = added.id
                    notice = "Shelf created."
                } else notice = "Could not create the shelf."
            }
        })
    actionsFor?.let { id -> books.firstOrNull { it.id == id } }?.let { book ->
        BookActionsDialog(book, shelves, skin,
            onDismiss = { actionsFor = null },
            onAddToShelf = { actionsFor = null; shelfPickerFor = book.id },
            onToggleFinished = {
                val finished = !book.finished
                actionsFor = null
                scope.launch {
                    store?.setFinished(book.id, finished)
                    notice = if (finished) "Marked finished." else "Marked unread. Your place is kept."
                }
            },
            onDetails = { actionsFor = null; detailsFor = book.id },
            onRemove = {
                actionsFor = null
                scope.launch {
                    store?.remove(book.id)
                    notice = "Removed from library. The file was not deleted."
                }
            })
    }
    shelfPickerFor?.let { id -> books.firstOrNull { it.id == id } }?.let { book ->
        ShelfPickerDialog(book, shelves, skin,
            onDismiss = { shelfPickerFor = null },
            onToggle = { shelf, on ->
                scope.launch {
                    store?.setOnShelf(book.id, shelf.id, on)
                    notice = if (on) "Added to ${shelf.name}." else "Removed from ${shelf.name}."
                }
            })
    }
    finishPromptFor?.let { id -> books.firstOrNull { it.id == id } }?.let { book ->
        AlertDialog(
            onDismissRequest = { finishPromptFor = null },
            containerColor = Color(skin.colors.surface.toInt()),
            title = { BasicText("Mark as finished?", style = settingsText(skin)) },
            text = {
                BasicText("“${book.title}” is at its last page.", style = settingsText(skin, skin.colors.textSecondary))
            },
            confirmButton = {
                TextButton(onClick = {
                    finishPromptFor = null
                    scope.launch {
                        store?.setFinished(book.id, true)
                        notice = "Marked finished."
                    }
                }) { BasicText("Mark as finished", style = settingsText(skin, skin.colors.accent)) }
            },
            dismissButton = {
                TextButton(onClick = { finishPromptFor = null }) {
                    BasicText("Not now", style = settingsText(skin, skin.colors.textSecondary))
                }
            },
        )
    }
    detailsFor?.let { id -> books.firstOrNull { it.id == id } }?.let { book ->
        BookDetailsDialog(book, book.id in missing, skin) { detailsFor = null }
    }
}

@Composable
internal fun DemoLibraryPage(skin: Skin) {
    val context = LocalContext.current
    val shelves = remember { DemoLibraryShelves }
    val tabs = remember(shelves) { LibraryShelfRules.tabs(shelves) }
    var selectedShelf by rememberSaveable { mutableStateOf(LibraryShelfIds.All) }
    var sortName by rememberSaveable { mutableStateOf(LibraryShelfSort.Recent.name) }
    val sort = LibraryShelfSort.entries.firstOrNull { it.name == sortName } ?: LibraryShelfSort.Recent
    val shelfBooks = remember(selectedShelf) { LibraryShelfRules.booksFor(selectedShelf, DemoLibraryBooks) }
    val ordered = remember(shelfBooks, sort) { LibraryShelfRules.sorted(shelfBooks, sort) }
    val continueId = remember { LibraryFormat.order(DemoLibraryBooks)
        .firstOrNull { it.lastOpenedAt != null && !it.finished }?.id }
    var findOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min = Metrics.menuRowHeightDp.dp)
            .padding(horizontal = Metrics.cardInsetDp.dp),
            verticalAlignment = Alignment.CenterVertically) {
            BasicText("Demo library", Modifier.weight(1f), style = hudLabelStyle(skin),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            LibraryAction("Find books", skin) { findOpen = true }
        }
        LibraryShelfBar(tabs, selectedShelf, sort, skin,
            onSelect = { selectedShelf = it },
            onSort = { sortName = it.name },
            onNewShelf = null)
        if (ordered.isEmpty()) {
            BasicText(LibraryShelfRules.emptyMessage(selectedShelf),
                Modifier.fillMaxWidth().padding(horizontal = Metrics.cardInsetDp.dp, vertical = Metrics.rowGapDp.dp),
                style = hudLabelStyle(skin), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val plan = LibraryLayout.plan(LocalHubSize.current, maxHeight.value, maxWidth.value, LocalDensity.current.fontScale)
            val demoMemory = LocalHubPageScrollMemory.current
            val demoState = rememberLazyGridState()
            RegisterHubScroll(demoState)
            LaunchedEffect(demoState, demoMemory, selectedShelf) {
                val saved = demoMemory?.get("demo:" + LibraryPage.Id)
                if (saved != null && selectedShelf == LibraryShelfIds.All) {
                    val target = LibraryScroll.restore(saved, ordered.size)
                    demoState.scrollToItem(target.index, target.offset)
                }
                snapshotFlow { demoState.firstVisibleItemIndex to demoState.firstVisibleItemScrollOffset }
                    .collect { (index, offset) -> demoMemory?.put("demo:" + LibraryPage.Id, index, offset) }
            }
            LibraryShelfGrid(ordered, plan, skin, continueId, emptySet(), 0,
                gridState = demoState,
                // T268: a demo book opens the same reader, which shows the built-in public-domain
                // sample (and one demo highlight) for demo:// locations. The first cell is Continue.
                onOpen = { book, cover -> ReaderContract.open(context, book, cover) },
                onLongPress = null,
                onDetails = null,
                onRemove = null,
                cover = { book, modifier -> TypographicBookCover(book, skin, modifier) })
        }
    }
    if (findOpen) DemoFindBooksDialog(skin) { findOpen = false }
}

/**
 * T251: one quiet row across the top of the Library. The built-in shelves come first, then the
 * user's own in their saved order; the row scrolls sideways instead of squeezing names. The sort
 * menu stays on the right, so the current order is always labelled.
 */
@Composable
private fun LibraryShelfBar(
    tabs: List<LibraryShelfTab>,
    selectedId: String,
    sort: LibraryShelfSort,
    skin: Skin,
    onSelect: (String) -> Unit,
    onSort: (LibraryShelfSort) -> Unit,
    onNewShelf: (() -> Unit)?,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = Metrics.menuRowHeightDp.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).padding(end = Metrics.itemGapDp.dp).clipToBounds()
            .hubOwnsHorizontalDrag()
            .horizontalScroll(rememberScrollState())
            .semantics { contentDescription = "Library shelves" },
            horizontalArrangement = Arrangement.spacedBy(Metrics.itemGapDp.dp)) {
            tabs.forEach { tab ->
                LibraryShelfChip(tab.label, tab.id == selectedId, skin) { onSelect(tab.id) }
            }
            if (onNewShelf != null) {
                LibraryShelfChip("+ Shelf", false, skin, description = "New shelf", onClick = onNewShelf)
            }
        }
        LibraryShelfSortMenu(sort, skin, onSort)
    }
}

@Composable
private fun LibraryShelfChip(
    label: String,
    selected: Boolean,
    skin: Skin,
    description: String = label,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Metrics.hubTabPillHeightDp.dp / 2)
    val fill = if (selected) Color(HubTabs.selectedFill(skin.colors.surface, skin.colors.accent).toInt())
        else Color.Transparent
    Box(Modifier.heightIn(min = Metrics.minTouchDp.dp).clip(shape).background(fill, shape)
        .semantics { contentDescription = description }
        .clickable(role = Role.Tab, onClick = onClick)
        .padding(horizontal = Metrics.rowGapDp.dp), contentAlignment = Alignment.Center) {
        BasicText(label, style = hubBodyStyle(skin).copy(fontSize = 13.sp, color = if (selected) Color(skin.colors.readableAccent.toInt()) else hubMuted(skin)),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LibraryShelfSortMenu(current: LibraryShelfSort, skin: Skin, onSort: (LibraryShelfSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    // 1.5.1: a small sort icon, not a wide dropdown; the menu names the choices and marks the current one.
    Box {
        Box(Modifier.size(Metrics.minTouchDp.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .semantics { contentDescription = "Sort shelf, now ${current.label}" }
            .clickable(role = Role.Button) { open = true }, contentAlignment = Alignment.Center) {
            SortGlyph(Color(skin.colors.textPrimary.toInt()))
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = Color(skin.colors.surface.toInt()),
            shape = RoundedCornerShape(skin.shape.surfaceCornerDp.dp),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(skin.shape.surfaceBorderDp.dp, Color(skin.colors.lineBase.toInt())),
        ) {
            LibraryShelfSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        BasicText(option.label, style = hudLabelStyle(skin).copy(color = Color(
                            (if (option == current) skin.colors.accent else skin.colors.textPrimary).toInt())))
                    },
                    onClick = { onSort(option); open = false },
                    modifier = Modifier.widthIn(min = Metrics.bookFindSortMenuWidthDp.dp),
                )
            }
        }
    }
}

@Composable
private fun LibraryAction(label: String, skin: Skin, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = Metrics.menuRowHeightDp.dp).widthIn(min = Metrics.bookFindSearchHeightDp.dp)
        .border(skin.shape.surfaceBorderDp.dp, Color((if (enabled) skin.colors.accent else skin.colors.lineActive).toInt()),
            RoundedCornerShape(skin.shape.surfaceCornerDp.dp))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = Metrics.bookFindChipInsetDp.dp), contentAlignment = Alignment.Center) {
        BasicText(label, style = hudLabelStyle(skin).copy(color = Color(
            (if (enabled) skin.colors.accent else skin.colors.textSecondary).toInt())))
    }
}

@Composable
private fun LibraryShelfGrid(
    books: List<Book>,
    plan: LibraryShelfPlan,
    skin: Skin,
    continueId: String?,
    missing: Set<String>,
    settleKey: Int,
    gridState: LazyGridState,
    onOpen: ((Book, ReaderMotion.Rect?) -> Unit)?,
    onLongPress: ((Book) -> Unit)?,
    onDetails: ((Book) -> Unit)?,
    onRemove: ((Book) -> Unit)?,
    onAdd: (() -> Unit)? = null,
    cover: @Composable (Book, Modifier) -> Unit,
) {
    val addIndex = LibraryLayout.addTileIndex(books.size)
    val itemCount = if (onAdd != null) addIndex + 1 else addIndex
    val add = onAdd
    LazyVerticalGrid(
        columns = GridCells.Fixed(plan.columns),
        state = gridState,
        modifier = Modifier.fillMaxSize().semantics { contentDescription = "Library shelf, ${books.size} ${if (books.size == 1) "book" else "books"}" },
        contentPadding = PaddingValues(bottom = Metrics.hubScrollEndGapDp.dp),
        horizontalArrangement = Arrangement.spacedBy(Metrics.bookShelfGapDp.dp),
        verticalArrangement = Arrangement.spacedBy(Metrics.bookShelfGapDp.dp),
    ) {
        items(count = itemCount, key = { index ->
            if (index == addIndex) LibraryLayout.AddTileKey else books[index].id
        }) { index ->
            if (add != null && index == addIndex) {
                LibraryAddTile(plan, skin, add)
            } else {
                val book = books[index]
                LibraryShelfCell(book, plan, skin, index, books.size, settleKey, book.id == continueId, book.id in missing,
                    onOpen, onLongPress, onDetails, onRemove, cover)
            }
        }
    }
}

/**
 * T227: the first free cell after the last book. It is a cover-sized, accent-outlined action, so the
 * empty Library and a full shelf both offer the same way into Find books.
 */
@Composable
private fun LibraryAddTile(plan: LibraryShelfPlan, skin: Skin, onAdd: () -> Unit) {
    val shape = RoundedCornerShape(Metrics.bookShelfCornerDp.dp)
    val accent = Color(skin.colors.accent.toInt())
    val glyphSize = (minOf(plan.coverWidthDp, plan.coverHeightDp) * Metrics.bookShelfAddGlyphFraction).dp
    Box(Modifier.width(plan.coverWidthDp.dp).height(plan.coverHeightDp.dp)
        .clip(shape)
        .border(BorderStroke(skin.shape.surfaceBorderDp.dp, accent), shape)
        .clickable(role = Role.Button, onClick = onAdd)
        .semantics(mergeDescendants = true) { contentDescription = "Add books" }
        .padding(Metrics.bookShelfAddInsetDp.dp),
        contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Metrics.bookShelfAddGapDp.dp)) {
            Canvas(Modifier.size(glyphSize)) {
                val stroke = Metrics.bookShelfAddStrokeDp.dp.toPx()
                val arm = size.minDimension * Metrics.bookShelfAddArmFraction
                drawLine(accent, Offset(center.x - arm, center.y), Offset(center.x + arm, center.y),
                    strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(accent, Offset(center.x, center.y - arm), Offset(center.x, center.y + arm),
                    strokeWidth = stroke, cap = StrokeCap.Round)
            }
            BasicText("Add books", style = hudLabelStyle(skin).copy(color = accent, textAlign = TextAlign.Center),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LibraryShelfCell(
    book: Book,
    plan: LibraryShelfPlan,
    skin: Skin,
    index: Int,
    count: Int,
    settleKey: Int,
    isContinue: Boolean,
    missing: Boolean,
    onOpen: ((Book, ReaderMotion.Rect?) -> Unit)?,
    onLongPress: ((Book) -> Unit)?,
    onDetails: ((Book) -> Unit)?,
    onRemove: ((Book) -> Unit)?,
    cover: @Composable (Book, Modifier) -> Unit,
) {
    val description = LibraryFormat.cellDescription(book, isContinue, missing)
    var coverBounds by remember(book.id) { mutableStateOf<ReaderMotion.Rect?>(null) }
    val settle = remember(book.id) { Animatable(1f) }
    LaunchedEffect(book.id, settleKey) {
        if (settleKey <= 0) return@LaunchedEffect
        settle.snapTo(Metrics.readerShelfSettleScale)
        delay(Springs.openDelayMs(index, count).toLong())
        settle.animateTo(1f, spring(Springs.open.damping, Springs.open.stiffness))
    }
    val actions = remember(book.id, onDetails, onRemove) {
        buildList {
            if (onDetails != null) add(CustomAccessibilityAction("Details") { onDetails(book); true })
            if (onRemove != null) add(CustomAccessibilityAction("Remove from library") { onRemove(book); true })
        }
    }
    Column(Modifier.fillMaxWidth().graphicsLayer {
        scaleX = settle.value
        scaleY = settle.value
        val progress = ((settle.value - Metrics.readerShelfSettleScale) /
            (1f - Metrics.readerShelfSettleScale)).coerceIn(0f, 1f)
        alpha = Metrics.readerShelfSettleAlpha + (1f - Metrics.readerShelfSettleAlpha) * progress
    }.semantics(mergeDescendants = true) {
        contentDescription = description
        if (onOpen != null) {
            role = Role.Button
            onClick("Open book") { onOpen(book, coverBounds); true }
        }
        if (actions.isNotEmpty()) customActions = actions
    }.then(if (onLongPress == null && onOpen == null) Modifier else Modifier.pointerInput(book.id) {
        detectTapGestures(onTap = { onOpen?.invoke(book, coverBounds) }, onLongPress = { onLongPress?.invoke(book) })
    }), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.width(plan.coverWidthDp.dp).height(plan.coverHeightDp.dp)
            .clip(RoundedCornerShape(Metrics.bookShelfCornerDp.dp))
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                coverBounds = ReaderMotion.coverOrNull(bounds.left, bounds.top, bounds.right, bounds.bottom)
            }) {
            cover(book, Modifier.fillMaxSize())
        }
        Box(Modifier.width(plan.coverWidthDp.dp).padding(top = Metrics.bookFindRowGapDp.dp)) {
            ProgressLine(book.progress, skin)
        }
        BasicText(book.title, Modifier.padding(top = Metrics.bookFindRowGapDp.dp),
            style = hudLabelStyle(skin).copy(color = Color(skin.colors.textPrimary.toInt())),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicText(LibraryFormat.secondaryLine(book, isContinue, missing),
            style = hudLabelStyle(skin).copy(color = Color(
                (if (isContinue) skin.colors.accent else skin.colors.textSecondary).toInt())),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A thin track and the read amount in the accent, under a cover. */
@Composable
private fun ProgressLine(progress: Double, skin: Skin) {
    val track = Color(skin.colors.lineBase.toInt()).copy(alpha = Metrics.bookShelfProgressTrackAlpha)
    val fill = Color(skin.colors.accent.toInt())
    val fraction = progress.coerceIn(0.0, 1.0).toFloat()
    Canvas(Modifier.fillMaxWidth().height(Metrics.bookShelfProgressDp.dp)) {
        drawRect(track)
        if (fraction > 0f) drawRect(fill, size = androidx.compose.ui.geometry.Size(size.width * fraction, size.height))
    }
}

@Composable
internal fun RealBookCover(book: Book, loader: BookMetadataLoader?, style: BookCoverStyle, skin: Skin,
    modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.background(Color(style.background))) {
        val width = constraints.maxWidth.coerceAtLeast(1)
        val height = constraints.maxHeight.coerceAtLeast(1)
        var image by remember(book.id, width, height) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(book.id, loader, width, height) {
            image = loader?.displayCover(book, width, height)?.asImageBitmap()
        }
        val bitmap = image
        if (bitmap != null) {
            Image(bitmap, "Cover of ${book.title}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            TypographicBookCover(book, skin, Modifier.fillMaxSize())
        }
    }
}

/** A generated cover drawn from skin tokens; also the calm placeholder while a cached cover loads. */
@Composable
internal fun TypographicBookCover(book: Book, skin: Skin, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Metrics.bookShelfCornerDp.dp)
    Column(modifier.background(Color(skin.colors.surface.toInt()), shape)
        .border(skin.shape.surfaceBorderDp.dp, Color(skin.colors.lineBase.toInt()), shape)
        .padding(horizontal = Metrics.rowGapDp.dp, vertical = Metrics.cardInsetDp.dp),
        verticalArrangement = Arrangement.SpaceBetween) {
        BasicText(book.title, style = hudValueStyle(skin).copy(color = Color(skin.colors.textPrimary.toInt())),
            maxLines = 4, overflow = TextOverflow.Ellipsis)
        BasicText(if (book.author.isNotBlank()) book.author else LibraryFormat.formatLabel(book.format),
            style = hudLabelStyle(skin), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun LibraryOptionChoices(skin: Skin) {
    var open by remember { mutableStateOf(false) }
    var shelvesOpen by remember { mutableStateOf(false) }
    val store = LocalLibraryStore.current
    val document by remember(store) { store?.shelves ?: flowOf(LibraryShelvesDocument()) }
        .collectAsState(initial = LibraryShelvesDocument())
    SettingsCard(skin) {
        SettingRow("Find books", skin, SettingKind.Page,
            description = "Add or remove books from shared storage. The files are never moved or deleted.",
            descriptionMaxLines = Int.MAX_VALUE, icon = "+") { open = true }
        if (store != null && !DemoMode.enabled) {
            SettingRow("Shelves", skin, SettingKind.Page,
                description = "Create, rename, reorder and delete your own shelves. Deleting a shelf keeps its books.",
                descriptionMaxLines = Int.MAX_VALUE,
                value = if (document.shelves.isEmpty()) "None yet" else "${document.shelves.size} own",
                divider = false) { shelvesOpen = true }
        }
    }
    if (open) {
        // T268: in demo mode even this route opens the fixed list, so no folder chooser or scan
        // can appear behind a demo screenshot.
        if (DemoMode.enabled) DemoFindBooksDialog(skin) { open = false }
        else FindBooksDialog(skin, onDismiss = { open = false }) { open = false }
    }
    if (shelvesOpen && store != null) {
        LibraryShelfManagerDialog(store, skin) { shelvesOpen = false }
    }
}

@Composable
private fun BookActionsDialog(
    book: Book,
    shelves: LibraryShelvesDocument,
    skin: Skin,
    onDismiss: () -> Unit,
    onAddToShelf: () -> Unit,
    onToggleFinished: () -> Unit,
    onDetails: () -> Unit,
    onRemove: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(skin.colors.surface.toInt()),
        title = { BasicText(book.title, style = settingsText(skin)) },
        text = {
            // T230: a long title or large text can make this taller than the dialog; scroll it.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SettingsCard(skin) {
                    SettingRow("Add to shelf", skin, SettingKind.Page, icon = "+",
                        description = if (shelves.shelves.isEmpty()) "No own shelves yet. Create one in the Library page options."
                        else "A book can be on more than one of your shelves.",
                        descriptionMaxLines = Int.MAX_VALUE) { onAddToShelf() }
                    SettingRow(if (book.finished) "Mark unread" else "Mark finished", skin, SettingKind.Action,
                        description = if (book.finished) "Moves the book back to Reading. Your place is kept."
                        else "Moves the book to Finished. Your place is kept.",
                        descriptionMaxLines = Int.MAX_VALUE, icon = if (book.finished) "↺" else "✓") { onToggleFinished() }
                    SettingRow("Details", skin, SettingKind.Page,
                        description = "Title, author, format, size, added and progress.",
                        descriptionMaxLines = Int.MAX_VALUE) { onDetails() }
                    SettingRow("Remove from library", skin, SettingKind.Action,
                        description = "The file stays on the phone. Only its Library row is removed.",
                        descriptionMaxLines = Int.MAX_VALUE, icon = "−", divider = false) { onRemove() }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { BasicText("Close", style = settingsText(skin, skin.colors.accent)) } },
    )
}

@Composable
private fun ShelfPickerDialog(
    book: Book,
    document: LibraryShelvesDocument,
    skin: Skin,
    onDismiss: () -> Unit,
    onToggle: (LibraryShelf, Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(skin.colors.surface.toInt()),
        title = { BasicText("Add to shelf", style = settingsText(skin)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (document.shelves.isEmpty()) {
                    BasicText("No own shelves yet. Create one in the Library page options.",
                        style = settingsText(skin, skin.colors.textSecondary, true))
                } else {
                    BasicText("A book can be on more than one shelf.",
                        style = settingsText(skin, skin.colors.textSecondary, true))
                    SettingsCard(skin) {
                        document.shelves.forEachIndexed { index, shelf ->
                            SettingRow(shelf.name, skin, SettingKind.Toggle,
                                checked = shelf.id in book.shelves,
                                divider = index != document.shelves.lastIndex) {
                                onToggle(shelf, shelf.id !in book.shelves)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { BasicText("Done", style = settingsText(skin, skin.colors.accent)) } },
    )
}

@Composable
private fun ShelfNameDialog(
    title: String,
    initial: String,
    document: LibraryShelvesDocument,
    exceptId: String?,
    skin: Skin,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    val shape = RoundedCornerShape(skin.shape.surfaceCornerDp.dp)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(skin.colors.surface.toInt()),
        title = { BasicText(title, style = settingsText(skin)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.lineGapDp.dp)) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    singleLine = true,
                    textStyle = settingsText(skin),
                    cursorBrush = SolidColor(Color(skin.colors.accent.toInt())),
                    modifier = Modifier.fillMaxWidth().heightIn(min = Metrics.touchTargetDp.dp)
                        .background(Color(skin.colors.surface.toInt()), shape)
                        .border(skin.shape.surfaceBorderDp.dp, Color(skin.colors.lineActive.toInt()), shape)
                        .padding(horizontal = Metrics.cardInsetDp.dp, vertical = Metrics.rowGapDp.dp)
                        .semantics { contentDescription = "Shelf name" },
                    decorationBox = { inner ->
                        Box {
                            if (text.isEmpty()) BasicText("Shelf name", style = settingsText(skin, skin.colors.textSecondary))
                            inner()
                        }
                    },
                )
                error?.let {
                    BasicText(it, style = settingsText(skin, skin.colors.warn, true), maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val check = LibraryShelfRules.checkName(text, document, exceptId)
                val name = check.name
                if (name != null) onSave(name) else error = check.error
            }) { BasicText("Save", style = settingsText(skin, skin.colors.accent)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { BasicText("Cancel", style = settingsText(skin, skin.colors.textSecondary)) }
        },
    )
}

/**
 * T251: create, rename, reorder and delete own shelves in one place. Each row names the shelf and
 * how many books are on it; deleting asks first and says that the books stay in the library.
 */
@Composable
private fun LibraryShelfManagerDialog(store: LibraryStore, skin: Skin, onDismiss: () -> Unit) {
    val document by store.shelves.collectAsState(initial = LibraryShelvesDocument())
    val books by store.books.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var createOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<LibraryShelf?>(null) }
    var deleting by remember { mutableStateOf<LibraryShelf?>(null) }
    var rulesFor by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(skin.colors.surface.toInt()),
        title = { BasicText("Shelves", style = settingsText(skin)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (document.shelves.isEmpty()) {
                    BasicText("No own shelves yet. New shelf creates one; books join it from a book's long-press menu.",
                        style = settingsText(skin, skin.colors.textSecondary, true))
                }
                document.shelves.forEachIndexed { index, shelf ->
                    val count = books.count { shelf.id in it.shelves }
                    Column(Modifier.fillMaxWidth().padding(vertical = Metrics.lineGapDp.dp)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = Metrics.menuRowHeightDp.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                BasicText(shelf.name, style = settingsText(skin))
                                BasicText(if (count == 1) "1 book" else "$count books",
                                    style = settingsText(skin, skin.colors.textSecondary, true))
                            }
                            ShelfTextAction("Rename", skin) { renaming = shelf }
                        }
                        Row(Modifier.fillMaxWidth()) {
                            ShelfTextAction(if (shelf.rules.isEmpty()) "Rules" else "Rules · ${shelf.rules.size}", skin) {
                                rulesFor = shelf.id
                            }
                            ShelfTextAction("Move earlier", skin, enabled = index > 0) {
                                scope.launch { store.moveShelf(shelf.id, -1) }
                            }
                            ShelfTextAction("Move later", skin, enabled = index < document.shelves.lastIndex) {
                                scope.launch { store.moveShelf(shelf.id, 1) }
                            }
                            ShelfTextAction("Delete", skin, destructive = true) { deleting = shelf }
                        }
                    }
                    if (index != document.shelves.lastIndex) SettingsDivider(skin)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { BasicText("Done", style = settingsText(skin, skin.colors.accent)) } },
        dismissButton = {
            TextButton(onClick = { createOpen = true }) { BasicText("New shelf", style = settingsText(skin, skin.colors.accent)) }
        },
    )
    if (createOpen) {
        ShelfNameDialog("New shelf", "", document, null, skin,
            onDismiss = { createOpen = false },
            onSave = { name ->
                createOpen = false
                scope.launch { store.createShelf(name) }
            })
    }
    rulesFor?.let { id -> document.shelf(id) }?.let { shelf ->
        ShelfRulesDialog(shelf, books, store, skin) { rulesFor = null }
    }
    renaming?.let { shelf ->
        ShelfNameDialog("Rename shelf", shelf.name, document, shelf.id, skin,
            onDismiss = { renaming = null },
            onSave = { name ->
                renaming = null
                scope.launch { store.renameShelf(shelf.id, name) }
            })
    }
    deleting?.let { shelf ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = Color(skin.colors.surface.toInt()),
            title = { BasicText("Delete ${shelf.name}?", style = settingsText(skin)) },
            text = { BasicText("The books on this shelf stay in the library.", style = settingsText(skin, skin.colors.textSecondary)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch { store.deleteShelf(shelf.id) }
                }) { BasicText("Delete", style = settingsText(skin, skin.colors.warn)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    BasicText("Cancel", style = settingsText(skin, skin.colors.textSecondary))
                }
            },
        )
    }
}

/**
 * T252: the rules of one shelf. Each rule is one row with how many books it matches now and a remove
 * mark; the chips below add a file type, a chosen folder or a piece of a name. Rules only ever add
 * books to the shelf, so removing a rule or a book by hand is never undone by them.
 */
@Composable
private fun ShelfRulesDialog(
    shelf: LibraryShelf,
    books: List<Book>,
    store: LibraryStore,
    skin: Skin,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val folderStore = remember(context) { BookFolderStore(context) }
    var chosen by remember { mutableStateOf<List<BookFolder>>(emptyList()) }
    LaunchedEffect(Unit) { chosen = runCatching { folderStore.snapshot() }.getOrDefault(emptyList()) }
    var folderMenu by remember { mutableStateOf(false) }
    var nameOpen by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(skin.colors.surface.toInt()),
        title = { BasicText("${shelf.name} rules", style = settingsText(skin)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Metrics.lineGapDp.dp)) {
                if (shelf.rules.isEmpty()) {
                    BasicText("No rules. Books you add yourself still go here.",
                        style = settingsText(skin, skin.colors.textSecondary, true))
                } else {
                    BasicText("A book matching any rule goes on this shelf.",
                        style = settingsText(skin, skin.colors.textSecondary, true))
                }
                shelf.rules.forEachIndexed { index, rule ->
                    val count = LibraryShelfRules.matchCount(rule, books)
                    Row(Modifier.fillMaxWidth().heightIn(min = Metrics.menuRowHeightDp.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        BasicText(ruleGlyph(rule.kind), Modifier.width((Metrics.minTouchDp.dp / 2)),
                            style = settingsText(skin, skin.colors.accent))
                        Column(Modifier.weight(1f)) {
                            BasicText(LibraryShelfRules.describe(rule), style = settingsText(skin),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            BasicText(if (count == 1) "Matches 1 book" else "Matches $count books",
                                style = settingsText(skin, skin.colors.textSecondary, true))
                        }
                        Box(Modifier.size(Metrics.minTouchDp.dp)
                            .clickable(role = Role.Button) { scope.launch { store.removeRule(shelf.id, index) } }
                            .semantics { contentDescription = "Remove rule ${LibraryShelfRules.describe(rule)}" },
                            contentAlignment = Alignment.Center) {
                            BasicText("×", style = settingsText(skin, skin.colors.warn))
                        }
                    }
                }
                BasicText("Add a rule", style = hudLabelStyle(skin))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Metrics.itemGapDp.dp)) {
                    LibraryFind.types.forEach { format ->
                        LibraryShelfChip(LibraryFind.typeLabel(format), false, skin,
                            description = "Add rule ${LibraryFind.typeLabel(format)} files") {
                            scope.launch { store.addRule(shelf.id, ShelfRule(ShelfRuleKind.Type, format.name)) }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Metrics.itemGapDp.dp)) {
                    Box {
                        LibraryShelfChip("▤ Folder", false, skin, description = "Add a folder rule") {
                            if (chosen.isEmpty()) note = "Choose folders in Find books first." else folderMenu = true
                        }
                        DropdownMenu(
                            expanded = folderMenu,
                            onDismissRequest = { folderMenu = false },
                            containerColor = Color(skin.colors.surface.toInt()),
                            shape = RoundedCornerShape(skin.shape.surfaceCornerDp.dp),
                            tonalElevation = 0.dp,
                            shadowElevation = 0.dp,
                            border = BorderStroke(skin.shape.surfaceBorderDp.dp, Color(skin.colors.lineBase.toInt())),
                        ) {
                            chosen.forEach { folder ->
                                DropdownMenuItem(
                                    text = { BasicText(folder.label, style = hudLabelStyle(skin).copy(
                                        color = Color(skin.colors.textPrimary.toInt()))) },
                                    onClick = {
                                        folderMenu = false
                                        scope.launch {
                                            store.addRule(shelf.id,
                                                ShelfRule(ShelfRuleKind.Folder, folder.location, folder.label))
                                        }
                                    })
                            }
                        }
                    }
                    LibraryShelfChip("Aa Name", false, skin, description = "Add a name rule") { nameOpen = true }
                }
                note?.let {
                    BasicText(it, style = settingsText(skin, skin.colors.textSecondary, true))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { BasicText("Done", style = settingsText(skin, skin.colors.accent)) } },
        dismissButton = {
            TextButton(onClick = {
                scope.launch {
                    val gained = store.sortByRules()
                    note = if (gained == 0) "Everything is already sorted." else
                        if (gained == 1) "1 book sorted." else "$gained books sorted."
                }
            }) { BasicText("Sort now", style = settingsText(skin, skin.colors.accent)) }
        },
    )
    if (nameOpen) {
        ShelfTextDialog("Name contains", "Part of a title or file name", skin,
            onDismiss = { nameOpen = false },
            onSave = { text ->
                nameOpen = false
                scope.launch { store.addRule(shelf.id, ShelfRule(ShelfRuleKind.Name, text)) }
            })
    }
}

private fun ruleGlyph(kind: ShelfRuleKind): String = when (kind) {
    ShelfRuleKind.Type -> "▭"
    ShelfRuleKind.Folder -> "▤"
    ShelfRuleKind.Name -> "Aa"
}

@Composable
private fun ShelfTextDialog(
    title: String,
    hint: String,
    skin: Skin,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val shape = RoundedCornerShape(skin.shape.surfaceCornerDp.dp)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(skin.colors.surface.toInt()),
        title = { BasicText(title, style = settingsText(skin)) },
        text = {
            BasicTextField(
                value = text,
                onValueChange = { text = it.take(LibraryShelfLimits.MaxRuleValue) },
                singleLine = true,
                textStyle = settingsText(skin),
                cursorBrush = SolidColor(Color(skin.colors.accent.toInt())),
                modifier = Modifier.fillMaxWidth().heightIn(min = Metrics.touchTargetDp.dp)
                    .background(Color(skin.colors.surface.toInt()), shape)
                    .border(skin.shape.surfaceBorderDp.dp, Color(skin.colors.lineActive.toInt()), shape)
                    .padding(horizontal = Metrics.cardInsetDp.dp, vertical = Metrics.rowGapDp.dp)
                    .semantics { contentDescription = hint },
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) BasicText(hint, style = settingsText(skin, skin.colors.textSecondary))
                        inner()
                    }
                },
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onSave(text) }) {
                BasicText("Add", style = settingsText(skin, skin.colors.accent))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { BasicText("Cancel", style = settingsText(skin, skin.colors.textSecondary)) }
        },
    )
}

/**
 * T252: look at the folders the user already chose, never anywhere else, and add files that appeared
 * since the last look. The very first pass adds nothing: it records what is already there.
 * Returns how many books were added.
 */
private suspend fun autoAddNewBooks(
    context: Context,
    store: LibraryStore,
    coverStyle: BookCoverStyle,
): Int {
    val folderStore = BookFolderStore(context)
    val chosenAll = folderStore.snapshot()
    if (chosenAll.isEmpty()) return 0
    val files = BookFiles(context)
    val allFiles = BuildConfig.LIBRARY_ALL_FILES_ALLOWED && Environment.isExternalStorageManager()
    val chosen = chosenAll.filter { it.tree || allFiles }
    if (chosen.isEmpty()) return 0
    val indexStore = BookIndexStore(context)
    val refreshed = indexStore.refresh(files.scanner(BuildConfig.LIBRARY_ALL_FILES_ALLOWED), BookTreeScanner(context),
        chosen.map(BookFolder::scanFolder), LibraryFind.types.toSet(), force = false)
    val roots = chosen.mapTo(hashSetOf()) { it.location }
    val inFolders = refreshed.files.filter { it.root in roots }.map { it.found() }
    val library = store.books.first()
    val plan = LibraryAutoAdd.plan(inFolders, indexStore.autoSeen(), library)
    val loader = BookMetadataLoader(context)
    val now = System.currentTimeMillis()
    var added = 0
    plan.add.forEachIndexed { position, file ->
        val loaded = runCatching { loader.load(file, coverStyle, addedAt = now + position, id = stableBookId(file.uri)) }
            .getOrNull() ?: return@forEachIndexed
        store.add(loaded.book)
        added++
    }
    indexStore.saveAutoSeen(plan.seen)
    return added
}

@Composable
private fun ShelfTextAction(
    label: String,
    skin: Skin,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    Box(Modifier.heightIn(min = Metrics.minTouchDp.dp)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = Metrics.lineGapDp.dp), contentAlignment = Alignment.Center) {
        BasicText(label, style = hudLabelStyle(skin).copy(color = Color(
            (when {
                !enabled -> skin.colors.textSecondary
                destructive -> skin.colors.warn
                else -> skin.colors.accent
            }).toInt())), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun BookDetailsDialog(book: Book, missing: Boolean, skin: Skin, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(skin.colors.surface.toInt()),
        title = { BasicText("Book details", style = settingsText(skin)) },
        text = {
            Column(Modifier.wrapContentHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Metrics.bookFindRowGapDp.dp)) {
                LibraryFormat.details(book, missing).forEach { (label, value) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.rowGapDp.dp)) {
                        BasicText(label, Modifier.width(Metrics.settingsValueMaxWidthDp.dp), style = hudLabelStyle(skin))
                        BasicText(value, Modifier.weight(1f), style = hudValueStyle(skin), maxLines = 4,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { BasicText("Close", style = settingsText(skin, skin.colors.accent)) } },
    )
}

@Composable
internal fun FindBooksDialog(skin: Skin, onDismiss: () -> Unit, onApplied: (Int) -> Unit) {
    val context = LocalContext.current
    val store = LocalLibraryStore.current
    val library by remember(store) { store?.books ?: flowOf(emptyList()) }.collectAsState(initial = emptyList())
    val loader = remember(context) { BookMetadataLoader(context) }
    val coverStyle = remember(context, skin) { bookCoverStyle(context, skin) }
    val scope = rememberCoroutineScope()
    val indexStore = remember(context) { BookIndexStore(context) }
    val folderStore = remember(context) { BookFolderStore(context) }
    val files = remember(context) { BookFiles(context) }
    val treeScanner = remember(context) { BookTreeScanner(context) }
    val scanner = remember(files) { files.scanner(BuildConfig.LIBRARY_ALL_FILES_ALLOWED) }
    val transition = remember { MutableTransitionState(false).apply { targetState = true } }
    var types by remember { mutableStateOf(LibraryFind.types.toSet()) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(LibrarySort.Newest) }
    var index by remember { mutableStateOf<BookIndex?>(null) }
    var folders by remember { mutableStateOf<List<BookFolder>>(emptyList()) }
    var foldersLoaded by remember { mutableStateOf(false) }
    var showFolders by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var knownLibrary by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var appliedCount by remember { mutableStateOf<Int?>(null) }

    fun close(result: Int? = null) {
        if (applying) return
        appliedCount = result
        transition.targetState = false
    }

    fun allFilesGranted(): Boolean =
        BuildConfig.LIBRARY_ALL_FILES_ALLOWED && Environment.isExternalStorageManager()

    fun blockedByAccess(chosen: List<BookFolder>): Boolean =
        chosen.any { !it.tree } && !allFilesGranted()

    suspend fun refresh(chosen: List<BookFolder>, force: Boolean) {
        if (chosen.isEmpty()) return
        if (blockedByAccess(chosen)) {
            notice = "Allow file access to scan these folders, or choose a folder with the system picker."
            return
        }
        busy = true
        notice = null
        try {
            val refreshed = indexStore.refresh(scanner, treeScanner, chosen.map(BookFolder::scanFolder),
                LibraryFind.types.toSet(), force)
            val retained = BookIndexRules.retainFolders(refreshed, chosen.mapTo(hashSetOf()) { it.location })
            if (retained != refreshed) indexStore.save(retained)
            index = retained
            if (retained.files.isEmpty()) notice = "No PDF, EPUB, text or comic files found in these folders."
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            notice = "Could not read these folders."
        } finally {
            busy = false
        }
    }

    fun useFolders(chosen: List<BookFolder>) {
        showFolders = false
        scope.launch {
            try {
                folderStore.save(chosen)
                folders = chosen
                val current = indexStore.snapshot()
                val retained = BookIndexRules.retainFolders(current, chosen.mapTo(hashSetOf()) { it.location })
                if (retained != current) indexStore.save(retained)
                index = retained
                if (chosen.isEmpty()) notice = "Choose at least one folder to scan."
                else refresh(chosen, force = false)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                notice = "Could not save the folders."
            }
        }
    }

    fun scanAgain() {
        if (folders.isEmpty()) {
            showFolders = true
            return
        }
        if (types.isEmpty()) { notice = "Pick at least one format."; return }
        notice = null
        scope.launch { refresh(folders, force = true) }
    }

    fun apply() {
        if (applying || store == null) return
        val shown = index?.files.orEmpty()
            .filter { it.root.isBlank() || it.root in folders.map { folder -> folder.location } }
            .map { it.found() }
        val plan = LibraryFind.plan(shown, library, types, query, sort, selected, keepSelected = selected)
        if (plan.changeCount == 0) return
        applying = true
        notice = null
        scope.launch {
            try {
                plan.remove.forEach { store.remove(it.id) }
                val now = System.currentTimeMillis()
                plan.add.forEachIndexed { position, file ->
                    val loaded = loader.load(file, coverStyle, addedAt = now + position, id = stableBookId(file.uri))
                    store.add(loaded.book)
                }
                applying = false
                close(plan.changeCount)
            } catch (_: Exception) {
                applying = false
                notice = "Could not update the library. Try fewer books."
            }
        }
    }

    // T225: the saved folder list is the first thing read. A first run opens the chooser and scans
    // nothing until the user ticks folders and confirms.
    LaunchedEffect(Unit) {
        try {
            val saved = folderStore.snapshot()
            folders = saved
            var loaded = indexStore.snapshot()
            loaded = indexStore.prunePicked { location -> files.canRead(location) }
            val retained = BookIndexRules.retainFolders(loaded, saved.mapTo(hashSetOf()) { it.location })
            if (retained != loaded) indexStore.save(retained)
            index = retained
            foldersLoaded = true
            if (saved.isEmpty()) showFolders = true else refresh(saved, force = false)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            foldersLoaded = true
            notice = "Could not read the saved folders."
        }
    }

    // Keep user ticks across refreshes and filters. Library books start ticked; files that vanish leave
    // the list. A selected row is never hidden by a later format or search change (T226).
    LaunchedEffect(index, library) {
        val current = index ?: return@LaunchedEffect
        val present = current.files.mapTo(hashSetOf()) { it.location }
        val inLibrary = library.map { it.uri }.filterTo(hashSetOf()) { it in present }
        selected = (selected.intersect(present)) + (inLibrary - knownLibrary)
        knownLibrary = inLibrary
    }

    LaunchedEffect(notice) {
        if (notice != null) {
            delay(Metrics.bookFindNoticeMs)
            if (!busy) notice = null
        }
    }

    val found = remember(index, folders) {
        index?.files.orEmpty()
            .filter { it.root.isBlank() || it.root in folders.map { folder -> folder.location } }
            .map { it.found() }
    }

    if (transition.targetState || transition.currentState) {
        Dialog(onDismissRequest = { close() },
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = false)) {
            AnimatedVisibility(
                visibleState = transition,
                enter = fadeIn(spring(Springs.sheet.damping, Springs.sheet.stiffness)) +
                    scaleIn(initialScale = Metrics.menuOpenFromScale,
                        animationSpec = spring(Springs.sheet.damping, Springs.sheet.stiffness)),
                exit = fadeOut(spring(Springs.sheet.damping, Springs.sheet.stiffness)) +
                    scaleOut(targetScale = Metrics.menuOpenFromScale,
                        animationSpec = spring(Springs.sheet.damping, Springs.sheet.stiffness)),
            ) {
                FindBooksBody(
                    skin = skin,
                    folders = folders,
                    foldersLoaded = foldersLoaded,
                    busy = busy,
                    notice = notice,
                    indexReady = index != null,
                    found = found,
                    library = library,
                    types = types,
                    query = query,
                    sort = sort,
                    selected = selected,
                    applying = applying,
                    onClose = { close() },
                    onFolders = { showFolders = true },
                    onScanAgain = { scanAgain() },
                    onTypes = { format -> types = LibraryFind.toggleType(types, format) },
                    onQuery = { query = it },
                    onSort = { sort = it },
                    onToggleRow = { row ->
                        selected = if (row.found.uri in selected) selected - row.found.uri
                        else selected + row.found.uri
                    },
                    onApply = { apply() },
                )
            }
        }
    }

    if (showFolders) {
        FolderChooserDialog(skin, folders, index, types, onDismiss = { showFolders = false }) { chosen ->
            useFolders(chosen)
        }
    }

    LaunchedEffect(transition.currentState, transition.targetState) {
        if (!transition.targetState && !transition.currentState) {
            val result = appliedCount
            if (result != null) onApplied(result) else onDismiss()
        }
    }
}

/** T226: one calm layout shared by the real sheet and the demo sheet. No file or store is read here. */
@Composable
private fun FindBooksBody(
    skin: Skin,
    folders: List<BookFolder>,
    foldersLoaded: Boolean,
    busy: Boolean,
    notice: String?,
    indexReady: Boolean,
    found: List<FoundBook>,
    library: List<Book>,
    types: Set<BookFormat>,
    query: String,
    sort: LibrarySort,
    selected: Set<String>,
    applying: Boolean,
    onClose: () -> Unit,
    onFolders: (() -> Unit)?,
    onScanAgain: (() -> Unit)?,
    onTypes: (BookFormat) -> Unit,
    onQuery: (String) -> Unit,
    onSort: (LibrarySort) -> Unit,
    onToggleRow: (LibraryFindRow) -> Unit,
    onApply: () -> Unit,
) {
    var collapsed by remember { mutableStateOf<Set<String>>(emptySet()) }
    val rows = remember(found, library, types, query, sort, selected) {
        LibraryFind.rows(found, library, types, query, sort, keepSelected = selected)
    }
    val plan = remember(found, library, types, query, sort, selected) {
        LibraryFind.plan(found, library, types, query, sort, selected, keepSelected = selected)
    }
    val groups = remember(rows) { LibraryFind.groups(rows) }
    val canApply = !applying && !busy && plan.changeCount > 0
    val addLabel = if (plan.add.isNotEmpty()) LibraryFind.addLabel(plan.add.size)
        else if (plan.changeCount > 0) "Update" else LibraryFind.addLabel(0)

    ParcDialogArea(Modifier.background(Color(skin.colors.bg.toInt()))) {
        Column(Modifier.fillMaxSize().padding(horizontal = Metrics.cardInsetDp.dp),
            verticalArrangement = Arrangement.spacedBy(Metrics.rowGapDp.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = Metrics.menuRowHeightDp.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Metrics.itemGapDp.dp)) {
                BasicText("Find books", Modifier.weight(1f),
                    style = hudValueStyle(skin).copy(fontSize = skin.typography.search.sizeSp.sp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                FindTextAction("Close", skin, onClick = onClose)
                FindAddButton(if (applying) "Adding…" else addLabel, skin, canApply, onApply)
            }

            when {
                !foldersLoaded -> FindPlainState("Reading your folders…", null, skin)
                folders.isEmpty() -> {
                    FindPlainState(
                        "Choose folders to scan",
                        "Parc only looks inside the folders you tick. Nothing is scanned until you choose.",
                        skin,
                    )
                    onFolders?.let { LibraryAction("Choose folders", skin, onClick = it) }
                }
                else -> {
                    onFolders?.let { FindFoldersRow(folders, skin, onChange = it) }
                    FindFormatControl(types, skin, onTypes)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Metrics.itemGapDp.dp)) {
                        FindSearchField(query, skin, onQuery, Modifier.weight(1f))
                        FindSortMenu(sort, skin, onSort)
                    }
                    notice?.let {
                        BasicText(it, style = hudLabelStyle(skin).copy(color = Color(skin.colors.accent.toInt())),
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = Metrics.menuRowHeightDp.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        BasicText(
                            when {
                                busy -> "Refreshing your books…"
                                !indexReady -> "Reading saved books…"
                                else -> LibraryFormat.countLine(found.size, rows.size, library.size)
                            },
                            Modifier.weight(1f), style = hudLabelStyle(skin), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        onScanAgain?.let { FindTextAction("Scan again", skin, enabled = !busy && !applying, onClick = it) }
                    }
                    when {
                        !indexReady -> FindPlainState("Reading saved books…", null, skin)
                        groups.isEmpty() -> FindPlainState(
                            if (types.isEmpty()) "No format chosen" else "No books to show",
                            LibraryFind.emptyMessage(found.size, rows.size, types, query),
                            skin,
                        )
                        else -> LazyColumn(
                            Modifier.fillMaxWidth().weight(1f),
                            verticalArrangement = Arrangement.spacedBy(Metrics.bookFindRowGapDp.dp),
                            contentPadding = PaddingValues(bottom = Metrics.bookFindStickyBarClearanceDp.dp),
                        ) {
                            groups.forEach { group ->
                                val isCollapsed = group.folder in collapsed
                                item(key = "folder-${group.folder}", contentType = "folder") {
                                    FindFolderHeader(group, isCollapsed, skin) {
                                        collapsed = LibraryFind.toggleFolder(collapsed, group.folder)
                                    }
                                }
                                if (!isCollapsed) {
                                    items(count = group.rows.size, key = { group.rows[it].found.uri },
                                        contentType = { "book" }) { position ->
                                        val row = group.rows[position]
                                        FindBookResultRow(
                                            row,
                                            row.found.uri in selected,
                                            skin,
                                            settleDelayMs = (position.coerceAtMost(8) * Metrics.bookFindRowSettleDelayMs),
                                        ) { onToggleRow(row) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = selected.isNotEmpty(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = Metrics.itemGapDp.dp),
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = spring(Springs.sheet.damping, Springs.sheet.stiffness),
            ) + fadeIn(spring(Springs.sheet.damping, Springs.sheet.stiffness)),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = spring(Springs.sheet.damping, Springs.sheet.stiffness),
            ) + fadeOut(spring(Springs.sheet.damping, Springs.sheet.stiffness)),
        ) {
            FindSelectionBar(LibraryFind.summaryLabel(selected.size, plan.add.size, plan.remove.size), if (applying) "Adding…" else addLabel, skin, canApply, onApply)
        }
    }
}

@Composable
private fun FindPlainState(title: String, detail: String?, skin: Skin) {
    Column(Modifier.fillMaxWidth().padding(vertical = Metrics.rowGapDp.dp),
        verticalArrangement = Arrangement.spacedBy(Metrics.lineGapDp.dp)) {
        BasicText(title, style = hudValueStyle(skin))
        detail?.let { BasicText(it, style = hudLabelStyle(skin), maxLines = 3, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun FindTextAction(label: String, skin: Skin, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = Metrics.minTouchDp.dp)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = Metrics.lineGapDp.dp), contentAlignment = Alignment.Center) {
        BasicText(label, style = hudLabelStyle(skin).copy(
            color = Color((if (enabled) skin.colors.accent else skin.colors.textSecondary).toInt())))
    }
}

@Composable
private fun FindAddButton(label: String, skin: Skin, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(skin.shape.surfaceCornerDp.dp)
    Box(Modifier.heightIn(min = Metrics.minTouchDp.dp).clip(shape)
        .background(Color((if (enabled) skin.colors.accent else skin.colors.lineBase).toInt()), shape)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = Metrics.cardInsetDp.dp), contentAlignment = Alignment.Center) {
        BasicText(label, style = hudLabelStyle(skin).copy(
            color = Color((if (enabled) skin.colors.bg else skin.colors.textSecondary).toInt()),
            fontSize = skin.typography.menu.sizeSp.sp,
            fontWeight = FontWeight(skin.typography.menu.weight),
        ), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** T226: one compact segmented control instead of four large outlined pills. */
@Composable
private fun FindFormatControl(types: Set<BookFormat>, skin: Skin, onToggle: (BookFormat) -> Unit) {
    val outer = RoundedCornerShape(skin.shape.surfaceCornerDp.dp)
    Row(Modifier.fillMaxWidth().clip(outer)
        .background(Color(skin.colors.bg.toInt()), outer)
        .border(skin.shape.surfaceBorderDp.dp, Color(skin.colors.lineBase.toInt()), outer)
        .padding(Metrics.bookFindSegmentGapDp.dp),
        horizontalArrangement = Arrangement.spacedBy(Metrics.bookFindSegmentGapDp.dp)) {
        LibraryFind.types.forEach { format ->
            val isSelected = format in types
            val shape = RoundedCornerShape(Metrics.bookFindBadgeCornerDp.dp)
            Box(Modifier.weight(1f).heightIn(min = Metrics.bookFindSegmentHeightDp.dp).clip(shape)
                .background(Color((if (isSelected) skin.colors.surface else skin.colors.bg).toInt()), shape)
                .toggleable(value = isSelected, role = Role.Checkbox, onValueChange = { onToggle(format) })
                .padding(horizontal = Metrics.lineGapDp.dp),
                contentAlignment = Alignment.Center) {
                BasicText(LibraryFind.typeLabel(format), style = hudLabelStyle(skin).copy(color = Color(
                    (if (isSelected) skin.colors.accent else skin.colors.textSecondary).toInt())),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun FindSearchField(query: String, skin: Skin, onQuery: (String) -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(skin.shape.surfaceCornerDp.dp)
    BasicTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        textStyle = hudValueStyle(skin).copy(fontSize = skin.typography.menu.sizeSp.sp),
        cursorBrush = SolidColor(Color(skin.colors.accent.toInt())),
        modifier = modifier.heightIn(min = Metrics.bookFindSearchHeightDp.dp)
            .background(Color(skin.colors.surface.toInt()), shape)
            .border(skin.shape.surfaceBorderDp.dp, Color(skin.colors.lineBase.toInt()), shape)
            .padding(horizontal = Metrics.cardInsetDp.dp, vertical = Metrics.rowGapDp.dp),
        decorationBox = { inner ->
            Box {
                if (query.isEmpty()) BasicText("Search by name or folder", style = hudLabelStyle(skin))
                inner()
            }
        },
    )
}

@Composable
private fun FindSortMenu(current: LibrarySort, skin: Skin, onSort: (LibrarySort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(skin.shape.surfaceCornerDp.dp)
    Box {
        Row(Modifier.widthIn(min = Metrics.bookFindSortButtonWidthDp.dp)
            .heightIn(min = Metrics.minTouchDp.dp).clip(shape)
            .border(skin.shape.surfaceBorderDp.dp, Color(skin.colors.lineBase.toInt()), shape)
            .clickable(role = Role.Button) { open = true }
            .padding(horizontal = Metrics.lineGapDp.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            BasicText(current.label, style = hudLabelStyle(skin).copy(color = Color(skin.colors.textPrimary.toInt())),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText("▾", style = hudLabelStyle(skin).copy(color = Color(skin.colors.accent.toInt())))
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = Color(skin.colors.surface.toInt()),
            shape = RoundedCornerShape(skin.shape.surfaceCornerDp.dp),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(skin.shape.surfaceBorderDp.dp, Color(skin.colors.lineBase.toInt())),
        ) {
            LibrarySort.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        BasicText(option.label, style = hudLabelStyle(skin).copy(color = Color(
                            (if (option == current) skin.colors.accent else skin.colors.textPrimary).toInt())))
                    },
                    onClick = { onSort(option); open = false },
                    modifier = Modifier.widthIn(min = Metrics.bookFindSortMenuWidthDp.dp),
                )
            }
        }
    }
}

@Composable
private fun FindFolderHeader(group: LibraryFindGroup, collapsed: Boolean, skin: Skin, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = Metrics.bookFindGroupHeaderHeightDp.dp)
        .clickable(role = Role.Button, onClick = onToggle)
        .padding(horizontal = Metrics.lineGapDp.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.itemGapDp.dp)) {
        BasicText(group.folder, Modifier.weight(1f), style = hudLabelStyle(skin).copy(
            color = Color(skin.colors.textPrimary.toInt())), maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicText("${group.rows.size}", style = hudLabelStyle(skin))
        BasicText(if (collapsed) "›" else "▾", style = hudLabelStyle(skin).copy(
            color = Color(skin.colors.accent.toInt())), maxLines = 1)
    }
}

@Composable
private fun FindBookResultRow(
    row: LibraryFindRow,
    selected: Boolean,
    skin: Skin,
    settleDelayMs: Int = 0,
    onToggle: () -> Unit,
) {
    val shape = RoundedCornerShape(skin.shape.surfaceCornerDp.dp)
    val settle = remember(row.found.uri) { Animatable(0f) }
    val settleOffsetPx = with(LocalDensity.current) { Metrics.bookFindRowSettleOffsetDp.dp.toPx() }
    LaunchedEffect(row.found.uri) {
        settle.snapTo(0f)
        if (settleDelayMs > 0) delay(settleDelayMs.toLong())
        settle.animateTo(1f, spring(Springs.open.damping, Springs.open.stiffness))
    }
    Row(Modifier.fillMaxWidth().heightIn(min = Metrics.minTouchDp.dp)
        .graphicsLayer {
            alpha = settle.value
            translationY = (1f - settle.value) * settleOffsetPx
        }
        .clip(shape)
        .background(if (selected) Color(skin.colors.accent.toInt()).copy(alpha = Metrics.bookFindSelectedTintAlpha)
            else Color.Transparent, shape)
        .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
        .padding(horizontal = Metrics.lineGapDp.dp, vertical = Metrics.lineGapDp.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.itemGapDp.dp)) {
        FindFormatBadge(row.found.format, skin)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Metrics.lineGapDp.dp)) {
            BasicText(row.found.name, style = hudValueStyle(skin).copy(
                fontSize = skin.typography.menu.sizeSp.sp,
                lineBreak = LineBreak.Paragraph,
            ), maxLines = 2, overflow = TextOverflow.Ellipsis)
            BasicText(LibraryFind.secondaryLine(row.found), style = hudLabelStyle(skin),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        FindRowCheck(selected, skin)
    }
}

@Composable
private fun FindFormatBadge(format: BookFormat, skin: Skin) {
    val shape = RoundedCornerShape(Metrics.bookFindBadgeCornerDp.dp)
    Box(Modifier.widthIn(min = Metrics.bookFindBadgeWidthDp.dp).heightIn(min = Metrics.bookFindBadgeHeightDp.dp)
        .background(Color(skin.colors.surface.toInt()), shape)
        .border(skin.shape.surfaceBorderDp.dp, Color(skin.colors.lineBase.toInt()), shape)
        .padding(horizontal = Metrics.lineGapDp.dp, vertical = Metrics.lineGapDp.dp),
        contentAlignment = Alignment.Center) {
        BasicText(LibraryFind.badgeLabel(format), style = hudLabelStyle(skin),
            maxLines = 1, overflow = TextOverflow.Clip)
    }
}

@Composable
private fun FindRowCheck(selected: Boolean, skin: Skin) {
    val shape = RoundedCornerShape(Metrics.bookFindBadgeCornerDp.dp)
    Box(Modifier.size(Metrics.bookFindTickDp.dp).clip(shape)
        .background(if (selected) Color(skin.colors.accent.toInt()) else Color.Transparent, shape)
        .border(Metrics.bookFindTickStrokeDp.dp, Color(
            (if (selected) skin.colors.accent else skin.colors.lineActive).toInt()), shape),
        contentAlignment = Alignment.Center) {
        if (selected) BasicText("✓", style = hudLabelStyle(skin).copy(color = Color(skin.colors.bg.toInt())),
            maxLines = 1)
    }
}

@Composable
private fun FindSelectionBar(summary: String, addLabel: String, skin: Skin, enabled: Boolean, onApply: () -> Unit) {
    val shape = RoundedCornerShape(topStart = skin.shape.surfaceCornerDp.dp, topEnd = skin.shape.surfaceCornerDp.dp)
    Row(Modifier.fillMaxWidth()
        .background(Color(skin.colors.surface.toInt()), shape)
        .border(BorderStroke(skin.shape.surfaceBorderDp.dp, Color(skin.colors.lineBase.toInt())), shape)
        .padding(horizontal = Metrics.cardInsetDp.dp, vertical = Metrics.rowGapDp.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.itemGapDp.dp)) {
        BasicText(summary, Modifier.weight(1f),
            style = hudValueStyle(skin).copy(fontSize = skin.typography.menu.sizeSp.sp),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        FindAddButton(addLabel, skin, enabled, onApply)
    }
}

@Composable
internal fun DemoFindBooksDialog(skin: Skin, onDismiss: () -> Unit) {
    var types by remember { mutableStateOf(LibraryFind.types.toSet()) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(LibrarySort.Newest) }
    var selected by remember {
        val libraryUris = DemoFindLibrary.mapTo(hashSetOf()) { it.uri }
        mutableStateOf<Set<String>>(
            libraryUris + DemoFoundBooks.filterNot { it.uri in libraryUris }.take(3).mapTo(hashSetOf()) { it.uri },
        )
    }
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = false)) {
        FindBooksBody(
            skin = skin,
            folders = listOf(BookFolder("demo://library", "Demo library")),
            foldersLoaded = true,
            busy = false,
            notice = null,
            indexReady = true,
            found = DemoFoundBooks,
            library = DemoFindLibrary,
            types = types,
            query = query,
            sort = sort,
            selected = selected,
            applying = false,
            onClose = onDismiss,
            onFolders = null,
            onScanAgain = null,
            onTypes = { format -> types = LibraryFind.toggleType(types, format) },
            onQuery = { query = it },
            onSort = { sort = it },
            onToggleRow = { row ->
                selected = if (row.found.uri in selected) selected - row.found.uri else selected + row.found.uri
            },
            onApply = onDismiss,
        )
    }
}

@Composable
private fun FindFoldersRow(folders: List<BookFolder>, skin: Skin, onChange: () -> Unit) {
    val summary = when {
        folders.isEmpty() -> "None chosen"
        folders.size == 1 -> folders.single().label
        else -> "${folders.size} folders"
    }
    Row(Modifier.fillMaxWidth().heightIn(min = Metrics.menuRowHeightDp.dp)
        .clickable(role = Role.Button, onClick = onChange)
        .padding(horizontal = Metrics.cardInsetDp.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.rowGapDp.dp)) {
        BasicText("Folders", style = hudValueStyle(skin).copy(fontSize = skin.typography.menu.sizeSp.sp))
        BasicText(summary, Modifier.weight(1f), style = hudLabelStyle(skin),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicText("›", style = hudLabelStyle(skin).copy(color = Color(skin.colors.accent.toInt())))
    }
}

/**
 * T225: the first step of Find books. With all-files access the storage root can be walked as plain
 * directories; without it the system's OpenDocumentTree picker supplies one persisted folder at a
 * time. Suggested folders are unticked and no file is scanned here.
 */
@Composable
private fun FolderChooserDialog(
    skin: Skin,
    initial: List<BookFolder>,
    index: BookIndex?,
    types: Set<BookFormat>,
    onDismiss: () -> Unit,
    onConfirm: (List<BookFolder>) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val files = remember(context) { BookFiles(context) }
    var allFiles by remember {
        mutableStateOf(BuildConfig.LIBRARY_ALL_FILES_ALLOWED && Environment.isExternalStorageManager())
    }
    var askAllFiles by remember { mutableStateOf(false) }
    val root = remember(files) { files.storageRoot() }
    var ticked by remember(initial) { mutableStateOf(initial.associateBy { it.location }) }
    var current by remember { mutableStateOf(root) }
    var entries by remember { mutableStateOf<List<File>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    val accessSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        allFiles = BuildConfig.LIBRARY_ALL_FILES_ALLOWED && Environment.isExternalStorageManager()
        if (allFiles) current = root
    }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            scope.launch {
                val label = withContext(Dispatchers.IO) { files.treeFolderLabel(uri) }
                val folder = BookFolder(uri.toString(), label.ifBlank { "Folder" }, tree = true)
                ticked = ticked + (folder.location to folder)
            }
        }
    }
    LaunchedEffect(current, allFiles) {
        val parent = current
        if (allFiles && parent != null) {
            loading = true
            entries = files.browseFolders(parent)
            loading = false
        } else {
            entries = emptyList()
        }
    }
    val suggestions = remember(current, root, allFiles) {
        if (allFiles && current == root) files.suggestedFolders() else emptyList()
    }
    val currentLabel = if (allFiles && current != null && root != null) files.folderLabel(root, current!!) else ""
    val shownEntries = remember(entries, suggestions) {
        entries.filterNot { entry ->
            suggestions.any { it.location == runCatching { entry.canonicalPath }.getOrDefault(entry.path) }
        }
    }
    val here = remember(current, currentLabel) {
        current?.let {
            BookFolder(
                runCatching { it.canonicalPath }.getOrDefault(it.absolutePath),
                currentLabel.ifBlank { files.rootLabel(it) },
            )
        }
    }
    val elsewhere = remember(ticked, suggestions, shownEntries, here) {
        val visible = suggestions.mapTo(hashSetOf()) { it.location } +
            shownEntries.mapTo(hashSetOf()) { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) } +
            listOfNotNull(here?.location)
        ticked.values.filterNot { it.location in visible }
    }

    fun countLabel(location: String): String? {
        val record = index?.folder(location) ?: return if (location in ticked) "Not scanned" else null
        if (record.lastScanAt <= 0) return if (location in ticked) "Not scanned" else null
        val count = index.files.count { it.root == location && it.format in types }
        return "$count ${if (count == 1) "book" else "books"}"
    }

    fun toggle(folder: BookFolder) {
        ticked = if (folder.location in ticked) ticked - folder.location else ticked + (folder.location to folder)
    }

    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = false)) {
        ParcDialogArea(Modifier.background(Color(skin.colors.bg.toInt()))) {
            Column(Modifier.fillMaxSize().padding(horizontal = Metrics.cardInsetDp.dp),
                verticalArrangement = Arrangement.spacedBy(Metrics.rowGapDp.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = Metrics.menuRowHeightDp.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    BasicText("Folders", Modifier.weight(1f), style = hudValueStyle(skin))
                    TextButton(onClick = onDismiss) { BasicText("Cancel", style = settingsText(skin, skin.colors.textSecondary)) }
                    TextButton(onClick = { onConfirm(ticked.values.toList()) }) {
                        BasicText("Done", style = settingsText(skin, skin.colors.accent))
                    }
                }
                BasicText(
                    if (allFiles) "Tick the folders Parc may scan. Nothing is scanned until you tap Done."
                    else "Add folders with the system picker. Parc keeps read access to the folders you choose.",
                    style = hudLabelStyle(skin),
                )
                if (allFiles) {
                    Row(Modifier.fillMaxWidth().heightIn(min = Metrics.menuRowHeightDp.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Metrics.rowGapDp.dp)) {
                        BasicText(currentLabel, Modifier.weight(1f), style = hudLabelStyle(skin),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (current != null && current != root) {
                            LibraryAction("Up", skin) { current = current?.parentFile ?: root }
                        }
                    }
                    LazyColumn(Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Metrics.bookFindRowGapDp.dp)) {
                        if (suggestions.isNotEmpty()) {
                            item(key = "suggested-label") { BasicText("Suggested", style = hudLabelStyle(skin)) }
                            items(count = suggestions.size, key = { suggestions[it].location }) { position ->
                                val folder = suggestions[position]
                                FolderChoiceRow(folder.label, folder.location in ticked, countLabel(folder.location), skin,
                                    onToggle = { toggle(folder) }, onOpen = null)
                            }
                        }
                        if (elsewhere.isNotEmpty()) {
                            item(key = "chosen-label") { BasicText("Chosen", style = hudLabelStyle(skin)) }
                            items(count = elsewhere.size, key = { "chosen-${elsewhere[it].location}" }) { position ->
                                val folder = elsewhere[position]
                                FolderChoiceRow(folder.label, true, countLabel(folder.location), skin,
                                    onToggle = { toggle(folder) }, onOpen = null)
                            }
                        }
                        if (here != null) {
                            item(key = "this-folder") {
                                FolderChoiceRow("Use this folder", here.location in ticked,
                                    countLabel(here.location), skin, onToggle = { toggle(here) }, onOpen = null)
                            }
                        }
                        if (shownEntries.isNotEmpty()) {
                            item(key = "folders-label") { BasicText("Folders", style = hudLabelStyle(skin)) }
                            items(count = shownEntries.size, key = { shownEntries[it].absolutePath }) { position ->
                                val child = shownEntries[position]
                                val folder = BookFolder(
                                    runCatching { child.canonicalPath }.getOrDefault(child.absolutePath),
                                    root?.let { files.folderLabel(it, child) } ?: child.name,
                                )
                                FolderChoiceRow(child.name, folder.location in ticked, countLabel(folder.location), skin,
                                    onToggle = { toggle(folder) }, onOpen = { current = child })
                            }
                        }
                        if (loading) item(key = "loading") { BasicText("Reading folders…", style = hudLabelStyle(skin)) }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Metrics.bookFindRowGapDp.dp)) {
                        if (ticked.isEmpty()) {
                            item(key = "empty") { BasicText("No folders chosen yet.", style = hudLabelStyle(skin)) }
                        } else {
                            items(count = ticked.size, key = { ticked.values.toList()[it].location }) { position ->
                                val folder = ticked.values.toList()[position]
                                FolderChoiceRow(folder.label, true, countLabel(folder.location), skin,
                                    onToggle = { toggle(folder) }, onOpen = null)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.rowGapDp.dp)) {
                        LibraryAction("Add folder", skin) { treePicker.launch(null) }
                        if (BuildConfig.LIBRARY_ALL_FILES_ALLOWED) {
                            LibraryAction("Allow file access", skin) { askAllFiles = true }
                        }
                    }
                }
            }
        }
    }
    if (askAllFiles) {
        AlertDialog(
            onDismissRequest = { askAllFiles = false },
            containerColor = Color(skin.colors.surface.toInt()),
            title = { BasicText("Folders", style = settingsText(skin)) },
            text = { BasicText(FindBooksAccess.Explanation, style = settingsText(skin, skin.colors.textSecondary, true)) },
            confirmButton = {
                TextButton(onClick = {
                    askAllFiles = false
                    runCatching {
                        accessSettings.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:${context.packageName}")))
                    }
                }) { BasicText("Continue", style = settingsText(skin, skin.colors.accent)) }
            },
            dismissButton = {
                TextButton(onClick = { askAllFiles = false }) {
                    BasicText("Use the picker", style = settingsText(skin, skin.colors.textSecondary))
                }
            },
        )
    }
}

@Composable
private fun FolderChoiceRow(
    name: String,
    selected: Boolean,
    countLabel: String?,
    skin: Skin,
    onToggle: () -> Unit,
    onOpen: (() -> Unit)?,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = Metrics.menuRowHeightDp.dp)
        .padding(horizontal = Metrics.cardInsetDp.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.rowGapDp.dp)) {
        Box(Modifier.size(Metrics.bookFindTickDp.dp)
            .border(Metrics.bookFindTickStrokeDp.dp, Color(
                (if (selected) skin.colors.accent else skin.colors.lineActive).toInt()),
                RoundedCornerShape(Metrics.bookShelfCornerDp.dp))
            .clickable(role = Role.Checkbox, onClick = onToggle),
            contentAlignment = Alignment.Center) {
            if (selected) BasicText("✓", style = hudLabelStyle(skin).copy(color = Color(skin.colors.accent.toInt())))
        }
        BasicText(name, Modifier.weight(1f)
            .then(if (onOpen != null) Modifier.clickable(role = Role.Button, onClick = onOpen) else Modifier),
            style = hudValueStyle(skin).copy(fontSize = skin.typography.menu.sizeSp.sp),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        countLabel?.let { BasicText(it, style = hudLabelStyle(skin), maxLines = 1) }
        if (onOpen != null) BasicText("›", style = hudLabelStyle(skin).copy(color = Color(skin.colors.accent.toInt())))
    }
}

internal fun bookCoverStyle(context: Context, skin: Skin): BookCoverStyle {
    val typeface = if (skin.typography.family == "monospace" || skin.typography.fallbackFamily == "monospace") {
        Typeface.MONOSPACE
    } else {
        runCatching { context.resources.getFont(com.brightblu.parc.core.model.R.font.figtree_variable) }
            .getOrDefault(Typeface.DEFAULT)
    }
    return BookCoverStyle(skin.colors.surface.toInt(), skin.colors.textPrimary.toInt(),
        skin.colors.textSecondary.toInt(), typeface)
}
