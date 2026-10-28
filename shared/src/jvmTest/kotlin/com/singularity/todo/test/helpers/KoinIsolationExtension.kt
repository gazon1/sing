package com.singularity.todo.test.helpers

import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.koin.core.context.stopKoin

/**
 * Stops the global Koin context after each test class.
 *
 * `forkEvery` is no longer 1, so several test classes can share one JVM; this
 * is what keeps one class's `startKoin()`/`loadKoinModules()` from leaking into
 * another. Per-class (not per-test) to avoid the teardown race documented in
 * `ToolRegistrarTest`: a per-test `stopKoin()` can pull the graph out from
 * under a sibling test running in parallel.
 */
class KoinIsolationExtension : AfterAllCallback {
    override fun afterAll(context: ExtensionContext) {
        runCatching { stopKoin() }
    }
}
