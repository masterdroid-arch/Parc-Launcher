package com.brightblu.parc.feature.settings.ui

import com.brightblu.parc.model.textFamily
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.model.*

/** The only four setting-row treatments. Form fields and list options are not setting rows. */
enum class SettingKind { Toggle, Choice, Page, Action }

fun settingsText(skin: Skin, tint: Long = skin.colors.textPrimary, caption: Boolean = false): TextStyle {
    val token = if (caption) skin.typography.caption else skin.typography.menu
    return TextStyle(color = Color(tint.toInt()), fontSize = token.sizeSp.sp,
        fontWeight = FontWeight(token.weight), letterSpacing = token.trackingSp.sp,
        fontFamily = skin.textFamily)
}

/**
 * OPUS1 settings design system. Every settings screen is built from these few pieces, so spacing,
 * alignment and colours are the same everywhere:
 * - [SettingsGroup]: a small-caps heading, one rounded card of rows, an optional one-line footer.
 * - [SettingRow]: optional sample on the left, title with a one-line explanation, the current value
 *   on the right (or a switch), and a chevron when it opens something.
 * - [SettingsPreviewFrame]: the one frame every live preview sits in.
 * - [SettingsOptionChips] and [SettingsVisualChoice]: inline choices that show their effect.
 * Colours come only from the skin tokens and sizes only from [Metrics]; text wraps instead of
 * clipping at large font sizes.
 */
@Composable
fun SettingsSection(title: String, skin: Skin) {
    ParcSectionHeader(title.uppercase(java.util.Locale.ROOT), settingsText(skin, skin.colors.textSecondary, true))
}

/**
 * A card of rows: its first and last row sit one card inset from the card edge (T166). A row draws its hairline
 * under itself, so the last one is clipped away here; it would otherwise float above the card's border.
 */
@Composable
fun SettingsCard(skin: Skin, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(Metrics.menuCornerDp.dp)
    Column(Modifier.fillMaxWidth().background(Color(skin.colors.surface.toInt()), shape)
        .border(skin.shape.surfaceBorderDp.dp, Color(skin.colors.lineBase.toInt()), shape).clip(shape)) {
        Column(Modifier.padding(vertical = ParcSpacing.rowCardVerticalPaddingDp().dp).drawWithContent {
            val cut = skin.shape.surfaceBorderDp.dp.toPx().coerceAtLeast(1f)
            clipRect(bottom = size.height - cut) { this@drawWithContent.drawContent() }
        }, content = content)
    }
}

/** A card of plain content (text, a field, a preview): [ParcCardContent] gives it the card inset. */
@Composable
fun SettingsPanel(skin: Skin, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(Metrics.menuCornerDp.dp)
    Column(Modifier.fillMaxWidth().background(Color(skin.colors.surface.toInt()), shape)
        .border(skin.shape.surfaceBorderDp.dp, Color(skin.colors.lineBase.toInt()), shape).clip(shape)) {
        Column(Modifier.parcSettingsPadding(), content = content)
    }
}

/** A titled card of rows with an optional explanation under it; the building block of every page. */
@Composable
fun SettingsGroup(title: String?, skin: Skin, footer: String = "", content: @Composable ColumnScope.() -> Unit) {
    if (title != null) SettingsSection(title, skin) else Spacer(Modifier.height(ParcSpacing.groupLeadDp(LocalParcBlockGap.current).dp))
    SettingsCard(skin, content)
    if (footer.isNotBlank()) BasicText(footer, style = settingsText(skin, skin.colors.textSecondary, true),
        modifier = Modifier.fillMaxWidth().padding(horizontal = Metrics.settingsRowInsetDp.dp, vertical = Metrics.sectionHeaderGapDp.dp))
}

/** The one frame live previews sit in: skin background, rounded, a small caption, fixed height. */
@Composable
fun SettingsPreviewFrame(skin: Skin, caption: String = "Preview", heightDp: Float = Metrics.settingsPreviewHeightDp,
    background: Long = skin.colors.bg, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(Metrics.menuCornerDp.dp)
    Column(Modifier.fillMaxWidth().padding(top = Metrics.sectionHeaderGapDp.dp)) {
        Box(Modifier.fillMaxWidth().height(heightDp.dp).clip(shape).background(Color(background.toInt()), shape)
            .border(skin.shape.surfaceBorderDp.dp, Color(skin.colors.lineBase.toInt()), shape)
            .semantics(mergeDescendants = true) { contentDescription = caption },
            contentAlignment = Alignment.Center, content = content)
        BasicText(caption, style = settingsText(skin, skin.colors.textSecondary, true),
            modifier = Modifier.padding(start = Metrics.settingsRowInsetDp.dp, top = Metrics.lineGapDp.dp))
    }
}

