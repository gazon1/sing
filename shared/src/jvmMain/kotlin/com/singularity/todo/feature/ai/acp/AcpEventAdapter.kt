package com.singularity.todo.feature.ai.acp

import com.singularity.todo.core.llm.AgentEvent
import com.singularity.todo.core.llm.AgentPlanStep
import com.singularity.todo.core.llm.AgentTurnOutcome
import com.singularity.todo.core.llm.PlanStepStatus
import com.singularity.todo.core.llm.ToolCallStatus

/**
 * Turns what the agent did into the application's activity vocabulary.
 *
 * ## Why this is a pure function
 *
 * The agent has many ways to say things; the application has four kinds of activity.
 * The mapping between them is a table, and a table that is a table can be checked by
 * exhaustion — every variant of the input, and what it produces.
 *
 * That matters most for the variants that produce **nothing**. The protocol gains
 * session updates over time, and an update this adapter does not know about has to
 * produce no event rather than a wrong one: an unrecognised update that invented an
 * activity would put a card on the reader's screen that no tool call ever justified.
 *
 * ## The ignore list, and why each entry is ignored
 *
 * - **The user's own message** — the application already knows what the user typed.
 * - **The agent's thoughts** — the agent's private reasoning is not the reader's business.
 * - **Session settings** — commands, modes, configuration and bookkeeping. They belong
 *   to a settings screen, not to the story of what the agent just did.
 * - **Unknown updates** — forward compatibility. An update this application does not
 *   know about is not yet something the reader should be shown.
 *
 * ## The status vocabulary
 *
 * The agent's bookkeeping has four tool-call states; the application has three. A
 * call that is pending and a call that is in progress are the same thing to a reader
 * — *it is happening* — and the difference between them is the agent's bookkeeping.
 *
 * ## Why no protocol type appears here
 *
 * The protocol library is reached in one file, and this is not it. Everything this
 * table reads is the application's own vocabulary, so the table can be read, checked
 * and changed without knowing what the protocol looks like — and an upgrade of the
 * library cannot change what a reader sees, because it cannot reach this file.
 */
object AcpEventAdapter {

    /**
     * The activity an update describes, or null when the update says nothing the reader
     * needs to see.
     */
    fun adapt(update: AcpSessionUpdate): AgentEvent? = when (update) {
        is AcpSessionUpdate.TextDelta -> AgentEvent.TextDelta(update.text)

        is AcpSessionUpdate.ToolCall -> AgentEvent.ToolCallUpdated(
            id = update.id,
            name = update.name,
            status = update.status.toStatus(),
            summary = update.summary,
        )

        is AcpSessionUpdate.Plan -> AgentEvent.PlanUpdated(
            steps = update.steps.map { step ->
                AgentPlanStep(title = step.title, status = step.status.toStepStatus())
            },
        )

        // Everything else is either the user's own message, the agent's private
        // reasoning, a session setting, or an update this application does not know.
        // None of them is activity, and an unrecognised update must not invent one.
        AcpSessionUpdate.UserMessage,
        AcpSessionUpdate.AgentThought,
        AcpSessionUpdate.SessionSetting,
        AcpSessionUpdate.Unrecognised,
        -> null
    }

    /**
     * How a turn that ended for [reason] ended.
     *
     * The distinction that carries weight: a turn that hit a limit **succeeded**. The
     * agent answered, and the answer was cut short — which is a caveat, not a failure.
     * Calling that a failure would tell the reader their agent broke when it did not.
     *
     * A refusal is a failure: the agent declined to answer, and there is no answer.
     */
    fun outcomeFor(reason: AcpStopReason): AgentTurnOutcome = when (reason) {
        AcpStopReason.END_TURN -> AgentTurnOutcome.Completed()

        AcpStopReason.MAX_TOKENS -> AgentTurnOutcome.Completed(
            note = "The agent stopped because it reached its token limit.",
        )

        AcpStopReason.MAX_TURN_REQUESTS -> AgentTurnOutcome.Completed(
            note = "The agent stopped because it reached its limit on turn requests.",
        )

        AcpStopReason.REFUSAL -> AgentTurnOutcome.Failed("The agent refused to answer.")

        AcpStopReason.CANCELLED -> AgentTurnOutcome.Cancelled
    }

    /**
     * The agent's four states, collapsed into the application's three.
     */
    private fun AcpToolCallState.toStatus(): ToolCallStatus = when (this) {
        AcpToolCallState.PENDING, AcpToolCallState.IN_PROGRESS -> ToolCallStatus.RUNNING
        AcpToolCallState.COMPLETED -> ToolCallStatus.COMPLETED
        AcpToolCallState.FAILED -> ToolCallStatus.FAILED
    }

    private fun AcpPlanStepState.toStepStatus(): PlanStepStatus = when (this) {
        AcpPlanStepState.PENDING -> PlanStepStatus.PENDING
        AcpPlanStepState.IN_PROGRESS -> PlanStepStatus.IN_PROGRESS
        AcpPlanStepState.COMPLETED -> PlanStepStatus.COMPLETED
    }
}
