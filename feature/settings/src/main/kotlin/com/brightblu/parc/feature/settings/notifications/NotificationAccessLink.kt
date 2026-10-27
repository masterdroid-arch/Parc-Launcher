package com.brightblu.parc.feature.settings.notifications

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** What the settings row can say about Notification access (T15, S13/S14). */
enum class NotificationAccessStatus {
    /** Parc is bound and counting. */
    Granted,

    /** Not granted: the row must offer the Special-access page. */
    NeedsAccess,
}

/**
 * The Settings link for Notification access (T15). There is no permission Parc can request: the user
 * flips the switch in Settings, and on Android 13+ a sideloaded build must first be allowed there
 * under "Allow restricted settings" (PROJECT.md §6.3, documented in onboarding).
 *
 * The helper owns no UI, no context and no Android type it cannot be handed: it takes the listener's
 * package and an [isGranted] probe, so the state decision is pure and testable, and the Android
 * calls stay in [of]. The app passes
 * `NotificationAccessLink.of(context, ComponentName(context, ParcNotificationListenerService::class.java))`.
 */
class NotificationAccessLink(
    private val listenerPackage: String,
    private val isGranted: () -> Boolean,
) {

    /** Current access state; the probe is re-read on every access, never cached across Settings. */
    val status: NotificationAccessStatus
        get() = if (isGranted()) NotificationAccessStatus.Granted else NotificationAccessStatus.NeedsAccess

    /**
     * The Special-access page for notification listeners, scoped to Parc's package so the system
     * lands on Parc's row. `EXTRA_APP_PACKAGE` is honoured by the Settings app on recent releases;
     * if an OEM ignores it, the user still gets the right list.
     */
    fun settingsIntent(): Intent = Intent(Action).putExtra(Settings.EXTRA_APP_PACKAGE, listenerPackage)

    companion object {
        /** The Special-access page this helper opens. */
        const val Action: String = Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS

        /** The production link, reading the grant state from the platform. */
        fun of(context: Context, listener: ComponentName): NotificationAccessLink =
            NotificationAccessLink(listener.packageName) {
                context.getSystemService(NotificationManager::class.java)
                    ?.isNotificationListenerAccessGranted(listener) ?: false
            }
    }
}
