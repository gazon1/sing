@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.singularity.todo.test.helpers

import kotlinx.coroutines.debug.CoroutineInfo
import kotlinx.coroutines.debug.DebugProbes
import java.time.Instant

/**
 * Test-only coroutine dump utility for `shared/jvmTest`.
 *
 * Mirrors [com.singularity.todo.test.helpers.CoroutineDiagnostics] in desktopApp but is
 * intentionally duplicated here — a shared test-fixtures module for one object is not
 * worth the added module complexity.
 *
 * This copy lives in `shared/jvmTest` because the JUnit extension that calls it
 * ([com.singularity.todo.test.FailureContextExtension]) also lives there, and both
 * share the same classloader / JVM fork.
 */
object CoroutineDiagnostics {

    /**
     * Writes the current coroutine snapshot to a string.
     *
     * Returns a plain-text report suitable for CI failure artifacts.
     * Safe to call even when the debug agent is not attached — returns an
     * informational message instead of throwing.
     *
     * @param testClass Name of the test class that produced the failure.
     */
    fun dump(testClass: String?): String {
        if (!DebugProbes.isInstalled) return "coroutines debug agent is not attached"
        val infos: List<CoroutineInfo> = DebugProbes.dumpCoroutinesInfo()
        return buildString {
            appendLine("=== Coroutine diagnostics ===")
            appendLine("timestamp=${Instant.now()}")
            appendLine("testClass=$testClass")
            appendLine("java=${System.getProperty("java.version")}")
            appendLine("activeCoroutines=${infos.size}")
            appendLine()
            infos.forEachIndexed { index, info ->
                append(formatCoroutine(info, index))
                if (index < infos.lastIndex) appendLine()
            }
        }
    }

    private fun formatCoroutine(info: CoroutineInfo, index: Int): String = buildString {
        appendLine("--- Coroutine #${index + 1} ---")
        appendLine("state=${info.state}")
        appendLine("context=${info.context}")
        appendLine("job=${info.job?.let { DebugProbes.jobToString(it) } ?: "null"}")
        appendLine("creationStackTrace:")
        info.creationStackTrace.forEach { element -> appendLine("    at $element") }
        appendLine("lastObservedStackTrace:")
        info.lastObservedStackTrace().forEach { element -> appendLine("    at $element") }
    }
}
