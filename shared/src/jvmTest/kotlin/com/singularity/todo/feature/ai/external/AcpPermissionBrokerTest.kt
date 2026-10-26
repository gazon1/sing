package com.singularity.todo.feature.ai.external

import com.singularity.todo.feature.ai.acp.AcpPermissionOption
import com.singularity.todo.feature.ai.acp.AcpPermissionRequest
import com.singularity.todo.feature.ai.acp.PermissionDecision
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

/**
 * The permission handshake, when the agent asks twice.
 *
 * A queue rather than one slot: an agent may ask a second question before the reader has
 * answered the first, and a slot would drop the first request on the floor — its caller
 * would wait out the whole timeout for an answer the reader was never shown.
 */
@Tag("fast")
class AcpPermissionBrokerTest {

    private val timeout = 5.minutes

    @Test
    fun `the oldest request is the one on screen`() = runTest {
        val broker = AcpPermissionBroker(timeout)

        broker.begin(request("first"))
        broker.begin(request("second"))

        assertEquals("first", broker.onScreen()?.title, "The question that was asked first is asked first")
    }

    @Test
    fun `a second request does not orphan the first`() = runTest {
        val broker = AcpPermissionBroker(timeout)
        val first = broker.begin(request("first"))
        broker.begin(request("second"))

        broker.answer(PermissionDecision.Declined)

        assertTrue(first.isCompleted, "the first request is answered, not abandoned")
        assertEquals(PermissionDecision.Declined, first.await())
    }

    @Test
    fun `the next request takes the screen when the first is answered`() = runTest {
        val broker = AcpPermissionBroker(timeout)
        broker.begin(request("first"))
        broker.begin(request("second"))

        broker.answer(PermissionDecision.Declined)

        assertEquals("second", broker.onScreen()?.title, "and the reader is asked the next question")
    }

    @Test
    fun `a finished request leaves the screen`() = runTest {
        val broker = AcpPermissionBroker(timeout)
        val decision = broker.begin(request("only"))

        broker.answer(PermissionDecision.Declined)
        broker.await(decision)

        assertNull(broker.onScreen(), "Nothing is on screen once the request is answered")
    }

    @Test
    fun `declining refuses every request that is waiting`() = runTest {
        val broker = AcpPermissionBroker(timeout)
        val first = broker.begin(request("first"))
        val second = broker.begin(request("second"))

        broker.decline()

        assertEquals(PermissionDecision.Declined, first.await(), "the first is refused")
        assertEquals(PermissionDecision.Declined, second.await(), "and so is the second")
        assertNull(broker.onScreen(), "and nothing is left on screen")
    }

    private fun request(title: String): AcpPermissionRequest = AcpPermissionRequest(
        toolCallId = "call-$title",
        title = title,
        options = listOf(AcpPermissionOption(optionId = "allow", label = "Allow once", isRejection = false)),
    )
}
