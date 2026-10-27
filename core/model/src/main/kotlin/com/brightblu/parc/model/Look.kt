package com.brightblu.parc.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * T71 icon mask. [Circle] is today's treatment; [None] leaves the raw icon bitmap untouched.
 * Immutable, non-null, thread-safe.
 */
@Serializable
enum class IconShape { Circle, Squircle, RoundedSquare, None }

/** T71 clock treatment under the skin's clock typography. Immutable and thread-safe. */
@Serializable
enum class ClockStyle { Thin, Bold, Mono, Digital, Hidden }

/** T71 home background. [Black] is today's behaviour; [Solid] uses the skin surface token. */
@Serializable
enum class WallpaperStyle { Black, System, Solid, Image }

/**
 * Persisted look settings (T71). All values are validated in the initializer, so a corrupt stored
 * document fails decoding and the app-local store keeps the defaults instead of half-applying it.
 * The default instance is exactly the pre-T71 appearance: 44 dp circle icons, thin clock, no date
 * or alarm line, black background.
 */
@Serializable
data class LookSettings(
    val iconShape: IconShape = IconShape.Circle,
    val iconSizeDp: Int = 44,
    val clockStyle: ClockStyle = ClockStyle.Thin,
    val showDate: Boolean = false,
    val showAlarm: Boolean = false,
    val wallpaper: WallpaperStyle = WallpaperStyle.Black,
    val wallpaperDimPercent: Int = 40,
    val panelOpacityPercent: Int = 85,
    val wallpaperReadability: Boolean = true,
    val imageReadabilityScrim: Boolean = true,
    val includeWallpaperInBackups: Boolean = false,
    val wallpaperImageRevision: Long = 0,
    val keepTextReadable: Boolean? = when (wallpaper) {
        WallpaperStyle.Image -> imageReadabilityScrim
        WallpaperStyle.System -> wallpaperReadability
        else -> wallpaperReadability || imageReadabilityScrim
    },
) {
    init {
        require(panelOpacityPercent in 40..100 && panelOpacityPercent % 5 == 0)
        require(iconSizeDp in LookRules.MinIconSizeDp..LookRules.MaxIconSizeDp)
        require(iconSizeDp % LookRules.IconSizeStepDp == 0) { "Icon size must use 2 dp steps" }
        require(wallpaperDimPercent in 0..80)
        require(wallpaperDimPercent % LookRules.DimStepPercent == 0) { "Wallpaper dim must use 10% steps" }
    }
}

/**
 * Pure rules shared by the renderer, the settings page and their tests. No Android types and no
 * allocation in the hot path (the mask test is called only while preparing a scene bitmap).
 */
object LookRules {
    const val MinIconSizeDp = 36
    const val MaxIconSizeDp = 52
    const val IconSizeStepDp = 2
    const val DimStepPercent = 10
    const val MinSlotPitchDp = Metrics.minSlotPitchDp
    /** Corner radius divided by the icon's full size; the renderer uses the same value. */
    const val RoundedSquareCornerFraction = 0.22f
    const val SquircleExponent = 4f

    val IconSizeSteps: List<Int> = (MinIconSizeDp..MaxIconSizeDp step IconSizeStepDp).toList()
    val DimSteps: List<Int> = (0..80 step DimStepPercent).toList()

    /** Nearest legal icon size, clamped to the slider range and rounded to a 2 dp step. */
    fun normalizeIconSize(requested: Int): Int {
        val clamped = requested.coerceIn(MinIconSizeDp, MaxIconSizeDp)
        val stepped = ((clamped + IconSizeStepDp / 2) / IconSizeStepDp) * IconSizeStepDp
        return stepped.coerceIn(MinIconSizeDp, MaxIconSizeDp)
    }

    /**
     * Effective icon size: the stored size, never larger than the tightest slot pitch the layout
     * produced. The home engine already holds every arc at or above [Metrics.minSlotPitchDp]; this
     * projection keeps a future geometry change from drawing overlapping icons and is unit-tested
     * across the reference screen sizes. The value is a whole 2 dp step.
     */
    fun effectiveIconSizeDp(requested: Int, tightestPitchDp: Float): Float {
        val pitchLimit = if (tightestPitchDp.isFinite() && tightestPitchDp > 0f)
            floor(tightestPitchDp / IconSizeStepDp) * IconSizeStepDp.toFloat() else MaxIconSizeDp.toFloat()
        val limit = min(MaxIconSizeDp.toFloat(), max(MinIconSizeDp.toFloat(), pitchLimit))
        return normalizeIconSize(requested).toFloat().coerceAtMost(limit)
    }

