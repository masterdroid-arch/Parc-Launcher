package com.brightblu.parc.ring

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * T80 sound generator. Short PCM tones are synthesised in memory at start, so Parc ships no audio
 * assets. Pure and deterministic (the noise is a fixed linear congruential sequence); every theme
 * stays far below the 20 KB budget in [maxBytesPerTheme].
 */
object FeelSynth {
    const val SampleRate = 22_050
    /** Budget for all four events of one theme, in bytes of 16-bit PCM. */
    const val maxBytesPerTheme = 20 * 1024

    private const val Peak = 0.8f

    /** PCM 16-bit mono for [event] in [theme]; empty for [SoundTheme.Off]. Full scale, volume is applied at playback. */
    fun generate(theme: SoundTheme, event: FeelEvent): ShortArray = when (theme) {
        SoundTheme.Off -> ShortArray(0)
        SoundTheme.SoftTicks -> softTick(event)
        SoundTheme.Mechanical -> mechanical(event)
        SoundTheme.Synth -> synth(event)
    }

    fun bytesFor(theme: SoundTheme): Int = FeelEvent.entries.sumOf { generate(theme, it).size * 2 }

    private fun samples(ms: Int) = SampleRate * ms / 1000

    private fun pcm(n: Int, f: (Int, Float) -> Float): ShortArray = ShortArray(n) { i ->
        val t = i.toFloat() / SampleRate
        (f(i, t).coerceIn(-1f, 1f) * Peak * Short.MAX_VALUE).toInt().toShort()
    }

    /** Quiet sine tick with a smooth attack and exponential decay. */
    private fun softTick(event: FeelEvent): ShortArray {
        val (hz, ms) = when (event) {
            FeelEvent.Tick -> 1800f to 18
            FeelEvent.Confirm -> 1400f to 30
            FeelEvent.Menu -> 1100f to 36
            FeelEvent.Reject -> 700f to 40
        }
        val n = samples(ms)
        return pcm(n) { i, t ->
            val attack = (i / 40f).coerceAtMost(1f)
            sin(2 * PI * hz * t).toFloat() * attack * exp(-t * 180f) * 0.7f
        }
    }

    /** Dry key click: a noise burst plus a low thump, both with a hard attack. */
    private fun mechanical(event: FeelEvent): ShortArray {
        val (hz, ms, noise) = when (event) {
            FeelEvent.Tick -> Triple(1500f, 14, 0.7f)
            FeelEvent.Confirm -> Triple(900f, 28, 0.6f)
            FeelEvent.Menu -> Triple(600f, 34, 0.6f)
            FeelEvent.Reject -> Triple(380f, 44, 0.5f)
        }
        var seed = 12345
        val n = samples(ms)
        return pcm(n) { _, t ->
            seed = seed * 1103515245 + 12345
            val rnd = ((seed ushr 16) and 0x7FFF) / 16384f - 1f
            rnd * noise * exp(-t * 400f) + sin(2 * PI * hz * t).toFloat() * exp(-t * 140f) * 0.6f
        }
    }

    /** Electronic blip: a square-ish tone with a pitch slide (rising on confirm, falling on reject). */
    private fun synth(event: FeelEvent): ShortArray {
        val (from, to, ms) = when (event) {
            FeelEvent.Tick -> Triple(1500f, 1500f, 22)
            FeelEvent.Confirm -> Triple(1000f, 1600f, 50)
            FeelEvent.Menu -> Triple(800f, 1200f, 55)
            FeelEvent.Reject -> Triple(900f, 450f, 70)
        }
        val n = samples(ms)
        var phase = 0.0
        return pcm(n) { i, t ->
            val hz = from + (to - from) * i / n
            phase += 2 * PI * hz / SampleRate
            val square = if (sin(phase) >= 0) 1f else -1f
            val tone = 0.6f * square + 0.4f * sin(phase).toFloat()
            val fade = (1f - i.toFloat() / n)
            tone * fade * fade * (i / 60f).coerceAtMost(1f) * 0.6f
        }
    }
}
