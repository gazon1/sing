package com.singularity.todo.test.helpers

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import java.text.SimpleDateFormat
import java.util.Date

/**
 * ## Available `singularity.*` system properties for desktop UI tests
 *
 * All properties are forwarded from the Gradle CLI to the forked test JVM via
 * `desktopApp/build.gradle.kts` — a `-D` flag on the Gradle command line is
 * silently ignored.
 *
 * | Property | Default | Effect |
 * |---|---|---|
 * | `singularity.test.log` | `false` | Routes Kermit to stdout at `Verbose` severity. All `Logger` calls (including `Logger.d { }`) appear in the test output under `<system-out>`. |
 * | `singularity.ui.dumpTree` | `false` | Prints the semantics tree wrapped in `=== SEMANTICS TREE START/END ===` markers before every assertion in `DesktopAppBootTest`. |
 *
 * Example — enable logging for one test class:
 * ```bash
 * ./gradlew :desktopApp:test --tests '*CreateTaskFlowTest' -Dsingularity.test.log=true
 * ```
 *
 * Example — dump semantics tree for a failing boot test:
 * ```bash
 * ./gradlew :desktopApp:test --tests '*DesktopAppBootTest' -Dsingularity.ui.dumpTree=true
 * ```
 *
 * Forwarding is configured in `desktopApp/build.gradle.kts`:
 * ```kotlin
 * System.getProperties().stringPropertyNames()
 *     .filter { it.startsWith("singularity.") }
 *     .forEach { key -> systemProperty(key, System.getProperty(key)) }
 * ```
 */

/**
 * System property that turns verbose Kermit output on for a desktop UI test run.
 *
 * `-Dsingularity.test.log=true` — every `Logger` call from `:shared`, including
 * debug and verbose, reaches stdout and is captured in the test report under
 * `<system-out>`.
 */
const val TEST_LOG_PROPERTY = "singularity.test.log"

/** Plain (uncoloured) writer so log lines stay greppable in CI output. */
private object PlainStdoutWriter : LogWriter() {
    override fun log(
        severity: Severity,
        message: String,
        tag: String,
        throwable: Throwable?,
    ) {
        val stamp = SimpleDateFormat("HH:mm:ss.SSS").format(Date())
        println("[$stamp] ${severity.name} $tag: $message")
        throwable?.printStackTrace()
    }
}

/**
 * The current list of [LogWriter]s registered with the global Kermit [Logger].
 *
 * Initialised to [PlainStdoutWriter] when [initTestLogging] is called with
 * [TEST_LOG_PROPERTY] set, and appended to by [installRingBuffer].  Both
 * functions call [Logger.setLogWriters] with the full list so that calling one
 * after the other does not discard the other's writers.
 */
private val kermitWriters = mutableListOf<LogWriter>()

/**
 * Installs [ringBuffer] into the global Kermit logger as an additional writer,
 * retaining all writers previously registered by [initTestLogging].
 *
 * Called by [com.singularity.todo.test.helpers.testKermitModule] once per test
 * before the test body runs. The buffer coexists with the stdout writer — verbose
 * output goes to both the ring buffer (for the failure report) and stdout (for
 * real-time CI log streaming).
 *
 * Idempotent per test: the same buffer instance is reset and reused for retries.
 */
fun installRingBuffer(ringBuffer: RingBufferLogWriter) {
    kermitWriters.add(ringBuffer)
    Logger.setLogWriters(*kermitWriters.toTypedArray())
    Logger.setMinSeverity(Severity.Verbose)
}

/**
 * Resets the writer list so the same JVM can run multiple [runDesktopAppTest]
 * calls cleanly (each starts with a fresh writer set).
 *
 * Called automatically by [runDesktopAppTest] before installing any writers.
 */
internal fun resetKermitWriters() {
    kermitWriters.clear()
}

/**
 * Routes Kermit to stdout at verbose severity for the duration of a test run.
 *
 * Kermit's JVM default already writes to stdout, so this is not about making the
 * suite speak up — it is about what it says. Two gaps made it useless for
 * diagnosis:
 *
 *  - the default severity filter hides `Logger.d { }` and `Logger.v { }`, which
 *    is where repository and ViewModel tracing lives, so the lines that explain a
 *    wrong value are exactly the ones that never print;
 *  - the default format has no timestamp and no tag, and a single test mounts a
 *    whole app, so interleaved output from a dozen components is hard to attribute.
 *
 * Off unless [TEST_LOG_PROPERTY] is set, so ordinary runs and CI stay quiet:
 *
 * ```bash
 * ./gradlew :desktopApp:test --tests '*CreateTaskFlowTest' -Dsingularity.test.log=true
 * ```
 *
 * `desktopApp/build.gradle.kts` forwards `singularity.*` into the forked test
 * JVM; without that the flag parses and does nothing.
 *
 * Idempotent — called once per test, repeated calls are harmless.
 */
fun initTestLogging() {
    if (System.getProperty(TEST_LOG_PROPERTY) != "true") return
    kermitWriters.add(PlainStdoutWriter)
    Logger.setLogWriters(*kermitWriters.toTypedArray())
    Logger.setMinSeverity(Severity.Verbose)
}
