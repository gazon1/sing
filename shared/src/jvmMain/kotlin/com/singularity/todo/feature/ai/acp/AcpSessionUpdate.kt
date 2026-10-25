package com.singularity.todo.feature.ai.acp

/**
 * What the agent did, in the application's own words.
 *
 * ## Why the protocol's updates are not carried
 *
 * The protocol has its own update type, and it grows: every version of the library
 * may add variants to it. A type that crosses into the screen cannot be allowed to
 * grow, because every place that reads it has to be revisited when it does.
 *
 * So the protocol's updates are translated here, at the door, into a vocabulary that
 * belongs to the application. What crosses the door is this type — and the variants
 * it has are the ones the application has decided to talk about.
 *
 * ## Why the uninteresting variants are here
 *
 * Four of these variants carry nothing a reader should see: the reader's own message,
 * the agent's private reasoning, the session's settings, and an update this
 * application does not recognise.
 *
 * They are variants rather than absences on purpose. A translation that simply
 * dropped them would have to decide *at the door* what is worth showing — and the
 * decision would be made where the protocol's shape is known and the reader's is not.
 * Here, the door only says *what happened*, and [AcpEventAdapter] decides what a
 * reader sees. That decision is a table, and a table can be checked by exhaustion.
 *
 * The cost is one extra step in the translation. The benefit is that the table of
 * what the reader sees is written without a single protocol type in it, and can be
 * read without knowing the protocol at all.
 */
sealed interface AcpSessionUpdate {

    /** The agent said something, in words. */
    data class TextDelta(val text: String) : AcpSessionUpdate

    /**
     * The agent called a tool, and this is where the call got to.
     *
     * [summary] is a short, sanitized, bounded description of what the call touched:
     * the only detail about the call that travels. The raw payload does not.
     */
    data class ToolCall(val id: String, val name: String, val status: AcpToolCallState, val summary: String?) :
        AcpSessionUpdate

    /** The agent's plan, as it currently stands. */
    data class Plan(val steps: List<AcpPlanStep>) : AcpSessionUpdate

    /** The reader's own message, coming back from the agent. */
    data object UserMessage : AcpSessionUpdate

    /** The agent's private reasoning. */
    data object AgentThought : AcpSessionUpdate

    /** A session setting: commands, modes, configuration, bookkeeping. */
    data object SessionSetting : AcpSessionUpdate

    /** An update this application does not recognise. */
    data object Unrecognised : AcpSessionUpdate
}

/**
 * Where a tool call got to.
 *
 * Four values, because the agent's bookkeeping has four: a call can be waiting, can
 * be running, can have worked, and can have broken. Collapsing them is not this
 * type's job — what a reader makes of them is the adapter's.
 */
enum class AcpToolCallState {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
}

/** One step of the agent's plan. */
data class AcpPlanStep(val title: String, val status: AcpPlanStepState)

/** Where a plan step got to. */
enum class AcpPlanStepState {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
}

/**
 * Why a turn ended.
 *
 * The agent's own reasons, one for one: the protocol distinguishes a turn that ran
 * out of tokens from one that ran out of turn requests, and the application keeps
 * them apart rather than flattening them into "finished".
 */
enum class AcpStopReason {
    END_TURN,
    MAX_TOKENS,
    MAX_TURN_REQUESTS,
    REFUSAL,
    CANCELLED,
}
