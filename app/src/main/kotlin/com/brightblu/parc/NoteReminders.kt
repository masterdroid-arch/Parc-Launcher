package com.brightblu.parc

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.media.AudioAttributes
import android.media.RingtoneManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * T183: note reminders on the same alarm and notification path as the timers. One alarm per note,
 * delivered to [TimerReceiver]. The `play` build, and any phone that refuses exact alarms, uses an inexact
 * alarm (the label then says "around"). After a reboot the alarms are armed again when Parc next starts,
 * exactly like timers; no boot receiver and no `RECEIVE_BOOT_COMPLETED`.
 */
internal class AndroidNoteAlarms(private val context: Context) : NoteReminderAlarms {
    override fun schedule(noteId: String, at: Long) {
        try {
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            val pending = NoteReminders.firePending(context, noteId)
            if (TimerScheduler.canScheduleExact(context)) {
                try { alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending); return }
                catch (e: SecurityException) { ParcLog.w("Notes", "exact alarm refused, using an inexact one", e) }
            }
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } catch (e: Exception) { ParcLog.e("Notes", "reminder scheduling failed", e) }
    }
    override fun cancel(noteId: String) {
        try {
            context.getSystemService(AlarmManager::class.java)?.cancel(NoteReminders.firePending(context, noteId))
            context.getSystemService(NotificationManager::class.java)?.cancel(NoteReminders.Tag, NoteReminders.notificationId(noteId))
        } catch (e: Exception) { ParcLog.e("Notes", "reminder cancel failed", e) }
    }
}

internal object NoteReminders {
    const val ChannelId = "note_reminders"
    const val Tag = "note"
    const val Title = "Reminder"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
        ParcLog.e("Notes", "reminder work failed", error)
    })

    fun notificationId(noteId: String): Int = noteId.hashCode()

    fun firePending(context: Context, noteId: String): PendingIntent = PendingIntent.getBroadcast(
        context, noteId.hashCode(),
        Intent(context, TimerReceiver::class.java).setAction(TimerReceiver.ActionNote)
            .setData(Uri.parse("parc-note://reminder/" + Uri.encode(noteId))).putExtra(NoteOpenRequests.ExtraNoteId, noteId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** The intent that opens Parc on one note. Used by the reminder and by a finished note timer. */
    fun openIntent(context: Context, noteId: String): Intent =
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(NoteOpenRequests.ExtraNoteId, noteId)

    fun openPending(context: Context, noteId: String, requestBase: Int): PendingIntent = PendingIntent.getActivity(
        context, requestBase + noteId.hashCode(), openIntent(context, noteId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** At app start: arm the alarms again and show the reminders that came due while nothing ran. */
    fun reconcile(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val store = NotesStore.get(app)
            val missed = store.rearmReminders(System.currentTimeMillis())
            missed.forEach { fire(app, it.id) }
        }
    }

    /** The alarm for [noteId] went off (or was missed): clear the reminder and notify. */
    suspend fun fire(context: Context, noteId: String) {
        val app = context.applicationContext
        val note = NotesStore.get(app).fireReminder(noteId, System.currentTimeMillis()) ?: return
        notify(app, note)
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(ChannelId) != null) return
        nm.createNotificationChannel(NotificationChannel(ChannelId, "Note reminders", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Reminds you of a note at the time you chose"
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        })
    }

    private fun notify(context: Context, note: Note) {
        if (!TimerNotifier.notificationsAllowed(context)) return
        try {
            ensureChannel(context)
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val line = NoteTitles.display(note)
            val builder = { text: String ->
                Notification.Builder(context, ChannelId).setSmallIcon(R.drawable.ic_timer)
                    .setContentTitle(Title).setContentText(text)
                    .setCategory(Notification.CATEGORY_REMINDER)
                    .setContentIntent(openPending(context, note.id, OpenRequestBase))
                    .setAutoCancel(true)
            }
            // On a locked screen the note's words stay hidden.
            val notification = builder(line).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(builder("A note").setVisibility(Notification.VISIBILITY_PUBLIC).build()).build()
            nm.notify(Tag, notificationId(note.id), notification)
        } catch (e: Exception) { ParcLog.e("Notes", "reminder notification failed", e) }
    }

    private const val OpenRequestBase = 300_000
}
