package com.brightblu.parc.core.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import com.brightblu.parc.core.data.profiles.LaunchOutcome
import com.brightblu.parc.core.data.profiles.classifyProfile
import com.brightblu.parc.core.data.profiles.personalOnlyProfileSet

/**
 * Production [LauncherSource] over the system LauncherApps service (T74).
 *
 * The app list covers every profile `LauncherApps.getProfiles()` exposes: the calling user plus a
 * managed (work) profile, and any further users the service hands to the launcher. User serials are
 * resolved through [UserManager]; no profile is assumed to be current. Pause state is the profile's
 * quiet mode. Every serial-taking call resolves that serial to its own [UserHandle], so launching,
 * shortcuts and icons never leak towards the calling user.
 */
internal class AndroidLauncherSource(
    private val context: Context,
) : LauncherSource {
    private val launcherApps =
        context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    private val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
    private val currentUser: UserHandle = Process.myUserHandle()

    /** Serial for the calling user; the icon-pack fallback still resolves its own icon here. */
    internal val currentUserSerial: Long = serialOf(currentUser) ?: 0L

    @Volatile
    private var cachedBySerial: Map<Long, List<LauncherActivityInfo>> = emptyMap()

    private val callbacks = HashMap<LauncherCallback, LauncherApps.Callback>()

    override fun activities(): List<SourceActivity> {
        val bySerial = HashMap<Long, List<LauncherActivityInfo>>()
        val result = ArrayList<SourceActivity>()
        for (handle in launcherProfiles()) {
            val serial = serialOf(handle) ?: continue
            val list = activityList(null, handle)
            bySerial[serial] = list
            for (info in list) {
                result += SourceActivity(
                    component = info.componentName.flattenToString(),
                    packageName = info.componentName.packageName,
                    label = info.label.toString(),
                    isSystem = info.applicationInfo?.let {
                        (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    } ?: false,
                    installTime = info.firstInstallTime,
                    userSerial = serial,
                )
            }
        }
        cachedBySerial = bySerial
        return result
    }

    override fun profiles(): SourceProfileSet {
        val managedSerials = try {
            userManager.getUserProfiles()
                .asSequence()
                .mapNotNull { serialOf(it) }
                .filter { it != currentUserSerial }
                .toSet()
        } catch (e: RuntimeException) {
            emptySet()
        }
        val profiles = launcherProfiles().mapNotNull { handle ->
            val serial = serialOf(handle) ?: return@mapNotNull null
            SourceProfile(
                serial = serial,
                kind = classifyProfile(serial, currentUserSerial, managedSerials),
                paused = isPaused(handle),
            )
        }
        return if (profiles.isEmpty()) {
            personalOnlyProfileSet(currentUserSerial)
        } else {
            SourceProfileSet(currentUserSerial, profiles)
        }
    }

    override fun registerCallback(callback: LauncherCallback) {
        val bridge = object : LauncherApps.Callback() {
            override fun onPackageAdded(packageName: String, user: UserHandle) =
                callback.onPackagesChanged()

            override fun onPackageRemoved(packageName: String, user: UserHandle) =
                callback.onPackagesChanged()

            override fun onPackageChanged(packageName: String, user: UserHandle) =
                callback.onPackagesChanged()

            override fun onPackagesAvailable(
                packageNames: Array<out String>,
                user: UserHandle,
                replacing: Boolean,
            ) = callback.onPackagesChanged()

            override fun onPackagesUnavailable(
                packageNames: Array<out String>,
                user: UserHandle,
                replacing: Boolean,
            ) = callback.onPackagesChanged()

            override fun onPackagesSuspended(packageNames: Array<out String>, user: UserHandle) =
                callback.onPackagesChanged()

            override fun onPackagesUnsuspended(packageNames: Array<out String>, user: UserHandle) =
                callback.onPackagesChanged()
        }
        callbacks[callback] = bridge
        launcherApps.registerCallback(bridge)
    }

    override fun unregisterCallback(callback: LauncherCallback) {
        callbacks.remove(callback)?.let { launcherApps.unregisterCallback(it) }
    }

    override fun startMainActivity(component: String, userSerial: Long): LaunchOutcome {
        val target = ComponentName.unflattenFromString(component) ?: return LaunchOutcome.Unavailable
        val handle = userForSerial(userSerial) ?: return LaunchOutcome.ProfileRemoved
        if (isPaused(handle)) return LaunchOutcome.ProfilePaused
        return try {
            launcherApps.startMainActivity(target, handle, null, null)
            LaunchOutcome.Started
        } catch (e: RuntimeException) {
            // A vanished or paused profile, a dead profile binder, or a refused start.
            failure(userSerial, handle)
        }
    }

    override fun shortcuts(component: String, userSerial: Long): List<SourceShortcut> {
        val target = ComponentName.unflattenFromString(component) ?: return emptyList()
        val handle = userForSerial(userSerial) ?: return emptyList()
        val query = LauncherApps.ShortcutQuery()
            .setPackage(target.packageName)
            .setActivity(target)
            .setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC,
            )
        val shortcuts = try {
            launcherApps.getShortcuts(query, handle)
        } catch (e: RuntimeException) {
            // No host permission, a paused profile or a dead profile binder: no shortcuts.
            return emptyList()
        }
        return shortcuts.orEmpty().mapNotNull { info ->
            val label = (info.shortLabel ?: info.longLabel)?.toString() ?: return@mapNotNull null
            SourceShortcut(info.id, label)
        }
    }

    override fun hasShortcutHostPermission(): Boolean = launcherApps.hasShortcutHostPermission()

    override fun renderIcon(component: String, userSerial: Long, sizePx: Int): Bitmap? {
        val info = findActivity(component, userSerial) ?: return null
        val drawable = info.getIcon(context.resources.displayMetrics.densityDpi) ?: return null
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // Adaptive icons are drawn unmasked (background then foreground, full 108 dp canvas scaled so
        // the 72 dp safe zone fills the square); Parc applies its own circle at draw time. Legacy
        // icons are inset to the same safe-zone proportion.
        if (drawable is AdaptiveIconDrawable) {
            val bounds = IconRasterGeometry.adaptiveLayerBounds(sizePx).toIntBounds()
            drawable.background?.let { drawLayer(canvas, it, bounds) }
            drawable.foreground?.let { drawLayer(canvas, it, bounds) }
        } else {
            drawLayer(canvas, drawable, IconRasterGeometry.legacyBounds(sizePx).toIntBounds())
        }
        return bitmap
    }

    private fun drawLayer(canvas: Canvas, drawable: Drawable, bounds: IntBounds) {
        drawable.setBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
        drawable.draw(canvas)
    }

    /** After a failed start, distinguish a profile that went away from any other refusal. */
    private fun failure(userSerial: Long, handle: UserHandle): LaunchOutcome = when {
        userForSerial(userSerial) == null -> LaunchOutcome.ProfileRemoved
        isPaused(handle) -> LaunchOutcome.ProfilePaused
        else -> LaunchOutcome.Unavailable
    }

    private fun findActivity(component: String, userSerial: Long): LauncherActivityInfo? {
        cachedBySerial[userSerial]
            ?.firstOrNull { it.componentName.flattenToString() == component }
            ?.let { return it }
        val handle = userForSerial(userSerial) ?: return null
        return activityList(component.substringBefore('/'), handle)
            .firstOrNull { it.componentName.flattenToString() == component }
    }

    private fun launcherProfiles(): List<UserHandle> {
        val profiles = try {
            launcherApps.getProfiles()
        } catch (e: RuntimeException) {
            // A profile can be removed between the service call and the reply; never fail the index.
            emptyList()
        }
        val distinct = profiles.orEmpty().distinct()
        return if (distinct.isEmpty()) listOf(currentUser) else distinct
    }

    /**
     * One profile's activity list. A paused, stopped, removed or half-initialised profile can
     * refuse the call or return a truncated parcel reply (`BadParcelableException`), so every
     * runtime failure becomes an empty list for that profile and the other profiles still list.
     */
    private fun activityList(packageName: String?, handle: UserHandle): List<LauncherActivityInfo> = try {
        launcherApps.getActivityList(packageName, handle) ?: emptyList()
    } catch (e: RuntimeException) {
        emptyList()
    }

    private fun serialOf(handle: UserHandle): Long? = try {
        userManager.getSerialNumberForUser(handle).takeIf { it >= 0L }
    } catch (e: RuntimeException) {
        null
    }

    private fun userForSerial(serial: Long): UserHandle? = try {
        userManager.getUserForSerialNumber(serial)
    } catch (e: RuntimeException) {
        null
    }

    private fun isPaused(handle: UserHandle): Boolean = try {
        userManager.isQuietModeEnabled(handle)
    } catch (e: RuntimeException) {
        false
    }
}
