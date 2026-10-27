package com.brightblu.parc

import androidx.compose.runtime.*
import com.brightblu.parc.core.data.focus.*
import com.brightblu.parc.feature.settings.ui.*
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.Metrics

@Composable
internal fun FocusFrictionSettings(mode: FocusMode, skin: Skin, change: (FocusMode) -> Unit) {
    val options = mode.friction
    var setupIssue by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf<String?>(null) }
    fun update(next: FocusFriction) = change(mode.copy(friction = next))
    SettingsSection("Emergency pass opens all apps; locked folders stay locked. Focus resumes automatically.", skin)
    SettingRow("Pass length", skin, SettingKind.Choice, descriptionMaxLines = Int.MAX_VALUE, value = "${options.passMinutes} min",
        description = "Tap to cycle from 1 to 15 minutes") { update(options.copy(passMinutes = options.passMinutes % 15 + 1)) }
    SettingRow("Pass limit", skin, SettingKind.Choice, descriptionMaxLines = Int.MAX_VALUE, value = "${options.passLimit} per ${options.period.name.lowercase()}",
        description = "Tap to cycle from 1 to 5 passes") { update(options.copy(passLimit = options.passLimit % 5 + 1)) }
    SettingRow("Reset period", skin, SettingKind.Choice, descriptionMaxLines = Int.MAX_VALUE, value = options.period.name,
        description = "Local midnight, or Monday at midnight") { update(options.copy(period = if (options.period == PassPeriod.Day) PassPeriod.Week else PassPeriod.Day)) }
    SettingRow("Ask before opening hidden apps", skin, SettingKind.Toggle, descriptionMaxLines = Int.MAX_VALUE, checked = mode.pauseBeforeOpen,
        description = "Show a question instead of removing apps from search") { change(mode.copy(pauseBeforeOpen = !mode.pauseBeforeOpen)) }
    SettingRow("Pause as often as needed", skin, SettingKind.Toggle, descriptionMaxLines = Int.MAX_VALUE, checked = options.unlimitedPasses,
        description = "Turn off the pass limit and the ten-minute wait between passes") { update(options.copy(unlimitedPasses = !options.unlimitedPasses)) }
    options.delaySeconds.forEachIndexed { index, seconds ->
        SettingRow("Wait ${index + 1}", skin, SettingKind.Choice, descriptionMaxLines = Int.MAX_VALUE, value = "$seconds seconds",
            description = "Before ${if (index == 0) "the first" else if (index == 1) "the second" else "later"} Pause or End. Tap for 0, 5, 30, 120 or 300 seconds.") {
            val choices = listOf(0, (Metrics.focusFirmDelayMs / 1_000).toInt()) + FocusFriction().delaySeconds
            val next = choices[(choices.indexOf(seconds) + 1).mod(choices.size)]
            update(options.copy(delaySeconds = options.delaySeconds.mapIndexed { i, old -> if (i == index) next else old }))
        }
    }
    SettingsSection("Leaving a wait cancels it. Limited passes must be at least 10 minutes apart.", skin)
    SettingRow("Strict Mode", skin, SettingKind.Toggle, descriptionMaxLines = Int.MAX_VALUE, checked = mode.strict,
        description = "No editing, deleting or switching modes while active, including during a pass. At least a random sentence is required.") { change(mode.copy(strict = !mode.strict)) }
    SettingsSection("Challenge after the delay", skin)
    FocusChallenge.entries.forEach { challenge ->
        val label = when (challenge) {
            FocusChallenge.None -> "None"
            FocusChallenge.Hold -> "Hold a button for 20 seconds"
            FocusChallenge.Sentence -> "Retype a long random sentence"
            FocusChallenge.Arithmetic -> "Solve 5 arithmetic problems"
            FocusChallenge.Accountability -> "Accountability code"
        }
        SettingRow(label, skin, SettingKind.Choice, descriptionMaxLines = Int.MAX_VALUE, value = if (options.challenge == challenge) "Selected" else "",
            description = if (challenge == FocusChallenge.Accountability) "A trusted person keeps the authenticator secret" else "") { update(options.copy(challenge = challenge)) }
    }
    if (options.accountabilitySecret == null) SettingRow("Set up accountability code", skin, SettingKind.Action, descriptionMaxLines = Int.MAX_VALUE,
        description = "Generate a secret on this device. Give it to someone you trust.") {
        runCatching { FocusTotp.newSecret() }.onSuccess { secret ->
            revealed = secret
            update(options.copy(accountabilitySecret = secret))
        }.onFailure { setupIssue = "Could not create a secret. Try again." }
    } else SettingsSection("Accountability code is set up. The secret cannot be shown again. Wait until Focus ends if your trusted person is unavailable.", skin)
    if (setupIssue.isNotEmpty()) SettingsSection(setupIssue, skin)
    revealed?.let { secret ->
        SettingsSection("Shown once: $secret", skin)
        SettingsSection("In any authenticator choose manual setup, time-based, SHA1, 6 digits, 30 seconds. Account: Parc Focus. Give this base32 secret to your trusted person before leaving. Exported backups include this secret.", skin)
        SettingRow("I have handed over the secret", skin, SettingKind.Action) { revealed = null }
    }
    SettingsSection("After the pass limit, wait until Focus ends or ask for the accountability code if set up.", skin)
    SettingsSection("Parc only controls Parc. You can still switch launcher or uninstall, so this is a speed bump and a promise to yourself.", skin)
}
