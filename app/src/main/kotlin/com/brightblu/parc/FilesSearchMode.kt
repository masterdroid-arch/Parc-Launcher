package com.brightblu.parc

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.brightblu.parc.core.data.library.Book
import com.brightblu.parc.core.data.library.BookFiles
import com.brightblu.parc.core.data.library.BookFormat
import com.brightblu.parc.core.data.library.BookMetadataLoader
import com.brightblu.parc.core.data.library.BookPosition
import com.brightblu.parc.core.data.library.BookScanResult
import com.brightblu.parc.core.data.library.FoundBook
import com.brightblu.parc.core.data.library.LibraryStore
import com.brightblu.parc.feature.search.SearchMode
import com.brightblu.parc.feature.search.SearchModeHost
import com.brightblu.parc.feature.search.SearchModeIds
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.parcPress
import com.brightblu.parc.model.textFamily
import com.brightblu.parc.reader.ReaderContract
import com.brightblu.parc.reader.ReaderOpenPositionsStore
import com.brightblu.parc.reader.ReaderOpenWith
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** T209: only PDF, EPUB and text are listed; comics stay a Library/Find books matter. */
internal object FilesSearchRules {
    val types: Set<BookFormat> = setOf(BookFormat.PDF, BookFormat.EPUB, BookFormat.TXT)

    /** Newest first; the query only looks at the file name, never at a folder or path. */
    fun rows(files: List<FoundBook>, query: String): List<FoundBook> {
        val needle = query.trim()
        return files.asSequence()
            .filter { it.format in types }
            .filter { needle.isEmpty() || it.name.contains(needle, ignoreCase = true) }
            .sortedWith(compareByDescending<FoundBook> { it.modifiedAt }
                .thenBy { it.name.lowercase(Locale.ROOT) })
            .toList()
    }

    fun countLine(shown: Int, searching: Boolean): String = when {
        searching -> "Finding files…"
        else -> if (shown == 1) "1 file · newest first" else "$shown files · newest first"
    }
}

/** All-files access needs both a build that can ask for it and the phone's grant. */
internal object FilesSearchAccess {
    fun granted(buildAllows: Boolean, systemManager: Boolean): Boolean = buildAllows && systemManager

    fun grantedNow(): Boolean = granted(BuildConfig.LIBRARY_ALL_FILES_ALLOWED,
        Environment.isExternalStorageManager())

    /** T271: demo mode lists fixed invented files and never reads or asks for shared storage. */
    fun initialGranted(demo: Boolean): Boolean = demo || grantedNow()
}

/**
 * T209: Files mode of the search sheet. With all-files access it lists PDF, EPUB and text files by
 * name, newest first; tapping opens the reader without adding the file. Long-press offers Add to
 * library. Without access (or in the play build) it shows one row that opens Find books.
 */
