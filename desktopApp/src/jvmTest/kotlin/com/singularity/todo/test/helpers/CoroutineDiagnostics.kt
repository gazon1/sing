@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.test.helpers

import kotlinx.coroutines.debug.CoroutineInfo
import kotlinx.coroutines.debug.DebugProbes
import kotlinx.coroutines.debug.State
import java.time.Instant

/**
 * Reads the state of all active coroutines via the kotlinx-coroutines-debug agent.
 *
 * Test-only utility: no DI, no Koin, no interfaces. Returns a formatted string;
 * callers (FailureBundle) handle all I/O.
 *
 * The agent is attached via `-javaagent` JVM argument in the test task configuration.
 * If the agent is not present [DebugProbes.isInstalled] returns false and the dump
 * returns a sentinel message.
 *
 * **Important:** this reads a **snapshot** of coroutine state at the moment of the call.
 * It does not show execution history, does not explain TestScheduler semantics
 * (advanceUntilIdle vs runCurrent), and does not prove a coroutine is leaked.
 */
object CoroutineDiagnostics {

    fun dump(testClass: String?, attempt: Int): String {
        if (!DebugProbes.isInstalled) {
            return "coroutines debug agent is not attached"
        }
        val infos: List<CoroutineInfo> = DebugProbes.dumpCoroutinesInfo()
        return buildString {
            appendLine("=== Coroutine diagnostics ===")
            appendLine("timestamp=${Instant.now()}")
            appendLine("testClass=$testClass / attempt=$attempt")
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
        info.creationStackTrace.forEach { element ->
            appendLine("    at $element")
        }
        appendLine("lastObservedStackTrace:")
        info.lastObservedStackTrace().forEach { element ->
            appendLine("    at $element")
        }
    }
}
