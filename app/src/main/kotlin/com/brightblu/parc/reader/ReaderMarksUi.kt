package com.brightblu.parc.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brightblu.parc.core.data.library.Book
import com.brightblu.parc.core.data.library.BookPosition
import com.brightblu.parc.core.data.library.ReaderBookDoc
import com.brightblu.parc.core.data.library.ReaderBookMarks
import com.brightblu.parc.core.data.library.ReaderBookmark
import com.brightblu.parc.core.data.library.ReaderHighlight
import com.brightblu.parc.core.data.library.ReaderHighlightTone
import com.brightblu.parc.core.data.library.ReaderMarksLimits
import com.brightblu.parc.core.data.library.ReaderMarksMath
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One normalised selection rectangle returned by the pagination script. */
internal data class ReaderSelectionRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** The selected text range and where its handles are drawn, all in 0..1 viewport fractions. */
internal data class ReaderSelection(
    val chapter: Int,
    val start: Int,
    val end: Int,
    val text: String,
    val rects: List<ReaderSelectionRect>,
    val startHandle: Pair<Float, Float>,
    val endHandle: Pair<Float, Float>,
) {
    companion object {
        fun parse(raw: String?): ReaderSelection? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val json = Json.parseToJsonElement(raw).jsonObject
                val array = json["rects"] as? JsonArray ?: JsonArray(emptyList())
                val rects = array.mapNotNull { element ->
                    val row = runCatching { element.jsonArray }.getOrNull() ?: return@mapNotNull null
                    if (row.size < 4) return@mapNotNull null
                    ReaderSelectionRect(
                        row[0].jsonPrimitive.float, row[1].jsonPrimitive.float,
                        row[2].jsonPrimitive.float, row[3].jsonPrimitive.float,
                    )
                }
                val start = json["start"]?.jsonArray
                val end = json["end"]?.jsonArray
                if (start == null || end == null || start.size < 2 || end.size < 2) return null
                val text = json["text"]?.jsonPrimitive?.content.orEmpty()
                val chapter = json["chapter"]?.jsonPrimitive?.int ?: 0
                val from = json["startOffset"]?.jsonPrimitive?.int ?: 0
                val to = json["endOffset"]?.jsonPrimitive?.int ?: from
                if (text.isBlank() || chapter < 0 || from < 0 || to <= from) return null
                ReaderSelection(chapter, from, to, text, rects,
                    start[0].jsonPrimitive.float to start[1].jsonPrimitive.float,
                    end[0].jsonPrimitive.float to end[1].jsonPrimitive.float)
            }.getOrNull()
        }
    }
}

internal fun readerHighlightColor(skin: Skin, tone: ReaderHighlightTone): Long = when (tone) {
    ReaderHighlightTone.Sand -> skin.colors.highlightSand
    ReaderHighlightTone.Sage -> skin.colors.highlightSage
    ReaderHighlightTone.Rose -> skin.colors.highlightRose
}

/** CSS rgba for one highlight wash; the alpha is a Metrics token, not a literal in UI code. */
internal fun readerHighlightCss(skin: Skin, tone: ReaderHighlightTone): String {
    val value = readerHighlightColor(skin, tone)
    val red = (value shr 16) and 0xFF
    val green = (value shr 8) and 0xFF
    val blue = value and 0xFF
    return String.format(Locale.US, "rgba(%d,%d,%d,%.3f)", red, green, blue, Metrics.readerHighlightAlpha)
}

internal fun readerHighlightColorsJson(skin: Skin): String = buildString {
    append('{')
    ReaderHighlightTone.entries.forEachIndexed { index, tone ->
        if (index > 0) append(',')
        append('"').append(tone.name.lowercase()).append("\":\"")
        append(readerHighlightCss(skin, tone)).append('"')
    }
    append('}')
}

