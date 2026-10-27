package com.brightblu.parc

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.brightblu.parc.core.data.timers.TimerBook
import com.brightblu.parc.core.data.timers.TimerCodec
import com.brightblu.parc.core.data.timers.TimerFormat
import com.brightblu.parc.core.data.timers.TimerRules
import com.brightblu.parc.core.data.timers.TimerStatus
import com.brightblu.parc.core.data.timers.TimersState
import com.brightblu.parc.ring.FeelPrefs
import com.brightblu.parc.ring.HapticPreset
import com.brightblu.parc.ring.Ringer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.IOException

internal val Context.timersDataStore by preferencesDataStore(name = "timers",
    corruptionHandler = com.brightblu.parc.core.data.ParcStoreRescue.handler("timers"))

/**
 * T101 app-local store: the timer book and the stopwatch as plain text, plus whether the
 * notification permission was already asked. Every change goes through [update], one atomic
 * DataStore edit, so the page and the alarm receiver never overwrite each other.
 */
internal class TimersStore(context: Context) {
    private val store = context.applicationContext.timersDataStore
    private val bookKey = stringPreferencesKey("timer_book")
    private val stopwatchKey = stringPreferencesKey("stopwatch")
    private val askedKey = booleanPreferencesKey("notification_asked")

    private fun Preferences.toState() = TimersState(TimerCodec.decodeBook(this[bookKey]), TimerCodec.decodeStopwatch(this[stopwatchKey]))

    val state: Flow<TimersState> = store.data.map { it.toState() }.catch {
        if (it is IOException) emit(TimersState()) else throw it
    }
    val asked: Flow<Boolean> = store.data.map { it[askedKey] == true }.catch {
        if (it is IOException) emit(false) else throw it
    }

    /** Applies [change] atomically and returns the state before and after. */
    suspend fun update(change: (TimersState) -> TimersState): Pair<TimersState, TimersState> {
        var before = TimersState()
        var after = TimersState()
        store.edit { prefs ->
            before = prefs.toState()
            after = change(before)
            prefs[bookKey] = TimerCodec.encodeBook(after.book)
            prefs[stopwatchKey] = TimerCodec.encodeStopwatch(after.stopwatch)
        }
        return before to after
    }

    suspend fun markAsked() { store.edit { it[askedKey] = true } }
}

/** Pure choices for the alert: kept free of the platform so they are unit-tested. */
internal object TimerAlertRules {
    /** Vibration pattern for the Feel haptic preset; null for Off or a silent ringer. */
    fun vibration(haptic: HapticPreset, ringer: Ringer): LongArray? = when {
        haptic == HapticPreset.Off || ringer == Ringer.Silent -> null
        haptic == HapticPreset.Soft -> longArrayOf(0, 120, 140, 120, 140, 120)
        haptic == HapticPreset.Crisp -> longArrayOf(0, 250, 150, 250, 150, 250)
        else -> longArrayOf(0, 450, 150, 450, 150, 450)
    }

    const val Title = "Timer done"
    fun text(totalMs: Long): String = "${TimerFormat.duration(totalMs)} timer finished"

    /** Notification id for timer [id]; stable so a repeat replaces instead of stacking. */
    fun notificationId(id: Int): Int = NotificationBase + id

    const val NotificationBase = 7100
}

/**
 * Entry point for every change to timers or the stopwatch. It settles finished timers, persists,
 * reschedules the system alarms to match and rings anything that just finished. Safe to call from
 * the page, the receiver or app start; nothing here lets an exception out.
 */
internal object TimerEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
        ParcLog.e("Timers", "background timer work failed", error)
    })

    /** Fire and forget from the UI. */
    fun launch(context: Context, change: (TimersState, Long) -> TimersState = { s, _ -> s }) {
        val app = context.applicationContext
        scope.launch { apply(app, change) }
    }

    fun launchMarkAsked(context: Context) {
        val app = context.applicationContext
        scope.launch { TimersStore(app).markAsked() }
    }

    suspend fun apply(context: Context, change: (TimersState, Long) -> TimersState = { s, _ -> s }): TimersState? = try {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val (before, after) = TimersStore(app).update { st ->
            val settled = st.copy(book = st.book.settle(now))
            change(settled, now)
        }
        TimerScheduler.sync(app, after.book)
        TimerNotifier.cancelStale(app, after.book)
        val finished = after.book.newlyFinished(before.book)
        if (finished.isNotEmpty()) TimerNotifier.ring(app, after.book, finished)
        after
    } catch (e: Exception) {
        ParcLog.e("Timers", "timer update failed", e)
        null
    }

    /** At app start: settle timers that ended while nothing ran (for example across a reboot) and re-arm the alarms. */
    fun reconcile(context: Context) = launch(context)
}

