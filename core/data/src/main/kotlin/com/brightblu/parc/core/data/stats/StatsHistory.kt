package com.brightblu.parc.core.data.stats

/**
 * Bounded sample history behind the stats sparklines (T32): the most recent [Capacity] samples of
 * the network receive rate, the network transmit rate and the CPU temperature, oldest first.
 *
 * Immutable: every `record` returns a new instance, so a snapshot already published on a
 * `StateFlow` never changes under a collector. `recordNetwork` appends both rates in one step and
 * the sampler only ever has both or neither, so the receive and transmit series stay the same
 * length and line up sample for sample. A null value is a missing sample and appends nothing,
 * which leaves a gap in time rather than a fake zero.
 */
data class StatsHistory(
    val networkRxBytesPerSecond: List<Long> = emptyList(),
    val networkTxBytesPerSecond: List<Long> = emptyList(),
    val cpuTemperatureC: List<Float> = emptyList(),
) {
    /** True when no series has a sample yet. */
    val isEmpty: Boolean
        get() = networkRxBytesPerSecond.isEmpty() &&
            networkTxBytesPerSecond.isEmpty() &&
            cpuTemperatureC.isEmpty()

    /** Appends one tick's rates; a null rate (no previous counter reading) appends nothing. */
    fun recordNetwork(rxBytesPerSecond: Long?, txBytesPerSecond: Long?): StatsHistory = StatsHistory(
        networkRxBytesPerSecond = append(networkRxBytesPerSecond, rxBytesPerSecond),
        networkTxBytesPerSecond = append(networkTxBytesPerSecond, txBytesPerSecond),
        cpuTemperatureC = cpuTemperatureC,
    )

    /** Appends one tick's CPU temperature; null appends nothing. */
    fun recordCpuTemperature(celsius: Float?): StatsHistory = StatsHistory(
        networkRxBytesPerSecond = networkRxBytesPerSecond,
        networkTxBytesPerSecond = networkTxBytesPerSecond,
        cpuTemperatureC = append(cpuTemperatureC, celsius),
    )

    companion object {
        /** Samples kept per series: one minute at the sampler's one-second tick. */
        const val Capacity = 60

        private fun <T> append(series: List<T>, value: T?): List<T> = when {
            value == null -> series
            series.size < Capacity -> series + value
            // Drop the oldest sample(s) this one pushes out, so the series never exceeds Capacity.
            else -> series.drop(series.size - Capacity + 1) + value
        }
    }
}
