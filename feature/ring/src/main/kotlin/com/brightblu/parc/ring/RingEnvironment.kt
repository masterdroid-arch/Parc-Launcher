package com.brightblu.parc.ring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner

internal class RingEnvironment {
    var visible by mutableStateOf(false)
    var animatorScale by mutableFloatStateOf(1f)
}

/** Observes only: never writes a global setting. Screen-off cancels even before lifecycle pause. */
@Composable
internal fun rememberRingEnvironment(): RingEnvironment {
    val view = LocalView.current
    val environment = remember(view) { RingEnvironment() }
    DisposableEffect(view) {
        val context = view.context
        val resolver = context.contentResolver
        val lifecycle = view.findViewTreeLifecycleOwner()?.lifecycle
        val power = context.getSystemService(PowerManager::class.java)
        fun update() {
            environment.visible = lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true && power.isInteractive
            environment.animatorScale = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
                .takeIf { it.isFinite() && it >= 0f } ?: 1f
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        val settings = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = update()
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = update()
        }
        lifecycle?.addObserver(observer)
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, settings)
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
        }, Context.RECEIVER_NOT_EXPORTED)
        update()
        onDispose {
            lifecycle?.removeObserver(observer)
            resolver.unregisterContentObserver(settings)
            context.unregisterReceiver(receiver)
        }
    }
    return environment
}
