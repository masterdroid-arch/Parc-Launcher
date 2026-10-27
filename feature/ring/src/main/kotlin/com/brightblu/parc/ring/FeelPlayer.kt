package com.brightblu.parc.ring

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View

/**
 * T80 central place where every launcher haptic event is performed. Call sites pass the view and a
 * [FeelEvent]; [prefs] is read at call time. With the default prefs (Crisp, sound Off) this is
 * exactly `view.performHapticFeedback(<the old constant>)`, so default behaviour is unchanged.
 *
 * Call sites keep their own existing gate (the Haptics On/Off setting); the Feel preset is applied
 * after it. Sounds are generated lazily on first use (or via [warmUp]) with [AudioTrack] and
 * never play while the ringer is silent or on vibrate, or at volume zero.
 */
class FeelPlayer(private val prefs: () -> FeelPrefs = { FeelPrefs() }) {
    private var vibrator: Vibrator? = null
    private var caps: FeelCapabilities? = null
    private val tracks = HashMap<Pair<SoundTheme, FeelEvent>, AudioTrack>()
    private val lock = Any()

    /** Performs [event] on [view]: haptic by preset, then the sound theme if allowed. [current] lets a settings preview play values that are not stored yet. */
    fun play(view: View, event: FeelEvent, current: FeelPrefs = prefs()) {
        performHaptic(view, event, current.haptic)
        if (current.sound != SoundTheme.Off) performSound(view.context, event, current)
    }

    /** Builds the tones for the current theme off the touch path. Safe to call repeatedly. */
    fun warmUp(context: Context) {
        val current = prefs()
        if (current.sound == SoundTheme.Off) { release(); return }
        FeelEvent.entries.forEach { track(current.sound, it) }
        capabilities(context)
    }

    /** Releases the audio tracks; they are rebuilt on demand. */
    fun release() = synchronized(lock) {
        tracks.values.forEach { runCatching { it.release() } }
        tracks.clear()
    }

    private fun performHaptic(view: View, event: FeelEvent, preset: HapticPreset) {
        val context = view.context
        when (val plan = FeelTheme.hapticPlan(preset, event, capabilities(context))) {
            HapticPlan.None -> Unit
            is HapticPlan.Platform -> view.performHapticFeedback(plan.constant)
            is HapticPlan.Predefined -> vibrate(view, event, VibrationEffect.createPredefined(plan.effectId))
            is HapticPlan.Composed -> {
                val effect = runCatching {
                    val composition = VibrationEffect.startComposition()
                    plan.steps.forEach { composition.addPrimitive(it.primitive, it.scale, it.delayMs) }
                    composition.compose()
                }.getOrNull()
                if (effect != null) vibrate(view, event, effect) else view.performHapticFeedback(event.platformConstant)
            }
        }
    }

    // VIBRATE is declared in the app manifest (this library has none), so lint cannot see it here.
    @SuppressLint("MissingPermission")
    private fun vibrate(view: View, event: FeelEvent, effect: VibrationEffect) {
        val v = vibrator
        if (v == null) { view.performHapticFeedback(event.platformConstant); return }
        // Touch usage so the system's touch-feedback switch still applies.
        val ok = runCatching {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        }.isSuccess
        if (!ok) view.performHapticFeedback(event.platformConstant)
    }

    private fun capabilities(context: Context): FeelCapabilities {
        caps?.let { return it }
        val v = runCatching {
            context.applicationContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
        }.getOrNull()
        vibrator = v
        val built = if (v == null) FeelCapabilities.Basic else object : FeelCapabilities {
            override val hasVibrator = v.hasVibrator()
            override fun effectSupported(effectId: Int) =
                runCatching { v.areAllEffectsSupported(effectId) == Vibrator.VIBRATION_EFFECT_SUPPORT_YES }.getOrDefault(false)
            override fun primitiveSupported(primitiveId: Int) =
                runCatching { v.areAllPrimitivesSupported(primitiveId) }.getOrDefault(false)
        }
        caps = built
        return built
    }

    private fun performSound(context: Context, event: FeelEvent, current: FeelPrefs) {
        val audio = context.applicationContext.getSystemService(AudioManager::class.java) ?: return
        val ringer = Ringer.fromAndroid(audio.ringerMode)
        if (!SoundRules.shouldPlay(current.sound, ringer, current.volumePercent)) return
        val track = track(current.sound, event) ?: return
        runCatching {
            track.stop()
            track.setPlaybackHeadPosition(0)
            track.setVolume(current.volume)
            track.play()
        }
    }

    private fun track(theme: SoundTheme, event: FeelEvent): AudioTrack? = synchronized(lock) {
        // Drop tracks of an older theme so only one theme's tones stay in memory.
        tracks.keys.filter { it.first != theme }.forEach { tracks.remove(it)?.release() }
        tracks[theme to event]?.let { return it }
        val pcm = FeelSynth.generate(theme, event)
        if (pcm.isEmpty()) return null
        val created = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(FeelSynth.SampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
                .also { it.write(pcm, 0, pcm.size) }
        }.getOrNull() ?: return null
        tracks[theme to event] = created
        created
    }
}