/** Draws the current selection lightly under the text and its two round handles. */
@Composable
internal fun ReaderSelectionLayer(
    selection: ReaderSelection,
    skin: Skin,
    onDragHandle: (String, Float, Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val latestSelection = rememberUpdatedState(selection)
    val latestDrag = rememberUpdatedState(onDragHandle)
    val latestDismiss = rememberUpdatedState(onDismiss)
    val density = LocalDensity.current
    val grab = with(density) { Metrics.readerSelectionHandleGrabDp.dp.toPx() }
    Box(Modifier.fillMaxSize()
        .pointerInput(Unit) {
            var active: String? = null
            detectDragGestures(
                onDragStart = { point ->
                    val current = latestSelection.value
                    val start = Offset(current.startHandle.first * size.width, current.startHandle.second * size.height)
                    val end = Offset(current.endHandle.first * size.width, current.endHandle.second * size.height)
                    active = when {
                        (point - start).getDistance() <= grab -> "start"
                        (point - end).getDistance() <= grab -> "end"
                        else -> null
                    }
                },
                onDrag = { change, _ ->
                    val handle = active ?: return@detectDragGestures
                    change.consume()
                    if (size.width > 0 && size.height > 0) {
                        latestDrag.value(handle, change.position.x / size.width, change.position.y / size.height)
                    }
                },
                onDragEnd = { active = null },
                onDragCancel = { active = null },
            )
        }
        .pointerInput(Unit) { detectTapGestures { latestDismiss.value() } }) {
        Canvas(Modifier.fillMaxSize()) {
            val wash = Color(skin.colors.accent.toInt()).copy(alpha = Metrics.readerSelectionAlpha)
            selection.rects.forEach { rect ->
                drawRect(wash, Offset(rect.left * size.width, rect.top * size.height),
                    Size((rect.right - rect.left) * size.width, (rect.bottom - rect.top) * size.height))
            }
            val radius = Metrics.readerSelectionHandleDp.dp.toPx()
            val handle = Color(skin.colors.accent.toInt())
            val halo = Color(skin.colors.surface.toInt())
            listOf(selection.startHandle, selection.endHandle).forEach { point ->
                val centre = Offset(point.first * size.width, point.second * size.height)
                drawCircle(halo, radius + Metrics.readerSelectionHaloDp.dp.toPx(), centre)
                drawCircle(handle, radius, centre)
            }
        }
    }
}

/** The small selection menu: three tones, Copy, Define, Translate, Add to Commonplace and Note. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderSelectionMenu(
    selection: ReaderSelection,
    skin: Skin,
    translateAvailable: Boolean,
    defineNotice: String?,
    onHighlight: (ReaderHighlightTone) -> Unit,
    onCopy: () -> Unit,
    onDefine: () -> Unit,
    onTranslate: () -> Unit,
    onAddToLightly: () -> Unit,
    onNote: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()
        .padding(horizontal = Metrics.readerSelectionMenuInsetDp.dp)
        .background(Color(skin.colors.surface.toInt()), RoundedCornerShape(Metrics.readerSheetCornerDp.dp))
        .border(Metrics.readerHairlineDp.dp, Color(skin.colors.lineActive.toInt()),
            RoundedCornerShape(Metrics.readerSheetCornerDp.dp))
        .padding(Metrics.readerChipInsetDp.dp),
        verticalArrangement = Arrangement.spacedBy(Metrics.readerChipGapDp.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.readerChipGapDp.dp),
            verticalAlignment = Alignment.CenterVertically) {
            BasicText("Highlight", Modifier.weight(1f), style = readerLabelStyle(skin))
            ReaderHighlightTone.entries.forEach { tone ->
                val label = ReaderMarksMath.toneLabel(tone)
                Box(Modifier.size(Metrics.readerSelectionSwatchDp.dp)
                    .background(Color(readerHighlightColor(skin, tone).toInt()),
                        RoundedCornerShape(Metrics.readerChipGapDp.dp))
                    .border(Metrics.readerHairlineDp.dp, Color(skin.colors.lineBase.toInt()),
                        RoundedCornerShape(Metrics.readerChipGapDp.dp))
                    .semantics { contentDescription = "Highlight $label" }
                    .clickable(role = Role.Button) { onHighlight(tone) })
            }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.readerChipGapDp.dp)) {
            ReaderBarAction("Copy", skin, onCopy)
            ReaderBarAction("Define", skin, onDefine)
            if (translateAvailable) ReaderBarAction("Translate", skin, onTranslate)
            ReaderBarAction("Note", skin, onNote)
            ReaderBarAction("Close", skin, onDismiss)
        }
        defineNotice?.let { line ->
            BasicText(line, style = readerLabelStyle(skin).copy(color = Color(skin.colors.textPrimary.toInt())))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            ReaderBarAction("Add to Commonplace", skin, onAddToLightly)
        }
        BasicText("${selection.text.length} characters selected", style = readerLabelStyle(skin))
    }
}

@Composable
internal fun ReaderBookmarkRibbon(skin: Skin, modifier: Modifier = Modifier) {
    Canvas(modifier
        .width(Metrics.readerMarkRibbonWidthDp.dp)
        .height(Metrics.readerMarkRibbonHeightDp.dp)
        .semantics { contentDescription = "Bookmarked page" }) {
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width, size.height)
            lineTo(size.width / 2f, size.height * Metrics.readerMarkRibbonNotchFraction)
            lineTo(0f, size.height)
            close()
        }
        drawPath(path, Color(skin.colors.accent.toInt()).copy(alpha = Metrics.readerMarkRibbonAlpha))
    }
}

@Composable
internal fun ReaderNoteDialog(
    initial: String,
    skin: Skin,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var note by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(skin.colors.surface.toInt()),
        title = { BasicText("Note", style = readerTitleStyle(skin)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.readerRowGapDp.dp)) {
                BasicTextField(note, { if (it.length <= ReaderMarksLimits.MaxNote) note = it },
                    Modifier.fillMaxWidth().heightIn(min = Metrics.readerTouchTargetDp.dp)
                        .semantics { contentDescription = "Highlight note" },
                    textStyle = readerValueStyle(skin), cursorBrush = SolidColor(Color(skin.colors.accent.toInt())))
                BasicText("${note.length} / ${ReaderMarksLimits.MaxNote}", style = readerLabelStyle(skin))
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(note) }) {
                BasicText("Save", style = readerValueStyle(skin).copy(color = Color(skin.colors.accent.toInt())))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { BasicText("Cancel", style = readerValueStyle(skin)) }
        },
    )
}

private data class MarkRow(
    val key: String,
    val heading: Boolean = false,
    val label: String = "",
    val snippet: String = "",
    val note: String = "",
    val target: BookPosition? = null,
    val highlightId: String? = null,
    val bookmarkId: String? = null,
    val chapter: Int = 0,
    val offset: Int = 0,
)

/** The Marks panel: bookmarks and highlights in chapter order, with snippets and jump targets. */
@Composable
internal fun ReaderMarksSheet(
    book: Book,
    doc: ReaderBookDoc?,
    marks: ReaderBookMarks,
    isPdf: Boolean,
    pageCount: Int,
    skin: Skin,
    onDismiss: () -> Unit,
    onJump: (BookPosition) -> Unit,
    onDeleteBookmark: (String) -> Unit,
    onDeleteHighlight: (String) -> Unit,
    onAddToLightly: (String) -> Unit,
) {
    val rows = remember(marks, doc, isPdf, pageCount) { markRows(book, doc, marks, isPdf, pageCount) }
    ReaderSheetFrame("Marks", skin, onDismiss) {
        Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(Metrics.readerRowGapDp.dp)) {
            if (isPdf) BasicText(ReaderMarksMath.PdfMarksExplanation, style = readerLabelStyle(skin))
            if (rows.isEmpty()) {
                BasicText(if (isPdf) "No bookmarks yet. Bookmark a page from the reading bar."
                    else "No bookmarks or highlights yet. Bookmark a page or select text.",
                    style = readerLabelStyle(skin))
            } else {
                BasicText("Tap to jump. Swipe left or hold a row to delete.", style = readerLabelStyle(skin))
                LazyColumn(Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Metrics.readerRowGapDp.dp)) {
                    items(rows, key = { it.key }) { row ->
                        if (row.heading) {
                            BasicText(row.label, Modifier.fillMaxWidth().padding(top = Metrics.readerRowGapDp.dp),
                                style = readerLabelStyle(skin).copy(color = Color(skin.colors.textPrimary.toInt())))
                        } else {
                            MarkSheetRow(row, skin, onJump, onDeleteBookmark, onDeleteHighlight, onAddToLightly)
                        }
                    }
                }
            }
        }
    }
}

