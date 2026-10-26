package com.singularity.todo.feature.ai.acp

/**
 * A request from the agent for permission to do something.
 *
 * ## The application's own type, not the protocol's
 *
 * The protocol's request carries protocol types. This one carries the application's,
 * so that everything above the protocol library — the screen, the ViewModel — is
 * written in the application's vocabulary and the library stays behind one door.
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
 * How the agent asks the reader for permission.
 *
 * ## The port the protocol library is plugged into
 *
 * The protocol library calls this when an agent wants something. Everything about
 * that call — the protocol's own request type, its options, the way a refusal is
 * expressed on the wire — belongs to the library and stays behind the door. What
 * crosses it is this: the application's request, and the application's answer.
 *
 * ## The request suspends until the reader answers
 *
 * There is no timeout here, and there is no queue: the protocol allows exactly one
 * outstanding request per session, so the request that arrives is the request on
 * screen. A refusal that arrives late is still a refusal.
 */
fun interface AcpSessionPermissions {

    /**
     * Asks the reader, and waits for the answer.
     *
     * @param request what the agent wants to do, in the application's words.
     * @return the reader's decision.
     */
    suspend fun requestPermission(request: AcpPermissionRequest): PermissionDecision
}
