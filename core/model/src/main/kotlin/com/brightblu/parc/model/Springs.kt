package com.brightblu.parc.model

/** Immutable, thread-safe spring tokens. Damping is dimensionless; stiffness assumes unit mass. */
data class SpringSpec(val damping: Float, val stiffness: Float)

/** DESIGN §2.6. Pure Kotlin; skin speed changes stiffness, never damping. */
object Springs {
    val press = SpringSpec(0.8f, 900f)
    val open = SpringSpec(0.62f, 380f)
    val sheet = SpringSpec(0.88f, 260f)
    val glow = SpringSpec(0.8f, 400f)
    const val openSpreadMs = 100
    const val settleStiffness = 320f

    fun settle(bounceDamping: Float) = SpringSpec(bounceDamping, settleStiffness)

    fun openDelayMs(index: Int, count: Int): Int =
        if (count <= 1 || index < 0 || index >= count) 0
        else (openSpreadMs.toLong() * index / (count - 1)).toInt()

    fun scaledStiffness(k: Float, motionScale: Float): Float = k * motionScale * motionScale
}
