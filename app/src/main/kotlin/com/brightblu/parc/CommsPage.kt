package com.brightblu.parc

import com.brightblu.parc.model.readableAccent
import com.brightblu.parc.feature.settings.ui.*

import android.content.ComponentName
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.brightblu.parc.core.data.comms.*
import com.brightblu.parc.core.data.notifications.NotificationListenerStatus
import com.brightblu.parc.core.data.notifications.ParcNotificationListenerService
import com.brightblu.parc.feature.settings.notifications.NotificationAccessLink
import com.brightblu.parc.feature.settings.notifications.NotificationAccessStatus
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.ModuleStyle
import com.brightblu.parc.model.ParcBlocks
import com.brightblu.parc.model.parcBottomInset
import com.brightblu.parc.model.parcCardPadding
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.WritingRules
import com.brightblu.parc.ring.module.frostedBackground
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@Composable
internal fun ConsoleAction(text: String, skin: Skin, modifier: Modifier = Modifier,
    enabled: Boolean = true, action: () -> Unit) {
    Box(modifier.heightIn(min = Metrics.commsActionDp.dp).widthIn(min = Metrics.commsActionDp.dp)
        .clickable(enabled = enabled, onClick = action).padding(Metrics.lineGapDp.dp), contentAlignment = Alignment.CenterStart) {
        BasicText(text, style = commsStyle(skin).copy(
            color = Color((if (enabled) skin.colors.readableAccent else skin.colors.lineActive).toInt())))
    }
}

