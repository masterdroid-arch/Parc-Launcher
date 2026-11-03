package com.brightblu.parc.feature.search

import com.brightblu.parc.model.textFamily
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.ParcPage
import com.brightblu.parc.model.Skin
import kotlinx.coroutines.launch

/** Height of one letter header; a count-of-grid token, not a literal size. */
private const val HeaderGrid = 8f

/**
 * The "All apps" alphabetical list with the fast-scroll letter index on the **left** edge
 * (DESIGN §5, S8: the right edge belongs to the system Back gesture strip). Full-screen; the host owns
 * Back. Labels only: slot icons are the renderer's bitmaps, not this list's business.
 */
@Composable
fun AbcListScreen(
    index: AbcIndex,
    skin: Skin,
    onLaunch: (AppId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // T166: a page like every other: the gutter, the status bar and the gesture strip are ParcPage's. The letter lane
    // is a column of its own at the left; the list starts after it, so a letter never sits beside a row's first word.
    ParcPage(modifier.background(Color(skin.colors.bg.toInt())).searchSkinDecoration(skin), scroll = false) {
        Row(Modifier.fillMaxSize()) {
            LetterIndex(
                keys = index.keys,
                skin = skin,
                modifier = Modifier.align(Alignment.CenterVertically),
            ) { sectionIndex ->
                scope.launch { listState.animateScrollToItem(index.flatOffsetOf(sectionIndex)) }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxHeight().padding(start = Metrics.itemGapDp.dp),
            ) {
                index.sections.forEach { section ->
                    item(key = "header-${section.key}") { SectionHeader(section.key, skin) }
                    items(section.apps, key = { it.id.value }) { entry ->
                        AppRow(entry, skin) { onLaunch(entry.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(key: Char, skin: Skin) {
    Box(
        Modifier
            .fillMaxWidth()
            .height((Metrics.gridDp * HeaderGrid).dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        BasicText(
            key.toString(),
            style = TextStyle(
                fontFamily = if (skin.style.annotationFamily == "monospace") androidx.compose.ui.text.font.FontFamily.Monospace else skin.textFamily,
                color = Color(skin.colors.textSecondary.toInt()),
                fontSize = skin.typography.caption.sizeSp.sp,
                fontWeight = FontWeight(skin.typography.caption.weight),
                letterSpacing = skin.typography.caption.trackingSp.sp,
            ),
        )
    }
}

@Composable
private fun AppRow(entry: AppEntry, skin: Skin, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .height(Metrics.menuRowHeightDp.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicText(
            entry.label,
            style = TextStyle(
                fontFamily = if (skin.style.annotationFamily == "monospace") androidx.compose.ui.text.font.FontFamily.Monospace else skin.textFamily,
                color = Color(skin.colors.textPrimary.toInt()),
                fontSize = skin.typography.menu.sizeSp.sp,
                fontWeight = FontWeight(skin.typography.menu.weight),
                letterSpacing = skin.typography.menu.trackingSp.sp,
            ),
        )
    }
}

@Composable
private fun LetterIndex(
    keys: List<Char>,
    skin: Skin,
    modifier: Modifier = Modifier,
    onLetter: (Int) -> Unit,
) {
    Column(
        modifier,
        verticalArrangement = Arrangement.Center,
    ) {
        keys.forEachIndexed { position, key ->
            Box(
                Modifier
                    .size(Metrics.gridDp.dp * LetterGrid)
                    .clickable { onLetter(position) },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    key.toString(),
                    style = TextStyle(
                fontFamily = if (skin.style.annotationFamily == "monospace") androidx.compose.ui.text.font.FontFamily.Monospace else skin.textFamily,
                        color = Color(skin.colors.textSecondary.toInt()),
                        fontSize = skin.typography.caption.sizeSp.sp,
                    ),
                )
            }
        }
    }
}

/** Width and height of one index letter: a lane of one gutter, beside the list. */
private const val LetterGrid = 5f
