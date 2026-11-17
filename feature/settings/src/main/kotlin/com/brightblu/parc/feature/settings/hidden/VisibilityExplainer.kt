package com.brightblu.parc.feature.settings.hidden

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brightblu.parc.feature.settings.ui.SettingsPanel
import com.brightblu.parc.feature.settings.ui.SettingsSection
import com.brightblu.parc.feature.settings.ui.settingsText
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin

/**
 * T219: the one explanation of Hide, Focus and Lock. The same scope line is reused at the top of
 * Hidden apps and Hidden folders; there is no second explainer screen.
 *
 * Plain sentences only: the card asks for no glyph in the chip or the three sections.
 */
internal object VisibilityCopy {
    const val Hide =
        "Hide: forever, until you unhide. Removes an app or folder from every Parc surface."
    const val Focus =
        "Focus: for now, until the mode ends. Hides chosen apps and, by default, their messages."
    const val Lock =
        "Lock: biometrics. Keeps the folder on Home with a padlock; its apps and messages stay hidden until you unlock."
    const val Shade = "The Android notification shade and other apps are not affected."
    const val Scope =
        "Hidden apps leave the arcs, search, All apps, folders, Messages and Media. Focus and Lock are temporary; see Hidden & locked."
}

/** The Visibility card at the top of Hidden & locked. */
@Composable
fun VisibilityCard(skin: Skin) {
    SettingsSection("Visibility", skin)
    SettingsPanel(skin) {
        VisibilityLine("Hide", VisibilityCopy.Hide, skin)
        Spacer(Modifier.height(Metrics.rowGapDp.dp))
        VisibilityLine("Focus", VisibilityCopy.Focus, skin)
        Spacer(Modifier.height(Metrics.rowGapDp.dp))
        VisibilityLine("Lock", VisibilityCopy.Lock, skin)
        Spacer(Modifier.height(Metrics.rowGapDp.dp))
        BasicText(VisibilityCopy.Shade, style = settingsText(skin, skin.colors.textSecondary, true))
    }
}

@Composable
private fun VisibilityLine(title: String, body: String, skin: Skin) {
    Column {
        BasicText(title, style = settingsText(skin, skin.colors.textPrimary))
        BasicText(body, style = settingsText(skin, skin.colors.textSecondary, true))
    }
}
