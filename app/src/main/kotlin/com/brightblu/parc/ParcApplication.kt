package com.brightblu.parc

import android.app.Application
import android.content.Intent
import android.os.Process
import kotlin.system.exitProcess

class ParcApplication : Application() {
    // AndroidX Startup requires an Application here; its storage still delegates to our base.
    override fun getApplicationContext(): android.content.Context = this

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(DemoMode.attach(base))
    }

    override fun onCreate() {
        super.onCreate()
        // OPUS6 (audit F6): a damaged preferences file is copied aside before its store restarts empty.
        com.brightblu.parc.core.data.ParcStoreRescue.dataStoreDir = java.io.File(filesDir, "datastore")
        com.brightblu.parc.core.data.ParcStoreRescue.onRescued = { _ -> ParcLog.e("Storage", "damaged settings file reset", null) }
        // Safe mode has its own process and must never install the failing renderer's handler.
        if (getProcessName() != packageName) return
        // T101: after a restart or reboot, finish timers that ended meanwhile and re-arm the rest.
        try { TimerEngine.reconcile(this) } catch (e: Exception) { ParcLog.e("Timers", "reconcile failed", e) }
        // T183: same for note reminders (arm again, show the ones that came due).
        try { NoteReminders.reconcile(this) } catch (e: Exception) { ParcLog.e("Notes", "reminder reconcile failed", e) }
        // T266: a weekly job the user turned on is re-armed at every start, so a reboot cannot leave
        // it unscheduled. Turning it off cancels the same unique job.
        try { WeeklyBackupScheduler.sync(this) } catch (e: Exception) { ParcLog.e("Backup", "weekly schedule sync failed", e) }
        val guard = CrashGuard(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                try {
                    ParcLog.e("Crash", "uncaught exception", error)
                    guard.saveTrace(thread, error)
                } catch (_: Exception) {
                    // Diagnostics must never get in the way of the crash path.
                }
                if (guard.record()) {
                    startActivity(Intent(this, SafeModeActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                }
            } catch (_: Exception) {
                // Always preserve Android's original crash reporting/termination path.
            } finally {
                if (previous != null) previous.uncaughtException(thread, error)
                else { Process.killProcess(Process.myPid()); exitProcess(10) }
            }
        }
    }
}
