package com.brightblu.parc

import com.brightblu.parc.model.textFamily
import com.brightblu.parc.feature.settings.ui.*

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.brightblu.parc.core.data.comms.*
import com.brightblu.parc.core.data.notifications.ParcNotificationListenerService
import com.brightblu.parc.feature.settings.notifications.NotificationAccessLink
import com.brightblu.parc.feature.settings.notifications.NotificationAccessStatus
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.ParcBlocks
import com.brightblu.parc.model.parcBottomInset
import com.brightblu.parc.model.parcCardPadding
import com.brightblu.parc.model.Skin
import kotlinx.coroutines.launch

@Composable
private fun consoleStyle(skin: Skin) = TextStyle(
    color = Color(skin.colors.textPrimary.toInt()), fontSize = Metrics.commsMessageSp.sp,
    fontFamily = if (skin.style.terminalStats) FontFamily.Monospace else skin.textFamily ?: FontFamily.SansSerif,
)

@Composable
internal fun ConsoleAction(text: String, skin: Skin, modifier: Modifier = Modifier,
    enabled: Boolean = true, action: () -> Unit) {
    Box(modifier.heightIn(min = Metrics.commsActionDp.dp).widthIn(min = Metrics.commsActionDp.dp)
        .clickable(enabled = enabled, onClick = action).padding(Metrics.lineGapDp.dp), contentAlignment = Alignment.CenterStart) {
        BasicText(text, style = consoleStyle(skin).copy(
            color = Color((if (enabled) skin.colors.accent else skin.colors.lineActive).toInt())))
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun CommsPage(repository: CommsExtrasRepository, skin: Skin, modifier: Modifier = Modifier,
    reviewAccess: Boolean? = null) {
    val allConversations by repository.conversations.collectAsState()
    val focusState = LocalFocusRepository.current?.state?.collectAsState()?.value
    // T98: packages in locked folders are hidden from Comms until the folder is unlocked.
    val hidden = focusState?.active?.hiddenComms.orEmpty() + LocalLockedPackages.current
    val conversations = remember(allConversations, hidden) { allConversations.filterNot { it.app in hidden } }
    val enabled by repository.enabled.collectAsState()
    val cannedReplies by repository.cannedReplies.collectAsState()
    val allDismissed by repository.history.collectAsState()
    val dismissed = remember(allDismissed, hidden) { allDismissed.filterNot { it.conversation.app in hidden } }
    var selected by remember { mutableStateOf<CommsConversationId?>(null) }
    val selection = CommsPresentation.selection(selected, conversations)
    LaunchedEffect(selection) { selected = selection }
    var draft by remember { mutableStateOf("") }
    // Drafts/notices are never saveable, logged or written to disk.
    var notice by remember { mutableStateOf<Pair<String, String>?>(null) }
    var sending by remember { mutableStateOf(false) }
    // T77: the history toggle is a view state only; nothing about it is persisted.
    var historyOn by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) historyOn = false }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    DisposableEffect(Unit) { onDispose { focus.clearFocus(); keyboard?.hide() } }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val ime = WindowInsets.ime.getBottom(density)
    var hadKeyboard by remember { mutableStateOf(false) }
    LaunchedEffect(ime) {
        if (ime > 0) hadKeyboard = true
        else if (hadKeyboard) { focus.clearFocus(); hadKeyboard = false }
    }
    LaunchedEffect(notice) { if (notice != null) { delay(Metrics.commsNoticeMs); notice = null } }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    fun openApp(app: String) {
        val launch = context.packageManager.getLaunchIntentForPackage(app)
        if (launch != null) runCatching { context.startActivity(launch) }
        else notice = "app unavailable" to app
    }
    // One send path for the field and the T77 chips row, so a chip reports exactly like Send.
    fun send(conversation: CommsConversation, body: String) {
        if (body.isBlank() || sending) return
        sending = true
        focus.clearFocus()
        keyboard?.hide()
        scope.launch {
            try {
                val result = repository.reply(conversation, body)
                notice = CommsPresentation.notice(result) to conversation.app
                if (result == ReplyResult.Sent) draft = ""
            } finally {
                sending = false
            }
        }
    }
    fun markRead(conversation: CommsConversation) {
        scope.launch {
            notice = CommsExtrasPresentation.readNotice(repository.markAsRead(conversation)) to conversation.app
        }
    }
    val access = remember(context) { NotificationAccessLink.of(context, ComponentName(context, ParcNotificationListenerService::class.java)) }
    var granted by remember { mutableStateOf(access.status == NotificationAccessStatus.Granted) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, access) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = access.status == NotificationAccessStatus.Granted
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val list = rememberLazyListState()
    RegisterHubScroll(list)
    val historyList = rememberLazyListState()
    RegisterHubScroll(historyList)
    val ordered = remember(conversations) { CommsPresentation.cards(conversations) }
    val mode = CommsExtrasPresentation.mode(enabled, historyOn, ordered.size, dismissed.size)
    val liveApps = remember(ordered) { ordered.map { it.app } }
    val compactLimit = if (LocalHubSize.current == HubSize.Compact) 1 else if (LocalHubSize.current == HubSize.Standard) 3 else Int.MAX_VALUE
    val noticeApp = notice?.second
    val noticePlacement = CommsExtrasPresentation.noticePlacement(noticeApp, liveApps)
    // T158: no page background (the hub card is the surface); 12 dp between the rows.
    Column(modifier.fillMaxSize()
        .pointerInput(Unit) { detectTapGestures(onTap = { focus.clearFocus(); keyboard?.hide() }) },
        verticalArrangement = Arrangement.spacedBy(com.brightblu.parc.model.ParcSpacing.hubRowGapDp().dp)) {
        if (reviewAccess ?: granted) HubHero(ordered.size.toString(), "Conversations", skin)
        if (!(reviewAccess ?: granted)) {
            HubPermissionCard("Comms needs notification access", "Allow", skin) { context.startActivity(access.settingsIntent()) }
        }
        // T77: the history toggle stays reachable even when nothing is active, so a dismissed
        // message can still be read after the conversation left the list.
        if (enabled) {
            Row(Modifier.fillMaxWidth().heightIn(min = Metrics.commsActionDp.dp),
                verticalAlignment = Alignment.CenterVertically) {
                BasicText(CommsExtrasPresentation.historyLabel(dismissed.size), style = consoleStyle(skin).copy(
                    fontSize = Metrics.commsHeaderSp.sp, color = Color(skin.colors.textSecondary.toInt())))
                Spacer(Modifier.weight(1f))
                ConsoleAction(CommsExtrasPresentation.historyToggle(historyOn), skin) {
                    historyOn = !historyOn
                    focus.clearFocus(); keyboard?.hide()
                }
            }
        }
        // A status line whose card left the list (mark-as-read through the dismiss fallback) is
        // reported on the page, so the outcome is never swallowed with the card.
        if (noticePlacement == CommsNoticePlacement.Page) {
            notice?.let { (text, app) -> ConsoleAction(text, skin) { openApp(app) } }
        }
        when (mode) {
            CommsMode.History -> CommsHistoryList(dismissed, historyList, skin)
            CommsMode.Live -> LazyColumn(Modifier.fillMaxSize(), state = list,
                contentPadding = PaddingValues(bottom = HubPageScroll.endPaddingDp.dp),
                verticalArrangement = Arrangement.spacedBy(Metrics.itemGapDp.dp)) {
                items(minOf(ordered.size, compactLimit), key = { ordered[it].id.toString() }) { index ->
                    val conversation = ordered[index]
                    val chosen = selection == conversation.id
                    val card = CommsExtrasPresentation.cardState(chosen, cannedReplies)
                    Column(Modifier.fillMaxWidth().commsFrame(skin, chosen).parcCardPadding()) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f).heightIn(min = Metrics.commsActionDp.dp)
                                .clickable {
                                    focus.clearFocus(); keyboard?.hide()
                                    selected = conversation.id; draft = ""; notice = null
                                }, contentAlignment = Alignment.CenterStart) {
                                BasicText(CommsPresentation.header(conversation), style = consoleStyle(skin).copy(
                                    fontSize = Metrics.commsHeaderSp.sp, color = Color(skin.colors.accent.toInt())),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            BasicText("${conversation.messages.size} ", style = consoleStyle(skin).copy(
                                fontSize = Metrics.commsHeaderSp.sp, color = Color(skin.colors.textSecondary.toInt())))
                            ConsoleAction(if (skin.style.terminalStats) "[read]" else "Read", skin) { markRead(conversation) }
                            ConsoleAction(if (skin.style.terminalStats) "[x all]" else "Dismiss", skin) { scope.launch { repository.dismiss(conversation) } }
                        }
                        // T56 behaviour: the status line is the affordance that opens the app when
                        // the notification cannot be answered or read in place. T77 keeps it on the
                        // card while the card is live.
                        notice?.takeIf { noticePlacement == CommsNoticePlacement.Card && it.second == conversation.app }?.let { (text, app) ->
                            ConsoleAction(text, skin) { openApp(app) }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(Metrics.commsMessageGapDp.dp)) {
                            CommsPresentation.recent(conversation).forEach { message ->
                                key(message) {
                                    MessageLine(conversation.app, message, skin, { openApp(conversation.app) }) {
                                        scope.launch { repository.dismiss(message) }
                                    }
                                }
                            }
                        }
                        if (card.showField) {
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = Metrics.commsActionDp.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                BasicText("> ", style = consoleStyle(skin))
                                BasicTextField(
                                    value = draft,
                                    onValueChange = { draft = it },
                                    modifier = Modifier.weight(1f)
                                        .heightIn(min = Metrics.commsActionDp.dp).wrapContentHeight(),
                                    enabled = !sending,
                                    singleLine = true,
                                    textStyle = consoleStyle(skin),
                                    cursorBrush = SolidColor(Color(skin.colors.accent.toInt())),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                    keyboardActions = KeyboardActions(onSend = { send(conversation, draft) }),
                                )
                            }
                        }
                        if (card.showQuickReplies) {
                            ReplyChips(card.quickReplies, skin, !sending) { reply -> send(conversation, reply) }
                        }
                    }
                }
            }
            CommsMode.Empty -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BasicText(hubTemplates.getValue("comms").empty, style = consoleStyle(skin).copy(color = Color(skin.colors.textSecondary.toInt())))
            }
            CommsMode.NoHistory -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BasicText("No saved messages yet", style = consoleStyle(skin).copy(color = Color(skin.colors.textSecondary.toInt())))
            }
        }
    }
}

