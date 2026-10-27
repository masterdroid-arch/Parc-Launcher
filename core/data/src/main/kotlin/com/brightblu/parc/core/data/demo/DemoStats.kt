package com.brightblu.parc.core.data.demo

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale
import kotlin.math.roundToInt

/**
 * T121: two weeks of invented stats for the demo namespace.
 *
 * Every field is a number or a day index, so there is no place for an app name, a contact, a
 * location or any other personal value. The fixture is the only source of demo stats, and
 * [DemoStatsStore] keeps it under the demo namespace created by T120a/T120b.
 */
@Serializable
data class DemoDay(
    /** 1 is the oldest of the fourteen days, [DemoStatsFixture.DayCount] is today. */
    val day: Int,
    val screenTimeMinutes: Int,
    val unlocks: Int,
    val steps: Int,
    val batteryPercent: Int,
)

/** An immutable demo sample. Built by [DemoStatsFixture] or decoded from the demo store. */
@Serializable
data class DemoSample(val days: List<DemoDay>) {
    /** The newest day; null only for a hand-built empty sample. */
    val today: DemoDay? get() = days.lastOrNull()

    val screenTimeAverageMinutes: Int get() = DemoStatsRules.average(days.map { it.screenTimeMinutes })
    val unlocksAverage: Int get() = DemoStatsRules.average(days.map { it.unlocks })
    val stepsAverage: Int get() = DemoStatsRules.average(days.map { it.steps })
    val batteryLowPercent: Int get() = DemoStatsRules.low(days.map { it.batteryPercent })
    val batteryHighPercent: Int get() = DemoStatsRules.high(days.map { it.batteryPercent })
}

/** Pure arithmetic and the validity gate applied to anything read back from the demo store. */
object DemoStatsRules {
    const val DayCount = 14

    /** Rounded mean; zero for an empty list so the UI never divides by zero. */
    fun average(values: List<Int>): Int =
        if (values.isEmpty()) 0 else (values.sum().toDouble() / values.size).roundToInt()

    fun low(values: List<Int>): Int = values.minOrNull() ?: 0

    fun high(values: List<Int>): Int = values.maxOrNull() ?: 0

    /** True only for exactly fourteen consecutive days with physically possible values. */
    fun isValid(sample: DemoSample): Boolean {
        val days = sample.days
        if (days.size != DayCount) return false
        if (days.map { it.day } != (1..DayCount).toList()) return false
        return days.all { day ->
            day.screenTimeMinutes in 0..1_440 &&
                day.unlocks in 0..1_000 &&
                day.steps in 0..100_000 &&
                day.batteryPercent in 0..100
        }
    }
}

/**
 * The hard-coded sample. Values are plausible for a quiet phone day: no app names are involved,
 * and the four series vary so a sparkline is worth drawing.
 */
object DemoStatsFixture {
    const val DayCount = DemoStatsRules.DayCount

    fun sample(): DemoSample = DemoSample(
        listOf(
            DemoDay(day = 1, screenTimeMinutes = 206, unlocks = 78, steps = 5_210, batteryPercent = 72),
            DemoDay(day = 2, screenTimeMinutes = 231, unlocks = 84, steps = 4_870, batteryPercent = 68),
            DemoDay(day = 3, screenTimeMinutes = 198, unlocks = 71, steps = 6_120, batteryPercent = 74),
            DemoDay(day = 4, screenTimeMinutes = 264, unlocks = 96, steps = 7_330, batteryPercent = 59),
            DemoDay(day = 5, screenTimeMinutes = 248, unlocks = 91, steps = 8_450, batteryPercent = 63),
            DemoDay(day = 6, screenTimeMinutes = 172, unlocks = 62, steps = 10_120, batteryPercent = 81),
            DemoDay(day = 7, screenTimeMinutes = 189, unlocks = 69, steps = 9_340, batteryPercent = 77),
            DemoDay(day = 8, screenTimeMinutes = 222, unlocks = 80, steps = 6_230, batteryPercent = 66),
            DemoDay(day = 9, screenTimeMinutes = 205, unlocks = 76, steps = 5_540, batteryPercent = 70),
            DemoDay(day = 10, screenTimeMinutes = 241, unlocks = 88, steps = 4_980, batteryPercent = 61),
            DemoDay(day = 11, screenTimeMinutes = 268, unlocks = 99, steps = 7_870, batteryPercent = 55),
            DemoDay(day = 12, screenTimeMinutes = 233, unlocks = 86, steps = 8_920, batteryPercent = 58),
            DemoDay(day = 13, screenTimeMinutes = 164, unlocks = 58, steps = 11_480, batteryPercent = 84),
            DemoDay(day = 14, screenTimeMinutes = 142, unlocks = 51, steps = 4_260, batteryPercent = 71),
        ),
    )
}

/** Locale-independent text for the demo tiles, tested in `DemoStatsTest`. */
object DemoStatsFormat {
    const val Unavailable = "—"

    /** `2 h 22 min`, `58 min`, `3 h`, or [Unavailable] for a negative value. */
    fun duration(minutes: Int): String {
        if (minutes < 0) return Unavailable
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0 -> "$rest min"
            rest == 0 -> "$hours h"
            else -> "$hours h $rest min"
        }
    }

    /** `4,260`, grouped with a comma regardless of the device locale. */
    fun count(value: Int): String = String.format(Locale.US, "%,d", value)

    fun percent(value: Int): String = "$value%"
}

/** The demo store's file format. Decoding rejects anything [DemoStatsRules] does not accept. */
object DemoStatsJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(sample: DemoSample): String = json.encodeToString(DemoSample.serializer(), sample)

    fun decode(raw: String): DemoSample? =
        runCatching { json.decodeFromString(DemoSample.serializer(), raw) }.getOrNull()
            ?.takeIf(DemoStatsRules::isValid)
}
