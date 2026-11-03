package com.brightblu.parc.feature.settings.onboarding

import com.brightblu.parc.model.textFamily
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.feature.settings.mainapps.MainAppsController
import com.brightblu.parc.feature.settings.mainapps.MainAppsEditor
import com.brightblu.parc.feature.settings.mainapps.MainAppsState
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.ParcPage
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.parcMinTouch
import com.brightblu.parc.model.TypeToken

/**
 * Onboarding flow (T18, DESIGN §5 S13, F-16): one plain screen per ask, no animation, no icons.
 *
 * Colours and sizes come only from [Skin] and [Metrics]. The composable takes [onOpenTarget] instead
 * of a Context, so the host owns the Intent and this file stays a renderer. [OnboardingContent] is
 * stateless, so a preview or the debug review host can drive it without the controller.
 */
@Composable
fun Onboarding(
    controller: OnboardingController,
    skin: Skin,
    onOpenTarget: (OnboardingTarget) -> Unit,
    modifier: Modifier = Modifier,
    safeTop: Boolean = true,
) {
    val state by controller.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = remember(context, scope) { MainAppsController.create(context, scope) }
    val pickerState by picker.state.collectAsState()
    var pickerVisited by remember { mutableStateOf(false) }
    var pickerSaved by remember { mutableStateOf(false) }
    LaunchedEffect(state.current.step) {
        if (state.current.step == OnboardingStep.MainApps) pickerVisited = true
    }

    fun saveThen(action: () -> Unit) {
        if (pickerSaved) {
            action()
        } else {
            picker.save {
                pickerSaved = true
                action()
            }
        }
    }

    OnboardingContent(
        state = state,
        skin = skin,
        onOpenTarget = onOpenTarget,
        onNext = {
            if (state.current.step == OnboardingStep.MainApps) saveThen(controller::next) else controller.next()
        },
        onBack = controller::back,
        onSkip = {
            // Skipping before the picker was ever shown leaves the existing main apps alone.
            if (pickerVisited) saveThen(controller::skip) else controller.skip()
        },
        picker = pickerState,
        onPickQuery = picker::setQuery,
        onPickToggle = picker::toggle,
        onPickMove = picker::move,
        modifier = modifier,
        safeTop = safeTop,
    )
}

/** Stateless surface: the current step, its settings rows and the footer. */
@Composable
fun OnboardingContent(
    state: OnboardingUiState,
    skin: Skin,
    onOpenTarget: (OnboardingTarget) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    picker: MainAppsState? = null,
    onPickQuery: (String) -> Unit = {},
    onPickToggle: (AppId) -> Unit = {},
    onPickMove: (Int, Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    safeTop: Boolean = true,
) {
    val spec = state.current
    ParcPage(modifier.background(color(skin.colors.bg)), safeTop = safeTop, scroll = false) {
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            BasicText(
                state.progress,
                style = style(skin, skin.colors.textSecondary, skin.typography.caption),
            )
            BasicText(
                spec.title,
                style = style(skin, skin.colors.textPrimary, skin.typography.title),
                modifier = Modifier.padding(top = Metrics.sectionHeaderGapDp.dp),
            )
            spec.lines.forEach { line ->
                BasicText(
                    line,
                    style = style(skin, skin.colors.textPrimary, skin.typography.menu),
                    modifier = Modifier.padding(top = Metrics.rowGapDp.dp),
                )
            }
            val statusLabel = state.currentStatusLabel
            if (statusLabel.isNotEmpty()) {
                BasicText(
                    statusLabel,
                    style = style(
                        skin, if (state.currentStatus == OnboardingStatus.Done) {
                            skin.colors.accent
                        } else {
                            skin.colors.textSecondary
                        },
                        skin.typography.caption,
                    ),
                    modifier = Modifier.padding(top = Metrics.rowGapDp.dp),
                )
            }
            if (spec.step == OnboardingStep.MainApps && picker != null) {
                MainAppsEditor(
                    state = picker,
                    skin = skin,
                    onQuery = onPickQuery,
                    onToggle = onPickToggle,
                    onMove = onPickMove,
                    modifier = Modifier.padding(top = Metrics.sectionHeaderGapDp.dp),
                )
            }
            spec.actions.forEach { action ->
                ActionRow(action.label, skin, onClick = { onOpenTarget(action.target) })
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .height(Metrics.touchTargetDp.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            FooterButton(
                label = "Back",
                tint = skin.colors.textSecondary,
                skin = skin,
                enabled = !state.isFirst,
                onClick = onBack,
            )
            FooterButton(
                label = "Skip setup",
                tint = skin.colors.textSecondary,
                skin = skin,
                enabled = !state.completed,
                onClick = onSkip,
            )
            FooterButton(
                label = if (state.isLast) "Done" else "Next",
                tint = skin.colors.accent,
                skin = skin,
                enabled = true,
                onClick = onNext,
            )
        }
    }
}

/** A settings page the step offers. Surface and border per DESIGN §2.4, 48 dp tall. */
@Composable
private fun ActionRow(label: String, skin: Skin, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = Metrics.rowGapDp.dp)
            .heightIn(min = Metrics.menuRowHeightDp.dp)
            .background(color(skin.colors.surface), RoundedCornerShape(Metrics.menuCornerDp.dp))
            .border(
                skin.shape.surfaceBorderDp.dp,
                color(skin.colors.lineBase),
                RoundedCornerShape(Metrics.menuCornerDp.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = Metrics.settingsRowInsetDp.dp, vertical = Metrics.cardInsetDp.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicText(label, style = style(skin, skin.colors.textPrimary, skin.typography.menu))
    }
}

/** Footer action: text only, so the screen stays plain. A disabled row is not clickable. */
@Composable
private fun FooterButton(
    label: String,
    tint: Long,
    skin: Skin,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .height(Metrics.touchTargetDp.dp)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .parcMinTouch()
            .padding(horizontal = Metrics.itemGapDp.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            label,
            style = style(
                skin, if (enabled) tint else skin.colors.textSecondary,
                skin.typography.menu,
            ),
        )
    }
}

private fun color(argb: Long): Color = Color(argb.toInt())

private fun style(skin: com.brightblu.parc.model.Skin, tint: Long, token: TypeToken): TextStyle = TextStyle(
    fontFamily = skin.textFamily,
    color = color(tint),
    fontSize = token.sizeSp.sp,
    fontWeight = FontWeight(token.weight),
    letterSpacing = token.trackingSp.sp,
)