@Composable
fun SettingRow(
    title: String, skin: Skin, kind: SettingKind, description: String = "", value: String = "",
    checked: Boolean = false, enabled: Boolean = true, destructive: Boolean = false,
    icon: String = "+", descriptionMaxLines: Int = 3, leading: (@Composable () -> Unit)? = null,
    divider: Boolean = true, onClick: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    val act = { if (destructive) confirm = true else onClick() }
    val tint = when { !enabled -> skin.colors.textSecondary; destructive -> skin.colors.warn
        kind == SettingKind.Action -> skin.colors.accent; else -> skin.colors.textPrimary }
    val showValue = value.isNotBlank() && kind != SettingKind.Toggle && kind != SettingKind.Action
    // Short values sit on the right like a phone's settings; long ones go under the title so
    // neither the title nor the value is squeezed at large text sizes.
    val valueBeside = showValue && SettingsLayoutRules.valueBeside(value)
    // T166: one row block for every Settings row: 56 dp tall at least, 20 dp left and right, 12 dp above and
    // below, and the trailing switch, value or arrow 20 dp from the edge of the card.
    ParcSettingsRow(
        modifier = if (kind == SettingKind.Toggle) Modifier.toggleable(checked, enabled, Role.Switch) { act() }
            else Modifier.clickable(enabled = enabled, role = Role.Button, onClick = act),
        leading = if (leading != null) {
            { Box(Modifier.size(Metrics.settingsSampleDp.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) { leading() } }
        } else if (kind == SettingKind.Action || (kind == SettingKind.Page && icon != "+")) {
            { Box(Modifier.size(Metrics.settingsSampleDp.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
                BasicText(icon, style = settingsText(skin, tint))
            } }
        } else null,
        trailing = if (valueBeside || kind != SettingKind.Action) {
            {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.rowGapDp.dp)) {
                    if (valueBeside) BasicText(value,
                        style = settingsText(skin, skin.colors.accent, true).copy(textAlign = androidx.compose.ui.text.style.TextAlign.End),
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = Metrics.settingsValueMaxWidthDp.dp))
                    when (kind) {
                        SettingKind.Toggle -> Switch(checked, onCheckedChange = null, enabled = enabled,
                            modifier = Modifier.clearAndSetSemantics {}, colors = settingsSwitchColors(skin))
                        SettingKind.Choice, SettingKind.Page ->
                            BasicText("›", style = settingsText(skin, skin.colors.accent), modifier = Modifier.clearAndSetSemantics {})
                        SettingKind.Action -> Unit
                    }
                }
            }
        } else null,
    ) {
        BasicText(title, style = settingsText(skin, tint))
        if (showValue && !valueBeside) BasicText(value, style = settingsText(skin, skin.colors.accent, true),
            modifier = Modifier.padding(top = Metrics.lineGapDp.dp))
        if (description.isNotBlank()) BasicText(description, style = settingsText(skin, skin.colors.textSecondary, true),
            maxLines = descriptionMaxLines, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Metrics.lineGapDp.dp))
    }
    if (divider) SettingsDivider(skin)
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        containerColor = Color(skin.colors.surface.toInt()),
        title = { BasicText("$title?", style = settingsText(skin)) },
        text = { BasicText("This changes saved data. Continue?", style = settingsText(skin, skin.colors.textSecondary)) },
        confirmButton = { TextButton(onClick = { confirm = false; onClick() }) { BasicText("Continue", style = settingsText(skin, skin.colors.warn)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { BasicText("Cancel", style = settingsText(skin, skin.colors.accent)) } })
}

/** The hairline between two rows of a card, inset like the row text. */
@Composable
fun SettingsDivider(skin: Skin) {
    Box(Modifier.fillMaxWidth().padding(horizontal = Metrics.settingsRowInsetDp.dp)
        .height(skin.shape.surfaceBorderDp.dp).background(Color(skin.colors.lineBase.toInt())))
}