/**
 * T233: Messages, with the messages as the page. The old large hero and full-width Live / History
 * row are gone; the header is one line, the list owns the height, and each card puts its newest
 * message before its actions. The footer is the last list item so it cannot take the list's room.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun CommsPage(repository: CommsExtrasRepository, skin: Skin, modifier: Modifier = Modifier,
    reviewAccess: Boolean? = null) {
    val allConversations by repository.conversations.collectAsState()
    val focusState = LocalFocusRepository.current?.state?.collectAsState()?.value
    // T217: Messages uses the launcher's message rule, not its old hiddenComms + Lock filter.
    // A Focus-hidden app's messages are hidden by default; "Always show messages" is the only
    // exception, and permanent hide, Lock and a hidden folder can never be overridden.
    val visibility = LocalVisibility.current
    val hidden = visibility.messageBlocked
    val conversations = remember(allConversations, hidden) { allConversations.filterNot { it.app in hidden } }
    val enabled by repository.enabled.collectAsState()
    val cannedReplies by repository.cannedReplies.collectAsState()
    // T215: the stored rows are the History source of truth; the old T77 projection is no longer
    // read here, so a row can be deleted by id and the count always follows the database.
    val allStored by repository.storedMessages.collectAsState()
    val stored = remember(allStored, hidden) { allStored.filterNot { it.app in hidden } }
    var selected by remember { mutableStateOf<CommsConversationId?>(null) }
    val selection = CommsPresentation.selection(selected, conversations)
    LaunchedEffect(selection) { selected = selection }
    var draft by remember { mutableStateOf("") }
    // Drafts/notices are never saved. T214 stores accepted messages separately in
    // CommsHistoryStore; this view only reads those rows.
    var notice by remember { mutableStateOf<Pair<String, String>?>(null) }
    var sending by remember { mutableStateOf(false) }
    // T215: the Live/History segment is a view state only; the stored rows are independent of it.
    var segment by remember { mutableStateOf(CommsSegment.Live) }
    var query by remember { mutableStateOf("") }
    var visibleRows by remember { mutableIntStateOf(CommsExtrasPresentation.HistoryPageSize) }
    var historyMenu by remember { mutableStateOf<CommsHistoryMessage?>(null) }
    var conversationMenu by remember { mutableStateOf<CommsHistoryGroup?>(null) }
    var deleteMessage by remember { mutableStateOf<CommsHistoryMessage?>(null) }
    var deleteConversation by remember { mutableStateOf<CommsHistoryGroup?>(null) }
    var historyNotice by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val replyFocus = remember { FocusRequester() }
    var focusRequest by remember { mutableIntStateOf(0) }
    var whyOpen by remember { mutableStateOf(false) }
    val listener by NotificationListenerStatus.state.collectAsState()
    DisposableEffect(Unit) { onDispose { focus.clearFocus(); keyboard?.hide() } }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val ime = WindowInsets.ime.getBottom(density)
    var hadKeyboard by remember { mutableStateOf(false) }
    LaunchedEffect(ime) {
        if (ime > 0) hadKeyboard = true
        else if (hadKeyboard) { focus.clearFocus(); hadKeyboard = false }
    }
    LaunchedEffect(notice) { if (notice != null) { delay(Metrics.commsNoticeMs); notice = null } }
    LaunchedEffect(historyNotice) { if (historyNotice != null) { delay(Metrics.commsNoticeMs); historyNotice = null } }
    LaunchedEffect(query) { visibleRows = CommsExtrasPresentation.HistoryPageSize }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    fun openApp(app: String) {
        val launch = context.packageManager.getLaunchIntentForPackage(app)
        if (launch != null) runCatching { context.startActivity(launch) }
        else notice = "app unavailable" to app
    }
    fun beginReply(conversation: CommsConversation) {
        selected = conversation.id
        draft = ""
        notice = null
        focusRequest++
    }
    fun cancelReply() {
        selected = null
        draft = ""
        focus.clearFocus()
        keyboard?.hide()
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
                if (result == ReplyResult.Sent) {
                    draft = ""
                    // T386: a sent reply closes the composer; the page keeps the "sent" notice.
                    selected = null
                }
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
    fun dismissAll() {
        scope.launch { conversations.forEach { repository.dismiss(it) } }
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
    val list = rememberHubListState()
    val historyList = rememberHubListState()
    val ordered = remember(conversations) { CommsPresentation.cards(conversations) }
    val accessOn = reviewAccess ?: granted
    // Access off chooses History and keeps it reachable; when access returns, the user's chosen
    // segment is left alone.
    LaunchedEffect(accessOn) { if (!accessOn) segment = CommsSegment.History }
    val mode = CommsExtrasPresentation.mode(segment, accessOn, enabled, ordered.size, stored.size)
    val focusShown = focusState?.active?.alwaysShowMessagePackages.orEmpty()
    val focusHiddenApps = remember(focusState, allConversations, visibility) {
        val forcedHidden = visibility.permanentHidden + visibility.lockedNow + visibility.hiddenFolderApps
        allConversations.filter {
            (it.app in visibility.focusBlocked && it.app !in focusShown && it.app !in forcedHidden) ||
                (it.app in focusState?.active?.hiddenComms.orEmpty() && it.app !in forcedHidden)
        }.map { it.app }.distinct()
    }
    val emptyState = CommsExtrasPresentation.emptyState(
        accessOn = accessOn,
        enabled = enabled,
        live = ordered.size,
        stored = stored.size,
        focusMode = focusState?.active?.name,
        focusHiddenApps = focusHiddenApps,
        drops = listener.lastDropReasons.size,
    )
    val whyRows = remember(listener.lastDropReasons, accessOn) {
        CommsPresentation.whyRows(listener.lastDropReasons, accessOn)
    }
    val openFocus = LocalEditFocus.current
    val liveApps = remember(ordered) { ordered.map { it.app } }
    val noticeApp = notice?.second
    val noticePlacement = CommsExtrasPresentation.noticePlacement(noticeApp, liveApps)
    val allHistoryGroups = remember(stored, query) { CommsExtrasPresentation.historyGroups(stored, query) }
    val historyTotal = CommsExtrasPresentation.historyTotal(allHistoryGroups)
    val historyGroups = remember(allHistoryGroups, visibleRows) {
        CommsExtrasPresentation.historyPage(allHistoryGroups, visibleRows)
    }
    val historyCards = remember(historyGroups) { CommsPresentation.historyCards(historyGroups) }
    val historySections = remember(historyCards) { CommsPresentation.historySections(historyCards) }
    fun replyFromHistory(message: CommsHistoryMessage) {
        val conversation = conversations.firstOrNull { it.id == message.conversation } ?: return
        segment = CommsSegment.Live
        beginReply(conversation)
    }
    fun openHistoryMessage(message: CommsHistoryMessage) {
        when (CommsExtrasPresentation.historyAction(message, conversations).action) {
            CommsHistoryAction.Reply -> replyFromHistory(message)
            CommsHistoryAction.Open -> openApp(message.app)
        }
    }
    BoxWithConstraints(modifier.fillMaxSize()
        .pointerInput(Unit) { detectTapGestures(onTap = { focus.clearFocus(); keyboard?.hide() }) }) {
        RegisterHubScroll(if (segment == CommsSegment.Live) list else historyList)
        LazyColumn(
            Modifier.fillMaxSize(),
            state = if (segment == CommsSegment.Live) list else historyList,
            contentPadding = PaddingValues(bottom = HubPageScroll.endPaddingDp.dp),
            verticalArrangement = Arrangement.spacedBy(Metrics.itemGapDp.dp),
        ) {
            item(key = "messages-header") {
                MessagesHeader(
                    live = ordered.size,
                    segment = segment,
                    skin = skin,
                    menuOpen = menuOpen,
                    onMenuOpenChange = { menuOpen = it },
                    onSelect = { next ->
                        segment = next
                        menuOpen = false
                        focus.clearFocus(); keyboard?.hide()
                    },
                    onDismissAll = {
                        menuOpen = false
                        dismissAll()
                    },
                )
            }
            // A status line whose card left the list (mark-as-read through the dismiss fallback) is
            // reported on the page, so the outcome is never swallowed with the card.
            if (noticePlacement == CommsNoticePlacement.Page) {
                item(key = "page-notice") {
                    notice?.let { (text, app) -> ConsoleAction(text, skin) { openApp(app) } }
                }
            }
            when (mode) {
                CommsMode.Live -> items(ordered, key = { it.id.toString() }) { conversation ->
                    val chosen = selection == conversation.id
                    LiveConversationCard(
                        conversation = conversation,
                        selected = chosen,
                        skin = skin,
                        notice = notice?.takeIf { noticePlacement == CommsNoticePlacement.Card && it.second == conversation.app }?.first,
                        onBeginReply = { beginReply(conversation) },
                        onMarkRead = { markRead(conversation) },
                        onDismiss = { scope.launch { repository.dismiss(conversation) } },
                        onOpenApp = { openApp(conversation.app) },
                    )
                }
                CommsMode.History -> {
                    item(key = "history-search") {
                        HistorySearch(query, { query = it }, accessOn, historyNotice, skin)
                    }
                    historySections.forEach { section ->
                        item(key = "date-${section.label}") { HistoryDateHeader(section.label, skin) }
                        items(section.messages, key = { "history-${it.id}" }) { message ->
                            HistoryMessageCard(
                                message = message,
                                conversations = conversations,
                                skin = skin,
                                onAction = { openHistoryMessage(message) },
                                onBodyLongPress = { historyMenu = message },
                                onHeaderLongPress = {
                                    conversationMenu = CommsHistoryGroup(
                                        app = message.app,
                                        conversationKey = message.conversationKey,
                                        title = message.conversationTitle,
                                        messages = listOf(message),
                                    )
                                },
                            )
                        }
                    }
                    if (historyTotal > visibleRows) {
                        item(key = "show-older") {
                            ConsoleAction("Show older", skin) {
                                visibleRows += CommsExtrasPresentation.HistoryPageSize
                            }
                        }
                    }
                }
                CommsMode.Empty -> item(key = "empty") {
                    CommsEmptyBody(emptyState, skin, Modifier.fillParentMaxHeight().fillMaxWidth()) { action ->
                        when (action) {
                            CommsEmptyAction.Allow -> runCatching { context.startActivity(access.settingsIntent()) }
                            CommsEmptyAction.TurnOn -> scope.launch { repository.setEnabled(true) }
                            CommsEmptyAction.OpenHistory -> {
                                segment = CommsSegment.History
                                focus.clearFocus(); keyboard?.hide()
                            }
                            CommsEmptyAction.OpenFocus -> openFocus()
                            CommsEmptyAction.Why -> whyOpen = true
                        }
                    }
                }
                CommsMode.NoHistory -> item(key = "no-history") {
                    Box(Modifier.fillParentMaxHeight().fillMaxWidth(), contentAlignment = Alignment.Center) {
                        BasicText("No stored messages yet", style = commsStyle(skin).copy(
                            color = Color(skin.colors.textSecondary.toInt())))
                    }
                }
            }
            item(key = "messages-footer") {
                BasicText(
                    CommsPresentation.footer(listener.connectedAtMs, accessOn, ordered.size, stored.size),
                    Modifier.fillMaxWidth().padding(top = Metrics.lineGapDp.dp),
                    style = commsStyle(skin, CommsType.caption(skin), skin.colors.textSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    // T386: the reply composer is one sheet above the keyboard, carrying the draft, Cancel and Send
    // together, instead of a field buried inside a scrolling card.
    val replying = conversations.firstOrNull { it.id == selection }
    if (replying != null && WritingRules.replyComposerVisible(selected != null, replying.canReply)) {
        CommsReplySheet(
            conversation = replying,
            cannedReplies = cannedReplies,
            draft = draft,
            onDraft = { draft = it },
            sending = sending,
            notice = notice?.takeIf { it.second == replying.app }?.first,
            skin = skin,
            replyFocus = replyFocus,
            focusRequest = focusRequest,
            onCancel = ::cancelReply,
            onSend = { send(replying, it) },
        )
    }
    if (whyOpen) CommsWhyDialog(whyRows, skin) { whyOpen = false }
    historyMenu?.let { message ->
        CommsMessageMenuDialog(
            message = message,
            skin = skin,
            onCopy = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(message.text))
                historyNotice = "copied"
                historyMenu = null
            },
            onDelete = {
                historyMenu = null
                deleteMessage = message
            },
            onDismiss = { historyMenu = null },
        )
    }
    deleteMessage?.let { message ->
        CommsConfirmDialog(
            title = "Delete message?",
            body = "This stored message is removed from this phone.",
            confirm = "Delete",
            skin = skin,
            onConfirm = {
                deleteMessage = null
                scope.launch { repository.deleteHistoryMessage(message.id) }
            },
            onDismiss = { deleteMessage = null },
        )
    }
    conversationMenu?.let { group ->
        CommsConversationMenuDialog(
            group = group,
            skin = skin,
            onDelete = {
                conversationMenu = null
                deleteConversation = group
            },
            onDismiss = { conversationMenu = null },
        )
    }
    deleteConversation?.let { group ->
        CommsConfirmDialog(
            title = "Delete conversation?",
            body = "Every stored message in this conversation is removed from this phone.",
            confirm = "Delete conversation",
            skin = skin,
            onConfirm = {
                deleteConversation = null
                scope.launch { repository.deleteHistoryConversation(group.app, group.conversationKey) }
            },
            onDismiss = { deleteConversation = null },
        )
    }
}

/** T233: one compact line, `Messages · 2 live`, with small text tabs on the same line. */
@Composable
private fun MessagesHeader(
    live: Int,
    segment: CommsSegment,
    skin: Skin,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onSelect: (CommsSegment) -> Unit,
    onDismissAll: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Metrics.commsHeaderRowDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            CommsPresentation.headerLine(live),
            Modifier.weight(1f),
            // Section, not the 28 sp title: with two 48 dp tabs and the menu on the same row, the
            // title role would ellipsize the live count at 384 dp wide.
            style = commsStyle(skin, CommsType.section(skin), skin.colors.textPrimary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        CommsTextTab(CommsSegment.Live.label, segment == CommsSegment.Live, skin) { onSelect(CommsSegment.Live) }
        CommsTextTab(CommsSegment.History.label, segment == CommsSegment.History, skin) { onSelect(CommsSegment.History) }
        if (live > 0) {
            Box {
                Box(
                    Modifier.heightIn(min = Metrics.commsActionDp.dp).widthIn(min = Metrics.commsActionDp.dp)
                        .clickable(role = Role.Button) { onMenuOpenChange(true) }
                        .semantics { contentDescription = "Messages menu"; role = Role.Button },
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText("…", style = commsStyle(skin, CommsType.section(skin), skin.colors.textSecondary))
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { onMenuOpenChange(false) },
                    modifier = Modifier.background(skin.floatingSurface()),
                ) {
                    DropdownMenuItem(
                        text = {
                            BasicText("Dismiss all", style = commsStyle(skin).copy(
                                color = Color(skin.colors.textPrimary.toInt())))
                        },
                        onClick = onDismissAll,
                    )
                }
            }
        }
    }
}

