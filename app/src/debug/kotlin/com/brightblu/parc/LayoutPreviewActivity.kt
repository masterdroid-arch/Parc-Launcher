package com.brightblu.parc

import android.app.Activity
import android.os.Bundle
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import com.brightblu.parc.ring.RingInsets
import com.brightblu.parc.ring.RingLayout
import com.brightblu.parc.ring.RingLayoutOptions

/** ADB-only T06 geometry inspection; production wiring/rendering belongs to T07/T09. */
class LayoutPreviewActivity : Activity() {
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        window.statusBarColor = Skin.Midnight.colors.bg.toInt()
        window.navigationBarColor = Skin.Midnight.colors.bg.toInt()
        setContentView(Preview(RingLayoutOptions(intent.getBooleanExtra("mirrored", false),
            intent.getBooleanExtra("aligned", false))))
        window.insetsController?.setSystemBarsAppearance(0,
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
    }

    private inner class Preview(private val options: RingLayoutOptions) : View(this) {
        private val skin = Skin.Midnight
        private val density = resources.displayMetrics.density
        private fun stroke(color: Long) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = skin.lineWidths.baseDp * density
            this.color = color.toInt()
        }
        private val linePaint = stroke(skin.colors.lineBase)
        private val targetPaint = stroke(skin.colors.textSecondary)
        private val hubPaint = stroke(skin.colors.textPrimary)
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = skin.colors.textPrimary.toInt()
            textSize = skin.typography.menu.sizeSp * resources.displayMetrics.scaledDensity
        }
        private var geometry: RingLayout? = null
        private var paths = emptyArray<Path>()
        private var title = ""
        private var selected = -1

        init { setBackgroundColor(skin.colors.bg.toInt()) }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            // T65: same keep-out sources as the HOME host, including gesture navigation.
            val insets = rootWindowInsets?.parcInsets(density) ?: RingInsets()
            val layout = RingLayout(w / density, h / density, insets, options)
            geometry = layout
            paths = Array(layout.arcs.size) { i ->
                val arc = layout.arcs[i]
                val x = arc.originXDp * density
                val y = arc.originYDp * density
                val r = arc.radiusDp * density
                Path().apply { addArc(x - r, y - r, x + r, y + r,
                    arc.startAngle, arc.endAngle - arc.startAngle) }
            }
            title = "${if (options.mirrored) "Left" else "Right"} / ${if (options.alignAcrossArcs) "spokes" else "staggered"}: ${layout.count} slots"
        }

        override fun onDraw(canvas: Canvas) {
            val layout = geometry ?: return
            var i = 0
            while (i < paths.size) { canvas.drawPath(paths[i], linePaint); i++ }
            i = 0
            while (i < layout.count) {
                canvas.drawCircle(layout.slotXDp(i) * density, layout.slotYDp(i) * density,
                    Metrics.touchTargetDp / 2f * density, targetPaint)
                i++
            }
            canvas.drawCircle(layout.originXDp * density, layout.originYDp * density,
                Metrics.hubRadiusDp * density, hubPaint)
            if (selected >= 0) {
                canvas.drawCircle(layout.slotXDp(selected) * density, layout.slotYDp(selected) * density,
                    Metrics.gridDp * density, fillPaint)
            }
            canvas.drawText(title, Metrics.textGutterDp * density,
                (Metrics.keepOutTopDp + Metrics.menuRowHeightDp) * density, fillPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_DOWN) {
                selected = geometry?.hitTest(event.x / density, event.y / density) ?: -1
                invalidate()
            }
            return true
        }
    }
}
