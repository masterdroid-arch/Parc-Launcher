package com.brightblu.parc

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.tan

/*
 * T189: the Sky page's maths. Framework-free (java.time only), no network, no sensor.
 * Sun: the NOAA solar equations (the ones behind the NOAA solar calculator, after Meeus, Astronomical Algorithms).
 * Moon: Meeus ch. 48 (illuminated fraction from the Moon's elongation with the six largest periodic terms).
 */

/**
 * The place the sky is worked out for. [utcOffsetSeconds] is the place's own offset when it is known (the weather
 * home place knows it); null means "use the phone's offset" (a place typed by hand).
 */
internal data class SkyPlace(val latitude: Double, val longitude: Double, val name: String = "", val utcOffsetSeconds: Int? = null) {
    val valid: Boolean get() = latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
}

/** A time range in epoch milliseconds. */
internal data class SkySpan(val startMillis: Long, val endMillis: Long)

internal enum class SunState { Normal, StaysUp, StaysDown }

/** The sun for one local date at a place. All times are epoch milliseconds. */
internal data class SunDay(
    val date: LocalDate,
    val state: SunState,
    val sunriseMillis: Long?,
    val sunsetMillis: Long?,
    val solarNoonMillis: Long,
    val dayLengthMillis: Long,
    val morningBlue: SkySpan?,
    val morningGolden: SkySpan?,
    val eveningGolden: SkySpan?,
    val eveningBlue: SkySpan?,
)

internal enum class MoonName(val label: String) {
    New("New moon"), WaxingCrescent("Waxing crescent"), FirstQuarter("First quarter"), WaxingGibbous("Waxing gibbous"),
    Full("Full moon"), WaningGibbous("Waning gibbous"), LastQuarter("Last quarter"), WaningCrescent("Waning crescent"),
}

/** The moon at one instant: [elongationDegrees] 0 is new, 90 first quarter, 180 full, 270 last quarter. */
internal data class MoonNow(val elongationDegrees: Double, val litFraction: Double, val waxing: Boolean, val name: MoonName) {
    val litPercent: Int get() = (litFraction * 100.0).roundToInt().coerceIn(0, 100)
}

