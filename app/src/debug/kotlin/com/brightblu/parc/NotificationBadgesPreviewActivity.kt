package com.brightblu.parc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.graphics.drawable.toBitmap
import com.brightblu.parc.core.data.notifications.NotificationUnreadStore
import com.brightblu.parc.model.*
import com.brightblu.parc.ring.CanvasRingRenderer
import com.brightblu.parc.ring.RingLayout
import com.brightblu.parc.ring.RingScenes

/** ADB-only T46 fixture: real listener counts from cmd notification post (com.android.shell).
 * Layout and icons are fixtures; unread counts are never injected or persisted.
 */
class NotificationBadgesPreviewActivity : ComponentActivity() {
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        val skin = Skin.Midnight
        window.statusBarColor = skin.colors.bg.toInt()
        window.navigationBarColor = skin.colors.bg.toInt()
        val id = AppId("com.android.shell/Test@0")
        val folder = FolderId("t46")
        val apps = listOf(AppEntry(id, "Shell", "com.android.shell", true, 0))
        val geometry = RingLayout(384f, 853f)
        val icon = packageManager.getApplicationIcon("com.android.shell").toBitmap(Metrics.iconBitmapPx, Metrics.iconBitmapPx)
        val layout = ParcLayout(folders = listOf(Folder(folder, "Notices", appIds = listOf(id))))
        val scene = RingScenes.home(geometry, listOf(
            RingSlot(SlotPosition("A1", 0), Slot.App(id), "Shell", icon),
            RingSlot(SlotPosition("A2", 0), Slot.Folder(folder), "Notices", icon),
        ), "")
        val allApps = intent.getBooleanExtra("allApps", false)
        val enabled = intent.getBooleanExtra("enabled", true)
        val optIn = intent.getBooleanExtra("optIn", false)
        setContent {
            val counts by NotificationUnreadStore.shared.unread.collectAsState()
            val renderer = remember { CanvasRingRenderer() }
            val target = remember(counts) {
                LauncherScenes.withUnread(scene.copy(layer = if (allApps) Layer.AllApps else Layer.Home),
                    layout, apps, counts, enabled, optIn)
            }
            renderer.Field(target, skin, object : RingCallbacks {
                override fun onTap(hit: SlotHit) = Unit
                override fun onLongPress(hit: SlotHit) = Unit
                override fun onDrag(hit: SlotHit, phase: DragPhase) = Unit
            })
        }
    }
}
