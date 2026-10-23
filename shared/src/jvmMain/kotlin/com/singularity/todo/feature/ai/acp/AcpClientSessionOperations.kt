package com.singularity.todo.feature.ai.acp

import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PermissionOptionId
import com.agentclientprotocol.model.PermissionOptionKind
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionUpdate
import kotlinx.serialization.json.JsonElement

/**
 * A request from the agent for permission to do something.
 *
 * ## The application's own type, not the protocol's
 *
 * The protocol's request carries protocol types. This one carries the application's, so
 * that everything above the protocol library — the screen, the ViewModel — is written in
 * the application's vocabulary and the library stays behind one door.
 */
data class AcpPermissionRequest(val toolCallId: String, val title: String, val options: List<AcpPermissionOption>)

/** One of the choices the agent offered. */
data class AcpPermissionOption(
    val optionId: String,
    val label: String,
    /** Whether choosing this refuses the call. */
    val isRejection: Boolean,
)

/**
 * What the reader decided about a request for permission.
 *
 * The decision is the reader's, so the request has to travel to the screen and the
 * answer has to travel back.
 */
sealed interface PermissionDecision {

    /** The reader chose an option the agent offered. */
    data class Selected(val optionId: String) : PermissionDecision

    /**
     * The reader refused, or nobody answered in time.
     *
     * A refusal is not "nothing": the agent offered a way to say no, and choosing it is
     * how a refusal is expressed — including "do not ask me again". So a refusal
     * selects the agent's own rejecting option when it offered one.
     */
    data object Declined : PermissionDecision
}

/**
 * The client side of a session: what the agent is allowed to ask of this application.
 *
 * ## Exactly two methods
 *
 * The protocol's client operations cover four areas — the file system, the terminal,
 * elicitation, and permissions — and this class implements the permissions and nothing
 * else.
 *
 * That is not an omission. The file system, terminal and elicitation methods have
 * defaults in the protocol library, and those defaults **refuse**: an agent that asks
 * to read a file, or to run a command, or to ask the user a question, is told no. The
 * application advertises neither of those capabilities when it initializes, so an agent
 * that honours the protocol never asks. And if one does ask anyway, the refusal is the
 * answer — an implementation that quietly granted access would be a hole in a guarantee
 * the application makes about the user's files.
 *
 * The application's own tool server is how the agent reaches the user's tasks, and it
 * goes through the proposal pipeline, which is where a write becomes a question for the
 * user. An agent with file and terminal access would have a second, quieter way to
 * change the user's data, and it would not ask.
 *
 * ## The permission request
 *
 * A request suspends until the reader answers. The protocol allows exactly one
 * outstanding request per session, so there is no queue to get wrong: the request that
 * arrives is the request on screen.
 */
class AcpClientSessionOperations(
    private val onPermissionRequest: suspend (request: AcpPermissionRequest) -> PermissionDecision,
) : ClientSessionOperations {

    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse {
        val decision = onPermissionRequest(
            AcpPermissionRequest(
                toolCallId = toolCall.toolCallId.value,
                title = toolCall.title.orEmpty(),
                options = permissions.map { it.toOption() },
            ),
        )
        val outcome = when (decision) {
            is PermissionDecision.Selected -> RequestPermissionOutcome.Selected(PermissionOptionId(decision.optionId))
            PermissionDecision.Declined -> refusingOutcome(permissions)
        }
        return RequestPermissionResponse(outcome = outcome)
    }

    /**
     * A notification that is not bound to a prompt.
     *
     * The protocol defines it for agents that have something to say outside a turn.
     * This application has no surface for it, and inventing one would show the reader
     * text nobody asked for — so it is received and dropped.
     */
    override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) {
        // Intentionally empty — see the KDoc above.
    }

    /**
     * How a refusal is expressed on the wire.
     *
     * The protocol has no "rejected" outcome: a refusal is the agent's own rejecting
     * option, chosen on its behalf. When the agent offered no such option, the only
     * honest answer left is that the turn was cancelled before the reader answered.
     */
    private fun refusingOutcome(permissions: List<PermissionOption>): RequestPermissionOutcome {
        val rejecting = permissions.firstOrNull { it.kind.isRejection() }
        return rejecting?.let { RequestPermissionOutcome.Selected(PermissionOptionId(it.optionId.value)) }
            ?: RequestPermissionOutcome.Cancelled
    }

    private fun PermissionOption.toOption(): AcpPermissionOption = AcpPermissionOption(
        optionId = optionId.value,
        label = name,
        isRejection = kind.isRejection(),
    )

    private fun PermissionOptionKind.isRejection(): Boolean =
        this == PermissionOptionKind.REJECT_ONCE || this == PermissionOptionKind.REJECT_ALWAYS
}