internal object SkyMath {
    private const val MillisPerDay = 86_400_000L
    private const val UnixEpochJd = 2_440_587.5
    /** Sunrise and sunset: the sun's centre 0.833 degrees below the horizon (disc radius plus average refraction). */
    const val SunriseAltitude = -0.833
    const val GoldenTop = 6.0
    const val GoldenBottom = -4.0
    const val BlueBottom = -6.0
    const val SynodicDays = 29.530588853

    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)
    private fun mod(x: Double, m: Double) = x - m * floor(x / m)

    fun julianDay(epochMillis: Long): Double = epochMillis.toDouble() / MillisPerDay + UnixEpochJd
    private fun century(jd: Double) = (jd - 2_451_545.0) / 36_525.0

    /** The sun's declination (radians) and the equation of time (minutes) at a Julian day. */
    private class SunPos(val declination: Double, val equationOfTime: Double)

    private fun sunPos(jd: Double): SunPos {
        val t = century(jd)
        val l0 = mod(280.46646 + t * (36000.76983 + t * 0.0003032), 360.0)
        val m = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val mr = rad(m)
        val c = sin(mr) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(2 * mr) * (0.019993 - 0.000101 * t) + sin(3 * mr) * 0.000289
        val trueLong = l0 + c
        val omega = 125.04 - 1934.136 * t
        val apparent = trueLong - 0.00569 - 0.00478 * sin(rad(omega))
        val meanObliq = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val obliq = meanObliq + 0.00256 * cos(rad(omega))
        val decl = asin(sin(rad(obliq)) * sin(rad(apparent)))
        val y = tan(rad(obliq) / 2.0).pow(2)
        val l0r = rad(l0)
        val eqTime = 4.0 * deg(
            y * sin(2 * l0r) - 2.0 * e * sin(mr) + 4.0 * e * y * sin(mr) * cos(2 * l0r) -
                0.5 * y * y * sin(4 * l0r) - 1.25 * e * e * sin(2 * mr),
        )
        return SunPos(decl, eqTime)
    }

    /** The sun's height above the horizon in degrees (geometric, no refraction) at an instant. */
    fun elevationDegrees(place: SkyPlace, epochMillis: Long): Double {
        val pos = sunPos(julianDay(epochMillis))
        val utcMinutes = mod(epochMillis.toDouble() / 60_000.0, 1440.0)
        val trueSolarTime = mod(utcMinutes + pos.equationOfTime + 4.0 * place.longitude, 1440.0)
        val hourAngle = rad(trueSolarTime / 4.0 - 180.0)
        val lat = rad(place.latitude)
        return deg(asin((sin(lat) * sin(pos.declination) + cos(lat) * cos(pos.declination) * cos(hourAngle)).coerceIn(-1.0, 1.0)))
    }

    /** Minutes after 0h UTC of [date] at which the sun's centre passes [altitude] degrees, rising or setting; null if it never does that day. */
    private fun eventMinutes(date: LocalDate, place: SkyPlace, altitude: Double, rising: Boolean): Double? {
        val jd0 = julianDay(date.toEpochDay() * MillisPerDay)
        val lat = rad(place.latitude)
        var minutes = 720.0 - 4.0 * place.longitude
        // First pass near local noon, then two refinements at the time the event was found.
        repeat(3) {
            val pos = sunPos(jd0 + minutes / 1440.0)
            val cosHa = (sin(rad(altitude)) - sin(lat) * sin(pos.declination)) / (cos(lat) * cos(pos.declination))
            if (!cosHa.isFinite() || cosHa < -1.0 || cosHa > 1.0) return null
            val ha = deg(acos(cosHa))
            minutes = 720.0 - 4.0 * (place.longitude + if (rising) ha else -ha) - pos.equationOfTime
        }
        return minutes
    }

    private fun toMillis(date: LocalDate, minutes: Double): Long = date.toEpochDay() * MillisPerDay + (minutes * 60_000.0).roundToLong()

    private fun span(date: LocalDate, place: SkyPlace, fromAlt: Double, fromRising: Boolean, toAlt: Double, toRising: Boolean): SkySpan? {
        val a = eventMinutes(date, place, fromAlt, fromRising) ?: return null
        val b = eventMinutes(date, place, toAlt, toRising) ?: return null
        return SkySpan(toMillis(date, a), toMillis(date, b))
    }

    /**
     * The sun on local [date] at [place]. The date only selects the day (its mean solar noon); the result is in absolute
     * time, so showing it in any offset is the caller's business.
     */
    fun sunDay(date: LocalDate, place: SkyPlace): SunDay {
        val jd0 = julianDay(date.toEpochDay() * MillisPerDay)
        var noon = 720.0 - 4.0 * place.longitude
        repeat(2) { noon = 720.0 - 4.0 * place.longitude - sunPos(jd0 + noon / 1440.0).equationOfTime }
        val noonMillis = toMillis(date, noon)
        val rise = eventMinutes(date, place, SunriseAltitude, rising = true)
        val set = eventMinutes(date, place, SunriseAltitude, rising = false)
        val state = when {
            rise != null && set != null -> SunState.Normal
            elevationDegrees(place, noonMillis) > SunriseAltitude -> SunState.StaysUp
            else -> SunState.StaysDown
        }
        val riseMillis = if (state == SunState.Normal) toMillis(date, rise!!) else null
        val setMillis = if (state == SunState.Normal) toMillis(date, set!!) else null
        val length = when (state) {
            SunState.Normal -> setMillis!! - riseMillis!!
            SunState.StaysUp -> MillisPerDay
            SunState.StaysDown -> 0L
        }
        return SunDay(
            date, state, riseMillis, setMillis, noonMillis, length,
            morningBlue = span(date, place, BlueBottom, true, GoldenBottom, true),
            morningGolden = span(date, place, GoldenBottom, true, GoldenTop, true),
            eveningGolden = span(date, place, GoldenTop, false, GoldenBottom, false),
            eveningBlue = span(date, place, GoldenBottom, false, BlueBottom, false),
        )
    }

    /** One local day of sun heights for the arc, every [stepMinutes] from local midnight (offset in seconds). */
    fun curve(date: LocalDate, place: SkyPlace, offsetSeconds: Int, stepMinutes: Int = 15): List<Double> {
        val start = date.toEpochDay() * MillisPerDay - offsetSeconds * 1000L
        return (0..(1440 / stepMinutes)).map { elevationDegrees(place, start + it * stepMinutes * 60_000L) }
    }

    // --- Moon ----------------------------------------------------------------------------------------------------------

    /** The Moon's elongation from the Sun in degrees, 0 until 360: new = 0, first quarter = 90, full = 180, last quarter = 270. */
    fun moonElongation(epochMillis: Long): Double {
        val t = century(julianDay(epochMillis))
        val d = 297.8501921 + 445267.1114034 * t - 0.0018819 * t * t + t * t * t / 545868.0
        val m = 357.5291092 + 35999.0502909 * t - 0.0001536 * t * t
        val mp = 134.9633964 + 477198.8675055 * t + 0.0087414 * t * t + t * t * t / 69699.0
        val dr = rad(d); val mr = rad(m); val mpr = rad(mp)
        val psi = d + 6.289 * sin(mpr) - 2.100 * sin(mr) + 1.274 * sin(2 * dr - mpr) + 0.658 * sin(2 * dr) + 0.214 * sin(2 * mpr) + 0.110 * sin(dr)
        return mod(psi, 360.0)
    }

    /** The share of the visible disc that is lit, 0 until 1. */
    fun litFraction(epochMillis: Long): Double = (1.0 - cos(rad(moonElongation(epochMillis)))) / 2.0

    /** The four main phases are named within this many degrees (about 15 hours) of the exact moment; between them the in-between names. */
    private const val MainPhaseWindow = 7.5

    fun nameFor(elongation: Double): MoonName = when {
        elongation < MainPhaseWindow || elongation >= 360.0 - MainPhaseWindow -> MoonName.New
        abs(elongation - 90.0) < MainPhaseWindow -> MoonName.FirstQuarter
        abs(elongation - 180.0) < MainPhaseWindow -> MoonName.Full
        abs(elongation - 270.0) < MainPhaseWindow -> MoonName.LastQuarter
        elongation < 90.0 -> MoonName.WaxingCrescent
        elongation < 180.0 -> MoonName.WaxingGibbous
        elongation < 270.0 -> MoonName.WaningGibbous
        else -> MoonName.WaningCrescent
    }

    fun moon(epochMillis: Long): MoonNow {
        val e = moonElongation(epochMillis)
        return MoonNow(e, (1.0 - cos(rad(e))) / 2.0, waxing = e < 180.0, name = nameFor(e))
    }

    /** The next moment after [fromMillis] when the elongation reaches [targetDegrees] (0 new, 180 full). Searches six-hour steps, then bisects. */
    fun nextPhase(fromMillis: Long, targetDegrees: Double): Long? {
        fun f(t: Long) = mod(moonElongation(t) - targetDegrees + 180.0, 360.0) - 180.0
        val step = 6L * 3_600_000L
        var a = fromMillis
        var fa = f(a)
        for (i in 1..(36 * 4)) {
            val b = a + step
            val fb = f(b)
            if (fa < 0.0 && fb >= 0.0 && fb - fa < 90.0) {
                var lo = a; var hi = b
                repeat(32) { val mid = (lo + hi) / 2; if (f(mid) < 0.0) lo = mid else hi = mid }
                return hi
            }
            a = b; fa = fb
        }
        return null
    }

    fun nextFullMoon(fromMillis: Long): Long? = nextPhase(fromMillis, 180.0)
    fun nextNewMoon(fromMillis: Long): Long? = nextPhase(fromMillis, 0.0)
}