/** T233: a small text tab; the selected one is primary with a thin accent underline, never a pill. */
@Composable
private fun CommsTextTab(label: String, selected: Boolean, skin: Skin, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = Metrics.minTouchDp.dp)
            .widthIn(min = Metrics.minTouchDp.dp)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = "Show $label"; role = Role.Tab }
            .padding(horizontal = Metrics.itemGapDp.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            label,
            Modifier.drawBehind {
                if (selected) {
                    val stroke = Metrics.commsTabUnderlineDp.dp.toPx()
                    val y = size.height - stroke / 2f
                    drawLine(Color(skin.colors.accent.toInt()), Offset(0f, y), Offset(size.width, y), stroke)
                }
            },
            style = commsStyle(skin, CommsType.caption(skin),
                if (selected) skin.colors.textPrimary else skin.colors.textSecondary),
            maxLines = 1,
        )
    }
}

/** T233: app in secondary, sender in primary, time at the right. No underline and no link look. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CommsCardHeader(app: String, sender: String, time: String, skin: Skin,
    onLongPress: (() -> Unit)? = null) {
    // A header with a long-press action keeps the 48 dp minimum touch height; a plain one stays
    // as tight as its text so the live cards do not grow.
    val modifier = Modifier.fillMaxWidth().then(
        if (onLongPress != null) Modifier
            .heightIn(min = Metrics.minTouchDp.dp)
            .combinedClickable(
                onClickLabel = "Conversation actions",
                onLongClickLabel = "Delete conversation",
                onClick = {},
                onLongClick = onLongPress,
            ) else Modifier,
    )
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            CommsPresentation.appName(app),
            Modifier.widthIn(max = Metrics.commsChipMaxWidthDp.dp),
            style = commsStyle(skin, CommsType.caption(skin), skin.colors.textSecondary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(Metrics.itemGapDp.dp))
        BasicText(
            sender,
            Modifier.weight(1f),
            style = commsStyle(skin, CommsType.body(skin), skin.colors.textPrimary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(Metrics.itemGapDp.dp))
        BasicText(
            time,
            style = commsStyle(skin, CommsType.caption(skin), skin.colors.textSecondary),
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiveConversationCard(
    conversation: CommsConversation,
    selected: Boolean,
    skin: Skin,
    notice: String?,
    onBeginReply: () -> Unit,
    onMarkRead: () -> Unit,
    onDismiss: () -> Unit,
    onOpenApp: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().commsCardSurface(skin).commsFrame(skin, selected).parcCardPadding()) {
        CommsCardHeader(
            app = conversation.app,
            sender = CommsPresentation.sender(conversation),
            time = CommsPresentation.latest(conversation)?.let { CommsPresentation.time(it) }.orEmpty(),
            skin = skin,
        )
        val text = CommsPresentation.latestText(conversation)
        if (text.isNotBlank()) {
            BasicText(
                text,
                Modifier.fillMaxWidth().padding(top = Metrics.commsCardInnerGapDp.dp),
                style = commsStyle(skin, CommsType.body(skin), skin.colors.textPrimary),
                maxLines = Metrics.commsMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (conversation.messages.size > 1) {
            BasicText(
                CommsPresentation.messageCount(conversation.messages.size),
                Modifier.padding(top = Metrics.commsCardInnerGapDp.dp),
                style = commsStyle(skin, CommsType.caption(skin), skin.colors.textSecondary),
            )
        }
        notice?.let { line -> ConsoleAction(line, skin) { onOpenApp() } }
        FlowRow(
            Modifier.fillMaxWidth().padding(top = Metrics.commsCardInnerGapDp.dp),
            horizontalArrangement = Arrangement.spacedBy(Metrics.itemGapDp.dp),
        ) {
            ConsoleAction(CommsPresentation.liveActionLabel(conversation), skin) {
                if (conversation.canReply) onBeginReply() else onOpenApp()
            }
            ConsoleAction("Mark read", skin) { onMarkRead() }
            ConsoleAction("Dismiss", skin) { onDismiss() }
        }
        if (selected && !conversation.canReply) {
            BasicText("This app does not allow replies here.",
                style = commsStyle(skin, CommsType.caption(skin), skin.colors.textSecondary))
        }
    }
}

/**
 * T386: the reply composer. It is one sheet above the keyboard with the sender header and Cancel on
 * the first 48 dp row and the draft, Send and the saved quick replies together below it, so the
 * keyboard never covers the draft or the action the user came to press.
 */
