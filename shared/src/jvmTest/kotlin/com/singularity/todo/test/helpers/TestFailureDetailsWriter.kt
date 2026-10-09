package com.singularity.todo.test.helpers

import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler
import java.io.File
import java.time.Instant

/**
 * Writes comprehensive failure diagnostics to `build/diagnostics/<TestClass>/failure-details.txt`
 * on every test failure, mirroring the `FailureBundle` pattern from Android instrumented tests.
 *
 * Registered automatically via `META-INF/services/org.junit.jupiter.api.extension.Extension`
 * alongside [com.singularity.todo.test.FailureContextExtension].
 *
 * ### What is captured
 *
 * | Field | Description |
 * |-------|-------------|
 * | exception type | Full class name of the thrown exception |
 * | message | The exception's message (may be null) |
 * | stackTrace | Full stacktrace of the exception |
 * | cause chain | Recursive cause → message → stacktrace |
 * | isTimeout | Whether the exception message/name contains "timeout" |
 * | threadName | Name of the thread the exception occurred on |
 * | testClass | Simple name of the test class |
 * | testMethod | Name of the failing test method (if resolvable) |
 * | timestamp | ISO-8601 wall-clock time |
 * | javaVersion | `java.version` system property |
 * | gitHash | 8-char git hash of HEAD |
 *
 * ### Output file
 *
 * ```
 * build/diagnostics/<TestClass>/failure-details.txt
 * ```
 *
 * Failures in writing the diagnostics are silent — they never affect the test result.
 * This extension cooperates with [com.singularity.todo.test.FailureContextExtension]:
 * both run on the same failure, and both write to the same diagnostics directory.
 */
class TestFailureDetailsWriter :
    BeforeAllCallback,
    TestExecutionExceptionHandler {

    private lateinit var context: FailureInfo

    override fun beforeAll(extensionContext: ExtensionContext) {
        context = FailureInfo.capture()
    }

    override fun handleTestExecutionException(
        extensionContext: ExtensionContext,
        throwable: Throwable,
    ) {
        writeFailureDetails(extensionContext, throwable)
    }

    private fun writeFailureDetails(extensionContext: ExtensionContext, throwable: Throwable) {
        runCatching {
            val testClass = extensionContext.requiredTestClass.simpleName
            val testMethod = runCatching {
                extensionContext.testMethod.map { it.name }.orElse(null)
            }.getOrNull()
            val buildDirPath = System.getProperty("shared.build.dir") ?: return@runCatching
            val buildDir = File(buildDirPath)
            val diagnosticsDir = File(buildDir, "diagnostics/$testClass")
            diagnosticsDir.mkdirs()
            val detailsFile = File(diagnosticsDir, "failure-details.txt")

            val isTimeout = isTimeoutException(throwable)
            val failureInfo = buildString {
                appendLine("=== Test failure details ===")
                appendLine("timestamp=${Instant.now()}")
                appendLine("testClass=$testClass")
                appendLine("testMethod=$testMethod")
                appendLine("isTimeout=$isTimeout")
                appendLine("java=${System.getProperty("java.version")}")
                appendLine("git=${context.gitHash}")
                appendLine()
                appendLine("--- Exception ---")
                appendLine("type=${throwable::class.java.name}")
                appendLine("message=${throwable.message ?: "(no message)"}")
                appendLine()
                appendLine("--- Stacktrace ---")
                throwable.stackTrace.forEach { element ->
                    appendLine("    at $element")
                }

                // Walk the full cause chain
                var cause: Throwable? = throwable.cause
                var depth = 0
                while (cause != null && depth < 10) {
                    appendLine()
                    appendLine("--- Caused by: ${cause::class.java.name} ---")
                    appendLine("message=${cause.message ?: "(no message)"}")
                    cause.stackTrace.forEach { element ->
                        appendLine("    at $element")
                    }
                    cause = cause.cause
                    depth++
                }

                appendLine()
                appendLine("--- Thread state ---")
                appendLine("thread=${Thread.currentThread().name}")
                appendLine("threadId=${Thread.currentThread().id}")
                appendLine("threadState=${Thread.currentThread().state}")

                appendLine()
                appendLine("--- Environment ---")
                appendLine("os=${System.getProperty("os.name")} ${System.getProperty("os.version")}")
                appendLine("availableProcessors=${Runtime.getRuntime().availableProcessors()}")
                appendLine("maxMemory=${Runtime.getRuntime().maxMemory()}")
                appendLine("freeMemory=${Runtime.getRuntime().freeMemory()}")
            }

            detailsFile.writeText(failureInfo)
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

/**
 * Lightweight environment snapshot captured once per test class.
 * Shares the same name as [com.singularity.todo.test.FailureContext] to avoid
 * introducing a second context object — the JUnit extension is the single owner.
 */
private data class FailureInfo(val gitHash: String, val javaVersion: String) {
    companion object {
        fun capture(): FailureInfo {
            val gitHash = runCatching {
                ProcessBuilder("git", "rev-parse", "--short=8", "HEAD")
                    .start()
                    .inputStream
                    .bufferedReader()
                    .readText()
                    .trim()
            }.getOrElse { "unknown" }

            return FailureInfo(
                gitHash = gitHash,
                javaVersion = System.getProperty("java.version") ?: "unknown",
            )
        }
    }
}
