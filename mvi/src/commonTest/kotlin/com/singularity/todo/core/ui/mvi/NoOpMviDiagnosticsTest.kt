package com.singularity.todo.core.ui.mvi

import com.singularity.todo.core.ui.MviLogLevel
import com.singularity.todo.core.ui.NoOpMviDiagnostics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The library's default [com.singularity.todo.core.ui.MviDiagnostics], pinned on its own.
 *
 * It is the implementation every consumer that supplies nothing gets, and every branch of it
 * decides what a user sees: `NoOpMviDiagnostics` is the fallback `MviViewModel` uses when an
 * injected `classify` throws, so its behaviour is on the failure path twice over. A default that
 * returned an empty message would turn a crash into a blank banner, and nothing else would say so.
 */
class NoOpMviDiagnosticsTest {
    @Test
    fun `a message is the throwable's own`() {
        val failure = NoOpMviDiagnostics.classify(IllegalStateException("disk gone"), "fallback label")

        assertEquals("disk gone", failure.message, "the throwable's message wins over the label")
    }

    @Test
    fun `a throwable with no message falls back to the call-site label`() {
        // The one case with nothing to say: an empty banner is worse than a label, and the label
        // is the only machine-shaped text available.
        val failure = NoOpMviDiagnostics.classify(RuntimeException(), "sync.push_failed")

        assertEquals("sync.push_failed", failure.message)
    }

    @Test
    fun `a throwable with an empty message falls back to the call-site label`() {
        // `RuntimeException("")` is not `RuntimeException()`: `message` is the empty string, which
        // `?:` does not catch. An empty banner is what the user would see.
        val failure = NoOpMviDiagnostics.classify(RuntimeException(""), "sync.push_failed")

        assertEquals(
            "sync.push_failed",
            failure.message,
            "an empty message is not a message — the label is what a reader can act on",
        )
    }

    @Test
    fun `the reported throwable is the one that was raised`() {
        val error = IllegalStateException("inner")
        val failure = NoOpMviDiagnostics.classify(error, "label")

        assertSame(error, failure.reportable, "an unclassified throwable is reported as it was raised")
    }

    @Test
    fun `the grouping key is the call-site label`() {
        // The default has nothing else to group by: no domain code exists on a bare throwable.
        val failure = NoOpMviDiagnostics.classify(IllegalStateException("inner"), "tags.delete_failed")

        assertEquals("tags.delete_failed", failure.issueKey)
    }

    @Test
    fun `reporting is silent`() {
        // The point of the default: a ViewModel constructed in a test is silent without a global
        // to reset. There is nothing to assert but the absence of an effect, which is why this is
        // a call rather than a measurement — what it pins is that the call exists and returns.
        NoOpMviDiagnostics.report(IllegalStateException("inner"), "label")
    }

    @Test
    fun `logging is silent`() {
        NoOpMviDiagnostics.log(MviLogLevel.Debug, "a debug line")
        NoOpMviDiagnostics.log(MviLogLevel.Warn, "a warn line")
    }
}
