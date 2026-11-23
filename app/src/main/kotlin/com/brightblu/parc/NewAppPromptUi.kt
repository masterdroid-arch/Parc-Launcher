package com.brightblu.parc

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.textFamily

/**
 * T246: the quiet one-line "Add <app> to an arc?" prompt. It is a single tappable row on the same
 * surface as the Undo chip; no bright fill, no second action. Tapping it opens the shared T312
 * "Add to arc" chooser.
 */
@Composable
internal fun NewAppPromptBar(label: String, skin: Skin, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(bottom = Metrics.snackbarBottomDp.dp), contentAlignment = Alignment.Center) {
        BasicText(
            NewAppPrompt.question(label),
            modifier = Modifier
                .background(
                    skin.floatingSurface(),
                    RoundedCornerShape(Metrics.snackbarCornerDp.dp),
                )
                .clickable(role = Role.Button, onClick = onAdd)
                .padding(horizontal = Metrics.cardInsetDp.dp, vertical = Metrics.rowGapDp.dp),
            style = TextStyle(
                fontFamily = skin.textFamily,
                color = Color(skin.colors.textPrimary.toInt()),
                fontSize = skin.typography.menu.sizeSp.sp,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
