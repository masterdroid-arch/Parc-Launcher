package com.brightblu.parc.model

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/*
 * T166: the four building blocks every screen is made from, so no text or control touches an edge.
 *   ParcPage            gutter, system insets, cutout and gesture-bar safe, one scrolling body
 *   ParcCardContent     the inset inside a card
 *   ParcSettingsRow     one row: 56 dp tall at least, 20 dp left and right, 12 dp above and below
 *   ParcSectionHeader   24 dp above, 8 dp below
 * Sizes come from Metrics and ParcSpacing (pure, unit tested); nothing here has a dp literal.
 */

/** The live page insets: status bar and cutout on top, navigation bar below, side cutouts at the sides. */
@Composable
fun rememberPageInsets(): ParcSpacing.PageInsets {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val top = WindowInsets.statusBars.union(WindowInsets.displayCutout)
    val scale = density.density
    return ParcSpacing.page(
        statusTopDp = top.getTop(density) / scale,
        navBottomDp = WindowInsets.navigationBars.getBottom(density) / scale,
        startInsetDp = WindowInsets.displayCutout.getLeft(density, direction) / scale,
        endInsetDp = WindowInsets.displayCutout.getRight(density, direction) / scale,
    )
}

/**
 * A full-screen page. The body has the screen gutter on both sides and clears the gesture strip at the
 * bottom.
 *  - [header] stays above the body (a title, a search field).
 *  - [safeTop] keeps the page below the status bar and any cutout; turn it off when a frame above already
 *    did that, like the Settings Back row.
 *  - [scroll] and [scrollState] make the body scroll, and let the host scroll it too.
 *  - [padBottom] = false is for a page whose own scroller fills the body: that scroller pads its content with
 *    [parcBottomInset], so its list runs to the bottom of the screen and ends clear of the gesture strip.
 *  - [blockGapDp] spaces the body's blocks (a stack of cards uses [Metrics.rowGapDp]).
 *  - [overlay] is drawn over the page area, for an undo bar or a notice, and sits above the gesture strip
 *    when it is aligned to the bottom.
 */
@Composable
fun ParcPage(
    modifier: Modifier = Modifier,
    safeTop: Boolean = true,
    scroll: Boolean = true,
    scrollState: ScrollState = rememberScrollState(),
    blockGapDp: Float = 0f,
    padBottom: Boolean = true,
    header: (@Composable ColumnScope.() -> Unit)? = null,
    overlay: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val insets = rememberPageInsets()
    Box(modifier.fillMaxSize()
        .padding(start = insets.startDp.dp, end = insets.endDp.dp)
        .padding(top = if (safeTop) insets.topDp.dp else 0.dp)) {
        Column(Modifier.fillMaxSize()) {
            header?.invoke(this)
            // A scrolling body pads its own end, so it scrolls clear of the gesture strip. A body that does not scroll either
            // pads its bottom (a fixed layout) or leaves it to the scroller inside it ([parcBottomInset], padBottom = false).
            val bodyBottom = if (scroll || padBottom) insets.bottomDp else 0f
            CompositionLocalProvider(LocalParcBottomInset provides (insets.bottomDp - bodyBottom)) {
                ParcBlocks(blockGapDp, Modifier.weight(1f).fillMaxWidth()
                    .then(if (scroll) Modifier.verticalScroll(scrollState) else Modifier)
                    .padding(bottom = bodyBottom.dp), content)
            }
        }
        if (overlay != null) Box(Modifier.fillMaxSize().padding(bottom = insets.bottomDp.dp), content = overlay)
    }
}

/** The space a page keeps clear at its bottom (gesture strip or navigation bar), for a scroller inside it to pad with. */
val LocalParcBottomInset = compositionLocalOf { 0f }

/** The end padding of a scroller inside a [ParcPage] whose body does not pad its own bottom. */
@Composable
fun Modifier.parcBottomInset(): Modifier = padding(bottom = LocalParcBottomInset.current.dp)

/** The same space as list content padding. */
@Composable
fun parcBottomInsetPadding(): PaddingValues = PaddingValues(bottom = LocalParcBottomInset.current.dp)

/** The gap a block container puts between its children; a section header takes it out of its own top gap. */
val LocalParcBlockGap = compositionLocalOf { 0f }

/**
 * A column of blocks spaced [gapDp] apart. Headers and group leads inside it read [LocalParcBlockGap], so a
 * section header is [Metrics.sectionGapDp] below the block above it, not the gap plus that.
 */
@Composable
fun ParcBlocks(gapDp: Float, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    CompositionLocalProvider(LocalParcBlockGap provides gapDp) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(gapDp.dp), content = content)
    }
}

/** The inset inside a card: 16 dp on all four sides, so text and controls never touch its edge. */
@Composable
fun ParcCardContent(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.parcCardPadding(), content = content)
}

/** 16 dp on all four sides: the inset of a card's content. */
fun Modifier.parcCardPadding(): Modifier = padding(Metrics.cardInsetDp.dp)

/** A card on a Settings page: the row inset (20 dp) at the sides, one card inset (16 dp) above and below. */
fun Modifier.parcSettingsPadding(): Modifier =
    padding(horizontal = Metrics.settingsRowInsetDp.dp, vertical = Metrics.cardInsetDp.dp)

/** The screen gutter, 20 dp on the left and right: where page text and cards start. */
fun Modifier.parcGutter(): Modifier = padding(horizontal = Metrics.screenGutterDp.dp)

/** 20 dp left and right, 12 dp above and below: the padding of a Settings row's content. */
fun Modifier.parcRowPadding(): Modifier =
    padding(horizontal = Metrics.settingsRowInsetDp.dp, vertical = Metrics.rowGapDp.dp)

/** A touch target is at least [Metrics.minTouchDp] each way. */
fun Modifier.parcMinTouch(): Modifier = heightIn(min = Metrics.minTouchDp.dp).widthIn(min = Metrics.minTouchDp.dp)

/**
 * One Settings row: the title block in [content] (title, value, description), an optional [leading]
 * sample before it and an optional [trailing] switch or value after it. At least 56 dp tall, the trailing
 * control 20 dp from the row's right edge. Put `clickable` or `toggleable` in [modifier] so the whole row,
 * padding included, is the touch target.
 */
@Composable
fun ParcSettingsRow(
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(modifier.fillMaxWidth().heightIn(min = Metrics.settingsRowMinHeightDp.dp).parcRowPadding(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.rowGapDp.dp)) {
        leading?.invoke()
        Column(Modifier.weight(1f), content = content)
        trailing?.invoke()
    }
}

/** A section heading: [Metrics.sectionGapDp] above, [Metrics.sectionHeaderGapDp] below, aligned with row text. */
@Composable
fun ParcSectionHeader(
    text: String, style: TextStyle, modifier: Modifier = Modifier,
    startInsetDp: Float = Metrics.settingsRowInsetDp,
) {
    BasicText(text, style = style, modifier = modifier.fillMaxWidth()
        .padding(start = startInsetDp.dp, top = ParcSpacing.sectionHeaderTopDp(LocalParcBlockGap.current).dp,
            bottom = Metrics.sectionHeaderGapDp.dp)
        .semantics { heading() })
}
