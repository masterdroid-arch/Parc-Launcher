package com.brightblu.parc.core.data.stats

/** Whether a metric alerts when it rises above or falls below its threshold. */
enum class StatsAlertDirection { High, Low }

/**
 * One configurable stats alert (T78). [validRange] is the range the settings editor may offer and
 * the range a stored value is clamped to; [hysteresis] is the width of the recovery band, so a value
 * sitting on the threshold cannot flap the alert on and off.
 *
 * Boundary rules, fixed by this model so every consumer agrees:
 * - [StatsAlertDirection.High]: active at `value >= threshold`; while active it stays active until
 *   `value <= threshold - hysteresis`.
 * - [StatsAlertDirection.Low]: active at `value < threshold`; while active it stays active until
 *   `value >= threshold + hysteresis`. [StatsAlertMetric.BatteryLow] additionally clears the moment
 *   the battery starts charging, whatever the percentage.
 *
 * Defaults are the T78 card's: battery temperature 42 °C, storage above 90 %, battery below 15 %
 * while not charging, RAM above 90 %. Values are already in the metric's own unit (percent or °C).
 */
enum class StatsAlertMetric(
    val defaultValue: Float,
    val validRange: ClosedFloatingPointRange<Float>,
    val hysteresis: Float,
    val direction: StatsAlertDirection,
) {
    BatteryTemperatureHigh(
        defaultValue = 42f,
        validRange = 0f..100f,
        hysteresis = 1f,
        direction = StatsAlertDirection.High,
    ),
    StorageHigh(
        defaultValue = 90f,
        validRange = 1f..100f,
        hysteresis = 2f,
        direction = StatsAlertDirection.High,
    ),
    BatteryLow(
        defaultValue = 15f,
        validRange = 0f..100f,
        hysteresis = 2f,
        direction = StatsAlertDirection.Low,
    ),
    RamHigh(
        defaultValue = 90f,
        validRange = 1f..100f,
        hysteresis = 2f,
        direction = StatsAlertDirection.High,
    ),
}

/**
 * One stored alert threshold (T78): which metric, whether the alert is armed, and the value the user
 * chose. [value] must be finite; [StatsAlertThresholds.normalize] clamps it into
 * [StatsAlertMetric.validRange] before it reaches the evaluator.
 *
 * Immutable and non-null.
 */
data class StatsAlertThreshold(
    val metric: StatsAlertMetric,
    val enabled: Boolean = true,
    val value: Float = metric.defaultValue,
) {
    init {
        require(value.isFinite()) { "Stats alert threshold must be finite: $value" }
        require(value >= metric.validRange.start && value <= metric.validRange.endInclusive) {
            "Stats alert threshold out of range for ${metric.name}: $value"
        }
    }
}

/** Defaults and normalisation for the four T78 thresholds. */
object StatsAlertThresholds {

    /** One enabled threshold per metric, in enum order, at the card's default values. */
    val Default: List<StatsAlertThreshold> = StatsAlertMetric.entries.map {
        StatsAlertThreshold(metric = it, enabled = true, value = it.defaultValue)
    }

    /**
     * Returns exactly one threshold per metric in enum order: a missing metric gets its default and
     * duplicate entries keep the first. Values are already constrained by [StatsAlertThreshold];
     * a decoded document is clamped before it is constructed. Never returns an empty list.
     */
    fun normalize(thresholds: List<StatsAlertThreshold>): List<StatsAlertThreshold> =
        StatsAlertMetric.entries.map { metric ->
            thresholds.firstOrNull { it.metric == metric } ?: defaultFor(metric)
        }

    /** The default threshold for one metric, enabled, at the card's value. */
    fun defaultFor(metric: StatsAlertMetric): StatsAlertThreshold =
        StatsAlertThreshold(metric = metric, enabled = true, value = metric.defaultValue)
}

/**
 * The pure alert evaluation (T78). Stateless: the caller passes the previously active set, which is
 * the whole hysteresis memory, so the same inputs always produce the same output.
 *
 * A disabled metric is never active, even if it was active before. A metric whose reading is
 * unavailable this tick is also inactive: an alert is a claim about the present, and it is dropped
 * rather than guessed at. A currently active alert survives inside its recovery band, which is what
 * stops a value hovering on the threshold from flickering.
 *
 * `BatteryLow` counts as "not charging" when the charging state is null: the launcher then cannot
 * tell that power is connected, and a low-battery warning that may be unnecessary is safer than
 * hiding one that is. `NotCharging` (plugged but not charging) counts as not charging.
 *
 * All inputs and the result are immutable; the function allocates only its result set.
 */
object StatsAlertRules {

    /**
     * The alerts active now. [previouslyActive] is the result of the previous call for the same
     * threshold set; pass an empty set on the first evaluation.
     */
    fun evaluate(
        stats: DeviceStats,
        thresholds: List<StatsAlertThreshold>,
        previouslyActive: Set<StatsAlertMetric> = emptySet(),
    ): Set<StatsAlertMetric> {
        val active = LinkedHashSet<StatsAlertMetric>()
        for (threshold in StatsAlertThresholds.normalize(thresholds)) {
            if (!threshold.enabled) continue
            val value = valueOf(threshold.metric, stats) ?: continue
            val wasActive = threshold.metric in previouslyActive
            val nowActive = when (threshold.metric) {
                StatsAlertMetric.BatteryLow -> {
                    val charging = stats.chargingState == ChargingState.Charging ||
                        stats.chargingState == ChargingState.Full
                    !charging && if (wasActive) {
                        value < threshold.value + threshold.metric.hysteresis
                    } else {
                        value < threshold.value
                    }
                }
                StatsAlertMetric.BatteryTemperatureHigh,
                StatsAlertMetric.StorageHigh,
                StatsAlertMetric.RamHigh,
                -> {
                    if (wasActive) {
                        value > threshold.value - threshold.metric.hysteresis
                    } else {
                        value >= threshold.value
                    }
                }
            }
            if (nowActive) active += threshold.metric
        }
        return active
    }

    /**
     * The current reading a metric watches, or null when the source is unavailable. Storage prefers
     * the used/total byte reading and falls back to `100 - freePercent`; both describe the same
     * "used" figure the card's 90 % threshold means.
     */
    fun valueOf(metric: StatsAlertMetric, stats: DeviceStats): Float? = when (metric) {
        StatsAlertMetric.BatteryTemperatureHigh -> stats.batteryTemperatureC
        StatsAlertMetric.StorageHigh ->
            stats.storage?.usedPercent?.toFloat()
                ?: stats.storageFreePercent?.let { (100 - it).toFloat() }
        StatsAlertMetric.BatteryLow -> stats.batteryPercent?.toFloat()
        StatsAlertMetric.RamHigh -> stats.ramUsedPercent?.toFloat()
    }
}