/** Schedules one `AlarmManager.setAlarmClock` per running timer, delivered to [TimerReceiver]. */
internal object TimerScheduler {
    fun firePending(context: Context, id: Int): PendingIntent = PendingIntent.getBroadcast(
        context, id, Intent(context, TimerReceiver::class.java).setAction(TimerReceiver.ActionFire).putExtra(TimerReceiver.ExtraId, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun showPending(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun canScheduleExact(context: Context): Boolean = try {
        BuildConfig.FLAVOR != "play" && context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    } catch (e: Exception) { false }

    /** Makes the system alarms match [book]: armed for running timers, cancelled for the rest. Idempotent. */
    fun sync(context: Context, book: TimerBook) {
        try {
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            for (id in 1..TimerRules.MaxTimers) {
                val timer = book.find(id)
                val pending = firePending(context, id)
                if (timer == null || timer.status != TimerStatus.Running) { alarms.cancel(pending); continue }
                schedule(context, alarms, timer.endAtMs, pending)
            }
        } catch (e: Exception) {
            ParcLog.e("Timers", "scheduling failed", e)
        }
    }

    private fun schedule(context: Context, alarms: AlarmManager, endAtMs: Long, pending: PendingIntent) {
        val exact = canScheduleExact(context)
        if (exact) {
            try {
                alarms.setAlarmClock(AlarmManager.AlarmClockInfo(endAtMs, showPending(context)), pending)
                return
            } catch (e: SecurityException) {
                ParcLog.w("Timers", "exact alarm refused, using an inexact one", e)
            }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAtMs, pending)
    }
}

/** The `Timers` notification channel and the finished-timer notification. */
internal object TimerNotifier {
    const val ChannelId = "timers"
    private const val Tag = "timer"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(ChannelId) != null) return
        val channel = NotificationChannel(ChannelId, "Timers", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Rings when a countdown timer finishes"
            // The system plays the sound (so the ringer mode and Do Not Disturb apply); the vibration is
            // done by the receiver from the Feel haptic choice, so the channel itself stays silent in that respect.
            enableVibration(false)
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        }
        nm.createNotificationChannel(channel)
    }

    fun notificationsAllowed(context: Context): Boolean = try {
        context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true
    } catch (e: Exception) { false }

    /** Removes notifications of timers that are deleted, reset or running again. */
    fun cancelStale(context: Context, book: TimerBook) {
        try {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            for (id in 1..TimerRules.MaxTimers) {
                if (book.find(id)?.status != TimerStatus.Finished) nm.cancel(Tag, TimerAlertRules.notificationId(id))
            }
        } catch (e: Exception) {
            ParcLog.e("Timers", "clearing notifications failed", e)
        }
    }

    fun dismiss(context: Context, id: Int) {
        try { context.getSystemService(NotificationManager::class.java)?.cancel(Tag, TimerAlertRules.notificationId(id)) }
        catch (e: Exception) { ParcLog.e("Timers", "dismiss failed", e) }
    }

    /** Posts the notification (sound until dismissed or one minute) and vibrates by the Feel choice. */
    suspend fun ring(context: Context, book: TimerBook, ids: List<Int>) {
        vibrate(context)
        if (!notificationsAllowed(context)) return
        try {
            ensureChannel(context)
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            ids.forEach { id ->
                val timer = book.find(id) ?: return@forEach
                val open = PendingIntent.getActivity(context, 200 + id,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                val dismiss = PendingIntent.getBroadcast(context, 100 + id,
                    Intent(context, TimerReceiver::class.java).setAction(TimerReceiver.ActionDismiss).putExtra(TimerReceiver.ExtraId, id),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                val notification = Notification.Builder(context, ChannelId)
                    .setSmallIcon(R.drawable.ic_timer)
                    .setContentTitle(TimerAlertRules.Title)
                    .setContentText(TimerAlertRules.text(timer.totalMs))
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setWhen(System.currentTimeMillis()).setShowWhen(true)
                    .setTimeoutAfter(TimerRules.RingMs)
                    .addAction(Notification.Action.Builder(null, "Dismiss", dismiss).build())
                    .build()
                // Repeat the sound until the notification is dismissed; the timeout above ends it after a minute.
                notification.flags = notification.flags or Notification.FLAG_INSISTENT
                nm.notify(Tag, TimerAlertRules.notificationId(id), notification)
            }
        } catch (e: Exception) {
            ParcLog.e("Timers", "notification failed", e)
        }
    }

    private suspend fun vibrate(context: Context) {
        try {
            val feel: FeelPrefs = FeelStore(context).prefs.first()
            val audio = context.getSystemService(AudioManager::class.java)
            val ringer = Ringer.fromAndroid(audio?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL)
            val pattern = TimerAlertRules.vibration(feel.haptic, ringer) ?: return
            val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
            if (!vibrator.hasVibrator()) return
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1),
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_NOTIFICATION))
        } catch (e: Exception) {
            ParcLog.e("Timers", "vibration failed", e)
        }
    }
}

/**
 * Delivered by `AlarmManager` when a timer ends and by the notification's Dismiss action. It runs
 * inside the app process, so nothing is allowed to escape: all work is in a bounded coroutine that
 * catches everything and always finishes the broadcast.
 */
class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = try { goAsync() } catch (e: Exception) { null }
        try {
            val app = context.applicationContext
            val action = intent.action
            val id = intent.getIntExtra(ExtraId, 0)
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    withTimeout(WorkLimitMs) {
                        when (action) {
                            ActionFire -> TimerEngine.apply(app)
                            ActionDismiss -> TimerNotifier.dismiss(app, id)
                            else -> Unit
                        }
                    }
                } catch (e: Exception) {
                    ParcLog.e("Timers", "receiver work failed", e)
                } finally {
                    try { pending?.finish() } catch (_: Exception) { }
                }
            }
        } catch (e: Exception) {
            ParcLog.e("Timers", "receiver failed", e)
            try { pending?.finish() } catch (_: Exception) { }
        }
    }

    companion object {
        const val ActionFire = "com.brightblu.parc.TIMER_FIRE"
        const val ActionDismiss = "com.brightblu.parc.TIMER_DISMISS"
        const val ExtraId = "timer_id"
        private const val WorkLimitMs = 8_000L
    }
}
