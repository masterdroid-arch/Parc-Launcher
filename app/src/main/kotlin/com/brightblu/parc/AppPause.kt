package com.brightblu.parc

import android.content.Context
import androidx.compose.runtime.Composable
import com.brightblu.parc.core.data.backup.BackupStore
import com.brightblu.parc.core.data.ledger.DayLedger
import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.Skin
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope

/**
 * T382: the optional, personal-build "pause before opening" feature.
 *
 * The contract lives here in `:app` so the main launcher can call it without knowing anything
 * personal. [PersonalExtras.appPause] is null in family and play, and every call site skips the
 * feature in that case, which keeps those builds byte-for-byte as they were behaviourally.
 */

/** Why the pause page is showing. */
internal enum class PauseReason { Breath, Limit, Downtime }

/** The answer a launch gets from the pause rules. */
internal sealed interface PauseDecision {
    /** Open normally. */
    data object Allow : PauseDecision

    /**
     * Show the pause page. [usedMinutes] is capped at [limitMinutes] so the sentence never says
     * "46 of 45"; [downtimeEnd] is a `07:00` label and is only set for [PauseReason.Downtime].
     */
    data class Gate(
        val reason: PauseReason,
        val openedToday: Int,
        val usedMinutes: Int = 0,
        val limitMinutes: Int = 0,
        val downtimeEnd: String? = null,
    ) : PauseDecision
}

/** One pause page, snapshotted when the launch was intercepted. */
internal data class PauseRequest(
    val appId: AppId,
    val label: String,
    val gate: PauseDecision.Gate,
)

/** [PersonalExtras.appPause] hands the app one of these; null means the build has no feature. */
internal fun interface AppPauseFactory {
    fun create(context: Context, scope: CoroutineScope): AppPauseFeature
}

/** Everything `:app` may ask the personal pause feature to do. */
internal interface AppPauseFeature {
    /** True when this app was chosen from its long-press menu (even while temporarily off). */
    fun isConfigured(appId: AppId): Boolean

    /** True when the chosen app currently pauses. */
    fun isEnabled(appId: AppId): Boolean

    /** Long-press row: add the app with pause on, or turn its pause off. */
    fun toggle(entry: AppEntry)

    /**
     * The launch decision. [ledger] is the Apps page record; null or a missing day must fail open
     * for the limit, never trap the user. [accessGranted] false means usage cannot be read.
     */
    fun decision(entry: AppEntry, ledger: DayLedger?, accessGranted: Boolean?, now: Long, zone: ZoneId): PauseDecision

    /** Count one app that actually opened from Parc. Resets by local calendar day. */
    fun markOpened(appId: AppId, now: Long)

    /** "Open anyway": grant the extra five minutes for a limit, or just record the choice. */
    fun recordOpenAnyway(appId: AppId, reason: PauseReason, now: Long)

    /** The single JSON document this feature adds to a Parc backup. */
    fun backupStore(context: Context): BackupStore

    /** The calm full-screen page. Back and "Not now" both call [onNotNow]. */
    @Composable
    fun PausePage(request: PauseRequest, skin: Skin, onOpen: () -> Unit, onNotNow: () -> Unit)

    /** The Settings page listed under Notifications & Focus. */
    @Composable
    fun SettingsPage(skin: Skin, apps: List<AppEntry>, onBack: () -> Unit)
}
