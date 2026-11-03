package com.brightblu.parc

import com.brightblu.parc.model.textFamily
import android.content.Context
import android.text.InputType
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.brightblu.parc.core.data.focus.*
import com.brightblu.parc.feature.settings.ui.*
import com.brightblu.parc.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** No clipboard, context menu, autofill, selection actions or multi-character IME commits. */
internal class FocusTypingField(context: Context) : EditText(context) {
    init {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        isLongClickable = false
        setTextIsSelectable(false)
        isFocusableInTouchMode = true
        val blocked = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode?, menu: Menu?) = false
            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?) = false
            override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?) = false
            override fun onDestroyActionMode(mode: ActionMode?) {}
        }
        customSelectionActionModeCallback = blocked
        customInsertionActionModeCallback = blocked
        filters = arrayOf(android.text.InputFilter { source, start, end, _, _, _ -> if (FocusChallenges.acceptsInsertion(end - start)) null else "" })
    }
    override fun onTextContextMenuItem(id: Int) = false
    override fun performLongClick() = false
}

@Composable
internal fun FocusExitDialog(repository: FocusRepository, exit: FocusExit, skin: Skin,
    onDismiss: () -> Unit, onGranted: () -> Unit = onDismiss) {
    var request by remember { mutableStateOf<FocusExitRequest?>(null) }
    var issue by remember { mutableStateOf("") }
    var remaining by remember { mutableLongStateOf(1L) }
    var holdRemaining by remember { mutableLongStateOf(Metrics.focusHoldMs) }
    var input by remember { mutableStateOf("") }
    var answers by remember { mutableStateOf(List(5) { "" }) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(repository, exit) {
        try {
            request = repository.beginExit(exit)
            if (request == null) issue = "No pass available. Wait until the cooldown or Focus ends. After the limit, an accountability code is required."
            while (true) {
                request?.let { remaining = repository.exitRemaining(it); holdRemaining = repository.holdRemaining(it) }
                delay(Metrics.focusCountdownTickMs)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { issue = "Could not load Focus. Cancel and try again." }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, request) {
        val ticket = request
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) { ticket?.let(repository::cancelExit); onDismiss() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); ticket?.let(repository::cancelExit) }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler { onDismiss() }
        Box(Modifier.fillMaxSize().background(Color(skin.colors.bg.toInt()))) {
            SettingsPageFrame(skin, onDismiss) {
                SettingsContent(skin, if (exit == FocusExit.Pass) "Emergency pass" else "End focus early") {
                    SettingsSection("Leaving this screen cancels the countdown and challenge.", skin)
                    SettingsCard(skin) {
                      Column(Modifier.parcCardPadding()) {
                        val ticket = request
                        if (ticket != null) {
                            SettingsSection(if (remaining > 0) "Wait ${(remaining + 999) / 1000} s" else "Delay complete", skin)
                            if (remaining == 0L) {
                                when (ticket.challenge) {
                                    FocusChallenge.None -> SettingsSection("Ready when you are.", skin)
                                    FocusChallenge.Sentence -> {
                                        SettingsSection("Retype this sentence. Paste is disabled.", skin)
                                        androidx.compose.foundation.text.BasicText(ticket.sentence,
                                            style = androidx.compose.ui.text.TextStyle(fontFamily = skin.textFamily, color = Color(skin.colors.textPrimary.toInt()),
                                                fontSize = skin.typography.menu.sizeSp.sp),
                                            modifier = Modifier.padding(Metrics.sectionHeaderGapDp.dp))
                                        AndroidView(factory = { context -> FocusTypingField(context).apply {
                                            contentDescription = "Retype the random sentence"
                                            background = null
                                            highlightColor = skin.colors.accent.toInt()
                                            textCursorDrawable = android.graphics.drawable.GradientDrawable().apply {
                                                setColor(skin.colors.accent.toInt())
                                                setSize((skin.lineWidths.activeDp * resources.displayMetrics.density).toInt().coerceAtLeast(1),
                                                    (skin.typography.menu.sizeSp * resources.displayMetrics.scaledDensity).toInt())
                                            }
                                            setTextColor(skin.colors.textPrimary.toInt())
                                            setHintTextColor(skin.colors.textSecondary.toInt())
                                            textSize = skin.typography.menu.sizeSp
                                            hint = "Type here"
                                            addTextChangedListener(object : android.text.TextWatcher {
                                                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                                                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { input = s.toString() }
                                                override fun afterTextChanged(s: android.text.Editable?) {}
                                            })
                                        } }, modifier = Modifier.fillMaxWidth().heightIn(min = Metrics.focusControlDp.dp)
                                            .border(skin.lineWidths.baseDp.dp, Color(skin.colors.lineActive.toInt()), RoundedCornerShape(Metrics.menuCornerDp.dp)))
                                    }
                                    FocusChallenge.Arithmetic -> ticket.problems.forEachIndexed { index, problem ->
                                        SettingsSection("${index + 1} of 5: ${problem.text}", skin)
                                        Box(Modifier.fillMaxWidth().border(skin.lineWidths.baseDp.dp, Color(skin.colors.lineActive.toInt()), RoundedCornerShape(Metrics.menuCornerDp.dp))) {
                                            FocusText(answers[index], skin, "Answer ${index + 1}") { value -> answers = answers.mapIndexed { i, old -> if (i == index) value.take(5) else old } }
                                        }
                                    }
                                    FocusChallenge.Accountability -> {
                                        SettingsSection("Ask your trusted person for the current 6-digit authenticator code. You can also wait until Focus ends.", skin)
                                        Box(Modifier.fillMaxWidth().border(skin.lineWidths.baseDp.dp, Color(skin.colors.lineActive.toInt()), RoundedCornerShape(Metrics.menuCornerDp.dp))) {
                                            FocusText(input, skin, "Accountability code") { input = it.filter(Char::isDigit).take(6) }
                                        }
                                    }
                                    FocusChallenge.Hold -> {
                                        Box(Modifier.fillMaxWidth().height(Metrics.focusControlDp.dp)
                                            .background(Color(skin.colors.accent.toInt()), RoundedCornerShape(Metrics.menuCornerDp.dp))
                                            .semantics { role = Role.Button; contentDescription = "Hold for 20 seconds" }
                                            .pointerInput(ticket) { detectTapGestures(onPress = {
                                                repository.hold(ticket, true)
                                                try { tryAwaitRelease() } finally { repository.hold(ticket, false) }
                                            }) }, contentAlignment = Alignment.Center) {
                                            androidx.compose.foundation.text.BasicText("Hold · ${(holdRemaining + 999) / 1000} s",
                                                style = androidx.compose.ui.text.TextStyle(fontFamily = skin.textFamily, color = Color(skin.colors.bg.toInt()), fontSize = skin.typography.menu.sizeSp.sp))
                                        }
                                        // Grant while the finger is still held: releasing resets the challenge.
                                        LaunchedEffect(holdRemaining) { if (holdRemaining == 0L && !busy) {
                                            busy = true
                                            try { if (repository.completeExit(ticket)) onGranted() else issue = "Try the challenge again" }
                                            catch (_: Exception) { issue = "Could not update Focus. Try again." }
                                            finally { busy = false }
                                        } }
                                    }
                                }
                            }
                            if (ticket.challenge != FocusChallenge.Hold) SettingRow("Continue", skin, SettingKind.Action,
                                enabled = remaining == 0L && !busy) {
                                scope.launch {
                                    busy = true
                                    try { if (repository.completeExit(ticket, input, answers)) onGranted() else issue = "Challenge incomplete or code incorrect. Try again." }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { issue = "Could not update Focus. Try again." }
                                    finally { busy = false }
                                }
                            }
                        }
                        if (issue.isNotEmpty()) SettingsSection(issue, skin)
                        if (request == null && repository.paidUnlockAvailable) SettingRow("Pay to unlock", skin, SettingKind.Action) {
                            scope.launch {
                                try { request = repository.beginExit(exit, paid = true); issue = "" }
                                catch (_: Exception) { issue = "Unlock provider unavailable" }
                            }
                        }
                        SettingRow("Stay focused", skin, SettingKind.Action) { onDismiss() }
                      }
                    }
                }
            }
        }
    }
}