/** A hand-typed place: "38.72, -9.14". Pure parse and encode, so the options sheet and the backup agree. */
internal object SkyPlaceText {
    /** Latitude and longitude from text like `38.72, -9.14`, `38.72 -9.14` or `38,72; -9,14`; null when it is not a place. */
    fun parse(text: String?): SkyPlace? {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty() || raw.length > 64) return null
        var parts = raw.split(Regex("[;/\\s]+")).filter { it.isNotEmpty() }
        if (parts.size == 1 && parts[0].count { it == ',' } == 1) parts = parts[0].split(',').filter { it.isNotEmpty() }
        if (parts.size != 2) return null
        val numbers = parts.map { it.trimEnd(',').replace(',', '.').toDoubleOrNull() ?: return null }
        return SkyPlace(numbers[0], numbers[1]).takeIf { it.valid }
    }

    /** The stored form: two decimals are about a kilometre, more is false precision. */
    fun encode(place: SkyPlace?): String = place?.let { String.format(Locale.ROOT, "%.4f,%.4f", it.latitude, it.longitude) }.orEmpty()

    /** Old, empty or garbled stored text reads as no place. */
    fun decode(stored: String?): SkyPlace? = parse(stored)

    fun display(place: SkyPlace): String = String.format(Locale.ROOT, "%.2f, %.2f", place.latitude, place.longitude)
}

/** Words and numbers the Sky page shows, built from the maths. Pure, so tests can read them. */
internal object SkyView {
    const val SetAPlace = "Set a place"
    const val StaysUp = "Sun stays up"
    const val StaysDown = "Sun stays down"

