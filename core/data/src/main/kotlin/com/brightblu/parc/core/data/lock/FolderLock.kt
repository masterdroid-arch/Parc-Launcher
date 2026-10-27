package com.brightblu.parc.core.data.lock

import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.Folder
import com.brightblu.parc.model.FolderId
import com.brightblu.parc.model.locked
import java.util.Locale

/**
 * T98: how long an unlock lasts. [millis] is null for [UntilScreenOff], which has no timer (the
 * unlock still ends when Parc is left). [id] is the persisted spelling.
 */
enum class UnlockTimeout(val id: String, val label: String, val millis: Long?) {
    Seconds30("30s", "30 seconds", 30_000L),
    Minute1("1m", "1 minute", 60_000L),
    Minutes5("5m", "5 minutes", 300_000L),
    UntilScreenOff("screen_off", "Until the screen turns off", null);

    companion object {
        val Default = Minute1

        /** An unknown or missing stored value falls back to the default instead of failing. */
        fun fromId(id: String?): UnlockTimeout = entries.firstOrNull { it.id == id } ?: Default
    }
}

/**
 * The unlock window as an immutable value. Pure: the caller passes a monotonic clock reading, so the
 * rules are tested with a fake clock. A clock reading that moves backwards counts as expired.
 */
data class UnlockState(val unlockedAtMs: Long? = null, val timeout: UnlockTimeout = UnlockTimeout.Default) {

    fun isUnlocked(nowMs: Long): Boolean {
        val at = unlockedAtMs ?: return false
        if (nowMs < at) return false
        val limit = timeout.millis ?: return true
        return nowMs - at < limit
    }

    /** Milliseconds left, null when locked or when the window has no timer. */
    fun remainingMs(nowMs: Long): Long? {
        if (!isUnlocked(nowMs)) return null
        val limit = timeout.millis ?: return null
        return limit - (nowMs - unlockedAtMs!!)
    }

    fun unlock(nowMs: Long, timeout: UnlockTimeout = this.timeout) = UnlockState(nowMs, timeout)

    fun relock() = copy(unlockedAtMs = null)
}

/** Result of asking the platform whether the device can authenticate the user. */
enum class AuthAvailability(val usable: Boolean, val message: String) {
    Available(true, ""),
    NoSecureLock(false, "Set a PIN, pattern, password or fingerprint in Android Settings first. Without a screen lock, a folder cannot be locked."),
    NoHardware(false, "This phone has no screen lock or biometric sensor that Parc can use, so a folder cannot be locked."),
    TemporarilyUnavailable(false, "Screen lock and biometrics are not available right now. Try again in a moment."),
    UpdateRequired(false, "A security update is needed before the screen lock can be used.");
}

/**
 * Maps the platform's `BiometricManager.canAuthenticate` code (asked for weak biometrics or the
 * device credential) to [AuthAvailability]. The codes are the documented constants, written out so
 * this stays testable without Android.
 */
object AuthenticatorRules {
    const val Success = 0
    const val ErrorHwUnavailable = 1
    const val ErrorNoneEnrolled = 11
    const val ErrorNoHardware = 12
    const val ErrorSecurityUpdateRequired = 15
    const val ErrorUnsupported = -2
    const val StatusUnknown = -1

    fun availability(code: Int, deviceSecure: Boolean): AuthAvailability = when (code) {
        Success -> if (deviceSecure) AuthAvailability.Available else AuthAvailability.NoSecureLock
        ErrorNoneEnrolled -> AuthAvailability.NoSecureLock
        ErrorHwUnavailable -> AuthAvailability.TemporarilyUnavailable
        ErrorSecurityUpdateRequired -> AuthAvailability.UpdateRequired
        ErrorNoHardware, ErrorUnsupported -> if (deviceSecure) AuthAvailability.NoHardware else AuthAvailability.NoSecureLock
        else -> if (deviceSecure) AuthAvailability.TemporarilyUnavailable else AuthAvailability.NoSecureLock
    }
}

