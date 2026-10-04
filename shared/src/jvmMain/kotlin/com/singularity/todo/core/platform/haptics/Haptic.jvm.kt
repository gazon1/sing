package com.singularity.todo.core.platform.haptics

/**
 * JVM stub for [Haptic] — no-op on desktop.
 */
actual fun createHaptic(): Haptic = NoOpHaptic
