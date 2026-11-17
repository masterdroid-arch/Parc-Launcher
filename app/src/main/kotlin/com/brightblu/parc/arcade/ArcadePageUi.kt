package com.brightblu.parc.arcade

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.brightblu.parc.CellLabel
import com.brightblu.parc.HubConfigStore
import com.brightblu.parc.HudCell
import com.brightblu.parc.hubPageScroll
import com.brightblu.parc.hudLabelStyle
import com.brightblu.parc.hudValueStyle
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.Springs
import kotlinx.coroutines.launch

/** The glue between the arcade shell and one game's composable. The only place that knows a game's code: add one branch per game. */
@Composable
private fun ArcadeGameUi(game: ArcadeGame, skin: Skin, active: Boolean, best: Int, onRunEnd: (Int) -> Unit) {
    when (game.id) {
        "orbit" -> OrbitGame(skin, active, best, onRunEnd)
        "lander" -> LanderGame(skin, active, best, onRunEnd)
    }
}

/**
 * T200: the Arcade page. It only exists once a game is unlocked (see [com.brightblu.parc.HubConfig.arcadeOpen]). Shows one tile per
 * unlocked game with its best score; a locked game is not drawn, named or described anywhere. Opening a game and coming back move on
 * the sheet spring and can be reversed mid-way; the open game gets `active = false` the instant the page or the hub goes away.
 */
@Composable
internal fun ArcadeHubPage(skin: Skin, active: Boolean) {
    val context = LocalContext.current
    val store = remember(context) { HubConfigStore(context) }
    val state by store.arcade.collectAsState(initial = ArcadeState())
    val scope = rememberCoroutineScope()
    var openId by remember { mutableStateOf<String?>(null) }
    val open = ArcadeCatalog.game(openId.orEmpty())?.takeIf { state.isUnlocked(it.id) }
    val reveal by animateFloatAsState(if (open != null) 1f else 0f, spring(Springs.sheet.damping, Springs.sheet.stiffness), label = "arcadeGame")
    var lastOpen by remember { mutableStateOf<ArcadeGame?>(null) }
    if (open != null) lastOpen = open
    val shown = open ?: lastOpen
    Box(Modifier.fillMaxSize()) {
        if (reveal < 1f) Column(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - reveal; scaleX = 1f - 0.04f * reveal; scaleY = 1f - 0.04f * reveal }
            .hubPageScroll(), verticalArrangement = Arrangement.spacedBy(Metrics.hudCellGapDp.dp)) {
            val games = ArcadeCatalog.games.filter { state.isUnlocked(it.id) }
            games.forEach { game ->
                val best = state.bestOf(game)
                val line = if (best > 0) "Best $best" else "Not played yet"
                HudCell(skin, Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "${game.title}. $line. Opens the game." }
                    .clickable(role = Role.Button) { openId = game.id }) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            BasicText(game.title, style = hudValueStyle(skin), maxLines = 1)
                            CellLabel(line, skin)
                        }
                    }
                }
            }
            if (games.isEmpty()) CellLabel("Nothing to play yet", skin)
        }
        if (shown != null && reveal > 0f) Column(Modifier.fillMaxSize().graphicsLayer { alpha = reveal; scaleX = 0.96f + 0.04f * reveal; scaleY = 0.96f + 0.04f * reveal }) {
            Row(Modifier.fillMaxWidth().heightIn(min = Metrics.menuRowHeightDp.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.heightIn(min = Metrics.menuRowHeightDp.dp)
                    .semantics { contentDescription = "Back to the arcade" }
                    .clickable(role = Role.Button) { openId = null }
                    .padding(end = Metrics.lineGapDp.dp), contentAlignment = Alignment.CenterStart) {
                    BasicText("‹ Arcade", style = hudLabelStyle(skin).copy(color = androidx.compose.ui.graphics.Color(skin.colors.accent.toInt())))
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    val best = state.bestOf(shown)
                    BasicText(shown.title + if (best > 0) " · best $best" else "", style = hudLabelStyle(skin), maxLines = 1)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                ArcadeGameUi(shown, skin, active && open != null, state.bestOf(shown)) { score ->
                    scope.launch { store.arcadeEdit { it.withBest(shown, score) } }
                }
            }
        }
    }
}
