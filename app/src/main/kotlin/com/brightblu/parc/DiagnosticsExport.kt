package com.brightblu.parc

import com.brightblu.parc.model.textFamily
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Result line of the last export or copy (T88). */
internal enum class DiagnosticsStatus(val text: String) {
    Idle(""),
    Working("Collecting..."),
    Saved("Saved to the file you chose"),
    Copied("Copied to the clipboard"),
    Failed("Could not complete that. Try Copy instead."),
}

/**
 * Handle returned by [rememberDiagnosticsExporter]: [export] opens the system file picker
 * (CreateDocument, `parc-log.txt`) and writes the report into the chosen file; [copy] puts the same
 * text on the clipboard. No permission and no network are involved. Stable across recomposition.
 */
internal class DiagnosticsExporter(
    val status: State<DiagnosticsStatus>,
    val export: () -> Unit,
    val copy: () -> Unit,
)

/**
 * T88: remembers the launcher and the actions for the diagnostics export. The Settings owner calls
 * this once and passes the result to [DiagnosticsExport], or calls [DiagnosticsExporter.export] from
 * their own row.
 */
@Composable
internal fun rememberDiagnosticsExporter(): DiagnosticsExporter {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status = remember { mutableStateOf(DiagnosticsStatus.Idle) }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportDiagnostics.MimeType),
    ) { uri ->
        if (uri != null) scope.launch { status.value = writeReport(context, uri.toString(), status) }
    }
    return remember(picker, context, scope) {
        DiagnosticsExporter(
            status = status,
            export = { picker.launch(ExportDiagnostics.FileName) },
            copy = { scope.launch { copyReport(context, status) } },
        )
    }
}

private suspend fun writeReport(
    context: Context,
    uri: String,
    status: androidx.compose.runtime.MutableState<DiagnosticsStatus>,
): DiagnosticsStatus {
    status.value = DiagnosticsStatus.Working
    return try {
        val text = DiagnosticsCollector.text(context)
        withContext(Dispatchers.IO) {
            val stream = context.contentResolver.openOutputStream(android.net.Uri.parse(uri), "wt")
                ?: throw IOException("cannot open the chosen file")
            stream.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
        }
        ParcLog.i("Diagnostics", "exported ${text.length} characters")
        DiagnosticsStatus.Saved
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        ParcLog.w("Diagnostics", "export failed", e)
        DiagnosticsStatus.Failed
    }
}

private suspend fun copyReport(
    context: Context,
    status: androidx.compose.runtime.MutableState<DiagnosticsStatus>,
) {
    status.value = DiagnosticsStatus.Working
    status.value = try {
        val text = DiagnosticsCollector.text(context)
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        if (clipboard == null) {
            DiagnosticsStatus.Failed
        } else {
            clipboard.setPrimaryClip(ClipData.newPlainText("Parc diagnostics", text))
            DiagnosticsStatus.Copied
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        ParcLog.w("Diagnostics", "copy failed", e)
        DiagnosticsStatus.Failed
    }
}

/**
 * T88 standalone block: an `Export diagnostics log` action, a `Copy` action and the status line,
 * styled from [skin] only. Drop it under the Settings row (or on its own page) with the exporter from
 * [rememberDiagnosticsExporter]. Stateless apart from that handle.
 */
@Composable
internal fun DiagnosticsExport(skin: Skin, exporter: DiagnosticsExporter, modifier: Modifier = Modifier) {
    val status by exporter.status
    val busy = status == DiagnosticsStatus.Working
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Metrics.itemGapDp.dp)) {
        BasicText(
            DiagnosticsEntry.Default.description,
            style = diagStyle(skin, skin.colors.textSecondary, skin.typography.caption),
        )
        DiagnosticsAction(DiagnosticsEntry.Default.title, FileHint, !busy, skin, exporter.export)
        DiagnosticsAction("Copy", "", !busy, skin, exporter.copy)
        if (status.text.isNotEmpty()) {
            BasicText(
                status.text,
                style = diagStyle(skin, skin.colors.textSecondary, skin.typography.caption),
                modifier = Modifier.padding(top = Metrics.lineGapDp.dp),
            )
        }
    }
}

private const val FileHint = "parc-log.txt"

@Composable
private fun DiagnosticsAction(label: String, detail: String, enabled: Boolean, skin: Skin, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Metrics.touchTargetDp.dp)
            .background(Color(skin.colors.surface.toInt()), RoundedCornerShape(Metrics.menuCornerDp.dp))
            .border(
                skin.shape.surfaceBorderDp.dp,
                Color(skin.colors.lineBase.toInt()),
                RoundedCornerShape(Metrics.menuCornerDp.dp),
            )
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Metrics.settingsRowInsetDp.dp, vertical = Metrics.cardInsetDp.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        BasicText(
            label,
            style = diagStyle(skin, if (enabled) skin.colors.textPrimary else skin.colors.textSecondary, skin.typography.menu),
            modifier = Modifier.weight(1f),
        )
        if (detail.isNotEmpty()) {
            BasicText(detail, style = diagStyle(skin, skin.colors.textSecondary, skin.typography.caption))
        }
    }
}

private fun diagStyle(skin: com.brightblu.parc.model.Skin, tint: Long, token: TypeToken): TextStyle = TextStyle(
    fontFamily = skin.textFamily,
    color = Color(tint.toInt()),
    fontSize = token.sizeSp.sp,
    fontWeight = FontWeight(token.weight),
    letterSpacing = token.trackingSp.sp,
)
