package com.brightblu.parc

import com.brightblu.parc.model.textFamily
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin

/**
 * T314: the number of editable text fields on the whole screen. The pull bar is a non-interactive
 * preview (a plain label with no SetText semantic action), so it never contributes; the search
 * sheet owns the only editable field, shown while search is open and the alphabetical All apps
 * list is not. Kept as a pure function because the repo has no Compose UI test harness (adding one
 * would need a library outside PROJECT.md §6.1); the unit test walks the same states a screen test
 * would.
 */
internal fun searchEditableFieldCount(searchOpen: Boolean, abcListOpen: Boolean): Int =
    if (searchOpen && !abcListOpen) 1 else 0

/**
 * T314: the effective preview progress. The pull bar is drawn only during the pull and its
 * cancelled return; once search opens it disappears and the sheet owns the field.
 */
internal fun pullPreviewProgress(searchOpen: Boolean, progress: Float): Float =
    if (searchOpen) 0f else progress

/** Progress is read only by the layer; dragging/return animation does not recompose this bar.
 * T314: the bar is a preview only. It is never an editable field; the search sheet owns the single
 * text field and its focus.
 */
@Composable
internal fun PullSearchBar(skin: Skin, progress: () -> Float) {
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
        BasicText("Search", Modifier.height(Metrics.searchPullBarHeightDp.dp).wrapContentHeight(),
            style = style.copy(color = Color(skin.colors.textSecondary.toInt())))
    }
}
