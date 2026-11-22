package com.brightblu.parc.reader

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brightblu.parc.R
import com.brightblu.parc.hudValueStyle
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin

internal fun readerChromeIsLight(skin: Skin) =
    Color(skin.colors.surface.toInt()).luminance() > Color(skin.colors.textPrimary.toInt()).luminance()

internal enum class ReaderChrome {
    Hidden, Panel, Toolbar;

    fun toggled() = if (this == Hidden) Panel else Hidden
}

@Composable
internal fun BoxScope.ReaderControlsToggle(visible: Boolean, toggle: () -> Unit) {
    val label = if (visible) "Hide reading controls" else "Show reading controls"
    // Provide a focus target in the tap zone without intercepting touch gestures or book selection.
    Box(Modifier.align(Alignment.Center).size(Metrics.readerTouchTargetDp.dp).semantics {
        contentDescription = label
        role = Role.Button
        onClick { toggle(); true }
    })
}

/**
 * T350: the page number stays in the corner while the bars are hidden. It is a caption on an
 * opaque chip, with a full touch target, and its tap opens the same "Go to page" sheet as the
 * number in the bottom panel.
 */
@Composable
internal fun BoxScope.ReaderPageBadge(label: String, skin: Skin, onClick: () -> Unit) {
    val surface = Color(skin.colors.surface.toInt()).copy(alpha = Metrics.readerBarScrimAlpha)
    Box(Modifier.align(Alignment.BottomEnd).navigationBarsPadding()
        .padding(end = Metrics.readerPageBadgeInsetDp.dp, bottom = Metrics.readerPageBadgeInsetDp.dp)
        .heightIn(min = Metrics.readerTouchTargetDp.dp)
        .widthIn(min = Metrics.readerTouchTargetDp.dp)
        .clickable(role = Role.Button) { onClick() }
        .semantics {
            contentDescription = "$label. Go to page."
            role = Role.Button
        },
        contentAlignment = Alignment.Center) {
        Box(Modifier.background(surface, RoundedCornerShape(Metrics.readerPageBadgeCornerDp.dp))
            .border(Metrics.readerHairlineDp.dp, Color(skin.colors.lineBase.toInt()),
                RoundedCornerShape(Metrics.readerPageBadgeCornerDp.dp))
            .padding(horizontal = Metrics.readerPageBadgeHorizontalPaddingDp.dp,
                vertical = Metrics.readerPageBadgeVerticalPaddingDp.dp)) {
            BasicText(label, maxLines = 1, style = readerCaptionStyle(skin),
                overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun ReaderControls(
    title: String,
    skin: Skin,
    bookmarked: Boolean,
    onBookmark: () -> Unit,
    menu: @Composable (dismiss: () -> Unit) -> Unit,
    showPanel: Boolean,
    footer: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val surface = Color(skin.colors.surface.toInt()).copy(alpha = 1f)
    // Only the two panels intercept taps. The page keeps its full viewport and gestures.
    val panel = Modifier.fillMaxWidth().background(surface)
        .pointerInput(Unit) { detectTapGestures { } }
    Box(Modifier.fillMaxSize()) {
        Row(panel.align(Alignment.TopCenter).statusBarsPadding()
            .padding(horizontal = Metrics.readerBarSideInsetDp.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.size(Metrics.readerTouchTargetDp.dp))
            BasicText(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = hudValueStyle(skin, role = skin.typography.section))
            ReaderIcon(if (bookmarked) "Remove bookmark" else "Add bookmark",
                if (bookmarked) R.drawable.reader_bookmarked else R.drawable.reader_bookmark, skin, onBookmark)
            Box {
                ReaderIcon("More reading options", R.drawable.reader_more, skin) { expanded = true }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, containerColor = surface) {
                    menu { expanded = false }
                }
            }
        }
        if (showPanel) Column(panel.align(Alignment.BottomCenter).navigationBarsPadding()
            .padding(horizontal = Metrics.readerBarSideInsetDp.dp, vertical = Metrics.readerBarTopInsetDp.dp)) {
            footer()
        }
    }
}

/** Above the sheet scrim so the visible arrow follows the same Back order as Android. */
@Composable
internal fun BoxScope.ReaderBackButton(skin: Skin) {
    val dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
    Box(Modifier.align(Alignment.TopStart).statusBarsPadding()
        .padding(start = Metrics.readerBarSideInsetDp.dp)) {
        ReaderIcon("Back", R.drawable.reader_back, skin) { dispatcher.onBackPressed() }
    }
}

@Composable
private fun ReaderIcon(label: String, drawable: Int, skin: Skin, action: () -> Unit) {
    Box(Modifier.size(Metrics.readerTouchTargetDp.dp).clickable(role = Role.Button, onClick = action),
        contentAlignment = Alignment.Center) {
        Icon(painterResource(drawable), label, Modifier.size(Metrics.settingsIconDp.dp),
            tint = Color(skin.colors.textPrimary.toInt()))
    }
}

@Composable
internal fun ReaderMenuItem(label: String, skin: Skin, dismiss: () -> Unit, action: () -> Unit, enabled: Boolean = true) {
    DropdownMenuItem(text = { BasicText(label, style = hudValueStyle(skin,
        color = if (enabled) skin.colors.textPrimary else skin.colors.textSecondary, role = skin.typography.body)) },
        enabled = enabled, modifier = Modifier.heightIn(min = Metrics.readerTouchTargetDp.dp).semantics { role = Role.Button },
        onClick = { dismiss(); action() })
}
