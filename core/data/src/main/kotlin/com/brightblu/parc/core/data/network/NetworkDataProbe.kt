package com.brightblu.parc.core.data.network

/** The two transports T51 reports separately. */
internal enum class NetworkTransport { Wifi, Mobile }

/** Neutral byte counts returned by one device-total query. */
internal data class NetworkBytes(val rxBytes: Long, val txBytes: Long)

/**
 * One per-uid bucket, with the package name resolved by the Android probe. [packageName] is null
 * when the platform cannot attribute the uid (system processes, removed apps, tethering); the pure
 * aggregation then drops it, because the card asks for labelled apps.
 */
internal data class UidBytes(
    val uid: Int,
    val packageName: String?,
    val rxBytes: Long,
    val txBytes: Long,
)

/** Current Wi-Fi link, reduced to the two public values T51 exposes. */
internal data class WifiLinkSample(val rssiDbm: Int?, val linkSpeedMbps: Int?)

/**
 * The Android boundary for T51's network data. Implementations must not throw and must return null
 * for a missing grant, a missing service or a refused query. [AndroidNetworkStatsRepository] guards
 * every call as well.
 *
 * Thread-safety: may be called from any thread; implementations must be re-entrant.
 */
internal interface NetworkDataProbe {

    /** True only while the GET_USAGE_STATS app op is allowed. Never throws. */
    fun usageAccessGranted(): Boolean

    /** Whole-device bytes for one transport, or null on refusal/failure. */
    fun deviceTotals(
        transport: NetworkTransport,
        startMillis: Long,
        endMillis: Long,
    ): NetworkBytes?

    /** Per-uid buckets for one transport, or null on refusal/failure. Empty is a real zero. */
    fun perUidTotals(
        transport: NetworkTransport,
        startMillis: Long,
        endMillis: Long,
    ): List<UidBytes>?

    /** Current Wi-Fi RSSI/link speed, or null when Wi-Fi is off, disconnected or denied. */
    fun wifiLink(): WifiLinkSample?

    /** Raw `TelephonyManager` network type, or null when the telephone permission is absent. */
    fun mobileNetworkType(): Int?
}