@Composable
private fun settingsSwitchColors(skin: Skin) = SwitchDefaults.colors(
    checkedThumbColor = Color(skin.colors.bg.toInt()), checkedTrackColor = Color(skin.colors.accent.toInt()),
    checkedBorderColor = Color(skin.colors.accent.toInt()), uncheckedThumbColor = Color(skin.colors.textSecondary.toInt()),
    uncheckedTrackColor = Color(skin.colors.surface.toInt()), uncheckedBorderColor = Color(skin.colors.lineActive.toInt()),
    disabledCheckedThumbColor = Color(skin.colors.textSecondary.toInt()),
    disabledCheckedTrackColor = Color(skin.colors.lineActive.toInt()),
    disabledCheckedBorderColor = Color(skin.colors.lineActive.toInt()),
    disabledUncheckedThumbColor = Color(skin.colors.lineActive.toInt()),
    disabledUncheckedTrackColor = Color(skin.colors.surface.toInt()),
    disabledUncheckedBorderColor = Color(skin.colors.lineBase.toInt()))

/**
 * An inline choice for a visual setting: title and current value, one line of explanation, then
 * every option as a chip with its own sample. Chips wrap onto more lines at large text sizes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsOptionChips(
    title: String, skin: Skin, options: List<String>, selected: Int, description: String = "",
    divider: Boolean = true, sample: (@Composable (index: Int, selected: Boolean) -> Unit)? = null,
    onSelect: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().parcRowPadding()) {
        // The selected chip carries the value, so the title gets the whole width.
        BasicText(title, style = settingsText(skin))
        if (description.isNotBlank()) BasicText(description, style = settingsText(skin, skin.colors.textSecondary, true),
            modifier = Modifier.padding(top = Metrics.lineGapDp.dp))
        FlowRow(Modifier.fillMaxWidth().padding(top = Metrics.rowGapDp.dp),
            horizontalArrangement = Arrangement.spacedBy(Metrics.itemGapDp.dp),
            verticalArrangement = Arrangement.spacedBy(Metrics.itemGapDp.dp)) {
            options.forEachIndexed { index, label ->
                val on = index == selected
                val shape = RoundedCornerShape(skin.shape.surfaceCornerDp.dp)
                Column(Modifier.widthIn(min = Metrics.settingsChipMinWidthDp.dp).heightIn(min = Metrics.touchTargetDp.dp)
                    .clip(shape).background(Color((if (on) skin.colors.bg else skin.colors.surface).toInt()), shape)
                    .border(maxOf(skin.shape.surfaceBorderDp, 1f).dp * (if (on) 2 else 1),
                        Color((if (on) skin.colors.accent else skin.colors.lineActive).toInt()), shape)
                    .selectable(on, role = Role.RadioButton) { onSelect(index) }
                    .parcSettingsPadding(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Metrics.lineGapDp.dp)) {
                    if (sample != null) Box(Modifier.size(Metrics.settingsSampleDp.dp).clearAndSetSemantics {},
                        contentAlignment = Alignment.Center) { sample(index, on) }
                    BasicText(label, style = settingsText(skin, if (on) skin.colors.accent else skin.colors.textPrimary, true)
                        .copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center))
                }
            }
        }
    }
    if (divider) SettingsDivider(skin)
}

@Composable
fun SettingsChoice(title: String, value: String, options: List<String>, skin: Skin, description: String = "",
    onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    SettingRow(title, skin, SettingKind.Choice, description, value = value) { open = true }
    if (open) AlertDialog(onDismissRequest = { open = false }, containerColor = Color(skin.colors.surface.toInt()),
        title = { BasicText(title, style = settingsText(skin)) },
        text = {
            androidx.compose.foundation.lazy.LazyColumn {
                items(options.size) { index ->
                    Row(Modifier.fillMaxWidth().heightIn(min = Metrics.touchTargetDp.dp)
                        .clickable(role = Role.RadioButton) { onSelect(index); open = false }
                        .semantics { selected = options[index] == value }
                        .padding(vertical = Metrics.itemGapDp.dp), verticalAlignment = Alignment.CenterVertically) {
                        BasicText(options[index], Modifier.weight(1f), style = settingsText(skin))
                        if (options[index] == value) BasicText("✓", style = settingsText(skin, skin.colors.accent))
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { open = false }) { BasicText("Back", style = settingsText(skin, skin.colors.accent)) } })
}

@Composable
fun SettingsSearch(query: String, skin: Skin, onQuery: (String) -> Unit) {
    BasicTextField(query, onQuery, singleLine = true, textStyle = settingsText(skin),
        cursorBrush = SolidColor(Color(skin.colors.accent.toInt())),
        modifier = Modifier.fillMaxWidth().heightIn(min = Metrics.touchTargetDp.dp)
            .background(Color(skin.colors.surface.toInt()), RoundedCornerShape(Metrics.menuCornerDp.dp))
            .border(skin.shape.surfaceBorderDp.dp, Color(skin.colors.lineActive.toInt()), RoundedCornerShape(Metrics.menuCornerDp.dp))
            .parcSettingsPadding().semantics { contentDescription = "Search settings" },
        decorationBox = { inner ->
            if (query.isEmpty()) BasicText("Search settings", style = settingsText(skin, skin.colors.textSecondary))
            inner()
        })
}

/** Material interaction states inherit the active skin as well as the explicitly painted rows. */
@Composable
fun SettingsTheme(skin: Skin, content: @Composable () -> Unit) {
    val scheme = remember(skin) {
        fun tint(value: Long) = Color(value.toInt())
        val c = skin.colors
        lightColorScheme(
            primary = tint(c.accent), onPrimary = tint(c.bg), primaryContainer = tint(c.surface), onPrimaryContainer = tint(c.textPrimary),
            secondary = tint(c.accent2), onSecondary = tint(c.bg), secondaryContainer = tint(c.surface), onSecondaryContainer = tint(c.textPrimary),
            tertiary = tint(c.accent), onTertiary = tint(c.bg), tertiaryContainer = tint(c.surface), onTertiaryContainer = tint(c.textPrimary),
            background = tint(c.bg), onBackground = tint(c.textPrimary), surface = tint(c.surface), onSurface = tint(c.textPrimary),
            surfaceVariant = tint(c.surface), onSurfaceVariant = tint(c.textSecondary), surfaceTint = tint(c.accent),
            error = tint(c.warn), onError = tint(c.bg), errorContainer = tint(c.surface), onErrorContainer = tint(c.warn),
            outline = tint(c.lineActive), outlineVariant = tint(c.lineBase), scrim = tint(c.scrim),
            inverseSurface = tint(c.textPrimary), inverseOnSurface = tint(c.bg), inversePrimary = tint(c.bg),
        )
    }
    val family = skin.textFamily
    val typography = remember(family) { skinTypography(family) }
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}

