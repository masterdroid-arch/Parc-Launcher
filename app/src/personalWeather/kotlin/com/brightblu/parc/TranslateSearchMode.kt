package com.brightblu.parc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
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
import java.util.UUID

/** Language names for the label: "English → Português (PT)". */
internal object TranslateLabels {
    fun source(detected: String?): String = when {
        detected == null -> "Detected"
        detected.uppercase().startsWith("EN") -> "English"
        detected.uppercase().startsWith("PT") -> "Português"
        else -> detected.uppercase()
    }
    fun line(detected: String?, target: TranslateTarget) = "${source(detected)} → ${target.label}"
}

/**
 * T196: Translate mode of the search sheet (internet build only). Sends the typed text to DeepL only
 * while this mode is active: after a pause in typing, or at once on Enter (see [TranslateFlow]).
 * Result under the field; tap copies; long-press (or the TalkBack action) offers "Save as note".
 * The language label flips the target. The last 20 translations show while the field is empty.
 */
@OptIn(ExperimentalFoundationApi::class)
internal class TranslateSearchMode(
    private val keys: (Context) -> DeepLKeyStore = DeepLKeyStore::of,
    private val transport: DeepLTransport = DeepLHttp,
    /** T256: the reader opens this sheet only on tap, so it sends at once instead of after the typing pause. */
    private val sendAtOnce: Boolean = false,
) : SearchMode {
    override val id = SearchModeIds.Translate
    override val label = "Translate"
    override val hint = "Text to translate"
    override val prefix: String? = "tr "

    /** Bumped by Enter and by saving a key, so the effect in [Body] runs again at once. */
    private var kick by mutableIntStateOf(0)
    private var immediateText by mutableStateOf<String?>(null)

    override fun onEnter(text: String, host: SearchModeHost): Boolean {
        if (!DeepLRules.worthSending(text)) return false
        immediateText = text
        kick++
        return true
    }

    @Composable
    override fun Body(text: String, skin: Skin, host: SearchModeHost) {
        val context = host.context
        val keyStore = remember(context) { keys(context) }
        val history = remember(context) { TranslateStore(context) }
        val saved by history.history.collectAsState(initial = emptyList())
        val flow = remember(keyStore) { TranslateFlow(DeepLClient(transport), { keyStore.load() }) }
        var state by remember { mutableStateOf<TranslateState>(TranslateState.Idle) }
        var forced by remember(text) { mutableStateOf<TranslateTarget?>(null) }
        var showKey by remember { mutableStateOf(false) }
        var notice by remember(text) { mutableStateOf<String?>(null) }
        val demo = DemoMode.enabled

        // A changed text, a flipped target, Enter or a new key restarts this effect and so cancels the old pause or request.
        LaunchedEffect(text, forced, kick) {
            if (demo) { state = TranslateState.Idle; return@LaunchedEffect }
            val old = state
            if ((old is TranslateState.Done && old.source != text.trim()) || old is TranslateState.Failed) state = TranslateState.Idle
            flow.run(text, immediate = sendAtOnce || immediateText == text || forced != null, forced) { state = it }
        }
        val done = state as? TranslateState.Done
        LaunchedEffect(done) {
            if (done != null && done.source == text.trim()) {
                history.add(TranslationEntry(done.source, done.outcome.text, done.outcome.target, System.currentTimeMillis()))
            }
        }
        LaunchedEffect(notice) {
            if (notice != null) { kotlinx.coroutines.delay(Metrics.searchCopiedNoticeMs); notice = null }
        }

        val settle = spring<Float>(Springs.sheet.damping, Springs.sheet.stiffness)
        val size = spring(Springs.sheet.damping, Springs.sheet.stiffness, IntSize.VisibilityThreshold)
        val caption = TextStyle(
            fontFamily = skin.textFamily,
            color = Color(skin.colors.textSecondary.toInt()),
            fontSize = skin.typography.caption.sizeSp.sp,
            fontWeight = FontWeight(skin.typography.caption.weight),
            letterSpacing = skin.typography.caption.trackingSp.sp,
        )
        val big = TextStyle(
            fontFamily = skin.textFamily,
            color = Color(skin.colors.textPrimary.toInt()),
            fontSize = skin.typography.search.sizeSp.sp,
            fontWeight = FontWeight(skin.typography.search.weight),
            letterSpacing = skin.typography.search.trackingSp.sp,
        )
        val shown: Any = when {
            showKey -> "key"
            demo -> "demo"
            else -> state
        }
        Column(
            Modifier
                .fillMaxWidth()
                .animateContentSize(spring(Springs.sheet.damping, Springs.sheet.stiffness))
                .padding(horizontal = Metrics.textGutterDp.dp, vertical = Metrics.rowGapDp.dp),
        ) {
            AnimatedContent(
                targetState = shown,
                transitionSpec = { (fadeIn(settle) togetherWith fadeOut(settle)).using(SizeTransform(clip = false) { _, _ -> size }) },
                contentKey = { if (it is TranslateState.Done) "done" else if (it is TranslateState.Failed) "failed" else it },
                label = "Translate mode",
            ) { s ->
                Column {
                    when {
                        s == "key" -> TranslateKeyPanel(skin, keyStore, history, onSaved = { kick++ }, onClose = { showKey = false })
                        s == "demo" -> BasicText("Translate is off in demo mode.", style = caption)
                        s is TranslateState.Working -> BasicText("Translating…", style = caption)
                        s is TranslateState.Failed -> {
                            BasicText(s.problem.message, style = caption.copy(color = Color(skin.colors.danger.toInt())))
                            if (s.problem == TranslateProblem.NoKey || s.problem == TranslateProblem.KeyRefused) {
                                ActionText("Open the key screen", skin) { showKey = true }
                            }
                        }
                        s is TranslateState.Done -> ResultBlock(s, skin, big, caption, notice,
                            onFlip = { forced = s.outcome.target.flipped },
                            onCopy = { copyText(context, s.outcome.text); notice = "Copied" },
                            onSave = { saveAsNote(context, s.outcome.text) { ok -> notice = if (ok) "Saved to Notes" else "Not saved: notes are full" } })
                        text.isBlank() -> HistoryBlock(saved, skin, big, caption) { host.setText(it.source) }
                        else -> BasicText(if (sendAtOnce) "Translating…" else "Keep typing, or press Enter.", style = caption)
                    }
                }
            }
            if (!showKey) ActionText("DeepL key and saved translations", skin) { showKey = true }
        }
    }

    private fun copyText(context: Context, value: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Translation", value))
    }

    private fun saveAsNote(context: Context, value: String, done: (Boolean) -> Unit) {
        val note = NoteSearchRules.newNote(UUID.randomUUID().toString(), value, System.currentTimeMillis()) ?: return
        NotesStore.get(context).saveReporting(note, done)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultBlock(
    s: TranslateState.Done, skin: Skin, big: TextStyle, caption: TextStyle, notice: String?,
    onFlip: () -> Unit, onCopy: () -> Unit, onSave: () -> Unit,
) {
    val press = remember { MutableInteractionSource() }
    Column {
        ActionText(TranslateLabels.line(s.outcome.detectedSource, s.outcome.target), skin, onClick = onFlip, description = "Language. Tap to translate the other way")
        Column(
            Modifier
                .heightIn(max = Metrics.searchNoteCardMaxHeightDp.dp)
                .verticalScroll(rememberScrollState())
                .parcPress(press)
                .combinedClickable(interactionSource = press, indication = null, role = Role.Button, onClick = onCopy, onLongClick = onSave)
                .semantics { customActions = listOf(CustomAccessibilityAction("Save as note") { onSave(); true }) },
        ) {
            BasicText(s.outcome.text, style = big)
        }
        BasicText(notice ?: "Tap to copy. Hold to save as a note.", style = caption, modifier = Modifier.padding(vertical = Metrics.rowGapDp.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryBlock(list: List<TranslationEntry>, skin: Skin, big: TextStyle, caption: TextStyle, onPick: (TranslationEntry) -> Unit) {
    if (list.isEmpty()) { BasicText("Type to translate to English or Português (PT).", style = caption); return }
    Column(Modifier.heightIn(max = Metrics.searchNoteCardMaxHeightDp.dp).verticalScroll(rememberScrollState())) {
        list.forEach { entry ->
            val press = remember { MutableInteractionSource() }
            Column(
                Modifier.fillMaxWidth().parcPress(press)
                    .combinedClickable(interactionSource = press, indication = null, role = Role.Button, onClick = { onPick(entry) })
                    .heightIn(min = Metrics.minTouchDp.dp).padding(vertical = Metrics.rowGapDp.dp),
            ) {
                BasicText(entry.result, style = big.copy(fontSize = skin.typography.menu.sizeSp.sp), maxLines = 2)
                BasicText("${entry.source} · ${entry.target.label}", style = caption, maxLines = 1)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ActionText(label: String, skin: Skin, description: String? = null, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val marked = buildAnnotatedString {
        withStyle(SpanStyle(color = Color(skin.colors.accent.toInt()))) { append("› ") }
        append(label)
    }
    BasicText(
        marked,
        modifier = Modifier
            .parcPress(source)
            .heightIn(min = Metrics.minTouchDp.dp)
            .combinedClickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .semantics { if (description != null) contentDescription = "$label. $description" }
            .padding(vertical = Metrics.rowGapDp.dp),
        style = TextStyle(
            fontFamily = skin.textFamily,
            color = Color(skin.colors.textSecondary.toInt()),
            fontSize = skin.typography.caption.sizeSp.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = skin.typography.caption.trackingSp.sp,
        ),
    )
}
