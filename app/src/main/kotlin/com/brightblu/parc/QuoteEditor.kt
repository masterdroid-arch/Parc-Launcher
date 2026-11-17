package com.brightblu.parc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.brightblu.parc.feature.settings.ui.*
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

internal data class QuoteDraft(val quote: Quote = Quote("own-${UUID.randomUUID()}", ""), val starterId: String? = null) {
    companion object {
        fun edit(quote: Quote): QuoteDraft = if (quote.id.startsWith("own-")) QuoteDraft(quote)
            else QuoteDraft(quote.copy(id = "own-${UUID.randomUUID()}"), quote.id)

        /** T263: text shared from another app keeps a marker so Commonplace can name it Shared text. */
        fun shared(text: String): QuoteDraft =
            QuoteDraft(Quote("${CommonplaceRules.SharedPrefix}${UUID.randomUUID()}", text))
    }
}

@Composable
internal fun QuoteEditorSheet(draft: QuoteDraft, skin: Skin, store: QuotesStore, close: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(draft.quote.text) }
    var author by rememberSaveable { mutableStateOf(draft.quote.author) }
    var work by rememberSaveable { mutableStateOf(draft.quote.work) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val quote = draft.quote.copy(text = text, author = author, work = work)
    Dialog(onDismissRequest = { if (!saving) close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        SettingsTheme(skin) {
            Box(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(Metrics.cardInsetDp.dp), contentAlignment = Alignment.BottomCenter) {
                Column(Modifier.fillMaxWidth().fillMaxHeight(Metrics.quoteSheetFraction)
                    .background(Color(skin.colors.surface.toInt()), RoundedCornerShape(skin.shape.surfaceCornerDp.dp))
                    .padding(Metrics.textGutterDp.dp), verticalArrangement = Arrangement.spacedBy(Metrics.itemGapDp.dp)) {
                    BasicText("Commonplace", style = settingsText(skin))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        NoteAction("Cancel", skin, enabled = !saving, action = close)
                        NoteAction("Paste", skin, enabled = !saving) {
                            clipboard.getText()?.let { text = it.text }
                        }
                        NoteAction("Save", skin, enabled = QuoteRules.valid(quote) && !saving) {
                            saving = true
                            scope.launch {
                                try { store.save(quote, draft.starterId); close() }
                                catch (e: CancellationException) { throw e }
                                catch (_: Exception) { error = "Could not save the quote. Your text is still here. Try again or free space in the collection." }
                                finally { saving = false }
                            }
                        }
                    }
                    if (draft.starterId != null) BasicText("Saves a personal copy and hides the starter.", style = settingsText(skin))
                    BasicText("Quote · ${text.length} / ${QuoteRules.MaxText}", style = settingsText(skin))
                    BasicTextField(text, { text = it }, Modifier.fillMaxWidth().weight(1f)
                        .semantics { contentDescription = "Quote text" }, enabled = !saving,
                        textStyle = noteStyle(skin), cursorBrush = SolidColor(Color(skin.colors.accent.toInt())))
                    Column(Modifier.weight(Metrics.quoteFieldsWeight, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(Metrics.itemGapDp.dp)) {
                        QuoteCreditField("Author (optional)", author, skin, !saving) { author = it }
                        QuoteCreditField("Source or work (optional)", work, skin, !saving) { work = it }
                        if (text.length > QuoteRules.MaxText) BasicText("Shorten the quote to 2,000 characters before saving.", style = settingsText(skin))
                        error?.let { BasicText(it, style = settingsText(skin)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuoteCreditField(label: String, value: String, skin: Skin, enabled: Boolean, change: (String) -> Unit) {
    BasicText(label, style = settingsText(skin))
    BasicTextField(value, { if (it.length <= QuoteRules.MaxCredit) change(it) }, Modifier.fillMaxWidth()
        .heightIn(min = Metrics.menuRowHeightDp.dp).semantics { contentDescription = label },
        enabled = enabled, singleLine = true, textStyle = noteStyle(skin), cursorBrush = SolidColor(Color(skin.colors.accent.toInt())))
}

@Composable
internal fun QuoteMenu(quote: Quote, skin: Skin, close: () -> Unit, edit: () -> Unit, copy: () -> Unit, hide: () -> Unit, delete: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    SettingsTheme(skin) {
        AlertDialog(onDismissRequest = close, containerColor = Color(skin.colors.surface.toInt()),
            title = { BasicText(if (confirmDelete) "Delete quote?" else "Quote", style = settingsText(skin)) },
            text = {
                if (confirmDelete) BasicText(if (quote.id.startsWith("own-")) "This removes your saved quote."
                    else "This hides the starter. Restore starter quotes brings it back.", style = settingsText(skin))
                else Column(Modifier.verticalScroll(rememberScrollState())) {
                    SettingRow("Edit", skin, SettingKind.Action, onClick = edit)
                    SettingRow("Copy", skin, SettingKind.Action, onClick = copy)
                    SettingRow("Hide", skin, SettingKind.Action, onClick = hide)
                    SettingRow("Delete", skin, SettingKind.Action, divider = false) { confirmDelete = true }
                }
            }, confirmButton = {
                TextButton(onClick = if (confirmDelete) delete else close) {
                    BasicText(if (confirmDelete) "Delete" else "Close", style = settingsText(skin))
                }
            }, dismissButton = { if (confirmDelete) TextButton(onClick = close) { BasicText("Cancel", style = settingsText(skin)) } })
    }
}
