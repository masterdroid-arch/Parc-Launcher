package com.brightblu.parc

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brightblu.parc.core.data.ledger.LedgerPeriod
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import kotlinx.coroutines.launch

/**
 * T185: the period chosen for a page, remembered per page in the hub store (`period_network`,
 * `period_apps`), and the function that moves it to the next period. Pages ask by their id.
 */
@Composable
internal fun rememberHubPeriod(page: String): Pair<LedgerPeriod, () -> Unit> {
    val context = LocalContext.current
    val store = remember(context) { HubConfigStore(context) }
    val scope = rememberCoroutineScope()
    val periods by store.periods.collectAsState(initial = emptyMap())
    val period = periods[page] ?: LedgerPeriod.Default
    return period to { scope.launch { store.setPeriod(page, period.next()) }; Unit }
}

/** The tappable period label above a figure: the honest label, one tap to the next period. */
@Composable
internal fun PeriodLabel(label: String, skin: Skin, onCycle: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = Metrics.menuRowHeightDp.dp)
            .semantics { contentDescription = "$label. Tap to show the next period."; role = Role.Button }
            .clickable(onClick = onCycle),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicText(
            label,
            style = hudLabelStyle(skin).copy(color = Color(skin.colors.accent.toInt())),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Stored period ids to periods; unknown or missing values read as the default. */
internal object HubPeriods {
    val pages = listOf("network", "apps", StepsPage.Id)
    fun keyName(page: String) = "period_$page"
    fun decode(raw: (String) -> String?): Map<String, LedgerPeriod> =
        pages.associateWith { LedgerPeriod.fromId(raw(keyName(it))) }
}