    /** `06:12`, in the given offset. */
    fun clock(epochMillis: Long, offsetSeconds: Int): String {
        val local = Instant.ofEpochMilli(epochMillis).atOffset(ZoneOffset.ofTotalSeconds(offsetSeconds))
        return String.format(Locale.ROOT, "%02d:%02d", local.hour, local.minute)
    }

    fun range(span: SkySpan?, offsetSeconds: Int): String =
        span?.let { clock(it.startMillis, offsetSeconds) + " to " + clock(it.endMillis, offsetSeconds) } ?: "—"

    /** `UTC+4`, `UTC+5:30`, `UTC-3`, `UTC`. */
    fun offsetLabel(offsetSeconds: Int): String {
        if (offsetSeconds == 0) return "UTC"
        val sign = if (offsetSeconds < 0) "-" else "+"
        val total = abs(offsetSeconds) / 60
        val h = total / 60
        val m = total % 60
        return "UTC$sign$h" + if (m != 0) String.format(Locale.ROOT, ":%02d", m) else ""
    }

    /** `7 h 52 min`, `24 h`, `0 h`. */
    fun length(millis: Long): String {
        val minutes = (millis / 60_000.0).roundToInt()
        return if (minutes % 60 == 0) "${minutes / 60} h" else "${minutes / 60} h ${String.format(Locale.ROOT, "%02d", minutes % 60)} min"
    }

    /** How today's daylight differs from yesterday's: `2 min shorter`, `1 min longer`, `1 h 05 min longer`, `Same as yesterday`. */
    fun change(todayMillis: Long, yesterdayMillis: Long): String {
        val seconds = ((todayMillis - yesterdayMillis) / 1000.0).roundToLong()
        val minutes = (abs(seconds) / 60.0).roundToInt()
        if (minutes == 0) return "Same as yesterday"
        val amount = if (minutes < 60) "$minutes min" else length(minutes * 60_000L)
        return amount + if (seconds > 0) " longer" else " shorter"
    }

    /** `Mon 24 Nov`, in the given offset. */
    fun day(epochMillis: Long, offsetSeconds: Int, locale: Locale = Locale.getDefault()): String {
        val local = Instant.ofEpochMilli(epochMillis).atOffset(ZoneOffset.ofTotalSeconds(offsetSeconds))
        return local.dayOfWeek.getDisplayName(TextStyle.SHORT, locale) + " " + local.dayOfMonth + " " + local.month.getDisplayName(TextStyle.SHORT, locale)
    }

    /** The whole page as text. [phoneOffsetSeconds] is the phone's offset at [now]; a place with its own different offset gets a note. */
    data class Data(
        val place: SkyPlace?,
        val offsetSeconds: Int,
        val offsetNote: String?,
        val sun: SunDay?,
        val yesterdayLengthMillis: Long?,
        val moon: MoonNow,
        val nextFullMillis: Long?,
        val nextNewMillis: Long?,
        val sunHeightNow: Double?,
        val curve: List<Double>,
        /** How far through the place's local day [now] is, 0 until 1; null with no place. */
        val nowFraction: Double? = null,
    ) {
        val sunHeadline: String? get() = when (sun?.state) {
            SunState.StaysUp -> StaysUp
            SunState.StaysDown -> StaysDown
            else -> null
        }
        val changeText: String? get() = if (sun != null && yesterdayLengthMillis != null) change(sun.dayLengthMillis, yesterdayLengthMillis) else null
    }

    fun build(place: SkyPlace?, now: Instant, phoneOffsetSeconds: Int): Data {
        val nowMillis = now.toEpochMilli()
        val moon = SkyMath.moon(nowMillis)
        val full = SkyMath.nextFullMoon(nowMillis)
        val new = SkyMath.nextNewMoon(nowMillis)
        if (place == null || !place.valid) return Data(null, phoneOffsetSeconds, null, null, null, moon, full, new, null, emptyList())
        val offset = place.utcOffsetSeconds ?: phoneOffsetSeconds
        val date = now.atOffset(ZoneOffset.ofTotalSeconds(offset)).toLocalDate()
        val sun = SkyMath.sunDay(date, place)
        val yesterday = SkyMath.sunDay(date.minusDays(1), place)
        val note = if (offset != phoneOffsetSeconds) "Times in the place's own time (${offsetLabel(offset)})" else null
        val dayStart = date.toEpochDay() * 86_400_000L - offset * 1000L
        return Data(place, offset, note, sun, yesterday.dayLengthMillis, moon, full, new,
            SkyMath.elevationDegrees(place, nowMillis), SkyMath.curve(date, place, offset), ((nowMillis - dayStart) / 86_400_000.0).coerceIn(0.0, 1.0))
    }
}
