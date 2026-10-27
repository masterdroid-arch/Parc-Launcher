package com.brightblu.parc

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin

/**
 * Folder name entry (T30 rename, T68 create) as a centred sheet rather than the anchored popup: the
 * field needs the IME, and the popup anchors near the ring where the keyboard would cover it. Every
 * colour and size comes from [Skin] and [Metrics]; the sheet sits above the keyboard via [imePadding].
 */
@Composable
internal fun FolderNameSheet(
    title: String,
    draftName: String,
    nameIssue: String,
    skin: Skin,
    onNameChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(Metrics.menuMinWidthDp.dp)
                .sheetSurface(skin)
                .padding(Metrics.hintCardPaddingDp.dp),
        ) {
            BasicText(
                title,
                style = TextStyle(
                    color = Color(skin.colors.textSecondary.toInt()),
                    fontSize = skin.typography.caption.sizeSp.sp,
                    fontWeight = FontWeight(skin.typography.caption.weight),
                    letterSpacing = skin.typography.caption.trackingSp.sp,
                ),
            )
            BasicTextField(
                value = draftName,
                onValueChange = onNameChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = Color(skin.colors.textPrimary.toInt()),
                    fontSize = skin.typography.menu.sizeSp.sp,
                    fontWeight = FontWeight(skin.typography.menu.weight),
                    letterSpacing = skin.typography.menu.trackingSp.sp,
                ),
                cursorBrush = SolidColor(Color(skin.colors.accent.toInt())),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Metrics.menuRowHeightDp.dp)
                    .focusRequester(focus),
            )
            if (nameIssue.isNotEmpty()) {
                BasicText(
                    nameIssue,
                    style = TextStyle(
                        color = Color(skin.colors.danger.toInt()),
                        fontSize = skin.typography.caption.sizeSp.sp,
                        fontWeight = FontWeight(skin.typography.caption.weight),
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = Metrics.gridDp.dp * 2),
                horizontalArrangement = Arrangement.End,
            ) {
                SheetButton("Cancel", skin.colors.textSecondary, skin, onCancel)
                SheetButton("Save", skin.colors.accent, skin, onSave)
            }
        }
    }
}

@Composable
private fun SheetButton(label: String, tint: Long, skin: Skin, onClick: () -> Unit) {
    Box(
        Modifier
            .height(Metrics.menuRowHeightDp.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = Metrics.gridDp.dp * 3),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            label,
            style = TextStyle(
                color = Color(tint.toInt()),
                fontSize = skin.typography.menu.sizeSp.sp,
                fontWeight = FontWeight(skin.typography.menu.weight),
                letterSpacing = skin.typography.menu.trackingSp.sp,
            ),
        )
    }
}

private fun Modifier.sheetSurface(skin: Skin): Modifier {
    val corner = RoundedCornerShape(Metrics.menuCornerDp.dp)
    return this
        .background(Color(skin.colors.surface.toInt()).copy(alpha = skin.shape.surfaceAlpha), corner)
        .border(skin.shape.surfaceBorderDp.dp, Color(skin.colors.lineBase.toInt()), corner)
}
