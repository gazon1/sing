@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.test.helpers

import kotlinx.coroutines.debug.DebugProbes
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler
import org.junit.jupiter.api.extension.TestWatcher
import java.io.File
import java.time.Instant

/**
 * JUnit Jupiter [TestWatcher] + [TestExecutionExceptionHandler] that captures a coroutine
 * snapshot when a test times out.
 *
 * Registered as a global extension via `META-INF/services` alongside
 * [com.singularity.todo.test.FailureContextExtension].
 * This extension does NOT replace the JUnit timeout mechanism — it cooperates with it:
 * when `@Timeout` or the `junit.jupiter.timeout.default` configuration kills a hanging test,
 * JUnit reports the timeout as an exception; this extension catches that exception,
 * writes the dump, and re-throws.
 *
 * If no timeout exception is thrown (test passes or fails normally), this extension is
 * a no-op for that test.
 *
 * Uses a no-arg constructor (required by JUnit's `Extension` service loader).
 * The build directory is read lazily from the `shared.build.dir` system property
 * (set in `shared/build.gradle.kts` for the jvmTest task).
 */
class CoroutinesTimeoutExtension : TestWatcher, TestExecutionExceptionHandler {

    private val buildDirPath: String by lazy {
        System.getProperty("shared.build.dir")
            ?: error("shared.build.dir system property not set — add to jvmTest task in build.gradle.kts")
    }

    override fun handleTestExecutionException(
        context: ExtensionContext,
        throwable: Throwable,
    ) {
        // Only act on timeout exceptions — pass everything else through unchanged.
        if (!isTimeoutException(throwable)) throw throwable

        val testClass = context.requiredTestClass.simpleName
        runCatching {
            val diagnosticsDir = File(buildDirPath, "diagnostics/$testClass")
            diagnosticsDir.mkdirs()
            val dumpFile = File(diagnosticsDir, "coroutines-timeout.txt")
            dumpFile.writeText(buildCoroutinesDump(testClass))
        }
        throw throwable
    }

    private fun buildCoroutinesDump(testClass: String): String = buildString {
        appendLine("=== Coroutine timeout snapshot ===")
        appendLine("timestamp=${Instant.now()}")
        appendLine("testClass=$testClass")
        appendLine("java=${System.getProperty("java.version")}")
        appendLine()
        appendLine("--- Coroutine dump at timeout ---")
        if (DebugProbes.isInstalled) {
            val infos = DebugProbes.dumpCoroutinesInfo()
            appendLine("activeCoroutines=${infos.size}")
            appendLine()
            infos.forEachIndexed { index, info ->
                appendLine("--- Coroutine #${index + 1} ---")
                appendLine("state=${info.state}")
                appendLine("context=${info.context}")
                appendLine("job=${info.job?.let { DebugProbes.jobToString(it) } ?: "null"}")
                appendLine("creationStackTrace:")
                info.creationStackTrace.forEach { element -> appendLine("    at $element") }
                appendLine("lastObservedStackTrace:")
                info.lastObservedStackTrace().forEach { element -> appendLine("    at $element") }
                if (index < infos.lastIndex) appendLine()
            }
        } else {
            appendLine("coroutines debug agent is not attached")
        }
    }

    private fun isTimeoutException(t: Throwable): Boolean {
        val msg = t.message ?: return false
        val name = t::class.simpleName ?: return false
        return name.contains("Timeout", ignoreCase = true) ||
            msg.contains("timed out", ignoreCase = true) ||
            msg.contains("timeout", ignoreCase = true)
    }
}
