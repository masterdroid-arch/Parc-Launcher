package com.brightblu.parc

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.brightblu.parc.feature.settings.ui.SettingKind
import com.brightblu.parc.feature.settings.ui.SettingRow
import com.brightblu.parc.feature.settings.ui.settingsText
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import kotlinx.coroutines.launch
import java.text.NumberFormat

/** T196: what the key screen says about privacy. One text for the screen and PRIVACY.md. */
internal object TranslatePrivacy {
    const val Text = "In Translate mode only, Parc sends the text you type in search or select in the reader " +
        "(when you tap Translate), the target language and your DeepL key to DeepL " +
        "(api-free.deepl.com or api.deepl.com, over https) so it can translate it. Nothing else is sent, nothing about the phone, " +
        "and nothing typed or selected in other modes. DeepL's free plan may store the texts it receives; read DeepL's own terms. " +
        "Parc encrypts the key on this phone. Manual and weekly backups include it, readable unless you set a backup passphrase. " +
        "The last 20 translations are kept on the phone only, outside backups. You can clear them here."
}

/** T196: pastes, saves, tests and removes the DeepL key; clears the saved translations. Used inside Translate mode and in the Weather options. */
@Composable
internal fun TranslateKeyPanel(skin: Skin, keys: DeepLKeyStore, history: TranslateStore, onSaved: () -> Unit, onClose: (() -> Unit)?) {
    val scope = rememberCoroutineScope()
    var pasted by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(keys.has()) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val demo = DemoMode.enabled

    fun save() {
        if (keys.save(pasted)) { pasted = ""; hasKey = true; message = "Key saved on this phone."; onSaved() }
        else message = "That does not look like a DeepL key."
    }
    fun test() {
        if (demo) { message = "Key test is off in demo mode."; return }
        val key = DeepLRules.cleanKey(pasted) ?: keys.load()
        if (key == null) { message = TranslateProblem.NoKey.message; return }
        busy = true; message = "Testing…"
        scope.launch {
            message = when (val r = DeepLClient(DeepLHttp).usage(key)) {
                is DeepLClient.UsageOutcome.Ok -> {
                    val f = NumberFormat.getIntegerInstance()
                    val limit = r.usage.limit?.let { " of ${f.format(it)}" } ?: ""
                    "Key works. ${f.format(r.usage.used)}$limit characters used this month."
                }
                is DeepLClient.UsageOutcome.Failed -> r.problem.message
            }
            busy = false
        }
    }
    Column(Modifier.fillMaxWidth()) {
        BasicText("DeepL key", style = settingsText(skin))
        BasicText(TranslatePrivacy.Text, Modifier.padding(vertical = Metrics.rowGapDp.dp), style = settingsText(skin, caption = true))
        BasicText(if (hasKey) "A key is saved." else "No key yet. Paste your own DeepL API key below.", style = settingsText(skin, caption = true))
        androidx.compose.material3.OutlinedTextField(pasted, { pasted = it.take(100) }, textStyle = settingsText(skin),
            label = { BasicText("Paste key", style = settingsText(skin)) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }))
        SettingRow("Save key", skin, SettingKind.Action, enabled = pasted.isNotBlank(), icon = "›") { save() }
        SettingRow(if (busy) "Testing…" else "Test key", skin, SettingKind.Action, enabled = !busy && (hasKey || pasted.isNotBlank()),
            description = "Asks DeepL how many characters you used this month", icon = "›") { test() }
        message?.let { BasicText(it, Modifier.padding(vertical = Metrics.rowGapDp.dp), style = settingsText(skin, skin.colors.accent, true)) }
        if (hasKey) SettingRow("Remove key", skin, SettingKind.Action, "Deletes the key from this phone", destructive = true, icon = "−") {
            keys.clear(); hasKey = false; message = "Key removed."
        }
        SettingRow("Clear saved translations", skin, SettingKind.Action, "The last 20 translations kept on this phone",
            destructive = true, icon = "−", divider = false) { scope.launch { history.clear(); message = "Saved translations cleared." } }
        if (onClose != null) ActionText("Back", skin, onClick = onClose)
    }
}
