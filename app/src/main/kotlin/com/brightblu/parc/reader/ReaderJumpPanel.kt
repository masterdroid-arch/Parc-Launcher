package com.brightblu.parc.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.brightblu.parc.core.data.library.ReaderBookDoc
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import kotlin.math.roundToInt

/** The two chapter (or page) directions shown under the slider. */
internal data class ReaderJumpSteps(
    val previousLabel: String,
    val nextLabel: String,
    val canPrevious: Boolean,
    val canNext: Boolean,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
)

/**
 * T344: the one bottom panel. Previewing a drag never moves the book; the seek happens on release.
 * Every control has a 48 dp target, a name, and an adjustable accessibility range.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderJumpPanel(
    kind: ReaderJumpKind,
    fraction: Float,
    label: String,
    entryText: String,
    singlePage: Boolean,
    doc: ReaderBookDoc?,
    pageCount: Int,
    skin: Skin,
    onSeek: (Float) -> Unit,
    onStep: (Int) -> Unit,
    onEntry: () -> Unit,
    onContents: (() -> Unit)?,
    onBookmarks: (() -> Unit)?,
    onNotes: (() -> Unit)?,
    onFind: (() -> Unit)?,
    previousPlace: (() -> Unit)?,
    steps: ReaderJumpSteps?,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Metrics.readerRowGapDp.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicText(label, Modifier.weight(1f), style = readerLabelStyle(skin),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!singlePage) {
                ReaderBarAction("−", skin, { onStep(-1) }, description = "One step back")
                ReaderBarAction(entryText, skin, onEntry, description = ReaderJump.entryTitle(kind))
                ReaderBarAction("+", skin, { onStep(1) }, description = "One step forward")
            }
        }
        if (!singlePage) {
            ReaderJumpSlider(kind, fraction, doc, pageCount, skin, onSeek, onStep)
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.readerChipGapDp.dp)) {
            onContents?.let { ReaderBarAction("Contents", skin, it) }
            onBookmarks?.let { ReaderBarAction("Bookmarks", skin, it) }
            onNotes?.let { ReaderBarAction("Notes", skin, it) }
            onFind?.let { ReaderBarAction("Find", skin, it) }
            previousPlace?.let { ReaderBarAction("Previous place", skin, it) }
        }
        steps?.let { jump ->
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.readerChipGapDp.dp)) {
                ReaderBarAction(jump.previousLabel, skin, jump.onPrevious, jump.canPrevious)
                ReaderBarAction(jump.nextLabel, skin, jump.onNext, jump.canNext)
            }
        }
    }
}

/**
 * A drag shows the destination in a bubble above the thumb. Only a completed gesture seeks.
 * A cancelled gesture (a parent takes the touch, the activity pauses) leaves the book where it was.
 */
