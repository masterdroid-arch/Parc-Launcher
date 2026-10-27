package com.brightblu.parc.core.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.SkinColors
import com.brightblu.parc.core.data.icons.IconPackRepository

/**
 * Bitmap producer for the icon cache. Rasterises the activity's drawable (adaptive or legacy)
 * at the requested size, and falls back to a letter tile. Colours are skin tokens, not literals.
 */
internal class AndroidIconProducer(
    private val source: LauncherSource,
    private val iconPacks: IconPackRepository? = null,
    private val colors: SkinColors = Skin.Midnight.colors,
) : IconProducer<Bitmap> {
    override suspend fun rasterise(id: AppId, sizePx: Int): Bitmap? {
        iconPacks?.iconFor(id.flattenedComponent(), sizePx)?.let { return it }
        return source.renderIcon(id.flattenedComponent(), id.userSerial(), sizePx)
    }

    override suspend fun letterTile(label: String, sizePx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val radius = IconRasterGeometry.letterTileRadius(sizePx)

        paint.color = colors.lineBase.toInt()
        canvas.drawCircle(radius, radius, radius, paint)

        paint.color = colors.textPrimary.toInt()
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        paint.textSize = IconRasterGeometry.letterTileTextSize(sizePx)
        val baseline = radius - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(firstLetter(label).toString(), radius, baseline, paint)
        return bitmap
    }
}
