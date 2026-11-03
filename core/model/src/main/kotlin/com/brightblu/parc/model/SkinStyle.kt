package com.brightblu.parc.model

import kotlinx.serialization.Serializable

/** Module surface treatment; rendering is adopted by later cards. */
@Serializable enum class ModuleStyle { solid, frosted }

@Serializable enum class ArcStyle { solid, dashed, contour, dotted }
@Serializable enum class IconFrame { disc, roundedSquare, none, registration, brackets }
@Serializable enum class HubStyle { plain, crosshair, compass }
@Serializable enum class AimStyle { solid, dotted, distance, coordinates }
@Serializable enum class LabelStyle { pill, plain, mapLabel, brackets }
@Serializable enum class BackgroundPattern { none, grid, contour, paper }
@Serializable enum class StatsFrame { brackets, legend, plain, terminal }
@Serializable enum class HeaderStyle { plain, ruled }

/** Presentation only. Never changes hit targets, slot positions or joystick thresholds. */
@Serializable
data class SkinStyle(
    val arc: ArcStyle = ArcStyle.solid,
    val ticks: Boolean = false,
    val bearings: Boolean = false,
    val iconFrame: IconFrame = IconFrame.disc,
    val iconScale: Float = 1f,
    val iconOutlineDp: Float = 0f,
    val hub: HubStyle = HubStyle.plain,
    val aim: AimStyle = AimStyle.solid,
    val label: LabelStyle = LabelStyle.pill,
    val background: BackgroundPattern = BackgroundPattern.none,
    val statsFrame: StatsFrame = StatsFrame.plain,
    val terminalStats: Boolean = false,
    val header: HeaderStyle = HeaderStyle.plain,
    val clockFamily: String = "",
    val clockWeight: Int = 0,
    val annotationFamily: String = "",
    val motion: MotionTokens? = null,
) {
    init {
        require(iconScale.isFinite() && iconScale in 0.6f..1f)
        require(iconOutlineDp.isFinite() && iconOutlineDp in 0f..3f)
        require(clockWeight == 0 || clockWeight in 100..900)
        require(clockFamily in setOf("", "serif", "sans-serif", "monospace"))
        require(annotationFamily in setOf("", "serif", "sans-serif", "monospace"))
        motion?.let {
            require(it.durSnap in 0..2000 && it.durShort in 0..2000 && it.durSearch in 0..2000)
            require(it.stagger in 0..2000 && it.staggerMaxSlots in 0..100)
            require(it.wakeSweep in 0..2000 && it.wakeSlotFade in 0..2000)
            require(it.glowSpringDamping.isFinite() && it.glowSpringDamping > 0f)
            require(it.glowSpringStiffness.isFinite() && it.glowSpringStiffness > 0f)
            require(it.breathePeriod > 0 && it.idleStop in 0..10_000)
            require(it.pulseDuration > 0 && it.pulseRepeat >= it.pulseDuration)
            for (curve in listOf(it.easeEmphasized, it.easeStandard)) {
                require(curve.x1.isFinite() && curve.x1 in 0f..1f && curve.x2.isFinite() && curve.x2 in 0f..1f)
                require(curve.y1.isFinite() && curve.y2.isFinite())
            }
        }
    }
}

/** Pure, testable mapping shared by scene preparation and preview drawing. */
data class SkinDrawingSpec(
    val dashOnDp: Float, val dashOffDp: Float,
    val ticks: Boolean, val bearings: Boolean,
    val iconFrame: IconFrame, val iconScale: Float, val iconOutlineDp: Float,
    val hub: HubStyle, val aim: AimStyle, val label: LabelStyle,
    val background: BackgroundPattern, val statsFrame: StatsFrame, val header: HeaderStyle,
    val clockFamily: String, val clockWeight: Int, val annotationFamily: String,
)

fun Skin.drawingSpec(): SkinDrawingSpec = SkinDrawingSpec(
    dashOnDp = when (style.arc) { ArcStyle.dashed -> Metrics.atlasMajorTickDp; ArcStyle.dotted -> Metrics.discRimDp; else -> 0f },
    dashOffDp = when (style.arc) { ArcStyle.dashed -> Metrics.gridDp * 1.5f; ArcStyle.dotted -> Metrics.gridDp + 1f; else -> 0f },
    ticks = style.ticks || style.arc == ArcStyle.contour, bearings = style.bearings,
    iconFrame = style.iconFrame, iconScale = style.iconScale, iconOutlineDp = style.iconOutlineDp,
    hub = style.hub, aim = style.aim, label = style.label, background = style.background,
    statsFrame = style.statsFrame, header = style.header,
    clockFamily = style.clockFamily.ifEmpty { typography.fallbackFamily },
    clockWeight = style.clockWeight.takeIf { it != 0 } ?: typography.clock.weight,
    annotationFamily = style.annotationFamily.ifEmpty { typography.fallbackFamily },
)

val Skin.effectiveMotion: MotionTokens get() = style.motion ?: motion
