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

/**
 * The [Haptic] that does nothing.
 *
 * Lives in `commonMain` rather than in each actual because it is three things at once: the JVM
 * implementation, the Android fallback when no vibrator is available, and the default for
 * `com.singularity.todo.core.ui.LocalHaptic` — which is what makes previews and desktop
 * previews work without a container. It was `private` in every actual, so nothing could share
 * it and the default had to be faked at each call site.
 */
object NoOpHaptic : Haptic {
    override suspend fun perform() {
        // no-op — nothing to vibrate
    }
}

/** Creates the platform-specific [Haptic] instance. */
expect fun createHaptic(): Haptic
