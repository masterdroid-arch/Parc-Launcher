package com.brightblu.parc.feature.settings.permissions

/** The optional permissions Parc asks for, each with the plain reason shown next to its state. */
enum class OptionalPermission(val title: String, val reason: String) {
    UsageAccess("Usage access", "Learns which apps you use, and shows screen time and data use."),
    NotificationAccess("Notification access", "Unread dots on icons and the Comms page."),
    NotificationPolicy("Do Not Disturb access", "Lets a Focus mode turn on its own Do Not Disturb rule. Off by default."),
    Contacts("Contacts", "Lets search find people by name or number."),
    Calendar("Calendar", "Shows your next events on the Agenda page and lets search find events by title. Read only; nothing leaves the phone."),
    Notifications("Notifications", "Lets a finished timer on the Timers page ring. Timers still run in Parc without it."),
    Files("Photos, videos and music", "Lets search find your pictures, videos and audio by file name. Names only; nothing is indexed or leaves the phone."),
}

/** Never a guess: [Unknown] is shown as "Can't tell" and still offers the action. */
enum class PermissionState { Granted, NotGranted, Unknown }

/** One line of the Permissions page. Immutable. */
data class PermissionRow(val permission: OptionalPermission, val state: PermissionState) {
    val stateLabel: String get() = when (state) {
        PermissionState.Granted -> "Allowed"
        PermissionState.NotGranted -> "Not allowed"
        PermissionState.Unknown -> "Can't tell"
    }

    /** The button text: nothing to do once granted; contacts and calendar ask in-app, the others open a system page. */
    val actionLabel: String? get() = when {
        state == PermissionState.Granted -> null
        permission == OptionalPermission.Contacts -> "Allow contacts"
        permission == OptionalPermission.Calendar -> "Allow calendar"
        permission == OptionalPermission.Files -> "Allow files"
        permission == OptionalPermission.Notifications -> "Allow notifications"
        else -> "Open ${permission.title.lowercase()} settings"
    }
}

/** Pure rules for the Permissions page and its summary line. */
object PermissionRules {
    /** [files] is true when at least one of the three media permissions is granted (search then reads those types). */
    fun rows(usage: Boolean?, notifications: Boolean?, contacts: Boolean?, calendar: Boolean?, timerNotifications: Boolean?,
        files: Boolean?): List<PermissionRow> = listOf(
        PermissionRow(OptionalPermission.UsageAccess, usage.toState()),
        PermissionRow(OptionalPermission.NotificationAccess, notifications.toState()),
        PermissionRow(OptionalPermission.Contacts, contacts.toState()),
        PermissionRow(OptionalPermission.Calendar, calendar.toState()),
        PermissionRow(OptionalPermission.Notifications, timerNotifications.toState()),
        PermissionRow(OptionalPermission.Files, files.toState()),
    )

    /** "2 of 6 allowed", or "All allowed". */
    fun summary(rows: List<PermissionRow>): String {
        val granted = rows.count { it.state == PermissionState.Granted }
        return if (granted == rows.size) "All allowed" else "$granted of ${rows.size} allowed"
    }

    /** The adb line that grants the temperatures permission (a computer is needed; Android has no screen for it). */
    fun dumpGrantCommand(packageName: String): String = "adb shell pm grant $packageName android.permission.DUMP"

    private fun Boolean?.toState(): PermissionState = when (this) {
        true -> PermissionState.Granted
        false -> PermissionState.NotGranted
        null -> PermissionState.Unknown
    }
}

/** T102: the media permissions the Files row requests together, and the pure rule for its state. */
object FilesPermissionRules {
    val permissions: List<String> = listOf(
        "android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_AUDIO",
    )

    /** Null when nothing could be read; otherwise true when any one of the three is granted. */
    fun state(granted: List<Boolean?>): Boolean? = when {
        granted.any { it == true } -> true
        granted.all { it == null } -> null
        else -> false
    }
}
