package com.brightblu.parc.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.brightblu.parc.core.data.library.ReaderBookDoc
import com.brightblu.parc.core.data.library.ReaderSearch
import com.brightblu.parc.core.data.library.ReaderSearchHit
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import kotlinx.coroutines.delay

internal object ReaderSearchMessages {
    const val PdfNeedsNewerPhone = "Searching inside a PDF needs Android 15 or newer. This phone cannot read its text."
    const val PdfNoText = "No matches. A scanned PDF has no text to search."
    const val TypeMore = "Type at least two letters."
    const val Searching = "Searching…"
    const val NoMatch = "No matches."
    fun capped(count: Int) = "First $count matches shown. Type more to narrow it."
}

/**
 * T344: the query, the result list and its scroll live above the sheet, so following a match and
 * pressing Back returns to the same list at the same place.
 */
internal class ReaderSearchSession {
    var query by mutableStateOf("")
    var hits by mutableStateOf<List<ReaderSearchHit>?>(null)
    var searched by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
}

/**
 * T254: search inside the open book. One field, one list; each row shows the match in its sentence
 * and where it is (chapter and percent, or page). Tap a row to jump there. Search starts after a
 * short pause in typing and is replaced as the text changes.
 */
@Composable
internal fun ReaderSearchSheet(
    skin: Skin,
    doc: ReaderBookDoc?,
    unavailable: String?,
    emptyHint: String?,
    search: suspend (String) -> List<ReaderSearchHit>,
    onDismiss: () -> Unit,
    onJump: (ReaderSearchHit) -> Unit,
    session: ReaderSearchSession = remember { ReaderSearchSession() },
    listState: LazyListState = rememberLazyListState(),
) {
    val query = session.query
    LaunchedEffect(query) {
        if (session.searched == query && session.hits != null) return@LaunchedEffect
        session.hits = null
        session.searched = null
        if (unavailable != null || !ReaderSearch.isSearchable(query)) { session.busy = false; return@LaunchedEffect }
        session.busy = true
        delay(300)
        session.hits = search(query)
        session.searched = query
        session.busy = false
    }
    val shape = RoundedCornerShape(Metrics.readerSheetCornerDp.dp)
    ReaderSheetFrame("Search in book", skin, onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(Metrics.readerContentsHeightFraction),
            verticalArrangement = Arrangement.spacedBy(Metrics.readerRowGapDp.dp)) {
            BasicTextField(
                value = query,
                onValueChange = { session.query = it.take(80) },
                singleLine = true,
                enabled = unavailable == null,
                textStyle = readerValueStyle(skin),
                cursorBrush = SolidColor(Color(skin.colors.accent.toInt())),
                modifier = Modifier.fillMaxWidth().heightIn(min = Metrics.readerTouchTargetDp.dp)
                    .background(Color(skin.colors.surface.toInt()), shape)
                    .border(Metrics.readerHairlineDp.dp, Color(skin.colors.lineActive.toInt()), shape)
                    .padding(horizontal = Metrics.readerChipInsetDp.dp, vertical = Metrics.readerRowGapDp.dp)
                    .semantics { contentDescription = "Search in book" },
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) BasicText("Search this book", style = readerLabelStyle(skin))
                        inner()
                    }
                },
            )
            val current = session.hits
            when {
                unavailable != null -> BasicText(unavailable, style = readerLabelStyle(skin))
                !ReaderSearch.isSearchable(query) -> BasicText(
                    if (query.isEmpty()) "" else ReaderSearchMessages.TypeMore, style = readerLabelStyle(skin))
                session.busy || current == null -> BasicText(ReaderSearchMessages.Searching, style = readerLabelStyle(skin))
                current.isEmpty() -> BasicText(emptyHint ?: ReaderSearchMessages.NoMatch, style = readerLabelStyle(skin))
                else -> {
                    BasicText(
                        if (current.size >= ReaderSearch.MaxResults) ReaderSearchMessages.capped(current.size)
                        else if (current.size == 1) "1 match" else "${current.size} matches",
                        style = readerLabelStyle(skin))
                    LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState,
                        verticalArrangement = Arrangement.spacedBy(Metrics.readerRowGapDp.dp)) {
                        items(current, key = { "${it.chapter}:${it.page}:${it.offset}" }) { hit ->
                            Column(Modifier.fillMaxWidth()
                                .background(Color(skin.colors.surface.toInt()), shape)
                                .border(Metrics.readerHairlineDp.dp, Color(skin.colors.lineBase.toInt()), shape)
                                .clickable(role = androidx.compose.ui.semantics.Role.Button) { onJump(hit) }
                                .padding(horizontal = Metrics.readerChipInsetDp.dp, vertical = Metrics.readerRowGapDp.dp)) {
                                BasicText(
                                    buildAnnotatedString {
                                        append(hit.before)
                                        withStyle(SpanStyle(color = Color(skin.colors.accent.toInt()))) { append(hit.match) }
                                        append(hit.after)
                                    },
                                    style = readerValueStyle(skin), maxLines = 3, overflow = TextOverflow.Ellipsis)
                                BasicText(ReaderSearch.where(doc, hit), style = readerLabelStyle(skin))
                            }
                        }
                    }
                }
            }
        }
    }
}
