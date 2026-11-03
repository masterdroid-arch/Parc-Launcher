package com.brightblu.parc

import com.brightblu.parc.model.textFamily
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin

/** Progress is read only by the layer; dragging/return animation does not recompose this bar.
 * The committed field edits the same query as the existing search sheet.
 */
@Composable
internal fun PullSearchBar(skin: Skin, progress: () -> Float, committed: Boolean,
    query: String, onQueryChange: (String) -> Unit) {
    val style = TextStyle(fontFamily = skin.textFamily, color = Color(skin.colors.textPrimary.toInt()),
        fontSize = skin.typography.search.sizeSp.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight(skin.typography.search.weight),
        letterSpacing = skin.typography.search.trackingSp.sp)
    Box(Modifier.fillMaxWidth().height(Metrics.searchPullBarHeightDp.dp)
        .graphicsLayer {
            val p = progress()
            translationY = -(1f - p) * (Metrics.keepOutTopDp + Metrics.searchPullBarHeightDp).dp.toPx() +
                Metrics.keepOutTopDp.dp.toPx()
            alpha = if (p > 0f) 1f else 0f
        }
        .background(Color(skin.colors.surface.toInt()))
        .padding(horizontal = Metrics.textGutterDp.dp)) {
        if (committed) BasicTextField(query, onQueryChange,
            Modifier.fillMaxWidth().height(Metrics.searchPullBarHeightDp.dp).wrapContentHeight(),
            textStyle = style, singleLine = true,
            cursorBrush = SolidColor(Color(skin.colors.accent.toInt())),
            decorationBox = { field ->
                if (query.isEmpty()) BasicText("Search", style = style.copy(color = Color(skin.colors.textSecondary.toInt())))
                field()
            })
        else BasicText("Search", Modifier.height(Metrics.searchPullBarHeightDp.dp).wrapContentHeight(),
            style = style.copy(color = Color(skin.colors.textSecondary.toInt())))
    }
}