/** Why an authentication ended without success. */
enum class AuthFailure { Cancelled, Failed, Unavailable }

/** Outcome of one authentication request. */
sealed interface AuthOutcome {
    data object Success : AuthOutcome
    data class Refused(val reason: AuthFailure, val message: String = "") : AuthOutcome
}

/**
 * Visibility rules for locked folders (T98). One set of packages is the only thing every surface
 * needs: it is empty while the unlock window is open (or nothing is locked), and otherwise holds the
 * packages of every app that sits in a locked folder. The rule is by package so a work-profile copy,
 * a pin on an arc and a second activity of the same app all disappear together.
 */
object LockedApps {

    /** Projection only: preserve the stored name and membership. */
    fun folderForDisplay(folder: Folder, lockedNow: Boolean, hideName: Boolean): Folder =
        if (lockedNow) folder.copy(name = if (hideName) LockedFolderText.HiddenName else folder.name, appIds = emptyList()) else folder

    /** Apps that belong to at least one locked folder. */
    fun lockedAppIds(folders: List<Folder>): Set<AppId> =
        folders.filter { it.locked }.flatMapTo(LinkedHashSet()) { it.appIds }

    /** Locked folders whose apps are hidden right now; empty while unlocked. */
    fun lockedFoldersNow(folders: List<Folder>, unlocked: Boolean): Set<FolderId> =
        if (unlocked) emptySet() else folders.filter { it.locked }.mapTo(LinkedHashSet()) { it.id }

    /** Packages hidden right now. Empty while unlocked or when no folder is locked. */
    fun lockedPackages(apps: List<AppEntry>, folders: List<Folder>, unlocked: Boolean): Set<String> {
        if (unlocked) return emptySet()
        val ids = lockedAppIds(folders)
        if (ids.isEmpty()) return emptySet()
        return ids.mapTo(LinkedHashSet()) { it.value.substringBefore('/') }.also { packages ->
            apps.filter { it.id in ids }.mapTo(packages) { it.packageName }
        }
    }

    /** The apps that stay visible. Returns the same list instance when nothing is hidden. */
    fun visibleApps(apps: List<AppEntry>, lockedPackages: Set<String>): List<AppEntry> =
        if (lockedPackages.isEmpty()) apps else apps.filterNot { it.packageName in lockedPackages }

    /** Count per package without the hidden ones (badges, menu counts). */
    fun visibleCounts(counts: Map<String, Int>, lockedPackages: Set<String>): Map<String, Int> =
        if (lockedPackages.isEmpty()) counts else counts.filterKeys { it !in lockedPackages }

    /**
     * Whether a remembered search text would reveal a hidden app. Recent searches are matched
     * against the hidden apps' labels in both directions; a short query is compared as a fragment.
     */
    fun queryRevealsLockedApp(query: String, lockedLabels: Collection<String>): Boolean {
        if (lockedLabels.isEmpty()) return false
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return false
        return lockedLabels.any { label ->
            val l = label.trim().lowercase(Locale.ROOT)
            l.isNotEmpty() && (l.contains(q) || q.contains(l))
        }
    }
}

/** T98 settings defaults and the label shown for a locked folder whose name is hidden. */
object LockedFolderText {
    const val HiddenName = "Locked"

    /** Root row detail of the settings page. */
    fun summary(lockedCount: Int): String = when (lockedCount) {
        0 -> "None locked"
        1 -> "1 locked"
        else -> "$lockedCount locked"
    }

    /** Shown on the settings page and in the lock prompts: what the lock does and does not do. */
    const val Honesty = "This hides the apps inside Parc only. Nothing is encrypted, and the apps stay " +
        "available from outside Parc, including other launchers. Android’s notification shade and system Recents can still show them. " +
        "Backups of locked folders are obfuscated, not encrypted."
}
