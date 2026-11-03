package com.brightblu.parc.ring.module

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import com.brightblu.parc.ring.RingLayout
import kotlin.math.hypot

/** Card rectangle in dp. */
data class CardRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
}

/** DESIGN 3.1: the solid card behind the hub module. Geometry is pure; the raster is built once per key. */
object ModuleCard {
    /**
     * The card sits at fixed offsets from the hub origin (36 dp right of it, 68 dp below it, 357 dp above it),
     * so it keeps the 12 dp edge insets on every screen. On a narrow screen the left side may shrink to 4 dp.
     * A hub on the left half of the screen (mirrored layout) gets the mirrored card.
     */
    fun rect(originXDp: Float, originYDp: Float, widthDp: Float): CardRect {
        val top = originYDp - Metrics.moduleCardAboveOriginDp
        val bottom = originYDp + Metrics.moduleCardBelowOriginDp
        return if (originXDp * 2f > widthDp) {
            val right = originXDp + Metrics.moduleCardRightOfOriginDp
            CardRect(maxOf(Metrics.moduleCardMinLeftDp, right - Metrics.moduleCardWidthDp), top, right, bottom)
        } else {
            val left = originXDp - Metrics.moduleCardRightOfOriginDp
            CardRect(left, top, minOf(widthDp - Metrics.moduleCardMinLeftDp, left + Metrics.moduleCardWidthDp), bottom)
        }
    }

    /** Flat rounded card in `moduleSurface`: no border, no shadow, no gradient. Built once, off the draw path. */
    fun raster(skin: Skin, rect: CardRect, density: Float): Bitmap {
        val bitmap = Bitmap.createBitmap((rect.width * density).toInt().coerceAtLeast(1),
            (rect.height * density).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = skin.colors.moduleSurface.toInt(); style = Paint.Style.FILL }
        val corner = skin.shape.moduleCornerDp * density
        Canvas(bitmap).drawRoundRect(RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()), corner, corner, paint)
        return bitmap
    }

    /** True when the module takes a touch at (x, y): a slot or the hub. Empty card area is passive. */
    fun takesTouch(layout: RingLayout, xDp: Float, yDp: Float): Boolean =
        hypot(xDp - layout.originXDp, yDp - layout.originYDp) <= Metrics.hubTouchDp / 2f || layout.hitTest(xDp, yDp) >= 0
}
