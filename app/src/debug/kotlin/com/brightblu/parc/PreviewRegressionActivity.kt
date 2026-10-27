package com.brightblu.parc

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import com.brightblu.parc.model.*
import com.brightblu.parc.ring.RingLayout

/** Dependency-free device regression: exercises real Android rasterization, never production data. */
class PreviewRegressionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread {
            try {
                val ids = (0..3).map { AppId("preview/$it") }
                val folder = Folder(FolderId("google"), "Google", appIds = ids)
                val geometry = RingLayout(384f, 853f)
                val arc = geometry.arcs[2]
                val layout = ParcLayout(folders = listOf(folder),
                    arcs = listOf(ArcAssignment(arc, mapOf(2 to Slot.Folder(folder.id)))))
                val apps = ids.mapIndexed { i, id -> AppEntry(id, "App$i", "preview", false, 0) }
                val skin = Skin.Midnight.copy(icons = IconTokens(saturation = 1f))
                val pending = FolderPreviews.prepare(layout, apps, PendingUsageRepository(), skin) { null }.getValue(folder.id)
                val size = Metrics.iconBitmapPx
                val factor = size / Metrics.iconDp
                val mini = Metrics.folderPreviewIconDp * factor
                val gap = Metrics.folderPreviewGapDp * factor
                val start = (size - 2 * mini - gap) / 2
                val colors = listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFF00.toInt())
                val icons = ids.mapIndexed { i, id -> id to Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { eraseColor(colors[i]) } }.toMap()
                val loaded = FolderPreviews.prepare(layout, apps, PendingUsageRepository(), skin) { icons[it] }.getValue(folder.id)
                for (i in ids.indices) {
                    val x = (start + (i % 2) * (mini + gap) + mini / 2).toInt()
                    val y = (start + (i / 2) * (mini + gap) + mini / 2).toInt()
                    check(pending.getPixel(x, y) ushr 24 > 0 && pending.getPixel(x, y) != skin.colors.surface.toInt()) { "Pending member $i is invisible" }
                    check(loaded.getPixel(x, y) == colors[i]) { "Loaded member $i is missing" }
                }
                val scene = LauncherScenes.build(geometry, layout, apps, ShellState(), "", mapOf(folder.id to loaded)) { null }
                check(scene.slots.single().bitmap === loaded)
                check(scene.slots.single().position == SlotPosition(arc.id, 2))
                val removed = FolderPreviews.prepare(layout.copy(folders = listOf(folder.copy(appIds = emptyList()))), apps,
                    PendingUsageRepository(), skin) { icons[it] }.getValue(folder.id)
                check(removed.getPixel(size / 2, size / 2) == 0)
                Log.i("T28bRegression", "PASS: four pending tiles; four loaded mini icons; stored position and raster forwarded; removed membership clears preview")
            } catch (failure: Throwable) {
                Log.e("T28bRegression", "FAIL", failure)
            } finally { runOnUiThread { finish() } }
        }.start()
    }
}