@Composable
private fun CommsReplySheet(
    conversation: CommsConversation,
    cannedReplies: List<String>,
    draft: String,
    onDraft: (String) -> Unit,
    sending: Boolean,
    notice: String?,
    skin: Skin,
    replyFocus: FocusRequester,
    focusRequest: Int,
    onCancel: () -> Unit,
    onSend: (String) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val sender = CommsPresentation.sender(conversation)
    val sheetShape = RoundedCornerShape(topStart = Metrics.menuCornerDp.dp, topEnd = Metrics.menuCornerDp.dp)
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                Modifier.fillMaxWidth().clip(sheetShape)
                    .background(Color(skin.colors.moduleSurface.toInt()))
                    .border(skin.shape.surfaceBorderDp.dp, Color(skin.colors.lineBase.toInt()), sheetShape)
                    .padding(horizontal = Metrics.commsReplySheetInsetDp.dp, vertical = Metrics.itemGapDp.dp),
            ) {
                LaunchedEffect(focusRequest) {
                    if (focusRequest > 0) {
                        // The sheet can leave while the effect is starting; a detached requester must
                        // never crash the page.
                        runCatching { replyFocus.requestFocus() }
                        keyboard?.show()
                    }
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Metrics.commsActionDp.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        "Reply to $sender",
                        Modifier.weight(1f),
                        style = commsStyle(skin, CommsType.section(skin), skin.colors.textPrimary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ConsoleAction("Cancel", skin, action = onCancel)
                }
                notice?.let { line ->
                    BasicText(
                        line,
                        Modifier.padding(bottom = Metrics.commsReplyNoticeGapDp.dp),
                        style = commsStyle(skin, CommsType.caption(skin), skin.colors.textSecondary),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Metrics.commsReplyRowMinDp.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (skin.style.terminalStats) BasicText("> ", style = commsStyle(skin))
                    BasicTextField(
                        value = draft,
                        onValueChange = onDraft,
                        modifier = Modifier.weight(1f).focusRequester(replyFocus)
                            .heightIn(min = Metrics.commsReplyRowMinDp.dp).wrapContentHeight()
                            .semantics { contentDescription = "Reply to $sender" },
                        enabled = !sending,
                        maxLines = 3,
                        textStyle = commsStyle(skin),
                        cursorBrush = SolidColor(Color(skin.colors.accent.toInt())),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (WritingRules.sendEnabled(draft, sending)) onSend(draft)
                        }),
                    )
                    Spacer(Modifier.width(Metrics.itemGapDp.dp))
                    CommsSendAction(skin, WritingRules.sendEnabled(draft, sending)) { onSend(draft) }
                }
                if (cannedReplies.isNotEmpty()) {
                    ReplyChips(cannedReplies, skin, !sending) { reply -> onSend(reply) }
                }
            }
        }
    }
}

