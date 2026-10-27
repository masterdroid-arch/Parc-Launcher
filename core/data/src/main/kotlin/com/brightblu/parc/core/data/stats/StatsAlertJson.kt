package com.brightblu.parc.core.data.stats

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * JSON codec for the stored T78 alert thresholds.
 *
 * Decoding is tolerant on purpose: a missing, blank or corrupt document reads as the card defaults,
 * so a bad preference can never disarm every alert silently. The metric is stored as its enum name
 * in a plain string, so a document written by a newer build that knows one more metric still loads
 * the metrics this build understands instead of discarding the whole list. Values are clamped into
 * the metric's range and a non-finite or unparsable value falls back to the default.
 *
 * Writing is canonical and always contains every known metric in enum order.
 */
internal object StatsAlertJson {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /** One stored threshold; [metric] is a string so unknown metrics can be skipped, not fatal. */
    @Serializable
    private data class ThresholdDto(
        val metric: String,
        val enabled: Boolean = true,
        val value: Float = 0f,
    )

    /** Encodes the normalised list in enum order. */
    fun encode(thresholds: List<StatsAlertThreshold>): String {
        val normalised = StatsAlertThresholds.normalize(thresholds)
        return json.encodeToString(
            ListSerializer(ThresholdDto.serializer()),
            normalised.map { ThresholdDto(it.metric.name, it.enabled, it.value) },
        )
    }

    /** Decodes a stored document; anything unusable becomes the defaults. */
    fun decode(text: String?): List<StatsAlertThreshold> {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return StatsAlertThresholds.Default
        val decoded = try {
            json.decodeFromString(ListSerializer(ThresholdDto.serializer()), raw)
        } catch (invalid: Exception) {
            return StatsAlertThresholds.Default
        }
        val parsed = decoded.mapNotNull { dto ->
            val metric = StatsAlertMetric.entries.firstOrNull { it.name == dto.metric }
                ?: return@mapNotNull null
            if (!dto.value.isFinite()) {
                StatsAlertThresholds.defaultFor(metric)
            } else {
                StatsAlertThreshold(
                    metric = metric,
                    enabled = dto.enabled,
                    value = dto.value.coerceIn(metric.validRange),
                )
            }
        }
        return StatsAlertThresholds.normalize(parsed)
    }
}