internal class FilesSearchMode(
    private val library: LibraryStore?,
) : SearchMode {
    override val id = SearchModeIds.Files
    override val label = "Files"
    override val hint = "Find a file by name"
    override val prefix: String? = "f "

    private var scanned by mutableStateOf<List<FoundBook>?>(null)
    private var scanning by mutableStateOf(false)
    private var trouble by mutableStateOf<String?>(null)
    private var notice by mutableStateOf<String?>(null)

    override fun onEnter(text: String, host: SearchModeHost): Boolean = false

    @Composable
    override fun Body(text: String, skin: Skin, host: SearchModeHost) {
        val context = LocalContext.current
        val owner = LocalLifecycleOwner.current
        val scope = rememberCoroutineScope()
        val books by remember(library) { library?.books ?: flowOf(emptyList<Book>()) }
            .collectAsState(initial = emptyList())
        // T262/T271: demo mode never reads shared storage; it lists the fixed, invented demo files.
        var granted by remember { mutableStateOf(FilesSearchAccess.initialGranted(DemoMode.enabled)) }
        var findOpen by remember { mutableStateOf(false) }
        var actionFor by remember { mutableStateOf<FoundBook?>(null) }
        var reload by remember { mutableStateOf(0) }
        val files = remember(context) { BookFiles(context) }
        val positions = remember(context) { ReaderOpenPositionsStore(context) }

        DisposableEffect(owner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    granted = FilesSearchAccess.initialGranted(DemoMode.enabled)
                }
            }
            owner.lifecycle.addObserver(observer)
            onDispose { owner.lifecycle.removeObserver(observer) }
        }

        // Each time the mode opens it asks the phone once, off the main thread. Typing only
        // filters the snapshot; it never starts another scan.
        LaunchedEffect(granted, reload) {
            if (!granted) return@LaunchedEffect
            if (DemoMode.enabled) { scanned = DemoFoundBooks; return@LaunchedEffect }
            scanning = true
            scanned = null
            trouble = null
            try {
                when (val result = withContext(Dispatchers.IO) {
                    files.scanner(BuildConfig.LIBRARY_ALL_FILES_ALLOWED).find(FilesSearchRules.types)
                }) {
                    is BookScanResult.Files -> scanned = result.files
                    BookScanResult.PickerRequired -> granted = false
                }
            } catch (_: Exception) {
                trouble = "Could not read shared storage."
            } finally {
                scanning = false
            }
        }

        LaunchedEffect(notice) {
            if (notice != null) {
                delay(Metrics.bookFindNoticeMs)
                notice = null
            }
        }

        fun open(file: FoundBook) {
            if (DemoMode.enabled) { notice = "Demo files are not real, so nothing opens."; return }
            val hash = ReaderContract.hashFor(file)
            val position = positions.position(hash) ?: BookPosition()
            ReaderContract.openUnadded(context, file, position, hash)
        }

        fun add(file: FoundBook) {
            if (DemoMode.enabled) { actionFor = null; notice = "Demo mode: nothing is added."; return }
            val store = library ?: return
            scope.launch {
                val now = System.currentTimeMillis()
                val style = bookCoverStyle(context, skin)
                val loaded = runCatching {
                    BookMetadataLoader(context).load(file, style, addedAt = now)
                }.getOrNull()
                val book = loaded?.book ?: ReaderOpenWith.book(file).copy(addedAt = now)
                runCatching { store.add(book) }
                actionFor = null
                notice = "Added to library"
            }
        }

        val caption = captionStyle(skin)
        Column(Modifier.fillMaxWidth().padding(
            horizontal = Metrics.textGutterDp.dp, vertical = Metrics.rowGapDp.dp)) {
            if (!granted) {
                FilesActionRow(
                    title = "Files",
                    line = "All-files access is off, so files are not listed here.",
                    action = "Open Find books",
                    skin = skin,
                    onAction = { findOpen = true },
                )
            } else {
                val rows = remember(scanned, text) { FilesSearchRules.rows(scanned.orEmpty(), text) }
                BasicText(FilesSearchRules.countLine(rows.size, scanning), style = caption)
                trouble?.let { BasicText(it, style = caption.copy(color = Color(skin.colors.danger.toInt()))) }
                if (!scanning && scanned != null && rows.isEmpty()) {
                    BasicText(
                        if (text.isBlank()) "No PDF, EPUB or text files found." else "No files match.",
                        Modifier.padding(vertical = Metrics.rowGapDp.dp),
                        style = caption,
                    )
                }
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = Metrics.searchFilesMaxHeightDp.dp),
                    verticalArrangement = Arrangement.spacedBy(Metrics.searchFilesRowGapDp.dp),
                ) {
                    items(count = rows.size, key = { rows[it].uri }) { index ->
                        val file = rows[index]
                        val inLibrary = ReaderOpenWith.inLibrary(file, books)
                        FilesRow(file = file, inLibrary = inLibrary, skin = skin,
                            onOpen = { open(file) }, onLongPress = { actionFor = file })
                    }
                }
            }
            notice?.let {
                BasicText(it, Modifier.padding(top = Metrics.rowGapDp.dp),
                    style = caption.copy(color = Color(skin.colors.accent.toInt())))
            }
        }

        if (findOpen) {
            // T268: demo screenshots use the fixed fictional list from every Find books route,
            // so no folder chooser or phone scan can appear.
            if (DemoMode.enabled) {
                DemoFindBooksDialog(skin) { findOpen = false }
            } else {
                FindBooksDialog(skin, onDismiss = { findOpen = false }) {
                    findOpen = false
                    reload++
                    notice = "Library updated."
                }
            }
        }
        actionFor?.let { file ->
            val inLibrary = ReaderOpenWith.inLibrary(file, books)
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { actionFor = null },
                containerColor = Color(skin.colors.surface.toInt()),
                title = {
                    BasicText(if (inLibrary) "In library" else "Add to library", style = hudValueStyle(skin))
                },
                text = {
                    BasicText(
                        if (inLibrary) "${file.name} is already in the library."
                        else "${file.name}\n${file.folderLabel} · ${LibraryFormat.size(file.sizeBytes)} · " +
                            "${LibraryFormat.formatLabel(file.format)}\n\nThe file stays where it is.",
                        style = caption.copy(color = Color(skin.colors.textSecondary.toInt())),
                    )
                },
                confirmButton = {
                    if (inLibrary) {
                        androidx.compose.material3.TextButton(onClick = { actionFor = null }) {
                            BasicText("Close", style = caption.copy(color = Color(skin.colors.accent.toInt())))
                        }
                    } else {
                        androidx.compose.material3.TextButton(onClick = { add(file) }) {
                            BasicText("Add", style = caption.copy(color = Color(skin.colors.accent.toInt())))
                        }
                    }
                },
                dismissButton = if (inLibrary) null else {
                    {
                        androidx.compose.material3.TextButton(onClick = { actionFor = null }) {
                            BasicText("Cancel", style = caption)
                        }
                    }
                },
            )
        }
    }

    private fun captionStyle(skin: Skin): TextStyle = TextStyle(
        fontFamily = skin.textFamily,
        color = Color(skin.colors.textSecondary.toInt()),
        fontSize = skin.typography.caption.sizeSp.sp,
        fontWeight = FontWeight(skin.typography.caption.weight),
        letterSpacing = skin.typography.caption.trackingSp.sp,
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FilesRow(
    file: FoundBook,
    inLibrary: Boolean,
    skin: Skin,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    val shape = RoundedCornerShape(skin.shape.surfaceCornerDp.dp)
    val description = buildString {
        append(file.name)
        append(". ")
        append(file.folderLabel)
        append(", ")
        append(LibraryFormat.size(file.sizeBytes))
        append(", ")
        append(LibraryFormat.formatLabel(file.format))
        if (inLibrary) append(". In library")
    }
    Column(
        Modifier.fillMaxWidth()
            .background(Color(skin.colors.surface.toInt()), shape)
            .border(skin.shape.surfaceBorderDp.dp, Color(skin.colors.lineBase.toInt()), shape)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                customActions = listOf(CustomAccessibilityAction("Add to library") { onLongPress(); true })
            }
            .combinedClickable(
                role = Role.Button,
                onClickLabel = "Open file",
                onLongClickLabel = "Add to library",
                onClick = onOpen,
                onLongClick = onLongPress,
            )
            .padding(horizontal = Metrics.cardInsetDp.dp, vertical = Metrics.searchFilesRowGapDp.dp),
    ) {
        BasicText(file.name, style = hudValueStyle(skin).copy(fontSize = skin.typography.menu.sizeSp.sp),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Metrics.searchFilesRowGapDp.dp)) {
            BasicText(
                "${file.folderLabel} · ${LibraryFormat.size(file.sizeBytes)} · ${LibraryFormat.formatLabel(file.format)}",
                Modifier.weight(1f),
                style = hudLabelStyle(skin), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (inLibrary) {
                BasicText("In library", style = hudLabelStyle(skin).copy(color = Color(skin.colors.accent.toInt())))
            }
        }
    }
}

@Composable
private fun FilesActionRow(
    title: String,
    line: String,
    action: String,
    skin: Skin,
    onAction: () -> Unit,
) {
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Column(
        Modifier.fillMaxWidth()
            .background(Color(skin.colors.surface.toInt()), RoundedCornerShape(skin.shape.surfaceCornerDp.dp))
            .border(skin.shape.surfaceBorderDp.dp, Color(skin.colors.lineBase.toInt()),
                RoundedCornerShape(skin.shape.surfaceCornerDp.dp))
            .parcPress(source)
            .heightIn(min = Metrics.searchRowHeightDp.dp)
            .clickable(interactionSource = source, indication = null, role = Role.Button) { onAction() }
            .padding(horizontal = Metrics.cardInsetDp.dp, vertical = Metrics.rowGapDp.dp),
    ) {
        BasicText(title, style = hudValueStyle(skin).copy(fontSize = skin.typography.menu.sizeSp.sp))
        BasicText(line, style = hudLabelStyle(skin))
        BasicText(action, Modifier.padding(top = Metrics.searchFilesRowGapDp.dp),
            style = hudLabelStyle(skin).copy(color = Color(skin.colors.accent.toInt())))
    }
}
