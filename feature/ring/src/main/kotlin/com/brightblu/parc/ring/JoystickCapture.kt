package com.brightblu.parc.ring

/**
 * The hidden arcade's hook on the hub joystick. While [captures] is true, a finger that goes down on the hub steers a
 * game: the renderer reports the finger's offset from the hub centre through [onStick] and does nothing else with that
 * touch (no aiming at an app, no launch, no hub hold, no hub tap). When [captures] is false the hub behaves as always.
 */
interface JoystickCapture {
    /** Asked once per touch that starts on the hub. */
    fun captures(): Boolean
    /** [dxDp] and [dyDp] are relative to the hub centre; [active] is false when the finger lifts or the touch is cancelled. */
    fun onStick(dxDp: Float, dyDp: Float, active: Boolean)
}
