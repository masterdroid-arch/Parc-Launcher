package com.brightblu.parc.feature.settings.permissions

import android.Manifest
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.brightblu.parc.core.data.notifications.ParcNotificationListenerService
import com.brightblu.parc.feature.settings.notifications.NotificationAccessLink
import com.brightblu.parc.feature.settings.notifications.NotificationAccessStatus
import com.brightblu.parc.feature.settings.onboarding.AndroidOnboardingIntents
import com.brightblu.parc.feature.settings.onboarding.OnboardingTarget
import com.brightblu.parc.feature.settings.ui.SettingKind
import com.brightblu.parc.feature.settings.ui.SettingRow
import com.brightblu.parc.feature.settings.ui.SettingsCard
import com.brightblu.parc.feature.settings.ui.SettingsSection
import com.brightblu.parc.feature.settings.ui.settingsText
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin

/** Reads each permission from the platform; null when a source cannot answer. No permission is needed to ask. */
object AndroidPermissionReader {
    fun usage(context: Context): Boolean? = runCatching {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return@runCatching null
        ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }.getOrNull()

    fun notifications(context: Context): Boolean? = runCatching {
        val listener = ComponentName(context, ParcNotificationListenerService::class.java)
        NotificationAccessLink.of(context, listener).status == NotificationAccessStatus.Granted
    }.getOrNull()

    fun contacts(context: Context): Boolean? = runCatching {
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }.getOrNull()

    fun calendar(context: Context): Boolean? = runCatching {
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    }.getOrNull()

    /** T102: true when any of the three media permissions is granted. */
    fun files(context: Context): Boolean? = FilesPermissionRules.state(FilesPermissionRules.permissions.map { permission ->
        runCatching { context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED }.getOrNull()
    })

    /** T101: the Android 13 notification permission (also false when the user switched notifications off for Parc). */
    fun postNotifications(context: Context): Boolean? = runCatching {
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            context.getSystemService(android.app.NotificationManager::class.java)?.areNotificationsEnabled() != false
    }.getOrNull()

    fun dump(context: Context): Boolean? = runCatching {
        context.checkSelfPermission("android.permission.DUMP") == PackageManager.PERMISSION_GRANTED
    }.getOrNull()

    fun rows(context: Context): List<PermissionRow> =
        PermissionRules.rows(usage(context), notifications(context), contacts(context), calendar(context), postNotifications(context), files(context)) + PermissionRow(
            OptionalPermission.NotificationPolicy, when (context.getSystemService(android.app.NotificationManager::class.java)?.isNotificationPolicyAccessGranted) {
                true -> PermissionState.Granted; false -> PermissionState.NotGranted; null -> PermissionState.Unknown
            })
}

/** The Permissions page body (T86): live status, a plain reason and one action per permission. Content only. */
@Composable
fun PermissionsContent(skin: Skin) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val contactsRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    // T100: the calendar permission is a runtime request. After a permanent refusal the system shows
    // no dialog, so the app's own settings page opens instead.
    val calendarRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refresh++
        if (!granted) runCatching {
            val activity = context as? android.app.Activity
            if (activity != null && !activity.shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR)) {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", context.packageName, null)))
            }
        }
    }
    // T101: POST_NOTIFICATIONS is a runtime permission. After a permanent refusal the system shows no dialog,
    // so Parc's notification settings page opens instead.
    val notificationsRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refresh++
        if (!granted) runCatching {
            val activity = context as? android.app.Activity
            if (activity != null && !activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
        }
    }
    // T102: the three media permissions are asked together. If none is granted afterwards and the system
    // shows no more dialogs, Parc's own settings page opens so the user can allow them there.
    val filesRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        refresh++
        if (results.values.none { it }) runCatching {
            val activity = context as? android.app.Activity
            if (activity != null && FilesPermissionRules.permissions.none { activity.shouldShowRequestPermissionRationale(it) }) {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", context.packageName, null)))
            }
        }
    }
    val personal = remember(context) { com.brightblu.parc.core.data.DistributionFeatures.personal(context) }
    val rows = remember(refresh) { AndroidPermissionReader.rows(context).filter {
        (it.permission != OptionalPermission.Contacts || com.brightblu.parc.core.data.DistributionFeatures.contacts(context)) &&
            (it.permission != OptionalPermission.Files || com.brightblu.parc.core.data.DistributionFeatures.files(context))
    } }
    val dump = remember(refresh) { if (personal) AndroidPermissionReader.dump(context) else null }
    val clipboard = LocalClipboardManager.current

    Column {
        BasicText("Every permission here is optional. Parc works without them, and each feature that needs one says so.",
            Modifier.padding(vertical = Metrics.gridDp.dp * 2), style = settingsText(skin, skin.colors.textSecondary, true))
        SettingsSection(PermissionRules.summary(rows), skin)
        SettingsCard(skin) {
            rows.forEach { row ->
                val action = row.actionLabel
                SettingRow(row.permission.title, skin, if (action == null) SettingKind.Choice else SettingKind.Action,
                    description = "${row.stateLabel}. ${row.permission.reason}", value = if (action == null) "✓" else "",
                    icon = "›") {
                    when (row.permission) {
                        OptionalPermission.UsageAccess -> AndroidOnboardingIntents.open(context, OnboardingTarget.UsageAccess)
                        OptionalPermission.NotificationAccess -> AndroidOnboardingIntents.open(context, OnboardingTarget.NotificationAccess)
                        OptionalPermission.NotificationPolicy -> context.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                        OptionalPermission.Contacts -> contactsRequest.launch(Manifest.permission.READ_CONTACTS)
                        OptionalPermission.Calendar -> calendarRequest.launch(Manifest.permission.READ_CALENDAR)
                        OptionalPermission.Files -> filesRequest.launch(FilesPermissionRules.permissions.toTypedArray())
                        OptionalPermission.Notifications -> {
                            val allowedByApp = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                            if (allowedByApp) context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
                            else notificationsRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }
            }
        }
        if (!personal) {
            SettingsSection("Temperatures", skin)
            BasicText("This build uses readings Android makes available. Extra temperature diagnostics through a computer are available in the personal build.",
                Modifier.padding(Metrics.gridDp.dp * 4), style = settingsText(skin, skin.colors.textSecondary, true))
        }
        if (personal) {
            SettingsSection("Advanced: temperatures", skin)
            SettingsCard(skin) {
                val command = PermissionRules.dumpGrantCommand(context.packageName)
                BasicText(
                    "CPU and GPU readings need one permission that Android only lets a computer grant. " +
                        "Status: ${when (dump) { true -> "allowed"; false -> "not allowed"; null -> "can't tell" }}. " +
                        "Connect the phone over USB or wireless debugging and run:",
                    Modifier.padding(Metrics.gridDp.dp * 4), style = settingsText(skin, skin.colors.textSecondary, true))
                BasicText(command, Modifier.padding(horizontal = Metrics.gridDp.dp * 4), style = settingsText(skin, skin.colors.accent, true))
                SettingRow("Copy the command", skin, SettingKind.Action, icon = "⧉") { clipboard.setText(AnnotatedString(command)) }
            }
        }
    }
}
