package com.brightblu.parc

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.feature.search.SearchMode
import com.brightblu.parc.feature.search.SearchModeHost
import com.brightblu.parc.feature.search.SearchModeIds
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.Springs
import com.brightblu.parc.model.parcPress
import com.brightblu.parc.model.textFamily
import kotlinx.coroutines.delay
import java.util.UUID

/**
 * T195: Note mode of the search sheet. Typing shows a live preview card (first line as the title,
 * the same rendering as the hub Notes page). Enter, or "Save", puts the text into the same
 * [NotesStore] the hub uses as a new, unpinned note (newest first); "Add to last note" appends a
 * line to the most recently edited note. The card then settles into "Saved to Notes" with Undo for
 * [Metrics.noteUndoMs]. Works offline; no permission.
 */
internal class NoteSearchMode(
    private val store: NotesStore,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : SearchMode {
    override val id = SearchModeIds.Note
    override val label = "Note"
    override val hint = "Jot a note"
    override val prefix: String? = "n "
    override val multiline = true

    private sealed interface Saved {
        val expiresAt: Long
        class New(val note: Note, override val expiresAt: Long) : Saved
        class Appended(val before: Note, override val expiresAt: Long) : Saved
    }

    private var saved by mutableStateOf<Saved?>(null)
    /** Why the last Save did not happen; shown under the card until the text changes. */
    private var problem by mutableStateOf<String?>(null)

    override fun onEnter(text: String, host: SearchModeHost): Boolean = saveNew(text, host)

    private fun saveNew(text: String, host: SearchModeHost): Boolean {
        val note = NoteSearchRules.newNote(newId(), text, wallClock()) ?: return false
        problem = null
        store.saveReporting(note) { ok ->
            if (ok) {
                saved = Saved.New(note, clock() + Metrics.noteUndoMs)
                host.setText("")
            } else problem = TooLarge
        }
        return true
    }

    private fun addToLast(text: String, host: SearchModeHost) {
        val addition = NoteSearchRules.cleaned(text) ?: return
        problem = null
        store.appendToLastEdited(addition, wallClock()) { before, _, failed ->
            when {
                before != null -> {
                    saved = Saved.Appended(before, clock() + Metrics.noteUndoMs)
                    host.setText("")
                }
                failed -> problem = TooLarge
                else -> problem = "There is no note to add to yet."
            }
        }
    }

    private fun undo() {
        when (val s = saved) {
            is Saved.New -> store.remove(s.note.id)
            is Saved.Appended -> store.save(s.before)
            null -> Unit
        }
        saved = null
    }

    @Composable
    override fun Body(text: String, skin: Skin, host: SearchModeHost) {
        val current = saved?.takeIf { clock() < it.expiresAt }
        LaunchedEffect(saved) {
            val s = saved ?: return@LaunchedEffect
            delay((s.expiresAt - clock()).coerceAtLeast(0))
            if (saved === s) saved = null
        }
        LaunchedEffect(text) { problem = null }
        val phase = when {
            text.isBlank() && current != null -> Phase.None
            text.isBlank() -> Phase.Hint
            else -> Phase.Draft
        }
        val settle = spring<Float>(Springs.sheet.damping, Springs.sheet.stiffness)
        val size = spring(Springs.sheet.damping, Springs.sheet.stiffness, IntSize.VisibilityThreshold)
        val captionStyle = TextStyle(
            fontFamily = skin.textFamily,
            color = Color(skin.colors.textSecondary.toInt()),
            fontSize = skin.typography.caption.sizeSp.sp,
            fontWeight = FontWeight(skin.typography.caption.weight),
            letterSpacing = skin.typography.caption.trackingSp.sp,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Metrics.rowGapDp.dp, vertical = Metrics.rowGapDp.dp),
        ) {
            AnimatedContent(
                targetState = phase,
                modifier = Modifier.weight(1f, fill = false),
                transitionSpec = { (fadeIn(settle) togetherWith fadeOut(settle)).using(SizeTransform(clip = false) { _, _ -> size }) },
                label = "Note mode",
            ) { shown ->
                when (shown) {
                    Phase.Hint -> BasicText(
                        "Type a note. Enter saves it to Notes.",
                        modifier = Modifier.padding(horizontal = Metrics.textGutterDp.dp - Metrics.rowGapDp.dp, vertical = Metrics.rowGapDp.dp),
                        style = captionStyle,
                    )
                    Phase.Draft -> DraftCard(text, skin, captionStyle, onSave = { saveNew(text, host) }, onAdd = { addToLast(text, host) })
                    Phase.None -> Unit
                }
            }
            // T237: the confirmation is not tied to an empty field. It stays for its full 8 s even when the
            // next jot is already being typed, and it sits directly above the field (and the keyboard).
            current?.let {
                SavedCard(
                    appended = it is Saved.Appended,
                    skin = skin,
                    captionStyle = captionStyle,
                    onUndo = ::undo,
                )
            }
            problem?.let {
                BasicText(
                    it,
                    modifier = Modifier.padding(horizontal = Metrics.textGutterDp.dp - Metrics.rowGapDp.dp, vertical = Metrics.rowGapDp.dp),
                    style = captionStyle.copy(color = Color(skin.colors.danger.toInt())),
                )
            }
        }
    }

    private enum class Phase { Hint, Draft, None }

    companion object {
        const val TooLarge = "Not saved: all notes together are full. Your text is still here."
    }
}

