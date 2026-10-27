package com.brightblu.parc

import android.graphics.Bitmap
import android.graphics.Canvas as NativeCanvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import com.brightblu.parc.ring.RingLayout
import com.brightblu.parc.ring.joystickBack
import kotlin.math.hypot

/** Host-owned hub above the full-screen ABC surface; only the hub starts a gesture. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun AllAppsJoystick(geometry: RingLayout, skin: Skin, exit: () -> Unit) {
    val density = LocalDensity.current.density
    val half = Metrics.hubTouchDp / 2f
    var back by remember { mutableStateOf(false) }
    var moved by remember { mutableStateOf(false) }
    val latestExit by rememberUpdatedState(exit)
    val paint = remember(skin, density) { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = skin.colors.accent.toInt(); style = Paint.Style.STROKE
        strokeWidth = skin.lineWidths.activeDp * density; strokeCap = Paint.Cap.ROUND
    } }
    val bitmapPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
    val glow = remember(skin, density) {
        val radius = skin.glowRadiusDp * density
        val size = (radius * 2).toInt().coerceAtLeast(1)
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(radius, radius, radius, skin.colors.glow.toInt(),
                    skin.colors.glow.toInt() and 0xffffff, Shader.TileMode.CLAMP)
            }
            NativeCanvas(it).drawCircle(radius, radius, radius, p)
        }
    }
    Canvas(Modifier.offset((geometry.originXDp - half).dp, (geometry.originYDp - half).dp)
        .size(Metrics.hubTouchDp.dp).semantics {
            contentDescription = "Back"
            onClick { latestExit(); true }
        }.pointerInteropFilter { event ->
            val dx = event.x / density - half
            val dy = event.y / density - half
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { moved = false; back = false }
                MotionEvent.ACTION_MOVE -> {
                    if (hypot(dx, dy) > Metrics.aimDragStartDp) moved = true
                    back = joystickBack(dx, dy)
                }
                MotionEvent.ACTION_UP -> {
                    val shouldExit = joystickBack(dx, dy) || !moved && hypot(dx, dy) <= half
                    back = false
                    if (shouldExit) latestExit()
                }
                MotionEvent.ACTION_CANCEL -> back = false
            }
            true
        }) {
        val c = drawContext.canvas.nativeCanvas
        val center = half * density
        c.drawCircle(center, center, Metrics.hubRadiusDp * density, paint)
        if (back) {
            val x = center + Metrics.backOffsetDp * density
            val r = Metrics.backChevronDp * density
            c.drawBitmap(glow, x - glow.width / 2f, center - glow.height / 2f, bitmapPaint)
            c.drawLine(x + r / 2f, center - r, x - r / 2f, center, paint)
            c.drawLine(x - r / 2f, center, x + r / 2f, center + r, paint)
        }
    }
}
