package com.singularity.todo.core.platform.haptics

import android.os.VibrationEffect
import android.os.VibratorManager

/**
 * Android haptic via [VibratorManager].
 * Uses a single short pulse (30 ms) — sufficient for completion feedback
 * without being intrusive.
 */
@Suppress("UNUSED", "NewApi") // API 31+: VibratorManager, EFFECT_TICK; unused: replaced by PlatformModule
actual fun createHaptic(): Haptic = NoOpHaptic

// Internal factory — PlatformModule.android.kt calls AndroidHaptic.of(vibratorManager)
// directly since it has the Koin-injected Context to resolve VibratorManager.
internal object AndroidHaptic {
    @Suppress("NewApi") // VibratorManager (API 31), EFFECT_TICK (API 29), getDefaultVibrator (API 31)
    fun of(vibratorManager: VibratorManager): Haptic = AndroidHapticImpl(vibratorManager)

    private class AndroidHapticImpl(private val vibratorManager: VibratorManager) : Haptic {
        @Suppress("NewApi") // VibratorManager & EFFECT_TICK require API 29+
        override suspend fun perform() {
            val vibrator = vibratorManager.defaultVibrator
            val effect = VibrationEffect.createOneShot(30L, VibrationEffect.EFFECT_TICK)
            vibrator.vibrate(effect)
        }
    }
}

private object NoOpHaptic : Haptic {
    override suspend fun perform() { /* no-op — real instance created by PlatformModule */ }
}