private fun markRows(book: Book, doc: ReaderBookDoc?, marks: ReaderBookMarks, isPdf: Boolean, pageCount: Int): List<MarkRow> {
    if (isPdf) {
        if (marks.bookmarks.isEmpty()) return emptyList()
        val rows = mutableListOf<MarkRow>()
        rows += MarkRow("heading-pages", heading = true, label = "Bookmarks")
        marks.bookmarks.sortedBy { it.page }.forEach { bookmark ->
            val page = bookmark.page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            rows += MarkRow("bookmark-${bookmark.id}", label = "Bookmark · Page ${page + 1}",
                snippet = bookmark.snippet.ifBlank { "Page ${page + 1}" },
                target = BookPosition(page = page), bookmarkId = bookmark.id, chapter = 0, offset = page)
        }
        return rows
    }
    val grouped = mutableListOf<MarkRow>()
    val chapters = (marks.bookmarks.map { it.chapter } + marks.highlights.map { it.chapter }).distinct().sorted()
    chapters.forEach { chapter ->
        val title = doc?.chapters?.getOrNull(chapter)?.title?.takeIf { it.isNotBlank() } ?: "Chapter ${chapter + 1}"
        grouped += MarkRow("heading-$chapter", heading = true, label = title)
        marks.bookmarks.filter { it.chapter == chapter }.sortedBy { it.characterOffset }.forEach { bookmark ->
            grouped += MarkRow("bookmark-${bookmark.id}", label = "Bookmark", snippet = bookmark.snippet,
                target = BookPosition(chapter = chapter, characterOffset = bookmark.characterOffset),
                bookmarkId = bookmark.id, chapter = chapter, offset = bookmark.characterOffset)
        }
        marks.highlights.filter { it.chapter == chapter }.sortedBy { it.start }.forEach { highlight ->
            grouped += MarkRow("highlight-${highlight.id}", label = "Highlight · ${ReaderMarksMath.toneLabel(highlight.tone)}",
                snippet = highlight.text.ifBlank { doc?.chapters?.getOrNull(chapter)?.text?.let {
                    ReaderMarksMath.snippet(it, highlight.start, highlight.end)
                }.orEmpty() },
                note = highlight.note, target = BookPosition(chapter = chapter, characterOffset = highlight.start),
                highlightId = highlight.id, chapter = chapter, offset = highlight.start)
        }
    }
    return grouped
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MarkSheetRow(
    row: MarkRow,
    skin: Skin,
    onJump: (BookPosition) -> Unit,
    onDeleteBookmark: (String) -> Unit,
    onDeleteHighlight: (String) -> Unit,
    onAddToLightly: (String) -> Unit,
) {
    val target = row.target ?: return
    val latestJump = rememberUpdatedState(onJump)
    val latestBookmark = rememberUpdatedState(onDeleteBookmark)
    val latestHighlight = rememberUpdatedState(onDeleteHighlight)
    val density = LocalDensity.current
    val threshold = with(density) { Metrics.readerMarkRowSwipeDp.dp.toPx() }
    Column(Modifier.fillMaxWidth()
        .background(Color(skin.colors.surface.toInt()), RoundedCornerShape(Metrics.readerSheetCornerDp.dp))
        .border(Metrics.readerHairlineDp.dp, Color(skin.colors.lineBase.toInt()),
            RoundedCornerShape(Metrics.readerSheetCornerDp.dp))
        .combinedClickable(role = Role.Button,
            onClick = { latestJump.value(target) },
            onLongClickLabel = "Delete mark",
            onLongClick = {
                row.highlightId?.let { latestHighlight.value(it) }
                row.bookmarkId?.let { latestBookmark.value(it) }
            })
        .pointerInput(row.key) {
            var travel = 0f
            detectHorizontalDragGestures(
                onDragStart = { travel = 0f },
                onHorizontalDrag = { change, amount -> travel += amount; change.consume() },
                onDragEnd = {
                    if (travel <= -threshold) {
                        row.highlightId?.let { latestHighlight.value(it) }
                        row.bookmarkId?.let { latestBookmark.value(it) }
                    }
                    travel = 0f
                },
                onDragCancel = { travel = 0f },
            )
        }
        .padding(horizontal = Metrics.readerChipInsetDp.dp, vertical = Metrics.readerRowGapDp.dp),
        verticalArrangement = Arrangement.spacedBy(Metrics.readerChipGapDp.dp)) {
        BasicText(row.label, style = readerLabelStyle(skin).copy(color = Color(skin.colors.accent.toInt())))
        BasicText(row.snippet.ifBlank { row.label }, style = readerValueStyle(skin),
            maxLines = Metrics.readerMarkRowMaxLines, overflow = TextOverflow.Ellipsis)
        if (row.note.isNotBlank()) BasicText("Note · ${row.note}", style = readerLabelStyle(skin),
            maxLines = Metrics.readerMarkRowMaxLines, overflow = TextOverflow.Ellipsis)
        if (row.highlightId != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ReaderBarAction("Add to Commonplace", skin, onClick = {
                    onAddToLightly(row.snippet.ifBlank { row.label })
                })
            }
        }
    }
}
