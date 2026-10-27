package com.brightblu.parc

import android.app.AlarmManager
import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.service.notification.Condition
import com.brightblu.parc.core.data.focus.FocusMode
import com.brightblu.parc.core.data.focus.FocusOverride
import com.brightblu.parc.core.data.focus.FocusRepository
import com.brightblu.parc.core.data.focus.FocusRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * Owns one automatic rule. Never changes the user's global policy or other apps' rules.
 *
 * OPUS6 (audit F8): the rule used to follow only the in-process Focus ticker, which sleeps while
 * the phone sleeps and dies with Parc's process, so Do Not Disturb came on late or stayed on after a
 * mode ended. Now every state change also schedules a system alarm at the next Focus change, and
 * [FocusDndReceiver] recomputes and applies the rule from the stored modes when it fires, even if
 * Parc's process was killed meanwhile. A clock or time-zone change recomputes too. After a reboot
 * Android starts Parc as the Home app, which re-applies the rule and re-arms the alarm.
 *
 * The alarm is exact where the build may use exact alarms (the personal build already holds
 * USE_EXACT_ALARM for Timers); otherwise (the Play build) it is the permission-free inexact
 * `setAndAllowWhileIdle`, which Android may deliver some minutes late while the phone is idle.
 */
internal fun bindFocusDnd(context: Context, repository: FocusRepository, scope: CoroutineScope) {
    val app = context.applicationContext
    scope.launch {
        repository.state.collectLatest { state ->
            FocusDnd.apply(app, state.active?.syncDnd == true)
            FocusDnd.scheduleNext(app, FocusDnd.nextCheck(repository.modes.value, state.nextChangeMillis))
        }
    }
}

internal object FocusDnd {
    private const val RequestCode = 4242

    /** Sets Parc's rule on or off; a no-op without Do Not Disturb access. Never throws. */
    fun apply(context: Context, active: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.isNotificationPolicyAccessGranted) return
        val condition = Uri.parse("condition://${context.packageName}/focus")
        runCatching {
            var id = manager.automaticZenRules.entries.firstOrNull { it.value.conditionId == condition }?.key
            if (active && id == null) id = manager.addAutomaticZenRule(AutomaticZenRule(
                "Parc Focus", null, ComponentName(context, MainActivity::class.java), condition,
                null, NotificationManager.INTERRUPTION_FILTER_PRIORITY, true))
            if (id != null) manager.setAutomaticZenRuleState(id, Condition(condition, "Parc Focus",
                if (active) Condition.STATE_TRUE else Condition.STATE_FALSE))
        }
    }

    /**
     * When the rule must next be re-checked: the next Focus change, but only while some mode syncs
     * Do Not Disturb (otherwise there is nothing to keep in step and no alarm is set). Pure.
     */
    fun nextCheck(modes: List<FocusMode>, nextChangeMillis: Long?): Long? =
        if (modes.any { it.syncDnd }) nextChangeMillis else null

    /** What the rule should be at [now] and when to look again, from the stored documents. Pure. */
    fun plan(now: Long, modes: List<FocusMode>, override: FocusOverride?, zone: ZoneId): Pair<Boolean, Long?> {
        val resolution = FocusRules.resolve(now, modes, override, zone)
        return (resolution.mode?.syncDnd == true) to nextCheck(modes, resolution.nextChangeMillis)
    }

    /** Arms (or with null cancels) the one alarm. Exact only where allowed. Never throws. */
    fun scheduleNext(context: Context, atMillis: Long?) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = PendingIntent.getBroadcast(context, RequestCode,
            Intent(context, FocusDndReceiver::class.java).setAction(FocusDndReceiver.ActionCheck),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching {
            if (atMillis == null) { alarms.cancel(intent); return }
            if (alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, intent)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, intent)
        }
    }
}

/**
 * OPUS6 (audit F8): recomputes Parc's Do Not Disturb rule from the stored Focus modes when the
 * scheduled check fires, or when the clock or the time zone changes. Works with Parc's process
 * killed. Demo mode never touches Do Not Disturb.
 */
class FocusDndReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (DemoMode.enabled) return
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val (modes, override) = FocusRepository.readStored(app)
                val (active, next) = FocusDnd.plan(System.currentTimeMillis(), modes, override, ZoneId.systemDefault())
                FocusDnd.apply(app, active)
                FocusDnd.scheduleNext(app, next)
            } catch (_: Exception) {
                // Storage unavailable: the in-app binder re-applies on the next start.
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ActionCheck = "com.brightblu.parc.action.FOCUS_DND_CHECK"
    }
}
