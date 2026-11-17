package com.brightblu.parc.core.data.steps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull

/** The phone's step counter, as the repository sees it. A fake stands in for it in tests. */
internal interface StepSource {
    /** True when this phone has a step counter sensor at all. */
    fun hasSensor(): Boolean

    /** True when the activity recognition permission is held (the sensor delivers nothing without it). */
    fun granted(): Boolean

    /** The sensor's readings as they arrive, until the collector stops. Empty when there is no sensor or no permission. */
    fun updates(): Flow<StepReading>

    /** One reading now, or null when there is no sensor, no permission, or it did not answer in time. */
    suspend fun readOnce(): StepReading?
}

/** `Sensor.TYPE_STEP_COUNTER` over [SensorManager]. No service: it listens only while somebody collects. */
internal class AndroidStepSource(context: Context) : StepSource {
    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? get() = manager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    override fun hasSensor(): Boolean = sensor != null

    override fun granted(): Boolean =
        app.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    override fun updates(): Flow<StepReading> = callbackFlow {
        val sm = manager
        val step = sensor
        if (sm == null || step == null || !granted()) {
            close()
            return@callbackFlow
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val value = event.values.firstOrNull() ?: return
                trySend(StepReading(value.toLong(), SystemClock.elapsedRealtime()))
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val registered = runCatching { sm.registerListener(listener, step, SensorManager.SENSOR_DELAY_NORMAL) }.getOrDefault(false)
        if (!registered) close()
        awaitClose { runCatching { sm.unregisterListener(listener) } }
    }

    override suspend fun readOnce(): StepReading? =
        withTimeoutOrNull(ReadTimeoutMillis) { updates().firstOrNull() }

    private companion object {
        /** An on-change sensor answers a new listener at once; this only bounds a sensor that stays silent. */
        const val ReadTimeoutMillis = 3_000L
    }
}
