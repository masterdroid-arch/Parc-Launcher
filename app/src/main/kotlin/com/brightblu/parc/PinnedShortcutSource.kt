package com.brightblu.parc

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.UserManager
import com.brightblu.parc.model.*

internal data class PinnedShortcutVisual(val icon: Bitmap?, val enabled: Boolean)

/** All queries and bitmap preparation run outside composition/drawing, on a worker dispatcher. */
internal class PinnedShortcutSource(private val context: Context) {
    private val launcher = context.getSystemService(LauncherApps::class.java)
    private val users = context.getSystemService(UserManager::class.java)
    private fun info(slot: Slot.Shortcut): ShortcutInfo? {
        val user = users.getUserForSerialNumber(slot.userSerial) ?: return null
        return launcher.getShortcuts(LauncherApps.ShortcutQuery().setPackage(slot.packageName)
            .setShortcutIds(listOf(slot.shortcutId)).setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED), user)
            ?.firstOrNull()
    }
    fun visual(slot: Slot.Shortcut): PinnedShortcutVisual = try {
        val info = info(slot)
        val drawable = info?.let { launcher.getShortcutIconDrawable(it, context.resources.displayMetrics.densityDpi) }
        val bitmap = drawable?.let {
            Bitmap.createBitmap(Metrics.iconBitmapPx, Metrics.iconBitmapPx, Bitmap.Config.ARGB_8888).also { bitmap ->
                it.setBounds(0, 0, bitmap.width, bitmap.height); it.draw(Canvas(bitmap))
            }
        }
        PinnedShortcutVisual(bitmap, info?.isEnabled == true)
    } catch (_: RuntimeException) { PinnedShortcutVisual(null, false) }

    fun launch(slot: Slot.Shortcut): Boolean = try {
        val info = info(slot)
        if (info?.isEnabled != true) false else {
            launcher.startShortcut(slot.packageName, slot.shortcutId, null, null, info.userHandle); true
        }
    } catch (_: RuntimeException) { false }
}
