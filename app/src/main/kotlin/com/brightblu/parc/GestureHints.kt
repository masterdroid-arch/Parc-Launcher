package com.brightblu.parc

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.textFamily
import kotlinx.coroutines.delay

/**
 * T260: the five gestures nothing on screen shows. Each has one short hint, shown once, the first time
 * it matters. The order is the order they are offered in; at most one hint appears per start of Parc, so
 * they trickle in over the first days instead of piling up.
 */
internal enum class GestureHint(val id: String, val glyph: String, val text: String) {
    TurnArc("turn_arc", "↻", "Drag along an arc to turn it and bring more apps into reach."),
    OpenSearch("search", "⌕", "Pull down on Home to search apps, notes and files."),
    OpenHub("hub", "◉", "Tap the hub to open your pages. Drag from it to aim at an app."),
    EditMode("edit", "✎", "Hold the hub, then drag apps to rearrange. Drag an empty arc to turn it."),
    PageName("page_name", "☰", "Hold a page name for quick options: hide, size, move."),
}

/** Pure choice of what to show next; the page-name hint belongs to the hub and is chosen there. */
internal object GestureHintRules {
    val homeOrder: List<GestureHint> = listOf(GestureHint.TurnArc, GestureHint.OpenSearch, GestureHint.OpenHub, GestureHint.EditMode)

    fun nextOnHome(seen: Set<String>): GestureHint? = homeOrder.firstOrNull { it.id !in seen }

    fun pageNameDue(seen: Set<String>, visiblePages: Int): Boolean =
        GestureHint.PageName.id !in seen && visiblePages >= 2

    /** "Skip hints" marks every hint seen. */
    fun allIds(): Set<String> = GestureHint.entries.map { it.id }.toSet()

    const val AutoHideMs = 9_000L
}

/**
 * A quiet one-line pill with a drawn symbol: it never covers the arcs and never blocks a touch outside
 * itself. "Got it" closes this hint; "Skip" closes every remaining hint. It closes itself after a few
 * seconds, and a hint that has been on screen counts as seen.
 */
@Composable
internal fun GestureHintChip(hint: GestureHint, skin: Skin, modifier: Modifier = Modifier,
    onGotIt: () -> Unit, onSkipAll: () -> Unit) {
    LaunchedEffect(hint) { delay(GestureHintRules.AutoHideMs); onGotIt() }
    // T391: never float over an open sheet; the hint timer above keeps running, so it is simply gone when the sheet closes.
    if (SheetHost.content != null) return
    // T411: tell the queue a tip is on screen, so the new-app bar and other tips wait.
    DisposableEffect(hint) {
        TipSession.latch.setShowing(if (hint == GestureHint.PageName) TipKind.PageName else TipKind.HomeGesture, true)
        onDispose { TipSession.latch.setShowing(if (hint == GestureHint.PageName) TipKind.PageName else TipKind.HomeGesture, false) }
    }
    val shape = RoundedCornerShape(Metrics.hintCornerDp.dp)
    Row(modifier.fillMaxWidth().padding(horizontal = Metrics.cardInsetDp.dp)
        .background(skin.floatingSurface(), shape)
        .border(skin.shape.surfaceBorderDp.dp, Color(skin.colors.lineBase.toInt()), shape)
        .padding(start = Metrics.cardInsetDp.dp)
        .semantics { contentDescription = "Hint. ${hint.text}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.itemGapDp.dp)) {
        BasicText(hint.glyph, style = TextStyle(fontFamily = skin.textFamily,
            color = Color(skin.colors.accent.toInt()), fontSize = skin.typography.title.sizeSp.sp))
        BasicText(hint.text, Modifier.weight(1f).padding(vertical = Metrics.rowGapDp.dp), maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = skin.textFamily, color = Color(skin.colors.textPrimary.toInt()),
                fontSize = skin.typography.menu.sizeSp.sp))
        HintAction("Got it", skin, true, onGotIt)
        HintAction("Skip", skin, false, onSkipAll)
    }
}

@Composable
private fun HintAction(label: String, skin: Skin, strong: Boolean, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = Metrics.minTouchDp.dp).widthIn(min = Metrics.minTouchDp.dp)
        .clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = Metrics.lineGapDp.dp), contentAlignment = Alignment.Center) {
        BasicText(label, style = TextStyle(fontFamily = skin.textFamily,
            color = Color((if (strong) skin.colors.accent else skin.colors.textSecondary).toInt()),
            fontSize = skin.typography.menu.sizeSp.sp))
    }
}
