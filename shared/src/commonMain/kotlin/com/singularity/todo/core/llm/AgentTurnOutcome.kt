package com.singularity.todo.core.llm

/**
 * How a turn ended.
 *
 * ## Why this is not an event
 *
 * [AgentEvent] carries what an agent **did**. This carries how the turn **ended**, and it
 * is a property of the turn rather than something that arrives in the stream.
 *
 * The distinction matters because of one case: a stream that ends without a completion
 * event. The agent did not finish — the provider died, the transport broke, the process
 * was killed. If "the flow ended" were read as "the agent answered", the user would be
 * told the agent finished when it did not, and the answer would be missing from a screen
 * that says it is complete.
 *
 * So the completion event means success, and the absence of it is what a failure is.
 *
 * ## Exactly one
 *
 * A turn reaches exactly one of these. The transition happens once, in one place, guarded
 * by a check that the outcome is still unset — a late event arriving after the turn ended
 * cannot change how it ended.
 */
sealed interface AgentTurnOutcome {

    /**
     * The turn finished successfully.
     *
     * [note] carries a caveat that does not change the fact of the success: that the agent
     * stopped because it hit a limit, rather than because it had nothing more to say.
     */
    data class Completed(val note: String? = null) : AgentTurnOutcome

    /**
     * The turn did not finish.
     *
     * [reason] says what the reader can be told. It is a sentence for a person, not a
     * stack trace and not an internal diagnostic.
     */
    data class Failed(val reason: String) : AgentTurnOutcome

    /** The turn was cancelled — by the user, or by the screen going away. */
    data object Cancelled : AgentTurnOutcome
}

/**
 * One tool call, as the reader sees it.
 *
 * The card needs an identifier to update in place, a name to show, a status, and a
 * summary. The raw payload is deliberately not among them.
 */
data class AgentToolCall(val id: String, val name: String, val status: ToolCallStatus, val summary: String? = null)