/** T386: the filled Send pill; 48 dp tall and at least 48 dp wide, with the skin's own accent. */
@Composable
private fun CommsSendAction(skin: Skin, enabled: Boolean, onClick: () -> Unit) {
    val fill = Color((if (enabled) skin.colors.accent else skin.colors.lineActive).toInt())
    val ink = if (enabled) skin.colors.bg else skin.colors.textSecondary
    Box(
        Modifier.heightIn(min = Metrics.commsActionDp.dp).widthIn(min = Metrics.commsActionDp.dp)
            .clip(RoundedCornerShape(percent = 50)).background(fill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Metrics.rowGapDp.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText("Send", style = commsStyle(skin, CommsType.body(skin), ink))
    }
}

@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun HistoryMessageCard(
    message: CommsHistoryMessage,
    conversations: List<CommsConversation>,
    skin: Skin,
    onAction: () -> Unit,
    onBodyLongPress: () -> Unit,
    onHeaderLongPress: () -> Unit,
) {
    val action = remember(message, conversations) {
        CommsExtrasPresentation.historyAction(message, conversations)
    }
    val dim = skin.colors.textSecondary
    val sender = message.sender.ifBlank { CommsPresentation.appName(message.app) }
    Column(Modifier.fillMaxWidth().commsCardSurface(skin).commsFrame(skin, false).parcCardPadding()) {
        CommsCardHeader(
            app = message.app,
            sender = sender,
            time = CommsPresentation.time(message.timeMs),
            skin = skin,
            onLongPress = onHeaderLongPress,
        )
        BasicText(
            message.text,
            Modifier.fillMaxWidth().padding(top = Metrics.commsCardInnerGapDp.dp)
                .heightIn(min = Metrics.minTouchDp.dp)
                .combinedClickable(
                    onClickLabel = if (action.action == CommsHistoryAction.Reply) "Reply" else CommsPresentation.openLabel(message.app),
                    onLongClickLabel = "Message actions",
                    onClick = onAction,
                    onLongClick = onBodyLongPress,
                ),
            style = commsStyle(skin, CommsType.body(skin), skin.colors.textPrimary),
            maxLines = Metrics.commsMaxLines,
            overflow = TextOverflow.Ellipsis,
        )
        FlowRow(
            Modifier.fillMaxWidth().padding(top = Metrics.commsCardInnerGapDp.dp),
            horizontalArrangement = Arrangement.spacedBy(Metrics.itemGapDp.dp),
        ) {
            if (action.action == CommsHistoryAction.Reply) {
                ConsoleAction("Reply", skin) { onAction() }
            } else {
                ConsoleAction(CommsPresentation.openLabel(message.app), skin) { onAction() }
                action.status?.let { status ->
                    BasicText(status, style = commsStyle(skin, CommsType.caption(skin), dim))
                }
            }
        }
    }
}

@Composable
private fun HistoryDateHeader(label: String, skin: Skin) {
    BasicText(
        label,
        Modifier.padding(top = Metrics.lineGapDp.dp),
        style = commsStyle(skin, CommsType.caption(skin), skin.colors.textSecondary),
        maxLines = 1,
    )
}

@Composable
private fun HistorySearch(query: String, onQuery: (String) -> Unit, accessOn: Boolean,
    notice: String?, skin: Skin) {
    val dim = skin.colors.textSecondary
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Metrics.lineGapDp.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Metrics.commsActionDp.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText("> ", style = commsStyle(skin))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    BasicText("Search messages", style = commsStyle(skin, CommsType.body(skin), dim))
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Metrics.commsActionDp.dp).wrapContentHeight(),
                    singleLine = true,
                    textStyle = commsStyle(skin),
                    cursorBrush = SolidColor(Color(skin.colors.accent.toInt())),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                )
            }
            if (query.isNotEmpty()) ConsoleAction("Clear search", skin) { onQuery("") }
        }
        if (!accessOn) {
            BasicText("Notification access is off. Stored messages are still readable.",
                style = commsStyle(skin, CommsType.caption(skin), dim))
        }
        notice?.let {
            BasicText(it, style = commsStyle(skin, CommsType.caption(skin), dim))
        }
    }
}