/** A visual choice stays beside its current value and preview, instead of opening a popup. */
@Composable
fun SettingsVisualChoice(title: String, value: Int, labels: List<String>, skin: Skin, description: String = "",
    divider: Boolean = true, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().parcRowPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(title, Modifier.weight(1f), style = settingsText(skin))
            BasicText(labels[value.coerceIn(labels.indices)], style = settingsText(skin, skin.colors.accent, true))
        }
        if (description.isNotBlank()) BasicText(description, style = settingsText(skin, skin.colors.textSecondary, true),
            modifier = Modifier.padding(top = Metrics.lineGapDp.dp))
        Slider(value = value.toFloat(), onValueChange = { onSelect(kotlin.math.round(it).toInt().coerceIn(labels.indices)) },
            valueRange = 0f..labels.lastIndex.toFloat(), steps = (labels.size - 2).coerceAtLeast(0),
            modifier = Modifier.semantics { stateDescription = labels[value.coerceIn(labels.indices)] })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            BasicText(labels.first(), style = settingsText(skin, skin.colors.textSecondary, true))
            BasicText(labels.last(), style = settingsText(skin, skin.colors.textSecondary, true))
        }
    }
    if (divider) SettingsDivider(skin)
}

/** Pure layout rules of [SettingRow], kept out of Compose for tests. */
object SettingsLayoutRules {
    /** A value up to this many characters sits beside the title; a longer one goes under it. */
    const val MaxBesideValueChars = 12

    fun valueBeside(value: String): Boolean = value.isNotBlank() && value.length <= MaxBesideValueChars
}

/** Material text roles that carry the skin's font, so dialog and button text follows the theme too. */
internal fun skinTypography(family: FontFamily?): androidx.compose.material3.Typography {
    val base = androidx.compose.material3.Typography()
    if (family == null) return base
    fun TextStyle.f() = copy(fontFamily = family)
    return base.copy(
        displayLarge = base.displayLarge.f(), displayMedium = base.displayMedium.f(), displaySmall = base.displaySmall.f(),
        headlineLarge = base.headlineLarge.f(), headlineMedium = base.headlineMedium.f(), headlineSmall = base.headlineSmall.f(),
        titleLarge = base.titleLarge.f(), titleMedium = base.titleMedium.f(), titleSmall = base.titleSmall.f(),
        bodyLarge = base.bodyLarge.f(), bodyMedium = base.bodyMedium.f(), bodySmall = base.bodySmall.f(),
        labelLarge = base.labelLarge.f(), labelMedium = base.labelMedium.f(), labelSmall = base.labelSmall.f(),
    )
}
