package com.brightblu.parc

import androidx.compose.runtime.*

/**
 * The page-level Back action a Settings screen registers, so the system Back gesture and the Back arrow both close the
 * screen on top first. (1.2.1: the swipe-up-to-exit gesture that used to share this file was removed on purpose.)
 */
internal class SettingsBackTarget { var action: (() -> Unit)? = null }
internal val LocalSettingsBackTarget = staticCompositionLocalOf<SettingsBackTarget?> { null }

@Composable
internal fun RegisterSettingsBack(onBack: () -> Unit) {
    val target = LocalSettingsBackTarget.current
    val latest by rememberUpdatedState(onBack)
    DisposableEffect(target) {
        val action = { latest() }
        target?.action = action
        onDispose { if (target?.action === action) target.action = null }
    }
}