@Composable
internal fun ReaderJumpSlider(
    kind: ReaderJumpKind,
    fraction: Float,
    doc: ReaderBookDoc?,
    pageCount: Int,
    skin: Skin,
    onSeek: (Float) -> Unit,
    onStep: (Int) -> Unit,
) {
    val state = remember(kind) { ReaderJumpSliderState() }
    LaunchedEffect(fraction) { state.follow(fraction) }
    val track = Color(skin.colors.lineBase.toInt())
    val active = Color(skin.colors.accent.toInt())
    val bubble = Color(skin.colors.surface.toInt())
    val line = Color(skin.colors.lineActive.toInt())
    val current = ReaderJump.preview(kind, state.shown, doc, pageCount)
    val density = LocalDensity.current
    val bubbleWidth = with(density) { Metrics.readerJumpBubbleWidthDp.dp.toPx() }

    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(Metrics.readerJumpBubbleHeightDp.dp)) {
            val width = constraints.maxWidth.toFloat()
            val left = if (width <= bubbleWidth) 0f
            else (width * state.shown - bubbleWidth / 2f).coerceIn(0f, width - bubbleWidth)
            if (state.dragging) {
                Box(Modifier.offset { IntOffset(left.roundToInt(), 0) }
                    .width(Metrics.readerJumpBubbleWidthDp.dp).fillMaxHeight()
                    .background(bubble, RoundedCornerShape(Metrics.readerJumpBubbleInsetDp.dp))
                    .border(Metrics.readerHairlineDp.dp, line,
                        RoundedCornerShape(Metrics.readerJumpBubbleInsetDp.dp))
                    .padding(horizontal = Metrics.readerJumpBubbleInsetDp.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                    contentAlignment = Alignment.Center) {
                    BasicText(current, style = readerLabelStyle(skin).copy(
                        color = Color(skin.colors.textPrimary.toInt())),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                BasicText(
                    if (state.touched) current else "Drag to preview. Release to go.",
                    Modifier.padding(horizontal = Metrics.readerJumpBubbleInsetDp.dp),
                    style = readerLabelStyle(skin), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val rangeCurrent = when (kind) {
            ReaderJumpKind.Percent -> ReaderJump.percentForFraction(state.shown).toFloat()
            ReaderJumpKind.Page -> ReaderJump.pageForFraction(state.shown, pageCount) + 1f
        }
        val rangeEnd = if (kind == ReaderJumpKind.Percent) 100f else pageCount.coerceAtLeast(1).toFloat()
        Canvas(Modifier.fillMaxWidth()
            .height(Metrics.readerJumpSliderHeightDp.dp)
            .focusable()
            .onKeyEvent { event ->
                when (event.key) {
                    Key.DirectionLeft -> { onStep(-1); true }
                    Key.DirectionRight -> { onStep(1); true }
                    else -> false
                }
            }
            .semantics {
                contentDescription = "Reading position"
                stateDescription = current
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = rangeCurrent,
                    range = if (kind == ReaderJumpKind.Percent) 0f..100f else 1f..rangeEnd,
                    steps = if (kind == ReaderJumpKind.Percent) 99 else (pageCount - 2).coerceAtLeast(0),
                )
                setProgress { target ->
                    onSeek(ReaderJump.rangeFraction(kind, target, pageCount))
                    true
                }
            }
            .pointerInput(kind, pageCount) {
                val radius = Metrics.readerJumpThumbDp.dp.toPx()
                fun fractionAt(x: Float): Float {
                    val usable = (size.width - radius * 2f).coerceAtLeast(1f)
                    return ((x - radius) / usable).coerceIn(0f, 1f)
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    state.press(fractionAt(down.position.x))
                    var finished = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: event.changes.firstOrNull() ?: break
                            if (!change.pressed) {
                                state.move(fractionAt(change.position.x))
                                change.consume()
                                state.release()?.let(onSeek)
                                finished = true
                                break
                            }
                            state.move(fractionAt(change.position.x))
                            change.consume()
                        }
                    } finally {
                        if (!finished) state.cancel()
                    }
                }
            }) {
            val centre = size.height / 2f
            val radius = Metrics.readerJumpThumbDp.dp.toPx()
            val trackWidth = Metrics.readerJumpTrackHeightDp.dp.toPx()
            val usable = (size.width - radius * 2f).coerceAtLeast(1f)
            val x = radius + usable * state.shown
            drawLine(track, Offset(radius, centre), Offset(size.width - radius, centre), trackWidth)
            drawLine(active, Offset(radius, centre), Offset(x, centre), trackWidth)
            drawCircle(active, radius, Offset(x, centre))
        }
    }
}

/** Direct numeric entry. A page for fixed pages, a percentage for reflowable text. */
@Composable
internal fun ReaderPositionEntrySheet(
    kind: ReaderJumpKind,
    fraction: Float,
    pageCount: Int,
    skin: Skin,
    onDismiss: () -> Unit,
    onGo: (Float) -> Unit,
) {
    var text by rememberSaveable(kind) {
        mutableStateOf(ReaderJump.entryText(kind, fraction, pageCount).removeSuffix("%"))
    }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun submit() {
        val target = ReaderJump.parseEntry(kind, text, pageCount)
        if (target == null) {
            error = "Enter ${ReaderJump.entryHint(kind, pageCount)}"
        } else {
            keyboard?.hide()
            onGo(target)
        }
    }

    ReaderSheetFrame(ReaderJump.entryTitle(kind), skin, onDismiss) {
        BasicText(
            if (kind == ReaderJumpKind.Percent) "Percent of the whole book"
            else "Page 1 to ${pageCount.coerceAtLeast(1)}",
            style = readerLabelStyle(skin),
        )
        BasicTextField(
            value = text,
            onValueChange = { value ->
                text = value.filter { it.isDigit() || it == '%' }.take(4)
                error = null
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            textStyle = readerValueStyle(skin),
            cursorBrush = SolidColor(Color(skin.colors.accent.toInt())),
            modifier = Modifier.fillMaxWidth()
                .heightIn(min = Metrics.readerTouchTargetDp.dp)
                .focusRequester(focus)
                .background(Color(skin.colors.surface.toInt()),
                    RoundedCornerShape(Metrics.readerChipGapDp.dp))
                .border(Metrics.readerHairlineDp.dp, Color(skin.colors.lineActive.toInt()),
                    RoundedCornerShape(Metrics.readerChipGapDp.dp))
                .padding(horizontal = Metrics.readerChipInsetDp.dp, vertical = Metrics.readerRowGapDp.dp)
                .semantics { contentDescription = ReaderJump.entryTitle(kind) },
        )
        BasicText(
            "Currently ${ReaderJump.entryText(kind, fraction, pageCount)} · " +
                ReaderJump.entryHint(kind, pageCount),
            style = readerLabelStyle(skin),
        )
        error?.let { BasicText(it, style = readerLabelStyle(skin).copy(
            color = Color(skin.colors.danger.toInt()))) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.readerChipGapDp.dp)) {
            ReaderAction("Go", skin, onClick = { submit() })
            ReaderAction("Cancel", skin, onClick = onDismiss)
        }
    }
}