    /** Index in [values] for a slider fraction in 0..1, rounded to the nearest step. */
    fun stepIndex(fraction: Float, values: List<Int>): Int {
        if (values.isEmpty()) return 0
        if (!fraction.isFinite()) return 0
        return (fraction.coerceIn(0f, 1f) * (values.size - 1)).roundToInt()
    }

    /** Mask test in normalised icon coordinates, where the icon spans -1..1 on both axes. */
    fun maskContains(shape: IconShape, x: Float, y: Float): Boolean = when (shape) {
        IconShape.Circle -> x * x + y * y <= 1f
        IconShape.Squircle -> abs(x).pow(SquircleExponent) + abs(y).pow(SquircleExponent) <= 1f
        IconShape.RoundedSquare -> {
            if (abs(x) > 1f || abs(y) > 1f) false
            else {
                // Normalised coordinates span -1..1, so a radius that is a fraction of the full
                // size is twice that fraction here.
                val radius = RoundedSquareCornerFraction * 2f
                val inner = 1f - radius
                if (abs(x) <= inner || abs(y) <= inner) true
                else {
                    val dx = abs(x) - inner
                    val dy = abs(y) - inner
                    dx * dx + dy * dy <= radius * radius
                }
            }
        }
        IconShape.None -> abs(x) <= 1f && abs(y) <= 1f
    }

    /** Clock face weight; [thinWeight] is the skin's own clock token weight. */
    fun clockWeight(style: ClockStyle, thinWeight: Int): Int = when (style) {
        ClockStyle.Thin -> thinWeight
        ClockStyle.Bold -> 700
        ClockStyle.Mono -> 400
        ClockStyle.Digital, ClockStyle.Hidden -> thinWeight
    }

    /** Root-row detail for the Look entry. Pure so the row text is testable. */
    fun summary(look: LookSettings): String =
        "${iconShapeLabel(look.iconShape)} · ${clockStyleLabel(look.clockStyle)}"

    fun iconShapeLabel(shape: IconShape): String = when (shape) {
        IconShape.Circle -> "Circle"
        IconShape.Squircle -> "Squircle"
        IconShape.RoundedSquare -> "Rounded square"
        IconShape.None -> "None"
    }

    fun clockStyleLabel(style: ClockStyle): String = when (style) {
        ClockStyle.Thin -> "Thin"
        ClockStyle.Bold -> "Bold"
        ClockStyle.Mono -> "Mono"
        ClockStyle.Digital -> "Digital"
        ClockStyle.Hidden -> "Hidden"
    }

    fun wallpaperLabel(style: WallpaperStyle): String = when (style) {
        WallpaperStyle.Image -> "Image"
        WallpaperStyle.Black -> "Black"
        WallpaperStyle.System -> "System wallpaper"
        WallpaperStyle.Solid -> "Solid skin colour"
    }
}

/** Seven-segment layout for the digital clock. Pure; the renderer only reads the sets. */
enum class ClockSegment { Top, UpperRight, LowerRight, Bottom, LowerLeft, UpperLeft, Middle }

