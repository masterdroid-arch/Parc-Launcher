package com.brightblu.parc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.brightblu.parc.core.data.ParcPrefs
import com.brightblu.parc.core.data.comms.*
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow

/** Debug-only deterministic console review or real listener review (`live=true`). */
class CommsReviewActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val live = intent.getBooleanExtra("live", false)
        val repository = if (live) CommsRepositories.create(this, ParcPrefs.create(this), scope) else ReviewComms()
        setContent {
            val skin = if (intent.getStringExtra("skin") == "phosphor") com.brightblu.parc.model.Phosphor.Skin else Skin.Midnight
            BoxWithConstraints(Modifier.fillMaxSize().background(Color(skin.colors.bg.toInt()))) {
                val density = androidx.compose.ui.platform.LocalDensity.current
                val geometry = remember(maxWidth, maxHeight) { com.brightblu.parc.ring.RingLayout(maxWidth.value, maxHeight.value) }
                val renderer = remember { com.brightblu.parc.ring.CanvasRingRenderer() }
                val scene = remember(geometry) { com.brightblu.parc.ring.RingScenes.home(geometry, emptyList(), "09:41") }
                renderer.Field(scene, skin, object : com.brightblu.parc.model.RingCallbacks {
                    override fun onTap(hit: com.brightblu.parc.model.SlotHit) = Unit
                    override fun onLongPress(hit: com.brightblu.parc.model.SlotHit) = Unit
                    override fun onDrag(hit: com.brightblu.parc.model.SlotHit, phase: com.brightblu.parc.model.DragPhase) = Unit
                })
                val top = Metrics.keepOutTopDp + skin.typography.clock.sizeSp + Metrics.gridDp * 4
                val bottom = com.brightblu.parc.ring.RingHudBounds.bottomDp(scene,
                    (skin.typography.label.sizeSp + Metrics.labelPaddingVerticalDp * 2) * density.fontScale)
                HubPages(skin, Modifier.padding(start = Metrics.textGutterDp.dp, end = Metrics.textGutterDp.dp, top = top.dp)
                    .height(com.brightblu.parc.ring.RingHudBounds.heightDp(top, bottom).dp),
                    comms = { CommsPage(repository, skin, reviewAccess = if (live) null else true) })
            }
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

internal class ReviewComms : CommsExtrasRepository {
    override val enabled = MutableStateFlow(true)
    override val allowedApps = MutableStateFlow(CommsPackages.Default)
    override val conversations = MutableStateFlow(listOf(
        conversation(CommsPackages.Telegram, "Sam", listOf(
            CommsMessage("fixture1", "sam", "Meet at 14:30? https://example.com/hello", 1_760_709_720_000L,
                links = listOf(CommsLink(15, 40)), canReply = true),
            CommsMessage("fixture2", "sam", "A small dithered image", 1_760_709_780_000L,
                image = CommsImage.fromWords(16, 16, List(4) { 0x55AA55AA55AA55AAL }), canReply = true),
        )),
        conversation(CommsPackages.Signal, "Robin", listOf(CommsMessage("fixture3", "robin",
            "A long message wraps to the screen width. ".repeat(20), 1_760_709_840_000L))),
    ))
    // T77 extras: the same defaults the production repository starts from, plus one fixture
    // entry already in the history so the toggle shows the dimmed view without a real dismissal.
    override val cannedReplies = MutableStateFlow(CommsCannedReplies.Default)
    override val keepHistory = MutableStateFlow(true)
    override val storedMessages = MutableStateFlow(listOf(
        CommsMessageEntity(
            id = 1,
            app = CommsPackages.WhatsApp,
            conversationKey = "Alex",
            conversationTitle = "Alex",
            sender = "alex",
            notificationTitle = "Alex",
            text = "Stored fixture line",
            timeMs = 1_760_709_000_000L,
            recordedAtMs = 1_760_709_100_000L,
            notificationKey = "fixture4",
            identity = "fixture4",
            live = false,
        ),
    ))
    override val history = MutableStateFlow(listOf(
        CommsHistoryEntry(CommsConversationId(CommsPackages.WhatsApp, "Alex"), "Alex",
            CommsMessage("fixture4", "alex", "Stored fixture line", 1_760_709_000_000L), 1_760_709_100_000L),
    ))
    override suspend fun setEnabled(enabled: Boolean) { this.enabled.value = enabled }
    override suspend fun setAllowedApps(packages: Set<String>) { allowedApps.value = packages }
    override suspend fun setCannedReplies(replies: List<String>) {
        cannedReplies.value = CommsCannedReplies.sanitize(replies)
    }
    override suspend fun setKeepHistory(enabled: Boolean) {
        // T214: recording off pauses writes; it does not clear what is already stored.
        keepHistory.value = enabled
    }
    override suspend fun clearHistory() { history.value = emptyList(); storedMessages.value = emptyList() }
    override suspend fun deleteHistoryMessage(id: Long) {
        storedMessages.value = storedMessages.value.filterNot { it.id == id }
        history.value = history.value.filterNot { it.message.notificationKey == "fixture4" }
    }
    override suspend fun deleteHistoryConversation(app: String, conversationKey: String) {
        storedMessages.value = storedMessages.value.filterNot { it.app == app && it.conversationKey == conversationKey }
        history.value = history.value.filterNot { it.conversation.app == app && it.conversation.key == conversationKey }
    }
    override suspend fun markAsRead(conversation: CommsConversation): MarkReadResult {
        conversations.value = conversations.value.filterNot { it.id == conversation.id }
        return if (conversation.canMarkRead) MarkReadResult.Marked else MarkReadResult.Dismissed
    }
    override suspend fun reply(conversation: CommsConversation, text: String) = if (conversation.canReply) ReplyResult.Sent else ReplyResult.NoReplyAction
    override suspend fun dismiss(message: CommsMessage) {
        conversations.value = conversations.value.map { it.copy(messages = it.messages.filterNot { m -> m.notificationKey == message.notificationKey }) }.filter { it.messages.isNotEmpty() }
    }
    override suspend fun dismiss(conversation: CommsConversation) { conversations.value = conversations.value.filterNot { it.id == conversation.id } }
    private fun conversation(app: String, title: String, messages: List<CommsMessage>) =
        CommsConversation(CommsConversationId(app, title), title, messages, messages.any { it.canReply }, messages.last().timeMs)
}