@Composable
private fun cardModifier(skin: Skin): Modifier {
    val shape = RoundedCornerShape(Metrics.searchNoteCardCornerDp.dp)
    return Modifier
        .fillMaxWidth()
        .background(Color(skin.colors.surface.toInt()).copy(alpha = Metrics.searchNoteCardFillAlpha), shape)
        .border(skin.shape.surfaceBorderDp.dp, Color(skin.colors.lineBase.toInt()), shape)
        .padding(Metrics.searchNoteCardPaddingDp.dp)
}

@Composable
private fun DraftCard(text: String, skin: Skin, captionStyle: TextStyle, onSave: () -> Unit, onAdd: () -> Unit) {
    val lines = remember(text) { NoteMarkup.parse(NoteSearchRules.cleaned(text).orEmpty()) }
    val body = TextStyle(
        fontFamily = skin.textFamily,
        color = Color(skin.colors.textPrimary.toInt()),
        fontSize = skin.typography.menu.sizeSp.sp,
        fontWeight = FontWeight(skin.typography.menu.weight),
    )
    Column(cardModifier(skin).animateContentSize(spring(Springs.sheet.damping, Springs.sheet.stiffness))) {
        Column(
            Modifier
                .heightIn(max = Metrics.searchNoteCardMaxHeightDp.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            lines.forEachIndexed { index, line ->
                if (index == 0) {
                    BasicText(renderedLine(line, skin), style = body.copy(fontWeight = FontWeight.SemiBold))
                } else if (line.raw.isNotEmpty()) {
                    BasicText(notePreviewLine(line, skin), style = body)
                } else {
                    BasicText(" ", style = body)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = Metrics.rowGapDp.dp),
            horizontalArrangement = Arrangement.spacedBy(Metrics.textGutterDp.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardAction("Save", accent = true, skin = skin, onClick = onSave)
            CardAction("Add to last note", accent = false, skin = skin, onClick = onAdd)
        }
    }
}

@Composable
private fun SavedCard(appended: Boolean, skin: Skin, captionStyle: TextStyle, onUndo: () -> Unit) {
    Row(
        cardModifier(skin),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            if (appended) "Added to your last note" else "Saved to Notes",
            style = captionStyle.copy(color = Color(skin.colors.textPrimary.toInt())),
        )
        CardAction("Undo", accent = true, skin = skin, onClick = onUndo)
    }
}

@Composable
private fun CardAction(label: String, accent: Boolean, skin: Skin, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    BasicText(
        label,
        modifier = Modifier
            .parcPress(source)
            .heightIn(min = Metrics.minTouchDp.dp)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .padding(vertical = Metrics.rowGapDp.dp),
        style = TextStyle(
            fontFamily = skin.textFamily,
            color = Color(if (accent) skin.colors.accent.toInt() else skin.colors.textSecondary.toInt()),
            fontSize = skin.typography.caption.sizeSp.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = skin.typography.caption.trackingSp.sp,
        ),
    )
}
