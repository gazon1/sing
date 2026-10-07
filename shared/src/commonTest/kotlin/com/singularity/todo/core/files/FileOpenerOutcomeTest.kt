package com.singularity.todo.core.files

import com.singularity.todo.test.fakes.FakeFileOpener
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `OpenOutcome` exists so a caller cannot forget the "nothing handles this" case. That
 * property is only worth anything if the type stays closed and the handling is total, so
 * both are checked here rather than trusted.
 */
@Tag("fast")
class FileOpenerOutcomeTest {

    @Test
    fun `the two outcomes are distinct values`() {
        // Not an enum, so there is no `entries` to count — which is the point: adding a
        // case is not an enum constant but a new subtype, and every `when` over this
        // type stops compiling until it is handled.
        assertTrue(OpenOutcome.NoHandler != OpenOutcome.Opened)
        assertIs<OpenOutcome.Opened>(OpenOutcome.Opened)
        assertIs<OpenOutcome.NoHandler>(OpenOutcome.NoHandler)
    }

    @Test
    fun `a total when over the outcome handles both cases`() {
        // Stands in for the caller's `when`. If a case were missing this would not
        // compile, which is the property the sealed type exists to provide.
        fun describe(outcome: OpenOutcome): String = when (outcome) {
            OpenOutcome.Opened -> "opened externally"
            OpenOutcome.NoHandler -> "nothing handles this type"
        }
        assertEquals("opened externally", describe(OpenOutcome.Opened))
        assertEquals("nothing handles this type", describe(OpenOutcome.NoHandler))
    }

    @Test
    fun `NoHandler is an outcome and not a thrown failure`() {
        // A desktop with no PDF reader is ordinary. An exception here would mean the
        // user is left on a button that did nothing.
        runTest {
            val opener = FakeFileOpener()
            opener.outcome = OpenOutcome.NoHandler
            assertEquals(OpenOutcome.NoHandler, opener.open("/tmp/a.pdf", "application/pdf"))
        }
    }

    @Test
    fun `NoHandler is distinct from success`() {
        // The whole reason this is not a Boolean.
        assertTrue(OpenOutcome.NoHandler != OpenOutcome.Opened)
    }

    @Test
    fun `the port records what it was asked to open`() {
        // "The code is wired" is the assertion this repository keeps needing, and a
        // no-op double cannot support it.
        runTest {
            val opener = FakeFileOpener()
            assertNull(opener.lastOpened, "nothing opened yet")
            opener.open("/tmp/a.pdf", "application/pdf")
            assertEquals("/tmp/a.pdf" to "application/pdf", opener.lastOpened)
            assertEquals(1, opener.opens.size)
        }
    }
}
