package com.brightblu.parc

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.textFamily
import com.brightblu.parc.model.readableAccent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * T219: the hub grip's Focus front door. The state is pure so the off/active/until/pinned cases
 * are unit-tested without Compose. The chip never uses a glyph: its dot is a drawn circle.
 */
internal data class FocusChipState(
    val visible: Boolean = false,
    val label: String = "",
    val description: String = "",
    val active: Boolean = false,
    /** The active mode's accent, or null when off (the chip then uses the secondary text colour). */
    val dotAccent: Long? = null,
    /** True when tapping opens the Focus page; false opens the summary/settings dialog. */
    val opensFocusPage: Boolean = false,
)

internal object FocusChip {
    private val Time = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    /**
     * [available] is true when at least one Focus mode is configured. A mode is shown even when the
     * modes list has not loaded yet, because an active mode is always a real front door. When neither
     * is true the chip is hidden: a dead chip would be worse than no chip.
     */
    fun state(
        activeName: String?,
        untilMillis: Long?,
        accent: Long?,
        available: Boolean,
        pinned: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
    ): FocusChipState {
        val active = activeName != null
        if (!available && !active) return FocusChipState()
        val label = when {
            !active -> "Focus off"
            untilMillis != null -> "$activeName · until ${time(untilMillis, zone)}"
            else -> activeName!!
        }
        val spoken = when {
            !active -> "Focus off"
            untilMillis != null -> "$activeName until ${time(untilMillis, zone)}"
            else -> activeName!!
        }
        val destination = if (pinned) "open Focus." else "open Focus settings."
        return FocusChipState(
            visible = true,
            label = label,
            description = if (active) "Focus. $spoken. Tap to $destination" else "Focus off. Tap to $destination",
            active = active,
            dotAccent = if (active) accent else null,
            opensFocusPage = pinned,
        )
    }

    /** `HH:mm` in [zone], zero-padded and locale independent. */
    fun time(untilMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(untilMillis).atZone(zone).toLocalTime().format(Time)
}

/** The grip chip: a 32 dp pill with a drawn dot, inside the hub's 48 dp touch row. */
@Composable
internal fun FocusChip(state: FocusChipState, skin: Skin, onClick: () -> Unit) {
    if (!state.visible) return
    val dot = when {
        state.active -> state.dotAccent ?: skin.colors.accent
        else -> skin.colors.textSecondary
    }
    Box(
        Modifier.heightIn(min = Metrics.menuRowHeightDp.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = state.description
                role = Role.Button
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier.height(Metrics.focusChipHeightDp.dp)
                .clip(CircleShape)
                .background(Color(skin.colors.surface.toInt()), CircleShape)
                .border(skin.shape.surfaceBorderDp.dp, Color(skin.colors.lineBase.toInt()), CircleShape)
                .padding(horizontal = Metrics.rowGapDp.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(Metrics.focusChipDotDp.dp).background(Color(dot.toInt()), CircleShape))
            Spacer(Modifier.width(Metrics.lineGapDp.dp))
            BasicText(
                state.label,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = TextStyle(
                    fontFamily = skin.textFamily,
                    color = Color(skin.colors.readableAccent.toInt()),
                    fontSize = skin.typography.caption.sizeSp.sp,
                    fontWeight = FontWeight(skin.typography.caption.weight),
                ),
            )
        }
    }
}
