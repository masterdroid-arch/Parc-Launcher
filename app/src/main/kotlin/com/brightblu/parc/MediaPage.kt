package com.brightblu.parc

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.core.data.media.*
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * T99: the Media page. The session list is read from MediaSessionManager through the existing
 * notification listener component. It is polled once per second only while [active] (the page is
 * the visible one); nothing runs in the background and nothing is kept on disk.
 */
@Composable
internal fun MediaHubPage(active: Boolean, tiles: List<String>, expanded: Boolean, skin: Skin) {
    val context = LocalContext.current
    val port = remember(context) { AndroidMediaSource(context) }
    var read by remember { mutableStateOf<MediaRead?>(null) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var picked by remember { mutableStateOf<String?>(null) }
    var cover by remember { mutableStateOf<Bitmap?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    // OPUS6 (audit F2): locked-folder and Focus-blocked apps never reach this page.
    val hidden = LocalHiddenPackages.current
    val latestHidden by rememberUpdatedState(hidden)
    LaunchedEffect(active, picked) {
        if (!active) return@LaunchedEffect
        while (true) {
            val result = withContext(Dispatchers.IO) {
                val r = MediaRules.withoutHidden(port.read(), latestHidden)
                val sessions = (r as? MediaRead.Sessions)?.sessions.orEmpty()
                val id = MediaRules.selected(sessions, picked)?.id
                r to (if (id != null) port.coverFor(id) else null)
            }
            read = result.first
            cover = result.second
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    DisposableEffect(port) { onDispose { port.clearCover() } }
    val shownRead = read?.let { MediaRules.withoutHidden(it, hidden) }
    if (shownRead == MediaRead.NoAccess) {
        HubPermissionCard("Media needs notification access", "Allow", skin) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            runCatching { if (intent.resolveActivity(context.packageManager) != null) context.startActivity(intent) }
        }
        return
    }
    PageTiles(tiles, expanded, skin, centreSingleTile = MediaRules.emptyLine(shownRead) != null) { _ ->
        val current = shownRead
        val empty = MediaRules.emptyLine(current)
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gridDp.dp)) {
            if (empty != null) {
                BasicText(if (shownRead != null) hubTemplates.getValue("media").empty else empty, style = hudValueStyle(skin))
                if (current == MediaRead.NoAccess) {
                    ActionText("Open notification access", skin) {
                        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        runCatching { if (intent.resolveActivity(context.packageManager) != null) context.startActivity(intent) }
                    }
                }
            } else {
                val sessions = MediaRules.order((current as MediaRead.Sessions).sessions)
                val session = MediaRules.selected(sessions, picked)!!
                if (sessions.size > 1) {
                    Row(Modifier.fillMaxWidth().clipToBounds(), horizontalArrangement = Arrangement.spacedBy(Metrics.gridDp.dp)) {
                        sessions.forEach { s ->
                            val label = remember(s.packageName) { appLabel(context, s.packageName) }
                            val chosen = s.id == session.id
                            Box(Modifier.heightIn(min = Metrics.touchTargetDp.dp)
                                .semantics { contentDescription = "Show $label${if (chosen) ", selected" else ""}" }
                                .clickable { picked = s.id }, contentAlignment = Alignment.CenterStart) {
                                BasicText(label, style = hudValueStyle(skin, if (chosen) skin.colors.accent else skin.colors.textSecondary),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gridDp.dp), verticalAlignment = Alignment.CenterVertically) {
                    val art = cover
                    if (art != null) {
                        val image = remember(art) { art.asImageBitmap() }
                        Image(image, "Album art", Modifier.size((LocalHubContentHeight.current / 3f).coerceIn(Metrics.menuRowHeightDp, Metrics.hubArtworkMaxDp).dp), contentScale = ContentScale.Crop)
                    }
                    Column(Modifier.weight(1f)) {
                        BasicText(MediaRules.titleText(session), style = hudValueStyle(skin).copy(fontSize = skin.typography.title.sizeSp.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val sub = MediaRules.subtitleText(session)
                        if (sub.isNotEmpty()) CellLabel(sub, skin)
                        CellLabel("${MediaRules.stateText(session.state)} · ${appLabel(context, session.packageName)}", skin)
                    }
                }
                val fraction = MediaRules.progress(session, now)
                if (fraction != null) DataBar(fraction, skin)
                Row(Modifier.fillMaxWidth()) {
                    BasicText(MediaRules.elapsedText(session, now), style = hudLabelStyle(skin))
                    Spacer(Modifier.weight(1f))
                    BasicText(MediaRules.durationText(session), style = hudLabelStyle(skin))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    ActionText("Prev", skin, "Previous track", enabled = session.canPrevious) { notice = fail(port.previous(session.id)) }
                    val pause = MediaRules.showsPause(session.state)
                    ActionText(if (pause) "Pause" else "Play", skin, if (pause) "Pause" else "Play") { notice = fail(port.playPause(session.id)) }
                    ActionText("Next", skin, "Next track", enabled = session.canNext) { notice = fail(port.next(session.id)) }
                    ActionText("Open app", skin) { notice = fail(port.openApp(session.id), "Couldn't open the app") }
                }
                notice?.let { CellLabel(it, skin) }
            }
        }
    }
}

private fun fail(ok: Boolean, text: String = "Couldn't send to the player"): String? = if (ok) null else text

private fun appLabel(context: android.content.Context, packageName: String): String = try {
    val pm = context.packageManager
    pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()
} catch (e: Exception) {
    packageName.substringAfterLast('.')
}

@Composable
private fun ActionText(text: String, skin: Skin, description: String = text, enabled: Boolean = true, click: () -> Unit) {
    Box(Modifier.heightIn(min = Metrics.touchTargetDp.dp).widthIn(min = Metrics.menuRowHeightDp.dp)
        .semantics { contentDescription = description }.clickable(enabled = enabled, onClick = click),
        contentAlignment = Alignment.CenterStart) {
        BasicText(text, style = hudValueStyle(skin, if (enabled) skin.colors.accent else skin.colors.lineActive))
    }
}
