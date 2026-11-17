package com.brightblu.parc

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import com.brightblu.parc.model.readableAccent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.core.data.library.Book
import com.brightblu.parc.core.data.library.BookPosition
import com.brightblu.parc.core.data.library.ReaderHighlight
import com.brightblu.parc.core.data.library.ReaderMarksDocument
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import com.brightblu.parc.reader.ReaderContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** One kept passage, from a quote, a book highlight or text shared into Parc. */
internal enum class CommonplaceKind(val label: String) {
    Quote("Quote"),
    Highlight("Book highlight"),
    SharedText("Shared text"),
}

internal data class CommonplaceItem(
    val id: String,
    val kind: CommonplaceKind,
    val text: String,
    val source: String = "",
    val note: String = "",
    val quoteId: String = "",
    val bookId: String = "",
    val chapter: Int = 0,
    val offset: Int = 0,
    val createdAt: Long = 0,
    /** A highlight can be opened only while its book is still in the library (T270). */
    val openable: Boolean = false,
) {
    /** The quiet second line: what this passage is and where it came from. */
    val caption: String get() = listOf(kind.label, source).filter { it.isNotBlank() }.joinToString(" · ")
}

/**
 * T263: Commonplace is one list of kept passages. Shared text is saved through the existing
 * Commonplace share target; a shared quote is marked from its id so it keeps its own name in the list.
 */
internal object CommonplaceRules {
    const val SharedPrefix = "own-shared-"

    fun kindOf(quote: Quote): CommonplaceKind =
        if (quote.id.startsWith(SharedPrefix)) CommonplaceKind.SharedText else CommonplaceKind.Quote

    fun items(quotes: List<Quote>, books: List<Book>, marks: ReaderMarksDocument): List<CommonplaceItem> {
        val byId = books.associateBy { it.id }
        val quoteItems = quotes.map { quote ->
            CommonplaceItem(
                id = "quote:${quote.id}", kind = kindOf(quote), text = quote.text,
                source = quote.credit, quoteId = quote.id,
            )
        }
        val highlightItems = marks.books.flatMap { (bookId, bookMarks) ->
            val book = byId[bookId]
            bookMarks.highlights.map { highlight ->
                highlightItem(bookId, book, highlight)
            }
        }.sortedWith(compareByDescending<CommonplaceItem> { it.createdAt }
            .thenBy { it.source.lowercase() }.thenBy { it.chapter }.thenBy { it.offset })
        return quoteItems + highlightItems
    }

    fun highlightItem(bookId: String, book: Book?, highlight: ReaderHighlight): CommonplaceItem =
        CommonplaceItem(
            id = "highlight:$bookId:${highlight.id}",
            kind = CommonplaceKind.Highlight,
            text = highlight.text.ifBlank { "Highlight" },
            source = book?.let { listOf(it.title, it.author).filter(String::isNotBlank).joinToString(" · ") }
                ?: "Saved book highlight",
            note = highlight.note,
            bookId = bookId,
            chapter = highlight.chapter,
            offset = highlight.start,
            createdAt = highlight.createdAt,
            openable = book != null,
        )

    /** Search folds case and surrounding space; every word must appear in the text, source or kind. */
    fun matches(item: CommonplaceItem, query: String): Boolean {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val haystack = listOf(item.text, item.source, item.note, item.kind.label).joinToString(" ").lowercase()
        return words.all { it in haystack }
    }

    fun results(items: List<CommonplaceItem>, query: String): List<CommonplaceItem> = items.filter { matches(it, query) }

    fun emptyMessage(items: List<CommonplaceItem>, query: String): String? = when {
        items.isEmpty() -> "Nothing kept yet. Add a quote, highlight a book or share text into Parc."
        query.isNotBlank() && results(items, query).isEmpty() -> "No passages match “${query.trim()}”."
        else -> null
    }
}

