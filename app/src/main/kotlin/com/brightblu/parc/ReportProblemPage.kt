package com.brightblu.parc

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.feature.settings.ui.SettingKind
import com.brightblu.parc.feature.settings.ui.SettingRow
import com.brightblu.parc.feature.settings.ui.SettingsCard
import com.brightblu.parc.feature.settings.ui.SettingsPanel
import com.brightblu.parc.feature.settings.ui.settingsText
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin

/** What the page can tell the person after a tap. Kept as plain words; nothing here is sent anywhere. */
private object ReportWords {
    const val Intro = "Opens a new issue on GitHub in your browser, with the text below already in it. Read it first and change anything you like."
    const val Free = "Posting on GitHub needs a free GitHub account. Copy details puts the same text on the clipboard for email or Reddit."
    const val Sends = "Only the Parc version, Android version and phone model are added. No names, app lists or usage data. Parc sends nothing itself."
    const val CrashTitle = "Add the last crash note"
    const val CrashHelp = "A short note Parc saved the last time it crashed. Off unless you turn it on."
    const val Copied = "Copied to the clipboard"
    const val CopyFailed = "Could not copy. Select the text and copy it by hand."
    const val NoBrowser = "No browser found. Use Copy details instead."
}

/**
 * T395: Settings > This build > Report a problem. The text is shown first and is fully editable; "Open GitHub" opens
 * the new-issue page through a plain view intent, "Copy details" puts the same text on the clipboard. Parc makes no
 * network call. The crash note switch only appears when a crash note has been saved, and starts off.
 */
@Composable
internal fun ReportProblemContent(skin: Skin) {
    val context = LocalContext.current
    val crashNote = remember(context) { ProblemReport.crashNote(runCatching { CrashGuard(context).lastTrace() }.getOrNull()) }
    val title = remember { ProblemReport.title(BuildConfig.VERSION_NAME) }
    var text by rememberSaveable {
        mutableStateOf(ProblemReport.body(ProblemReport.details(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE,
            Build.VERSION.RELEASE.orEmpty(), Build.VERSION.SDK_INT, Build.MANUFACTURER.orEmpty(), Build.MODEL.orEmpty())))
    }
    var withCrash by rememberSaveable { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Metrics.rowGapDp.dp)) {
        BasicText(ReportWords.Intro, style = settingsText(skin, skin.colors.textSecondary),
            modifier = Modifier.padding(horizontal = Metrics.settingsRowInsetDp.dp))
        BasicText("Title: $title", style = settingsText(skin, skin.colors.textSecondary, true),
            modifier = Modifier.padding(horizontal = Metrics.settingsRowInsetDp.dp))
        SettingsPanel(skin) {
            BasicTextField(
                value = text,
                onValueChange = { text = it; status = "" },
                modifier = Modifier.fillMaxWidth().heightIn(min = Metrics.reportFieldMinHeightDp.dp)
                    .semantics { contentDescription = "Problem report text" },
                textStyle = settingsText(skin),
                cursorBrush = SolidColor(Color(skin.colors.accent.toInt())),
            )
        }
        if (crashNote.isNotEmpty()) {
            SettingsCard(skin) {
                SettingRow(ReportWords.CrashTitle, skin, SettingKind.Toggle, ReportWords.CrashHelp, checked = withCrash, divider = false) {
                    withCrash = !withCrash
                    text = ProblemReport.withCrashNote(text, crashNote, withCrash)
                    status = ""
                }
            }
        }
        ReportButton("Open GitHub", skin, primary = true) {
            status = if (openLink(context, ProblemReport.issueUrl(title, text))) "" else ReportWords.NoBrowser
        }
        ReportButton("Copy details", skin, primary = false) {
            status = if (copyText(context, text)) ReportWords.Copied else ReportWords.CopyFailed
        }
        if (status.isNotEmpty()) {
            BasicText(status, style = settingsText(skin, skin.colors.textSecondary, true),
                modifier = Modifier.padding(horizontal = Metrics.settingsRowInsetDp.dp))
        }
        BasicText(ReportWords.Free, style = settingsText(skin, skin.colors.textSecondary, true),
            modifier = Modifier.padding(horizontal = Metrics.settingsRowInsetDp.dp))
        BasicText(ReportWords.Sends, style = settingsText(skin, skin.colors.textSecondary, true),
            modifier = Modifier.padding(horizontal = Metrics.settingsRowInsetDp.dp))
    }
}

/** A full-width pill, at least 48 dp tall: the primary one is Parc orange, the other is the quiet surface. */
@Composable
private fun ReportButton(label: String, skin: Skin, primary: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(percent = 50)
    val fill = if (primary) Color(Skin.ParcOrange.toInt()) else Color(skin.colors.moduleSurface.toInt())
    val ink = if (primary) Color(Skin.Midnight.colors.bg.toInt()) else Color(skin.colors.textPrimary.toInt())
    Box(Modifier.fillMaxWidth().heightIn(min = Metrics.touchTargetDp.dp).clip(shape).background(fill, shape)
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = Metrics.cardInsetDp.dp),
        contentAlignment = Alignment.Center) {
        BasicText(label, style = settingsText(skin, if (primary) Skin.Midnight.colors.bg else skin.colors.textPrimary)
            .copy(color = ink, fontWeight = FontWeight.Medium, fontSize = skin.typography.body.sizeSp.sp))
    }
}

private fun openLink(context: Context, url: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }

private fun copyText(context: Context, text: String): Boolean = runCatching {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
    clipboard.setPrimaryClip(ClipData.newPlainText("Parc problem report", text))
    true
}.getOrDefault(false)