/** The one empty reason and one action, plus the access-off history note. */
@Composable
private fun CommsEmptyBody(state: CommsEmptyState?, skin: Skin,
    modifier: Modifier = Modifier, action: (CommsEmptyAction) -> Unit) {
    if (state == null) return
    Column(modifier.verticalScroll(rememberHubScrollState()), verticalArrangement = Arrangement.Center) {
        HudCell(skin, Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.lineGapDp.dp)) {
                BasicText(state.message, style = commsStyle(skin, CommsType.body(skin), skin.colors.textPrimary))
                state.note?.let { note ->
                    BasicText(note, style = commsStyle(skin, CommsType.caption(skin), skin.colors.textSecondary))
                }
                Box(Modifier.heightIn(min = Metrics.minTouchDp.dp)
                    .clickable { action(state.action) }.padding(vertical = Metrics.lineGapDp.dp),
                    contentAlignment = Alignment.CenterStart) {
                    BasicText(state.actionLabel,
                        style = commsStyle(skin, CommsType.body(skin), skin.colors.readableAccent))
                }
            }
        }
    }
}

/** The Why sheet: package and reason only, never a title, sender or message body. */
@Composable
private fun CommsWhyDialog(rows: List<CommsWhyRow>, skin: Skin, dismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = dismiss,
        containerColor = Color(skin.colors.surface.toInt()),
        title = { BasicText("Why?", style = commsStyle(skin, CommsType.body(skin), skin.colors.textPrimary)) },
        text = {
            // T230: the dropped-notification list can be longer than the dialog; scroll it.
            Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Metrics.lineGapDp.dp)) {
                if (rows.isEmpty()) {
                    BasicText("No notifications have been dropped.",
                        style = commsStyle(skin, CommsType.caption(skin), skin.colors.textSecondary))
                } else {
                    rows.forEach { row ->
                        BasicText("${row.packageName} · ${row.reason}",
                            style = commsStyle(skin, CommsType.body(skin), skin.colors.textPrimary))
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = dismiss) {
                BasicText("Close", style = commsStyle(skin, CommsType.body(skin), skin.colors.textSecondary))
            }
        },
    )
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
        // Brackets only on terminal skins; every other skin uses plain words.
        BasicText(CommsPresentation.quickRepliesLabel(skin.style.terminalStats),
            style = commsStyle(skin, CommsType.caption(skin), skin.colors.textSecondary))
        replies.forEach { reply ->
            val shape = RoundedCornerShape(Metrics.commsChipCornerDp.dp)
            Box(Modifier.heightIn(min = Metrics.minTouchDp.dp)
                .widthIn(min = Metrics.minTouchDp.dp, max = Metrics.commsChipMaxWidthDp.dp)
                .background(Color(skin.colors.surface.toInt()).copy(alpha = skin.shape.surfaceAlpha), shape)
                .border(skin.shape.surfaceBorderDp.dp, Color((if (enabled) skin.colors.lineActive else skin.colors.lineBase).toInt()), shape)
                .clickable(enabled = enabled) { send(reply) }
                .padding(horizontal = Metrics.itemGapDp.dp, vertical = Metrics.lineGapDp.dp),
                contentAlignment = Alignment.Center) {
                BasicText(reply, style = commsStyle(skin, CommsType.body(skin),
                    if (enabled) skin.colors.readableAccent else skin.colors.lineActive),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun CommsMessageMenuDialog(
    message: CommsHistoryMessage,
    skin: Skin,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(skin.colors.surface.toInt()),
        title = { BasicText("Message", style = commsStyle(skin, CommsType.body(skin), skin.colors.textPrimary)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.lineGapDp.dp)) {
                BasicText(message.text, style = commsStyle(skin, CommsType.body(skin), skin.colors.textSecondary),
                    maxLines = Metrics.commsMaxLines, overflow = TextOverflow.Ellipsis)
                ConsoleAction("Copy", skin, action = onCopy)
                ConsoleAction("Delete", skin, action = onDelete)
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                BasicText("Close", style = commsStyle(skin, CommsType.body(skin), skin.colors.textSecondary))
            }
        },
    )
}

@Composable
private fun CommsConversationMenuDialog(
    group: CommsHistoryGroup,
    skin: Skin,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(skin.colors.surface.toInt()),
        title = { BasicText(CommsPresentation.header(group.app, group.title),
            style = commsStyle(skin, CommsType.body(skin), skin.colors.textPrimary)) },
        text = { ConsoleAction("Delete conversation", skin, action = onDelete) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                BasicText("Close", style = commsStyle(skin, CommsType.body(skin), skin.colors.textSecondary))
            }
        },
    )
}

