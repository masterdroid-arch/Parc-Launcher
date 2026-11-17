package com.brightblu.parc.reader

import android.app.NotificationManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.BasicText
import com.brightblu.parc.core.data.library.Book
import com.brightblu.parc.core.data.library.ReaderBookDoc
import com.brightblu.parc.core.data.library.ReaderSettings
import com.brightblu.parc.core.data.library.ReaderTtsRules
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import androidx.compose.ui.unit.dp

/** T257: whether the quiet media notification can be shown, checked without asking again. */
internal object ReaderTtsNotifications {
    fun allowed(context: Context): Boolean = try {
        context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true
    } catch (_: Exception) {
        false
    }
}

/** T257: the small read-aloud sheet. It explains itself in one line and keeps every value labelled. */
@Composable
internal fun ReaderListenSheet(
    book: Book,
    doc: ReaderBookDoc,
    settings: ReaderSettings,
    state: ReaderTtsState,
    notificationsAllowed: Boolean,
    skin: Skin,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onSpeed: (Float) -> Unit,
    onStopAtChapterEnd: (Boolean) -> Unit,
    onAllowNotifications: () -> Unit,
    onDismiss: () -> Unit,
) {
    val mine = state.bookId == book.id && state.phase != ReaderTtsPhase.Idle
    val phase = if (mine) state.phase else ReaderTtsPhase.Idle
    val chapter = (state.chapter + 1).coerceIn(1, doc.chapters.size.coerceAtLeast(1))
    val status = when (phase) {
        ReaderTtsPhase.Playing -> "Reading chapter $chapter of ${doc.chapters.size}"
        ReaderTtsPhase.Paused -> state.message.ifBlank { "Paused at chapter $chapter" }
        ReaderTtsPhase.Preparing -> "Preparing voice"
        ReaderTtsPhase.Finished -> "Finished"
        ReaderTtsPhase.Unavailable -> state.message.ifBlank { "This phone cannot read aloud." }
        ReaderTtsPhase.Idle -> "Not started"
    }
    ReaderSheetFrame("Read aloud", skin, onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Metrics.readerRowGapDp.dp)) {
            BasicText(status, style = readerValueStyle(skin))
            BasicText("The phone's own voice reads the text. An installed voice works offline.",
                style = readerLabelStyle(skin))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.readerChipGapDp.dp)) {
                when (phase) {
                    ReaderTtsPhase.Playing -> ReaderAction("Pause", skin, onPause)
                    ReaderTtsPhase.Paused -> ReaderAction("Resume", skin, onPlay)
                    ReaderTtsPhase.Unavailable -> ReaderAction("Try again", skin, onPlay)
                    else -> ReaderAction("Play", skin, onPlay)
                }
                if (phase == ReaderTtsPhase.Playing || phase == ReaderTtsPhase.Paused ||
                    phase == ReaderTtsPhase.Preparing) {
                    ReaderAction("Stop", skin, onStop)
                }
            }
            if (!notificationsAllowed) {
                BasicText("Allow notifications so the reading controls stay in the shade with the screen off.",
                    style = readerLabelStyle(skin))
                ReaderAction("Allow notifications", skin, onAllowNotifications)
            }
            ReaderChoiceRow(
                label = "Speed",
                choices = ReaderTtsRules.Speeds.map { it to ReaderTtsRules.speedLabel(it) },
                chosen = ReaderTtsRules.nearestSpeed(settings.ttsSpeed),
                skin = skin,
            ) { value -> onSpeed(value) }
            ReaderChoiceRow(
                label = "Stop at chapter end",
                choices = listOf(false to "Off", true to "On"),
                chosen = settings.ttsStopAtChapterEnd,
                skin = skin,
            ) { value -> onStopAtChapterEnd(value) }
            Spacer(Modifier.height(Metrics.readerRowGapDp.dp))
        }
    }
}
