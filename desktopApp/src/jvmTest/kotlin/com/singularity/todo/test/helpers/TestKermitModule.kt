package com.singularity.todo.test.helpers

import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Provides a per-test [RingBufferLogWriter] bound to the Koin graph so it can be
 * retrieved by [FailureBundle] on failure without the harness needing a separate
 * reference to it.
 *
 * The buffer is installed into the global Kermit logger by [installRingBuffer],
 * which is called by [runDesktopAppTest] before the test body runs.
 *
 * The module is a singleton so that the same buffer is returned throughout a
 * single test execution (including retries), and is reset via [RingBufferLogWriter.reset]
 * before each attempt.
 */
fun testKermitModule(): Module = module {
    single { RingBufferLogWriter(capacity = 2_000) }
}