@Composable
private fun CommsConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    skin: Skin,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(skin.colors.surface.toInt()),
        title = { BasicText(title, style = commsStyle(skin, CommsType.body(skin), skin.colors.textPrimary)) },
        text = { BasicText(body, style = commsStyle(skin, CommsType.body(skin), skin.colors.textSecondary)) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm) {
                BasicText(confirm, style = commsStyle(skin, CommsType.body(skin), skin.colors.warn))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                BasicText("Cancel", style = commsStyle(skin, CommsType.body(skin), skin.colors.textSecondary))
            }
        },
    )
}

/** T262: the card surface follows glass exactly like the hub and the search sheet. */
private fun Modifier.commsCardSurface(skin: Skin): Modifier =
    if (skin.shape.moduleStyle == ModuleStyle.frosted) {
        frostedBackground(skin, skin.shape.surfaceCornerDp, floating = false)
    } else {
        background(Color(skin.colors.surface.toInt()).copy(alpha = skin.shape.surfaceAlpha),
            RoundedCornerShape(skin.shape.surfaceCornerDp.dp))
    }

/** Cache all frame geometry and colors outside the draw loop. */
private fun Modifier.commsFrame(skin: Skin, selected: Boolean): Modifier = drawWithCache {
    val color = Color((if (selected) skin.colors.accent else skin.colors.lineActive).toInt())
    val surface = Color(skin.colors.surface.toInt()).copy(alpha = skin.shape.surfaceAlpha)
    val frosted = skin.shape.moduleStyle == ModuleStyle.frosted
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
        if (!frosted) drawRect(surface)
        if (terminal) drawPath(box, color, style = outline)
        if (!terminal) {
            if (!frosted) drawRoundRect(surface, cornerRadius = radius)
            drawRoundRect(color, cornerRadius = radius, style = outline)
        }
    }
}

