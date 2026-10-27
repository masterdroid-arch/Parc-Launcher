package com.brightblu.parc

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import com.brightblu.parc.core.data.lock.AuthAvailability
import com.brightblu.parc.core.data.lock.AuthFailure
import com.brightblu.parc.core.data.lock.AuthOutcome
import com.brightblu.parc.core.data.lock.AuthenticatorRules
import com.brightblu.parc.core.data.lock.LockedApps
import com.brightblu.parc.core.data.lock.UnlockState
import com.brightblu.parc.core.data.lock.UnlockTimeout
import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.Folder
import com.brightblu.parc.model.FolderId
import com.brightblu.parc.model.locked
import com.brightblu.parc.model.withFolderLocked
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** In-memory per-folder unlock windows. Leaving Parc, screen-off and process death relock all. */
internal class FolderLock(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val timeout: () -> UnlockTimeout,
) {
    private val _windows = MutableStateFlow<Map<FolderId, UnlockState>>(emptyMap())
    val windows: StateFlow<Map<FolderId, UnlockState>> = _windows.asStateFlow()
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()
    private var timer: Job? = null
    var onChange: () -> Unit = {}
    // The null-id overload is retained for older fixtures; production always supplies folder ids.
    fun unlock(ids: Set<FolderId> = setOf(FolderId("*"))): Boolean {
        val now = clock()
        _windows.value = _windows.value + ids.associateWith { UnlockState().unlock(now, timeout()) }
        publish()
        return true
    }
    fun isUnlocked(id: FolderId): Boolean =
        (_windows.value[id] ?: _windows.value[FolderId("*")])?.isUnlocked(clock()) == true
    fun relock() {
        timer?.cancel()
        _windows.value = emptyMap()
        _unlocked.value = false
        runCatching { onChange() }
    }
    fun onHostLeft() = relock()
    fun onScreenOff() = relock()
    fun expireIfDue() {
        _windows.value = _windows.value.filterValues { it.isUnlocked(clock()) }
        publish()
    }
    private fun publish() {
        _unlocked.value = _windows.value.isNotEmpty()
        runCatching { onChange() }
        timer?.cancel()
        val remaining = _windows.value.values.mapNotNull { it.remainingMs(clock()) }.minOrNull() ?: return
        timer = scope.launch { delay(remaining); expireIfDue() }
    }

}

/** T98: packages hidden by a locked folder right now; screens that list apps or messages filter with it. */
internal val LocalLockedPackages = androidx.compose.runtime.staticCompositionLocalOf<Set<String>> { emptySet() }

/** OPUS6 (audit F2): every package hidden right now: locked folders' apps plus the Focus block list. */
internal val LocalHiddenPackages = androidx.compose.runtime.staticCompositionLocalOf<Set<String>> { emptySet() }

/** Platform authentication behind a fakeable port. */
internal interface FolderAuthenticator {
    fun availability(): AuthAvailability

    /** Shows the system prompt; [onResult] is called once, on the main thread. */
    fun authenticate(title: String, subtitle: String, onResult: (AuthOutcome) -> Unit)

    fun cancel()
}

/** `android.hardware.biometrics.BiometricPrompt`: biometrics or the device credential. */
internal class AndroidFolderAuthenticator(private val context: () -> Context?) : FolderAuthenticator {
    private var signal: CancellationSignal? = null

    override fun availability(): AuthAvailability = try {
        val ctx = context() ?: return AuthAvailability.TemporarilyUnavailable
        val manager = ctx.getSystemService(BiometricManager::class.java)
        val code = manager?.canAuthenticate(Allowed) ?: AuthenticatorRules.ErrorNoHardware
        val secure = ctx.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
        AuthenticatorRules.availability(code, secure)
    } catch (e: Exception) {
        AuthAvailability.TemporarilyUnavailable
    }

    override fun authenticate(title: String, subtitle: String, onResult: (AuthOutcome) -> Unit) {
        var delivered = false
        fun deliver(outcome: AuthOutcome) {
            if (delivered) return
            delivered = true
            try { onResult(outcome) } catch (e: Exception) { ParcLog.e("Parc", "Lock callback failed", e) }
        }
        try {
            val ctx = context() ?: return deliver(AuthOutcome.Refused(AuthFailure.Unavailable))
            cancel()
            val prompt = BiometricPrompt.Builder(ctx)
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(Allowed)
                .build()
            val cancellation = CancellationSignal()
            signal = cancellation
            prompt.authenticate(cancellation, ctx.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    deliver(AuthOutcome.Success)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    // Documented codes: 5 canceled, 10 user canceled, 13 negative button.
                    val cancelled = errorCode == 5 || errorCode == 10 || errorCode == 13
                    deliver(AuthOutcome.Refused(if (cancelled) AuthFailure.Cancelled else AuthFailure.Failed))
                }
                // onAuthenticationFailed: one bad attempt; the system prompt stays open for another.
            })
        } catch (e: Exception) {
            deliver(AuthOutcome.Refused(AuthFailure.Unavailable))
        }
    }

    override fun cancel() {
        try { signal?.cancel() } catch (e: Exception) { /* already finished */ }
        signal = null
    }

    private companion object {
        const val Allowed = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }
}

