package com.singularity.todo.test

import com.singularity.todo.test.helpers.CoroutineDiagnostics
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler
import java.io.File
import java.time.Instant

/**
 * Captures the test-environment context (git hash, Java version, wall-clock time)
 * and annotates every test failure with it — making a flake or bizarre result
 * reproducible even when reported hours or days later from CI.
 *
 * Registered automatically via `META-INF/services/org.junit.jupiter.api.extension.Extension`
 * so it applies to every `shared/jvmTest` class without per-file opt-in.
 *
 * ### What is captured
 *
 * | Field | Source |
 * | gitHash | `git rev-parse --short=8 HEAD` |
 * | javaVersion | `System.getProperty("java.version")` |
 * | timestamp | `Instant.now()` at the start of the test class |
 *
 * ### Failure augmentation
 *
 * When a test throws an `AssertionError`, the handler throws a replacement whose
 * message is prefixed with the context. The original throwable is the cause.
 * ```
 * [env] java=21.0.5+9 / git=7f3e2a1 / 2026-10-01T14:23:01Z
 *     Expected: <1> but was: <2>
 * ```
 *
 * Non-AssertionError exceptions are passed through unchanged.
 */
class FailureContextExtension :
    BeforeAllCallback,
    TestExecutionExceptionHandler {

    private lateinit var context: FailureContext

    override fun beforeAll(extensionContext: ExtensionContext) {
        context = FailureContext.capture()
    }

    /**
     * Called when a test throws an exception.
     *
     * The return type is `Unit` — to replace the exception, throw a new one.
     * This matches the JUnit Jupiter extension contract.
     */
    override fun handleTestExecutionException(extensionContext: ExtensionContext, throwable: Throwable) {
        // Write coroutine dump first — independent of the exception type.
        // Failures in writing the dump do not affect the original failure.
        writeCoroutineDump(extensionContext)

        // Pass through anything that is not an AssertionError.
        if (throwable !is AssertionError) return

        val label = context.label()
        val originalMessage = throwable.message ?: ""
        val augmentedMessage = if (originalMessage.isBlank()) {
            label
        } else {
            "$label${System.lineSeparator()}$originalMessage"
        }

        // Throw the augmented exception — this replaces the original in the test result.
        throw AssertionError(augmentedMessage, throwable).apply {
            stackTrace = throwable.stackTrace
        }
    }

    /**
     * Writes a coroutine snapshot to `build/diagnostics/<TestClass>/coroutines.txt`
     * in the shared module's build directory.
     *
     * This runs on **any** test failure (not just AssertionError), making it useful
     * for diagnosing hangs and unexpected exception types.
     * Failures in writing the dump are silent — they never affect the test result.
     */
    private fun writeCoroutineDump(extensionContext: ExtensionContext) {
        runCatching {
            val testClass = extensionContext.requiredTestClass.simpleName
            val buildDirPath = System.getProperty("shared.build.dir")
                ?: return@runCatching
            val buildDir = File(buildDirPath)
            val diagnosticsDir = File(buildDir, "diagnostics/$testClass")
            diagnosticsDir.mkdirs()
            val dumpFile = File(diagnosticsDir, "coroutines.txt")
            dumpFile.writeText(CoroutineDiagnostics.dump(testClass))
        }
    }
}

/**
 * Environment context captured once per test class.
 *
 * @property gitHash 8-char git hash of the workspace HEAD, or `"unknown"` if
 *                  `git rev-parse` fails.
 * @property javaVersion The `java.version` system property, e.g. `"21.0.5+9"`.
 * @property timestamp Wall-clock time at the moment [capture] was called.
 */
data class FailureContext(val gitHash: String, val javaVersion: String, val timestamp: Instant) {
    /** Short one-line prefix for prepending to an error message. */
    fun label(): String = "[env] java=$javaVersion / git=$gitHash / $timestamp"

    companion object {
        /** Runs git as a subprocess — failures are non-fatal, returns `"unknown"`. */
        fun capture(): FailureContext {
            val gitHash = runCatching {
                ProcessBuilder("git", "rev-parse", "--short=8", "HEAD")
                    .start()
                    .inputStream
                    .bufferedReader()
                    .readText()
                    .trim()
            }.getOrElse { "unknown" }

            return FailureContext(
                gitHash = gitHash,
                javaVersion = System.getProperty("java.version") ?: "unknown",
                timestamp = Instant.now(),
            )
        }
    }
}