internal enum class CommsSettingsSection { Summary, Apps, Replies }

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun CommsSettings(repository: CommsExtrasRepository, skin: Skin, apps: List<CommsAppEntry> = emptyList(),
    section: CommsSettingsSection = CommsSettingsSection.Summary,
    onOpenApps: () -> Unit = {}, onOpenReplies: () -> Unit = {}) {
    val enabled by repository.enabled.collectAsState()
    val allowed by repository.allowedApps.collectAsState()
    val keepHistory by repository.keepHistory.collectAsState()
    val stored by repository.storedMessages.collectAsState()
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
        BasicText("Messages and quick replies",
            style = commsStyle(skin, CommsType.title(skin), skin.colors.textPrimary))
        CommsSwitch("Messages", enabled, skin) { value -> scope.launch { repository.setEnabled(value) } }
        CommsSwitch("Keep history", keepHistory, skin) { value -> scope.launch { repository.setKeepHistory(value) } }
        BasicText(
            "Messages from WhatsApp, Telegram and Instagram are stored on this phone: text and links only, no images, and not in backups. Nothing is deleted automatically. Clear all messages below, or long-press one in History, to remove it.",
            style = commsStyle(skin, CommsType.caption(skin), skin.colors.textSecondary),
        )
        SettingsSection("Messages", skin)
        SettingsCard(skin) {
            SettingRow("Messages apps", skin, SettingKind.Page, description = "Choose which apps can show messages", onClick = onOpenApps)
            SettingRow("Quick replies", skin, SettingKind.Page, description = "Add or remove saved reply text", onClick = onOpenReplies)
        }
        SettingsSection("Stored messages", skin)
        SettingsCard(skin) {
            val bytes = remember(stored) { CommsExtrasPresentation.storageBytes(stored) }
            SettingRow(
                "Clear all messages", skin, SettingKind.Action,
                description = CommsExtrasPresentation.storageLine(stored.size, bytes),
                enabled = stored.isNotEmpty(), destructive = stored.isNotEmpty(), icon = "−",
                onClick = { scope.launch { repository.clearHistory() } },
            )
        }
        }
        if (section == CommsSettingsSection.Apps) {
        val choices = remember(apps, allowed) { CommsAppPicker.choices(apps, allowed) }
        CommsAppGroup.entries.forEach { group ->
            val groupChoices = choices.filter { it.group == group }
            if (groupChoices.isNotEmpty()) {
                SettingsSection(group.label, skin)
                groupChoices.forEach { choice ->
                    CommsSwitch(choice.label, choice.packageName in allowed, skin) { on ->
                        scope.launch {
                            val current = repository.allowedApps.value
                            repository.setAllowedApps(if (on) current + choice.packageName else current - choice.packageName)
                        }
                        if (!on) undo.offer {
                            scope.launch { repository.setAllowedApps(repository.allowedApps.value + choice.packageName) }
                        }
                    }
                }
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
            BasicText(EditorSpec.QuickReplies.empty,
                style = commsStyle(skin, CommsType.caption(skin), skin.colors.textSecondary))
        }
        Row(Modifier.fillMaxWidth().heightIn(min = Metrics.commsActionDp.dp).bringIntoViewRequester(editorView),
            verticalAlignment = Alignment.CenterVertically) {
            BasicText("> ", style = commsStyle(skin))
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f).heightIn(min = Metrics.commsActionDp.dp).wrapContentHeight(),
                enabled = canned.size < CommsCannedReplies.Max,
                singleLine = true,
                textStyle = commsStyle(skin),
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
        BasicText("${canned.size}/${CommsCannedReplies.Max}",
            style = commsStyle(skin, CommsType.caption(skin), skin.colors.textSecondary))
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

/**
 * T233: a demo page with made-up conversations. It uses the real page layout, but its own
 * in-memory repository, so no notification, contact or stored row is read while demo mode is on.
 */
@Composable
internal fun DemoCommsPage(skin: Skin) {
    val repository = remember { DemoCommsRepository() }
    CommsPage(repository, skin, reviewAccess = true)
}

private class DemoCommsRepository : CommsExtrasRepository {
    private val now = System.currentTimeMillis()
    override val conversations: StateFlow<List<CommsConversation>> = MutableStateFlow(listOf(
        conversation(
            app = CommsPackages.WhatsApp,
            title = "Sam",
            messages = listOf(
                CommsMessage("demo-w1", "Sam", "Are we still walking by the river at six?", now - 9 * 60_000L,
                    canReply = true, canMarkRead = true),
                CommsMessage("demo-w2", "Sam", "The forecast looks dry now.", now - 6 * 60_000L,
                    canReply = true, canMarkRead = true),
                CommsMessage("demo-w3", "Sam", "Meet by the old bridge instead — https://example.com/walk", now - 2 * 60_000L,
                    canReply = true, canMarkRead = true),
            ),
            canReply = true,
        ),
        conversation(
            app = CommsPackages.Telegram,
            title = "Work group",
            messages = listOf(
                CommsMessage("demo-t1", "Mira", "The notes from this morning are in the shared folder. I will add the two missing pages after lunch, so nothing needs to be rushed before then.", now - 31 * 60_000L),
            ),
            canReply = false,
        ),
        conversation(
            app = CommsPackages.Instagram,
            title = "Alex",
            messages = listOf(
                CommsMessage("demo-i1", "Alex", "That photo from the market came out well.", now - 70 * 60_000L),
                CommsMessage("demo-i2", "Alex", "Send it when you have a moment.", now - 55 * 60_000L),
            ),
            canReply = true,
        ),
    ))
    override val allowedApps: StateFlow<Set<String>> = MutableStateFlow(CommsPackages.Default)
    override val enabled: StateFlow<Boolean> = MutableStateFlow(true)
    override val cannedReplies: StateFlow<List<String>> = MutableStateFlow(CommsCannedReplies.Default)
    override val keepHistory: StateFlow<Boolean> = MutableStateFlow(true)
    override val storedMessages: StateFlow<List<CommsMessageEntity>> = MutableStateFlow(listOf(
        stored(1, CommsPackages.WhatsApp, "Sam", "Sam", "A made-up stored message from today.", now - 3 * 60 * 60_000L),
        stored(2, CommsPackages.Telegram, "Work group", "Mira", "A made-up stored message from yesterday.", now - 26 * 60 * 60_000L),
    ))
    override val history: StateFlow<List<CommsHistoryEntry>> = MutableStateFlow(emptyList())

    override suspend fun setEnabled(enabled: Boolean) = Unit
    override suspend fun setAllowedApps(packages: Set<String>) = Unit
    override suspend fun setCannedReplies(replies: List<String>) = Unit
    override suspend fun setKeepHistory(enabled: Boolean) = Unit
    override suspend fun clearHistory() = Unit
    override suspend fun deleteHistoryMessage(id: Long) = Unit
    override suspend fun deleteHistoryConversation(app: String, conversationKey: String) = Unit
    override suspend fun reply(conversation: CommsConversation, text: String): ReplyResult =
        if (conversation.canReply) ReplyResult.Sent else ReplyResult.NoReplyAction
    override suspend fun markAsRead(conversation: CommsConversation): MarkReadResult = MarkReadResult.Marked
    override suspend fun dismiss(message: CommsMessage) = Unit
    override suspend fun dismiss(conversation: CommsConversation) = Unit

    private fun conversation(app: String, title: String, messages: List<CommsMessage>, canReply: Boolean) =
        CommsConversation(
            id = CommsConversationId(app, title),
            title = title,
            messages = messages,
            canReply = canReply,
            updatedAtMs = messages.maxOf { it.timeMs },
            canMarkRead = messages.any { it.canMarkRead },
        )

    private fun stored(id: Long, app: String, key: String, sender: String, text: String, time: Long) =
        CommsMessageEntity(
            id = id,
            app = app,
            conversationKey = key,
            conversationTitle = key,
            sender = sender,
            notificationTitle = key,
            text = text,
            timeMs = time,
            recordedAtMs = time,
            notificationKey = "",
            identity = "demo-$id",
            live = false,
            removedAtMs = time,
        )
}
