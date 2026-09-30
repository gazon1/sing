package com.singularity.todo.test.helpers

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import java.text.SimpleDateFormat
import java.util.Date

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
    Logger.setLogWriters(PlainStdoutWriter)
    Logger.setMinSeverity(Severity.Verbose)
}