/**
 * What the session, the folder editor and the settings page need to know about locked folders, and
 * the one way to ask the user to authenticate. A null gate in the session means "no locking" (older
 * tests, debug fixtures).
 */
interface FolderLockGate {
    /** The folder carries the lock flag (whether or not the window is open). */
    fun isFolderFlagged(id: FolderId): Boolean

    /** The folder is flagged and its apps are hidden right now. */
    fun isFolderLockedNow(id: FolderId): Boolean

    /** The app sits in a locked folder and is hidden right now. */
    fun isAppLocked(id: AppId): Boolean

    /** Show `Locked` instead of a locked folder's name. */
    fun hideName(): Boolean

    /**
     * Asks the user to authenticate. [onSuccess] runs only after a successful authentication; with
     * [openWindow] the unlock window starts first. A refusal (no screen lock, cancelled, failed) tells
     * the user why and never runs [onSuccess].
     */
    fun authenticate(openWindow: Boolean, onSuccess: () -> Unit)

    fun authenticateFolder(id: FolderId, onSuccess: () -> Unit) = authenticate(true, onSuccess)

    /** Ends the unlock window now. */
    fun relock()
}

internal class ServicesFolderLockGate(
    private val lock: FolderLock,
    private val folders: () -> List<Folder>,
    private val apps: () -> List<AppEntry>,
    private val hideName: () -> Boolean,
    private val authenticator: FolderAuthenticator,
    private val notify: (String) -> Unit,
) : FolderLockGate {

    override fun isFolderFlagged(id: FolderId): Boolean = folders().firstOrNull { it.id == id }?.locked == true

    override fun isFolderLockedNow(id: FolderId): Boolean = isFolderFlagged(id) && !lock.isUnlocked(id)

    override fun isAppLocked(id: AppId): Boolean {
        val all = apps()
        val packageName = all.firstOrNull { it.id == id }?.packageName
        val hiddenFolders = folders().filter { !lock.isUnlocked(it.id) }
        val ids = LockedApps.lockedAppIds(hiddenFolders)
        if (id in ids) return true
        return packageName != null && LockedApps.lockedPackages(all, hiddenFolders, false).contains(packageName)
    }

    override fun hideName(): Boolean = hideName.invoke()

    override fun authenticate(openWindow: Boolean, onSuccess: () -> Unit) {
        val availability = authenticator.availability()
        if (!availability.usable) {
            notify(availability.message)
            return
        }
        authenticator.authenticate("Confirm it is you", "Parc will show the apps in locked folders") { outcome ->
            when (outcome) {
                AuthOutcome.Success -> if (!openWindow || lock.unlock(folders().filter { it.locked }.map { it.id }.toSet())) onSuccess()
                is AuthOutcome.Refused ->
                    if (outcome.reason != AuthFailure.Cancelled) notify("Could not confirm it is you")
            }
        }
    }

    override fun authenticateFolder(id: FolderId, onSuccess: () -> Unit) {
        authenticate(false) { if (isFolderFlagged(id)) { lock.unlock(setOf(id)); onSuccess() } }
    }
    override fun relock() = lock.relock()
}

/** Sets or clears a folder's lock flag. The frozen repository has no such mutation, so importLayout validates the document. */
internal suspend fun com.brightblu.parc.model.LayoutRepository.setFolderLockFlag(id: FolderId, locked: Boolean) {
    val snapshot = layout.value
    val next = snapshot.withFolderLocked(id, locked)
    if (next == snapshot) return
    runCatching { importLayout(com.brightblu.parc.model.LayoutJson.encode(next)) }
}

/** One gate per host composable; the prompt needs the activity context. */
@androidx.compose.runtime.Composable
internal fun rememberFolderLockGate(services: LauncherServices): ServicesFolderLockGate {
    val context = androidx.compose.ui.platform.LocalContext.current
    return androidx.compose.runtime.remember(services, context) {
        val prefs = ParcUiPrefs(context)
        ServicesFolderLockGate(services.folderLock,
            folders = { services.presentation.value.layout.folders },
            apps = { services.presentation.value.apps },
            hideName = { prefs.lockHideName() },
            authenticator = AndroidFolderAuthenticator { context },
            notify = { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show() })
    }
}
