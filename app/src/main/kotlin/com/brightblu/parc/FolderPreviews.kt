package com.brightblu.parc

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.brightblu.parc.model.*

/** Called on a worker dispatcher. The renderer receives one immutable raster per folder. */
internal object FolderPreviews {
    fun members(folder: Folder, ranked: List<AppId>, available: Set<AppId>, hidden: Set<AppId>): List<AppId> =
        (ranked.filter { it in folder.appIds } + folder.appIds).distinct()
            .filter { it in available && it !in hidden }.take(Metrics.folderPreviewCount)

    fun prepare(layout: ParcLayout, apps: List<AppEntry>, usage: UsageRepository, skin: Skin,
        lockedFolders: Set<FolderId> = emptySet(), icon: (AppId) -> Bitmap?): Map<FolderId, Bitmap> {
        val available = apps.mapTo(mutableSetOf()) { it.id }
        val size = Metrics.iconBitmapPx
        val scale = size / Metrics.iconDp
        val mini = Metrics.folderPreviewIconDp * scale
        val gap = Metrics.folderPreviewGapDp * scale
        val start = (size - mini * Metrics.folderPreviewColumns - gap) / 2f
        return layout.folders.associate { folder ->
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val ids = members(folder, usage.rank(folder.id), available, layout.hidden)
            if (folder.id in lockedFolders) {
                // T98: a locked folder shows a padlock and never a hint of what is inside.
                drawLockDisc(canvas, paint, size.toFloat(), scale, skin)
            } else if (ids.isEmpty()) {
                paint.color = skin.colors.lineActive.toInt()
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = skin.lineWidths.baseDp * scale
                canvas.drawCircle(size / 2f, size / 2f, (size - paint.strokeWidth) / 2f, paint)
            } else {
                paint.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
                    setSaturation(skin.icons.saturation)
                })
                ids.forEachIndexed { index, id ->
                    val source = icon(id)
                    val left = start + (index % Metrics.folderPreviewColumns) * (mini + gap)
                    val top = start + (index / Metrics.folderPreviewColumns) * (mini + gap)
                    val rect = RectF(left, top, left + mini, top + mini)
                    val mask = Path().apply { addCircle(rect.centerX(), rect.centerY(), mini / 2f, Path.Direction.CW) }
                    canvas.save()
                    canvas.clipPath(mask)
                    if (source != null) canvas.drawBitmap(source, null, rect, paint)
                    else {
                        // Cache misses are asynchronous. Keep membership visible until iconsLoaded
                        // rebuilds the preview, rather than publishing an apparently empty folder.
                        paint.color = skin.colors.lineActive.toInt()
                        canvas.drawCircle(rect.centerX(), rect.centerY(), mini / 2f, paint)
                        paint.color = skin.colors.textPrimary.toInt()
                        paint.textAlign = Paint.Align.CENTER
                        paint.textSize = mini * Metrics.previewPlaceholderTextFraction
                        val label = apps.firstOrNull { it.id == id }?.label.orEmpty().take(1)
                        canvas.drawText(label, rect.centerX(), rect.centerY() - (paint.ascent() + paint.descent()) / 2f, paint)
                    }
                    canvas.restore()
                }
            }
            folder.id to bitmap
        }
    }

    /** Ring plus padlock. Called on a worker thread while the preview raster is prepared, never per frame. */
    private fun drawLockDisc(canvas: Canvas, paint: Paint, size: Float, scale: Float, skin: Skin) {
        val stroke = skin.lineWidths.baseDp * scale
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = skin.colors.lineActive.toInt()
        canvas.drawCircle(size / 2f, size / 2f, (size - stroke) / 2f, paint)
        val bodyW = size * 0.34f
        val bodyH = size * 0.26f
        val left = (size - bodyW) / 2f
        val top = size * 0.46f
        paint.color = skin.colors.textPrimary.toInt()
        paint.strokeWidth = stroke * 1.2f
        val shackle = RectF(left + bodyW * 0.18f, size * 0.26f, left + bodyW * 0.82f, top + stroke)
        canvas.drawArc(shackle, 180f, 180f, false, paint)
        canvas.drawLine(shackle.left, shackle.centerY(), shackle.left, top, paint)
        canvas.drawLine(shackle.right, shackle.centerY(), shackle.right, top, paint)
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(RectF(left, top, left + bodyW, top + bodyH), size * 0.04f, size * 0.04f, paint)
        paint.style = Paint.Style.FILL
        paint.color = skin.colors.bg.toInt()
        canvas.drawCircle(size / 2f, top + bodyH * 0.45f, size * 0.025f, paint)
    }
}
