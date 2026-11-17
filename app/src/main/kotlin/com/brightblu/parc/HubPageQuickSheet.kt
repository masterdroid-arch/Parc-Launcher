package com.brightblu.parc

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.brightblu.parc.feature.settings.ui.*
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.ParcBlocks
import com.brightblu.parc.model.ParcDialogArea
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.Springs
import com.brightblu.parc.model.parcBottomInset
import com.brightblu.parc.model.parcRowPadding
import com.brightblu.parc.model.readableAccent

/**
 * T242: hold a page name to get this small sheet: Hide, Size, Move left / right and Page settings. It is
 * the page-options sheet's own rows in a smaller frame, so every change is the same change.
 */
@Composable
internal fun HubPageQuickSheet(page: String, skin: Skin, config: HubConfig,
    update: ((HubConfig) -> HubConfig) -> Unit, onDismiss: () -> Unit, onPageSettings: () -> Unit,
) {
    val visible = config.pages
    val index = visible.indexOf(page)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(skin.colors.surface.toInt()),
        title = { BasicText(hubPageName(page), style = settingsText(skin)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SettingsCard(skin) {
                    SettingRow("Hide page", skin, SettingKind.Action, icon = "−",
                        description = "It stays in Edit pages, where it can be shown again.",
                        descriptionMaxLines = Int.MAX_VALUE) {
                        update { it.showPage(page, false) }; onDismiss()
                    }
                    HubPageSizeChoices(page, skin, config, update)
                    HubOptionMovePair(skin, "Show ${hubPageName(page)} before the previous visible page",
                        "Show ${hubPageName(page)} after the next visible page",
                        canEarlier = index > 0, canLater = index in 0 until visible.lastIndex,
                        onEarlier = { update { it.moveVisiblePage(page, -1) } },
                        onLater = { update { it.moveVisiblePage(page, 1) } }, labels = "Move left" to "Move right")
                    SettingRow("Page settings", skin, SettingKind.Page, divider = false) { onPageSettings() }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) {
            BasicText("Done", style = settingsText(skin, skin.colors.accent)) } },
    )
}