@Composable
internal fun CommonplaceHubPage(skin: Skin) {
    val quotes = rememberQuotesStore()
    val scope = rememberCoroutineScope()
    val library = LocalLibraryStore.current
    val books by remember(library) { library?.books ?: kotlinx.coroutines.flow.flowOf(emptyList()) }.collectAsState(initial = emptyList())
    val marks by remember(library) { library?.marksDocument ?: kotlinx.coroutines.flow.flowOf(ReaderMarksDocument()) }.collectAsState(initial = ReaderMarksDocument())
    var starters by remember { mutableStateOf<List<Quote>>(emptyList()) }
    var quoteLibrary by remember { mutableStateOf(QuoteLibrary()) }
    var error by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf<Quote?>(null) }
    var editor by remember { mutableStateOf<QuoteDraft?>(null) }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(quotes) {
        try { starters = quotes.starters() } catch (e: CancellationException) { throw e } catch (_: Exception) { error = "Could not read kept passages." }
    }
    LaunchedEffect(quotes) {
        try { quotes.library.collect { quoteLibrary = it } } catch (e: CancellationException) { throw e } catch (_: Exception) { error = "Could not read kept passages." }
    }
    val items = remember(quoteLibrary, starters, books, marks) {
        CommonplaceRules.items(quoteLibrary.visible(starters), books, marks)
    }
    val context = LocalContext.current
    CommonplaceBody(items, error, skin, onAdd = { editor = QuoteDraft() },
        onQuoteMenu = { id -> menu = quoteLibrary.visible(starters).firstOrNull { it.id == id } },
        onHighlight = { item ->
            books.firstOrNull { it.id == item.bookId }?.let { book ->
                context.startActivity(ReaderContract.intent(context, book,
                    BookPosition(chapter = item.chapter, characterOffset = item.offset)))
            }
        })
    menu?.let { selected ->
        QuoteMenu(selected, skin, close = { menu = null }, edit = { editor = QuoteDraft.edit(selected); menu = null },
            copy = {
                clipboard.setText(AnnotatedString(listOf(selected.text, selected.credit).filter(String::isNotBlank).joinToString("\n\n")))
                menu = null
            },
            hide = { scope.launch {
                try { quotes.hide(selected.id); menu = null } catch (e: CancellationException) { throw e } catch (_: Exception) { error = "Could not save this change."; menu = null }
            } },
            delete = { scope.launch {
                try { quotes.delete(selected.id); menu = null } catch (e: CancellationException) { throw e } catch (_: Exception) { error = "Could not save this change."; menu = null }
            } })
    }
    editor?.let { draft -> QuoteEditorSheet(draft, skin, quotes) { editor = null } }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CommonplaceBody(
    items: List<CommonplaceItem>,
    error: String?,
    skin: Skin,
    onAdd: () -> Unit,
    onQuoteMenu: (String) -> Unit,
    onHighlight: (CommonplaceItem) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(items, query) { CommonplaceRules.results(items, query) }
    val empty = CommonplaceRules.emptyMessage(items, query)
    Column(Modifier.fillMaxSize()) {
        // 1.5.1: one slim row: the search field and the add button. The page name is already the tab.
        Row(Modifier.fillMaxWidth().padding(bottom = Metrics.itemGapDp.dp), verticalAlignment = Alignment.CenterVertically) {
            HubSearchField(query, "Search passages", "Search kept passages", skin, Modifier.weight(1f)) { query = it }
            Box(Modifier.size(Metrics.minTouchDp.dp).semantics { contentDescription = "Add quote" }.combinedClickable(onClick = onAdd),
                contentAlignment = Alignment.Center) {
                BasicText("+", style = hubTitleStyle(skin).copy(fontSize = 24.sp))
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().hubPageScroll(), verticalArrangement = Arrangement.spacedBy(Metrics.itemGapDp.dp)) {
            if (empty != null) BasicText(empty, style = hubCaptionStyle(skin), modifier = Modifier.padding(vertical = Metrics.itemGapDp.dp))
            if (error != null) BasicText(error, style = hudLabelStyle(skin).copy(color = Color(skin.colors.warn.toInt())))
            results.forEach { item ->
                CommonplaceCard(item, skin,
                    onClick = if (item.kind == CommonplaceKind.Highlight && item.openable) ({ onHighlight(item) }) else null,
                    onLongClick = if (item.quoteId.isNotBlank()) ({ onQuoteMenu(item.quoteId) }) else null)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CommonplaceCard(item: CommonplaceItem, skin: Skin, onClick: (() -> Unit)?, onLongClick: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth()
        .background(Color(skin.colors.paper.toInt()), RoundedCornerShape(skin.shape.surfaceCornerDp.dp))
        .then(if (onClick != null || onLongClick != null) Modifier.combinedClickable(
            role = Role.Button, onClick = { onClick?.invoke() }, onLongClick = { onLongClick?.invoke() }) else Modifier)
        .padding(Metrics.commonplaceInsetDp.dp), verticalArrangement = Arrangement.spacedBy(Metrics.commonplaceGapDp.dp)) {
        // 1.5.1: a kept passage is read in a smaller serif than Today's quote card, so several cards show at once.
        BasicText(item.text, maxLines = Metrics.commonplaceMaxLines, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Color(skin.colors.ink.toInt()), fontFamily = FontFamily.Serif,
            fontSize = Metrics.commonplaceTextSp.sp, lineHeight = Metrics.commonplaceLineSp.sp))
        if (item.note.isNotBlank()) BasicText(item.note, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Color(skin.colors.ink.toInt()).copy(alpha = 0.8f),
            fontSize = Metrics.commonplaceCaptionSp.sp))
        BasicText(item.caption, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(
            color = Color(skin.colors.ink.toInt()).copy(alpha = 0.72f), fontFamily = FontFamily.Serif,
            fontSize = Metrics.commonplaceCaptionSp.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium))
    }
}

@Composable
internal fun DemoCommonplacePage(skin: Skin) {
    val items = remember { listOf(
        CommonplaceItem("demo-quote", CommonplaceKind.Quote, "Leave a little room in the day for the things you did not plan.",
            "A sample voice · Demo notebook"),
        CommonplaceItem("demo-shared", CommonplaceKind.SharedText, "A line shared from another app, kept on this phone.",
            "Shared text"),
        CommonplaceItem("demo-highlight", CommonplaceKind.Highlight, "There were four walls, a floor and a roof.",
            "A sample book · A sample author", note = "A quiet place to start."),
    ) }
    CommonplaceBody(items, null, skin, onAdd = {}, onQuoteMenu = {}, onHighlight = {})
}
