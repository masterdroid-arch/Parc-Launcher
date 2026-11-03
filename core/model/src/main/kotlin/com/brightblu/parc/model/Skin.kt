package com.brightblu.parc.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** ARGB sRGB colours as unsigned-range Longs. Immutable, non-null, thread-safe. */
@Serializable
data class SkinColors(
    val bg: Long,
    val lineBase: Long,
    val lineActive: Long,
    val accent: Long,
    val glow: Long,
    val textPrimary: Long,
    val textSecondary: Long,
    val pulse: Long,
    val danger: Long,
    val scrim: Long,
    val surface: Long,
    val accent2: Long = accent,
    /** Warning amber (T33/T32): the middle rung of the HUD's temperature colour scale. */
    val warn: Long = 0xFFFFB000L,
    val moduleSurface: Long = surface,
    val moduleGuide: Long = lineActive,
    val moduleInk: Long = textPrimary,
)

/** Font role (sp, numeric weight, tracking in sp). Immutable, non-null, thread-safe. */
@Serializable
data class TypeToken(val sizeSp: Float, val weight: Int, val trackingSp: Float)

/** Typography from DESIGN §2.2. Immutable, non-null, thread-safe; font loading belongs to app. */
@Serializable
data class TypographyTokens(
    val family: String = "Figtree",
    val fallbackFamily: String = "sans-serif",
    val clockTabularFigures: Boolean = true,
    val clock: TypeToken = TypeToken(44f, 200, -0.5f),
    val title: TypeToken = TypeToken(28f, 300, 0f),
    val search: TypeToken = TypeToken(20f, 400, 0f),
    val label: TypeToken = TypeToken(12f, 500, 0.2f),
    val caption: TypeToken = TypeToken(11f, 500, 0.3f),
    val menu: TypeToken = TypeToken(15f, 450, 0f),
    val maxDynamicTypeScale: Float = 1.3f,
    val fixedSlotLabels: Boolean = true,
    val numeralFamily: String = "",
    val clockDot: TypeToken = TypeToken(56f, 500, 0f),
    val countDot: TypeToken = TypeToken(28f, 600, 0f),
    val bigDot: TypeToken = TypeToken(88f, 600, 0f),
) {
    init {
        require(numeralFamily == "" || numeralFamily == "Doto")
        for (role in listOf(clockDot, countDot, bigDot)) {
            require(role.sizeSp.isFinite() && role.sizeSp >= 24f)
            require(role.weight in 100..900 && role.trackingSp.isFinite())
        }
    }
}

/** Ring stroke sizes in dp. Immutable, non-null, thread-safe. */
@Serializable
data class LineWidths(val baseDp: Float = 1f, val activeDp: Float = 1.5f)

/** Shape/depth tokens; colours and line widths live in Skin. Immutable, non-null, thread-safe. */
@Serializable
data class ShapeTokens(
    val roundLineCaps: Boolean = true,
    val lineEndFadeFraction: Float = 0.12f,
    val surfaceAlpha: Float = 0.92f,
    val surfaceAccentFraction: Float = 0.04f,
    val surfaceCornerDp: Float = 20f,
    val surfaceBorderDp: Float = 1f,
    val dropShadows: Boolean = false,
    val backgroundGradients: Boolean = false,
    val moduleStyle: ModuleStyle = ModuleStyle.solid,
    val tileCornerDp: Float = 20f,
    val moduleCornerDp: Float = 32f,
) {
    init {
        require(tileCornerDp.isFinite() && tileCornerDp >= 0f)
        require(moduleCornerDp.isFinite() && moduleCornerDp >= 0f)
    }
}

/** Supported icon treatment. Immutable, non-null, thread-safe. */
@Serializable
enum class IconStyle { plain, tile }

/** Icon presentation; immutable, non-null, thread-safe. */
@Serializable
data class IconTokens(
    val circleMask: Boolean = true,
    val saturation: Float = 0.88f,
    val alphaUnfocused: Float = 0.94f,
    val alphaFocused: Float = 1f,
    val uprightIcons: Boolean = true,
    val horizontalLabels: Boolean = true,
)

