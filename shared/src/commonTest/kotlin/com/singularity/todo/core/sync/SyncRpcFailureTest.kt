package com.singularity.todo.core.sync

import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.TransportFacts
import com.singularity.todo.core.error.TransportFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag

/**
 * What a failed call to a database function says.
 *
 * ## Why this is a test of its own
 *
 * The wording and the code of a failure are the project's decisions, and they are
 * the ones a person reads and a dashboard groups by. They used to live in the
 * vendor's file, where they could only be checked by reading it; now they live
 * here, where they can be asserted.
 *
 * The two cases are the two ways a call fails before it is even attempted, and
 * after: there is no project to call, and the server refused.
 */
@Tag("fast")
class SyncRpcFailureTest {

    /** No project: the call is not attempted, and it says so. */
    @Test
    fun `an unconfigured project is named, not counted as a refusal`() {
        val error = SyncRpcFailure.notConfigured("push_changes")

        assertIs<AppError.Validation>(error)
        assertEquals("sync.not_configured", error.code)
        assertTrue(
            error.message.orEmpty().contains("push_changes"),
            "the message must name the function that could not be called: ${error.message}",
        )
    }

    /** A refusal repeats the server's own facts, in the order that answers "what did it say?". */
    @Test
    fun `a refusal repeats the status, the server's code and its words`() {
        val failure = SyncRpcFailure.refused(
            failure = TransportFailure.REJECTED,
            facts = TransportFacts(
                statusCode = 403,
                serverCode = "PGRST301",
                detail = "new row violates row-level security policy",
            ),
            function = "push_changes",
            cause = IllegalStateException("the vendor's exception"),
        )

        assertEquals(TransportFailure.REJECTED, failure.failure)
        assertTrue(
            failure.message.orEmpty().startsWith("push_changes failed: 403: PGRST301: "),
            "the message must lead with the function and the status: ${failure.message}",
        )
        assertTrue(
            failure.message.orEmpty().contains("row-level security policy"),
            "the server's own words must survive the trip: ${failure.message}",
        )
    }

    /** A failure with nothing to say still says what happened. */
    @Test
    fun `a refusal without facts still names the failure`() {
        val failure = SyncRpcFailure.refused(
            failure = TransportFailure.NETWORK,
            facts = TransportFacts(statusCode = null, serverCode = null, detail = null),
            function = "pull_changes",
            cause = IllegalStateException("socket closed"),
        )

        assertEquals(TransportFailure.NETWORK, failure.failure)
        assertTrue(
            failure.message.orEmpty().startsWith("pull_changes failed: "),
            "the message must still say which call failed: ${failure.message}",
        )
    }
}
