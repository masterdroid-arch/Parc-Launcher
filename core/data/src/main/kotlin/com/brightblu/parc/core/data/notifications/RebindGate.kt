package com.brightblu.parc.core.data.notifications

/**
 * Rate limit for `NotificationListenerService.requestRebind` (T15).
 *
 * Android unbinds a listener for reasons that come in bursts (component disabled and re-enabled, a
 * package change, a profile switch), and each disconnect would otherwise fire another rebind
 * request. The gate is a pure clock so the policy is unit-tested instead of argued about; the
 * service feeds it `SystemClock.elapsedRealtime()`.
 */
internal class RebindGate(private val minIntervalMillis: Long = DefaultMinIntervalMillis) {

    init {
        require(minIntervalMillis >= 0) { "minIntervalMillis must not be negative" }
    }

    private var lastRequestMillis: Long? = null

    /** True when a request may be made at [nowMillis]; a granted call is recorded immediately. */
    @Synchronized
    fun shouldRequest(nowMillis: Long): Boolean {
        val previous = lastRequestMillis
        if (previous != null && nowMillis - previous < minIntervalMillis) return false
        lastRequestMillis = nowMillis
        return true
    }

    companion object {
        /** One request per 10 s is plenty: the system either rebinds or it does not. */
        const val DefaultMinIntervalMillis = 10_000L
    }
}
