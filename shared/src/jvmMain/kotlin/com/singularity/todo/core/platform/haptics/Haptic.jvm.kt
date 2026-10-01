package com.singularity.todo.core.platform.haptics

/**
 * JVM stub for [Haptic] — no-op on desktop.
 */
actual fun createHaptic(): Haptic = NoOpHaptic

private object NoOpHaptic : Haptic {
    override suspend fun perform() {
        // no-op on JVM
    }
}