/**
 * T77 quick-reply chips under the selected card's field: the canned replies in their stored order,
 * one tap sends. Scrolls horizontally because the list can hold eight entries; the height is the
 * shared action touch target.
 */
@Composable
private fun ReplyChips(replies: List<String>, skin: Skin, enabled: Boolean, send: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.commsChipGapDp.dp)) {
        // The `[r]` label names the row, the same console notation as `[read]` and `[x all]`.
        BasicText("[r]", style = consoleStyle(skin).copy(
            fontSize = Metrics.commsHeaderSp.sp, color = Color(skin.colors.textSecondary.toInt())))
        replies.forEach { reply ->
            val shape = RoundedCornerShape(Metrics.commsChipCornerDp.dp)
            Box(Modifier.heightIn(min = Metrics.commsActionDp.dp).widthIn(max = Metrics.commsChipMaxWidthDp.dp)
                .background(Color(skin.colors.surface.toInt()).copy(alpha = skin.shape.surfaceAlpha), shape)
                .border(skin.shape.surfaceBorderDp.dp, Color((if (enabled) skin.colors.lineActive else skin.colors.lineBase).toInt()), shape)
                .clickable(enabled = enabled) { send(reply) }
                .padding(horizontal = Metrics.itemGapDp.dp, vertical = Metrics.lineGapDp.dp),
                contentAlignment = Alignment.Center) {
                BasicText(reply, style = consoleStyle(skin).copy(
                    color = Color((if (enabled) skin.colors.accent else skin.colors.lineActive).toInt())),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * T77 dismissed-message view: the same card frame, every line dimmed and deliberately free of the
 * `[x]`/`[x all]` controls, because a dismissed message has nothing left to dismiss.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CommsHistoryList(entries: List<CommsHistoryEntry>, state: androidx.compose.foundation.lazy.LazyListState, skin: Skin) {
    val cards = remember(entries) { CommsExtrasPresentation.historyCards(entries) }
    val dim = Color(skin.colors.textSecondary.toInt()).copy(alpha = Metrics.commsHistoryTextAlpha)
    LazyColumn(Modifier.fillMaxSize(), state = state,
        contentPadding = PaddingValues(bottom = HubPageScroll.endPaddingDp.dp),
        verticalArrangement = Arrangement.spacedBy(Metrics.itemGapDp.dp)) {
        items(cards.size, key = { cards[it].lines.last().conversation.toString() }) { index ->
            val card = cards[index]
            Column(Modifier.fillMaxWidth().commsFrame(skin, false).parcCardPadding()) {
                BasicText(card.header, style = consoleStyle(skin).copy(
                    fontSize = Metrics.commsHeaderSp.sp, color = dim),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                card.lines.forEach { entry ->
                    val (clock, body) = CommsPresentation.lineParts(entry.message)
                    BasicText("$clock  $body", style = consoleStyle(skin).copy(color = dim),
                        maxLines = Metrics.commsMaxLines, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Cache all frame geometry and colors outside the draw loop. */
private fun Modifier.commsFrame(skin: Skin, selected: Boolean): Modifier = drawWithCache {
    val color = Color((if (selected) skin.colors.accent else skin.colors.lineActive).toInt())
    val surface = Color(skin.colors.surface.toInt()).copy(alpha = skin.shape.surfaceAlpha)
    val b = skin.hud.bracketLengthDp.dp.toPx()
    val stroke = skin.hud.bracketWidthDp.dp.toPx()
    val points = arrayOf(
        Offset(0f,b), Offset.Zero, Offset(b,0f),
        Offset(size.width-b,0f), Offset(size.width,0f), Offset(size.width,b),
        Offset(size.width,size.height-b), Offset(size.width,size.height), Offset(size.width-b,size.height),
        Offset(b,size.height), Offset(0f,size.height), Offset(0f,size.height-b))
    val terminal = skin.style.terminalStats
    val box = androidx.compose.ui.graphics.Path().apply {
        moveTo(stroke, stroke); lineTo(size.width - stroke, stroke)
        lineTo(size.width - stroke, size.height - stroke); lineTo(stroke, size.height - stroke); close()
    }
    val radius = androidx.compose.ui.geometry.CornerRadius(skin.shape.surfaceCornerDp.dp.toPx())
    val outline = androidx.compose.ui.graphics.drawscope.Stroke(stroke)
    onDrawBehind {
        drawRect(surface)
        if (terminal) drawPath(box, color, style = outline)
        if (!terminal) {
            drawRoundRect(surface, cornerRadius = radius)
            drawRoundRect(color, cornerRadius = radius, style = outline)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MessageLine(app: String, message: CommsMessage, skin: Skin, openApp: () -> Unit, dismiss: () -> Unit) {
    val style = consoleStyle(skin)
    val measurer = rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    var expanded by remember(message) { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val (clock, body) = remember(message) { CommsPresentation.lineParts(message) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        BasicText("$clock  ",
            style = style.copy(fontSize = Metrics.commsHeaderSp.sp, color = Color(skin.colors.textSecondary.toInt())))
        Column(Modifier.weight(1f)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val full = body
                val width = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
                val layout = remember(full, width, style) { measurer.measure(AnnotatedString(full), style, constraints = androidx.compose.ui.unit.Constraints(maxWidth = width)) }
                val end = CommsPresentation.visibleEnd((0 until layout.lineCount).map { layout.getLineEnd(it, visibleEnd = true) }, expanded) ?: full.length
                val annotated = buildAnnotatedString {
                    append(full.substring(0, end))
                    message.links.forEach { link ->
                        val start = link.start
                        val stop = link.endExclusive.coerceAtMost(end)
                        if (start < stop && link.endExclusive <= message.text.length) {
                            val raw = message.text.substring(link.start, link.endExclusive)
                            val url = if (raw.startsWith("www.", true)) "https://$raw" else raw
                            addStyle(SpanStyle(textDecoration = TextDecoration.Underline, color = Color(skin.colors.accent.toInt())), start, stop)
                            addStringAnnotation("url", url, start, stop)
                        }
                    }
                }
                var result by remember { mutableStateOf<TextLayoutResult?>(null) }
                // Gesture offsets use the actual wrapped text, preserving UTF-16 link spans.
                BasicText(annotated, Modifier.fillMaxWidth().then(Modifier.pointerLinkGestures(annotated, { result },
                    { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
                    { url -> clipboard.setText(AnnotatedString(url)) })), style = style, onTextLayout = { result = it })
            }
            // A separate action keeps [more] reachable even when the third line fills the width.
            if (!expanded) {
                MoreAction(message.text, style, measurer, skin) { expanded = true }
            }
            message.image?.let { mask ->
                val bitmap = remember(mask, skin.colors.accent) {
                    Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888).also { image ->
                        val pixels = IntArray(mask.width * mask.height) { i -> if (mask.bitAt(i % mask.width, i / mask.width)) skin.colors.accent.toInt() else skin.colors.bg.toInt() }
                        image.setPixels(pixels, 0, mask.width, 0, 0, mask.width, mask.height)
                    }.asImageBitmap()
                }
                Image(bitmap, "[img]", Modifier.size(Metrics.commsImageDp.dp).clickable(onClick = openApp), filterQuality = androidx.compose.ui.graphics.FilterQuality.None)
                ConsoleAction("[img]", skin, action = openApp)
            }
        }
        ConsoleAction(if (skin.style.terminalStats) "[x]" else "Dismiss", skin, action = dismiss)
    }
}

@Composable
private fun MoreAction(text: String, style: TextStyle, measurer: TextMeasurer, skin: Skin, expand: () -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val layout = remember(text, width, style) { measurer.measure(AnnotatedString(text), style, constraints = androidx.compose.ui.unit.Constraints(maxWidth = width)) }
        if (layout.lineCount > Metrics.commsMaxLines) ConsoleAction(if (skin.style.terminalStats) "[more]" else "More", skin, action = expand)
    }
}

private fun Modifier.pointerLinkGestures(text: AnnotatedString, layout: () -> TextLayoutResult?,
    open: (String) -> Unit, copy: (String) -> Unit): Modifier = this.then(
    Modifier.pointerInput(text) {
        fun link(position: androidx.compose.ui.geometry.Offset): String? {
            val result = layout() ?: return null
            val offset = result.getOffsetForPosition(position)
            return text.getStringAnnotations("url", offset, offset).firstOrNull()?.item
        }
        detectTapGestures(onTap = { point -> link(point)?.let(open) },
            onLongPress = { point -> link(point)?.let(copy) })
    })

internal enum class CommsSettingsSection { Summary, Apps, Replies }

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun CommsSettings(repository: CommsExtrasRepository, skin: Skin, apps: List<Pair<String, String>> = emptyList(),
    section: CommsSettingsSection = CommsSettingsSection.Summary,
    onOpenApps: () -> Unit = {}, onOpenReplies: () -> Unit = {}) {
    val enabled by repository.enabled.collectAsState()
    val allowed by repository.allowedApps.collectAsState()
    val keepHistory by repository.keepHistory.collectAsState()
    val canned by repository.cannedReplies.collectAsState()
    var draft by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    // The reply input and its add action stay clear of the keyboard in the edge-to-edge
    // window, so the column keeps clear of the IME and, once the inset has settled, asks the
    // scroll container to reveal the field together with its [add] action.
    val editorView = remember { BringIntoViewRequester() }
    val ime = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current)
    LaunchedEffect(ime, section) {
        if (ime > 0 && section == CommsSettingsSection.Replies) {
            delay(Metrics.commsImeSettleMs)
            editorView.bringIntoView()
        }
    }
    val undo = rememberEditorUndo()
    val spec = if (section == CommsSettingsSection.Apps) EditorSpec.CommsApps else EditorSpec.QuickReplies
    val body: @Composable () -> Unit = {
    ParcBlocks(Metrics.rowGapDp, Modifier.fillMaxWidth().imePadding()) {
        if (section == CommsSettingsSection.Summary) {
        BasicText("Messages and quick replies", style = settingsText(skin).copy(
            fontSize = skin.typography.title.sizeSp.sp, fontWeight = androidx.compose.ui.text.font.FontWeight(skin.typography.title.weight)))
        CommsSwitch("Comms", enabled, skin) { value -> scope.launch { repository.setEnabled(value) } }
        CommsSwitch("Keep history", keepHistory, skin) { value -> scope.launch { repository.setKeepHistory(value) } }
        SettingsSection("Messages", skin)
        SettingsCard(skin) {
            SettingRow("Comms apps", skin, SettingKind.Page, description = "Choose which apps can show messages", onClick = onOpenApps)
            SettingRow("Quick replies", skin, SettingKind.Page, description = "Add or remove saved reply text", onClick = onOpenReplies)
        }
        }
        if (section == CommsSettingsSection.Apps) {
        val choices = remember(apps, allowed) {
            (CommsPackages.Default.map { it to CommsPresentation.tag(it) } + apps + allowed.map { it to CommsPresentation.tag(it) })
                .distinctBy { it.first }.sortedBy { it.second.lowercase() }
        }
        choices.forEach { (app, label) ->
            CommsSwitch(label, app in allowed, skin) { on ->
                scope.launch { val current = repository.allowedApps.value; repository.setAllowedApps(if (on) current + app else current - app) }
                if (!on) undo.offer { scope.launch { repository.setAllowedApps(repository.allowedApps.value + app) } }
            }
        }
        }
        if (section == CommsSettingsSection.Replies) {
        // T77: canned replies, at most eight, stored in the same order the chips show.
        SettingsSection("Quick replies", skin)
        canned.forEachIndexed { index, reply ->
            SettingsCard(skin) {
                SettingRow("Remove reply", skin, SettingKind.Action, description = reply, icon = "×", divider = false) {
                    scope.launch { repository.setCannedReplies(CannedReplyEditor.remove(repository.cannedReplies.value, index)) }
                    undo.offer { scope.launch {
                        val current = repository.cannedReplies.value
                        if (reply !in current) repository.setCannedReplies(current.toMutableList().apply { add(index.coerceAtMost(size), reply) })
                    } }
                }
            }
        }
        if (canned.isEmpty()) {
            BasicText(EditorSpec.QuickReplies.empty, style = consoleStyle(skin).copy(
                fontSize = Metrics.commsHeaderSp.sp, color = Color(skin.colors.textSecondary.toInt())))
        }
        Row(Modifier.fillMaxWidth().heightIn(min = Metrics.commsActionDp.dp).bringIntoViewRequester(editorView),
            verticalAlignment = Alignment.CenterVertically) {
            BasicText("> ", style = consoleStyle(skin))
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f).heightIn(min = Metrics.commsActionDp.dp).wrapContentHeight(),
                enabled = canned.size < CommsCannedReplies.Max,
                singleLine = true,
                textStyle = consoleStyle(skin),
                cursorBrush = SolidColor(Color(skin.colors.accent.toInt())),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (CannedReplyEditor.canAdd(canned, draft)) {
                        val next = CannedReplyEditor.add(canned, draft)
                        draft = ""
                        scope.launch { repository.setCannedReplies(next) }
                    }
                }),
            )
            ConsoleAction("+ Add", skin, modifier = Modifier.heightIn(min = Metrics.touchTargetDp.dp), enabled = CannedReplyEditor.canAdd(canned, draft)) {
                val next = CannedReplyEditor.add(canned, draft)
                draft = ""
                scope.launch { repository.setCannedReplies(next) }
            }
        }
        BasicText("${canned.size}/${CommsCannedReplies.Max}", style = consoleStyle(skin).copy(
            fontSize = Metrics.commsHeaderSp.sp, color = Color(skin.colors.textSecondary.toInt())))
        }
    }
    }
    if (section == CommsSettingsSection.Summary) {
        Column(Modifier.verticalScroll(rememberHubScrollState()).parcBottomInset()) { body() }
    } else EditorScaffold(spec, skin,
        empty = if (section == CommsSettingsSection.Apps) allowed.isEmpty() else canned.isEmpty(),
        undo = if (undo.action != null) ({ undo.run() }) else null) { body() }

}

@Composable
private fun CommsSwitch(label: String, on: Boolean, skin: Skin, change: (Boolean) -> Unit) {
    SettingsCard(skin) {
        SettingRow(label, skin, SettingKind.Toggle, checked = on, divider = false) { change(!on) }
    }
}