object SevenSegment {
    private val digits: Map<Char, Set<ClockSegment>> = mapOf(
        '0' to setOf(ClockSegment.Top, ClockSegment.UpperRight, ClockSegment.LowerRight,
            ClockSegment.Bottom, ClockSegment.LowerLeft, ClockSegment.UpperLeft),
        '1' to setOf(ClockSegment.UpperRight, ClockSegment.LowerRight),
        '2' to setOf(ClockSegment.Top, ClockSegment.UpperRight, ClockSegment.Middle,
            ClockSegment.LowerLeft, ClockSegment.Bottom),
        '3' to setOf(ClockSegment.Top, ClockSegment.UpperRight, ClockSegment.Middle,
            ClockSegment.LowerRight, ClockSegment.Bottom),
        '4' to setOf(ClockSegment.UpperLeft, ClockSegment.Middle, ClockSegment.UpperRight,
            ClockSegment.LowerRight),
        '5' to setOf(ClockSegment.Top, ClockSegment.UpperLeft, ClockSegment.Middle,
            ClockSegment.LowerRight, ClockSegment.Bottom),
        '6' to setOf(ClockSegment.Top, ClockSegment.UpperLeft, ClockSegment.Middle,
            ClockSegment.LowerLeft, ClockSegment.LowerRight, ClockSegment.Bottom),
        '7' to setOf(ClockSegment.Top, ClockSegment.UpperRight, ClockSegment.LowerRight),
        '8' to setOf(ClockSegment.Top, ClockSegment.UpperRight, ClockSegment.LowerRight,
            ClockSegment.Bottom, ClockSegment.LowerLeft, ClockSegment.UpperLeft, ClockSegment.Middle),
        '9' to setOf(ClockSegment.Top, ClockSegment.UpperRight, ClockSegment.LowerRight,
            ClockSegment.Bottom, ClockSegment.Middle, ClockSegment.UpperLeft),
    )

    /** Segments for one digit, or null when [value] is not a supported digit. */
    fun segmentsFor(value: Char): Set<ClockSegment>? = digits[value]

    /** True when every character is a digit or the colon separator. */
    fun supports(text: String): Boolean = text.isNotEmpty() && text.all { it == ':' || digits.containsKey(it) }
}

/**
 * The optional lines under the clock. Empty strings are dropped by [text].
 *
 * [focusLine] (T73) is the active focus mode, e.g. `focus · Work`. It leads the line because it is
 * transient and the user may need it at a glance; the date and alarm follow in their T71 order.
 */
data class ClockDetail(
    val dateLine: String = "",
    val alarmLine: String = "",
    val focusLine: String = "",
) {
    /** True when at least one line has text, so a caller can reserve a subline. */
    val hasText: Boolean get() = focusLine.isNotBlank() || dateLine.isNotBlank() || alarmLine.isNotBlank()

    val text: String
        get() = listOf(focusLine, dateLine, alarmLine)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
}

/** Pure clock/date/alarm formatting for the look screen and the renderer. */
object LookClock {
    private val DatePattern = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private val TimePattern = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

    /** e.g. `Thu 15 Oct`. The caller supplies the locale; tests pin English. */
    fun dateLine(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        date.format(DateTimeFormatter.ofPattern("EEE d MMM", locale))

    /**
     * e.g. `Alarm 07:30`, or empty when there is no next alarm or it is already due. The trigger
     * is an epoch-millisecond timestamp as returned by `AlarmManager.getNextAlarmClock`.
     */
    fun alarmLine(triggerAtMillis: Long?, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        if (triggerAtMillis == null || triggerAtMillis <= nowMillis) return ""
        val time = Instant.ofEpochMilli(triggerAtMillis).atZone(zone).toLocalTime()
        return "Alarm " + time.format(TimePattern)
    }

    /** Combines the enabled lines in display order. */
    fun detail(showDate: Boolean, showAlarm: Boolean, date: LocalDate, alarmAtMillis: Long?,
        nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): ClockDetail = ClockDetail(
        dateLine = if (showDate) dateLine(date) else "",
        alarmLine = if (showAlarm) alarmLine(alarmAtMillis, nowMillis, zone) else "",
    )

    /** Default English date pattern, exposed for the renderer's fixed-width planning. */
    fun dateText(date: LocalDate): String = date.format(DatePattern)

    /** Digital clock width in characters including the colon. */
    fun digitalCharacters(text: String): Int = text.length
}

/** Strict versioned JSON codec for [LookSettings]; unknown fields and invalid values throw. */
object LookJson {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    fun migrate(look: LookSettings): LookSettings {
        val readable = look.keepTextReadable ?: when (look.wallpaper) {
            WallpaperStyle.Image -> look.imageReadabilityScrim
            WallpaperStyle.System -> look.wallpaperReadability
            else -> look.wallpaperReadability || look.imageReadabilityScrim
        }
        return look.copy(keepTextReadable = readable, wallpaperReadability = readable, imageReadabilityScrim = readable)
    }
    fun encode(look: LookSettings): String = json.encodeToString(LookSettings.serializer(), migrate(look))
    fun decode(source: String): LookSettings = migrate(json.decodeFromString(LookSettings.serializer(), source))
}
