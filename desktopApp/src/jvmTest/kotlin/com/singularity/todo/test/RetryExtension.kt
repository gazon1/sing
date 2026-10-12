package com.singularity.todo.test

import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.InvocationInterceptor
import org.junit.jupiter.api.extension.ReflectiveInvocationContext
import java.lang.reflect.Method

/**
 * Retries failed desktop Compose UI tests up to [retry.maxAttempts] total attempts.
 *
 * - On success on attempt N > 1: logs a `::notice` annotation for flakiness tracking.
 * - On persistent failure after all retry attempts: rethrows the last exception.
 * - Consumes the `retry.maxAttempts` system property already forwarded by
 *   [desktopApp/build.gradle.kts]. Default is 1 (no retry).
 *
 * Uses [InvocationInterceptor], NOT [TestExecutionExceptionHandler]: the latter
 * cannot restart a test — it can only swallow or rethrow the caught exception.
 * [InvocationInterceptor.interceptTestMethod] wraps each invocation and calls
 * [InvocationInterceptor.Invocation.proceed] in a loop to re-run the test body.
 */
class RetryExtension : InvocationInterceptor {

    private val maxAttempts: Int =
        System.getProperty("retry.maxAttempts")?.toIntOrNull() ?: 1

    override fun interceptTestMethod(
        invocation: InvocationInterceptor.Invocation<Void>,
        invocationContext: ReflectiveInvocationContext<Method>,
        extensionContext: ExtensionContext
    ) {
        if (maxAttempts <= 1) {
            invocation.proceed()
            return
        }

        var lastThrowable: Throwable? = null

        for (attempt in 1..maxAttempts) {
            try {
                invocation.proceed()
                if (attempt > 1) {
                    // Success on retry — test was flaky
                    val displayName = extensionContext.displayName
                    println("[FLAKY] '$displayName' passed on attempt $attempt of $maxAttempts")
                    println(
                        "::notice title=Passed on retry::Test '$displayName' passed on attempt $attempt of $maxAttempts"
                    )
                }
                return
            } catch (t: Throwable) {
                lastThrowable = t
                val displayName = extensionContext.displayName
                println(
                    "[RETRY] '$displayName' failed on attempt $attempt of $maxAttempts: ${t.message}"
                )
            }
        }

        // All attempts exhausted — propagate the last failure
        throw lastThrowable!!
    }
}
