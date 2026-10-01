package com.singularity.todo.core.platform.haptics

import android.os.VibrationEffect
import android.os.VibratorManager

/**
 * Android haptic via [VibratorManager].
 * Uses a single short pulse (30 ms) — sufficient for completion feedback
 * without being intrusive.
 */
actual fun createHaptic(vibratorManager: VibratorManager): Haptic = AndroidHaptic(vibratorManager)

private class AndroidHaptic(private val vibratorManager: VibratorManager) : Haptic {
    override suspend fun perform() {
        val vibrator = vibratorManager.defaultVibrator
        val effect = VibrationEffect.createOneShot(30L, VibrationEffect.EFFECT_TICK)
        vibrator.vibrate(effect)
    }
}