/** Cubic Bezier control points. Immutable, non-null, thread-safe. */
@Serializable
data class CubicBezier(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

/** Motion tokens from DESIGN §2.6. Immutable, non-null, thread-safe. Durations are milliseconds. */
@Serializable
data class MotionTokens(
    val easeEmphasized: CubicBezier = CubicBezier(0.2f, 0f, 0f, 1f),
    val easeStandard: CubicBezier = CubicBezier(0.3f, 0f, 0.1f, 1f),
    val durSnap: Int = 320,
    val durShort: Int = 160,
    val durSearch: Int = 220,
    val stagger: Int = 18,
    val staggerMaxSlots: Int = 8,
    val wakeSweep: Int = 400,
    val wakeSlotFade: Int = 160,
    val glowSpringDamping: Float = 0.8f,
    val glowSpringStiffness: Float = 400f,
    val breathePeriod: Int = 7_000,
    val idleStop: Int = 10_000,
    val pulseDuration: Int = 1_600,
    val pulseRepeat: Int = 12_000,
    val respectAnimatorScale: Boolean = true,
    val touchCancelsToNearestState: Boolean = true,
)

/** Optional stats HUD geometry. Immutable and safe to share across threads. */
@Serializable
data class HudTokens(
    val bracketLengthDp: Float = 8f,
    val bracketWidthDp: Float = 1f,
    val segmentGapDp: Float = 4f,
    val monospace: Boolean = true,
) {
    init {
        require(bracketLengthDp.isFinite() && bracketLengthDp > 0f)
        require(bracketWidthDp.isFinite() && bracketWidthDp > 0f)
        require(segmentGapDp.isFinite() && segmentGapDp >= 0f)
    }
}

/**
 * Data-only skin schema (DESIGN §2.1–2.6/2.8). Immutable, non-null, thread-safe.
 * Geometry remains in Metrics. glowRadiusDp is a provisional tuning value, not specified by design.
 * surface is bg mixed with 4% accent; surfaceAlpha applies separately.
 */
@Serializable
data class Skin(
    val id: String,
    val name: String,
    val colors: SkinColors,
    val iconStyle: IconStyle = IconStyle.plain,
    val glowRadiusDp: Float = 48f,
    val lineWidths: LineWidths = LineWidths(),
    val motionScale: Float = 1f,
    val breatheAmount: Float = 0.04f,
    val typography: TypographyTokens = TypographyTokens(),
    val shape: ShapeTokens = ShapeTokens(),
    val icons: IconTokens = IconTokens(),
    val motion: MotionTokens = MotionTokens(),
    val depthScale: List<Float> = listOf(1f, 0.94f, 0.88f, 0.82f),
    val depthAlpha: List<Float> = listOf(1f, 0.96f, 0.90f, 0.84f),
    val parallaxDp: Float = 6f,
    val hud: HudTokens = HudTokens(),
    val style: SkinStyle = SkinStyle(),
) {
    init {
        require(depthScale.size == 4 && depthScale.all { it.isFinite() && it > 0f && it <= 1f })
        require(depthAlpha.size == 4 && depthAlpha.all { it.isFinite() && it in 0f..1f })
        require(parallaxDp.isFinite() && parallaxDp in 0f..6f)
        require(motionScale in 0.8f..1.2f)
        require(glowRadiusDp.isFinite() && glowRadiusDp > 0f)
        require(breatheAmount in 0f..1f)
    }

    companion object {
        val FocusPalette = listOf(
            "Teal" to 0xFF008577L, "Blue" to 0xFF2979FFL, "Violet" to 0xFF9575CDL,
            "Pink" to 0xFFE86AACL, "Red" to 0xFFE45353L, "Orange" to 0xFFEF8C32L,
            "Yellow" to 0xFFE0BE30L, "Green" to 0xFF4B9E5CL)

        val Midnight = Skin("midnight", "Midnight", SkinColors(
            bg = 0xFF000000, lineBase = 0xFF2A3544, lineActive = 0xFF3A4A5E,
            accent = 0xFF7FB4C8, glow = 0x477FB4C8, textPrimary = 0xFFE6EAF0,
            textSecondary = 0xFF8C97A6, pulse = 0xFFA8C7FA, danger = 0xFFE5736B,
            scrim = 0xB8000000, surface = 0xFF050708, moduleSurface = 0xFF141B24,
        ))
        val Dusk = Skin("dusk", "Dusk", SkinColors(
            bg = 0xFF000000, lineBase = 0xFF2A2236, lineActive = 0xFF4A3B63,
            accent = 0xFFB79CDB, glow = 0x47B79CDB, textPrimary = 0xFFECE7F2,
            textSecondary = 0xFF978FA4, pulse = 0xFFD2BFF0, danger = 0xFFE5736B,
            scrim = 0xB8000000, surface = 0xFF070609, moduleSurface = 0xFF1A1526,
        ))
    }
}

/** Strict skin JSON codec; thread-safe, non-null. Unknown fields/invalid tokens throw. */
object SkinJson {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    fun encode(skin: Skin): String = json.encodeToString(Skin.serializer(), skin)
    fun decode(source: String): Skin = json.decodeFromString(Skin.serializer(), source)
}
