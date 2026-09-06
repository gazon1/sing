package com.singularity.todo.core.log

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Basic sanity tests for Kermit Logger API.
 * Tests that Logger instance can be created and message calls compile without errors.
 * Full integration testing of log output is done via KoinLoggingGraphTest.
 */
class LoggingTest {

    @Test
    fun `Logger can be constructed with StaticConfig and tag`() {
        // Verify Logger constructor accepts StaticConfig and tag — smoke test
        val log = Logger(StaticConfig(Severity.Debug), "T")
        // Instance methods compile and do not throw
        log.v { "verbose" }
        log.d { "debug" }
        log.i { "info" }
        log.w { "warn" }
        log.e { "error" }
        log.e(RuntimeException("boom")) { "with throwable" }
    }

    @Test
    fun `Logger withTag creates tagged logger`() {
        val log = Logger.withTag("MyTag")
        log.i { "hello" }
        log.e(RuntimeException("err")) { "failed" }
    }

    @Test
    fun `LoggerHolder wraps a Logger`() {
        val log = Logger.withTag("App")
        val holder = LoggerHolder(log)
        holder.log.i { "wrapped logger works" }
    }
}
