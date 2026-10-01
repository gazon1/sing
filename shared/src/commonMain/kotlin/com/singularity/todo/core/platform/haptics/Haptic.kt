package com.singularity.todo.core.platform.haptics

/**
 * Platform haptic feedback capability.
 *
 * Android: short vibration using the system vibration service.
 * JVM/Desktop: no-op.
 *
 * Why a platform capability rather than a direct call inside [Celebration]:
 * the same haptic primitive is needed by other UI events (checkbox tap, swipe
 * feedback). Coupling it to Celebration would mislocate the abstraction.
 */
interface Haptic {
    /** Performs a single haptic pulse. On JVM this is a no-op. */
    suspend fun perform()
}

/** Creates the platform-specific [Haptic] instance. */
expect fun createHaptic(): Haptic
