package com.brightblu.parc.familyupdate

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.brightblu.parc.BuildConfig
import com.brightblu.parc.ParcLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** The one address Parc asks. The release build has no other (see [UpdateOverride]). */
internal object UpdateEndpoint {
    const val Latest = "https://api.github.com/repos/masterdroid-arch/Parc-Launcher/releases/latest"
}

/** Builds the one engine of the process from the real phone: preferences, network, installer and clock. */
internal object UpdateRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var instance: UpdateEngine? = null

    fun engine(context: Context): UpdateEngine = instance ?: synchronized(this) {
        instance ?: create(context.applicationContext).also { instance = it }
    }

    private fun create(app: Context): UpdateEngine = UpdateEngine(
        prefs = UpdatePrefs(app.getSharedPreferences("parc_family_update", Context.MODE_PRIVATE)),
        transport = UpdateHttp(),
        installedVersion = BuildConfig.VERSION_NAME,
        cacheDir = app.cacheDir,
        latestUrl = { UpdateOverride.releaseUrl(app) ?: UpdateEndpoint.Latest },
        handOff = { apk -> openInstaller(app, apk) },
        canInstall = { app.packageManager.canRequestPackageInstalls() },
        askInstallPermission = { openInstallSettings(app) },
        clock = System::currentTimeMillis,
        zone = { ZoneId.systemDefault() },
        scope = scope,
    )

    /** Hands the verified file to Android's package installer. The person still confirms there. */
    private fun openInstaller(app: Context, apk: File): Boolean = try {
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.update", apk)
        app.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = ClipData.newRawUri("parc-update", uri)
        })
        true
    } catch (e: Exception) {
        ParcLog.w("Update", "installer did not open (${e.javaClass.simpleName})")
        false
    }

    /** Android's "install unknown apps" screen for Parc. The person allows it once. */
    private fun openInstallSettings(app: Context) {
        runCatching {
            app.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** Keeps the once-a-day background job in step with the switch. No notification, no constraint beyond a healthy battery. */
internal object UpdateScheduler {
    private const val Name = "parc-update-check"

    fun sync(context: Context, on: Boolean) {
        val work = WorkManager.getInstance(context)
        if (!on) { work.cancelUniqueWork(Name); return }
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build()).build()
        work.enqueueUniquePeriodicWork(Name, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

/** The background check. It asks the engine, which applies the switch and the 24 hour rule. Failures are silent. */
class UpdateCheckWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        UpdateRuntime.engine(applicationContext).backgroundCheck()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ParcLog.w("Update", "background check failed (${e.javaClass.simpleName})")
        Result.success()
    }
}
