@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package com.singularity.todo.feature.ai.acp

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.PlanEntryStatus
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.model.ToolCallStatus as AcpToolCallStatus
import com.singularity.todo.core.llm.AgentEvent
import com.singularity.todo.core.llm.AgentPlanStep
import com.singularity.todo.core.llm.AgentTurnOutcome
import com.singularity.todo.core.llm.PlanStepStatus
import com.singularity.todo.core.llm.ToolCallStatus
import com.singularity.todo.core.llm.toolCallSummary

/**
 * Turns the protocol's session updates into the application's activity vocabulary.
 *
 * ## Why this is a pure function
 *
 * The protocol has many ways to say things; the application has four kinds of activity.
 * The mapping between them is a table, and a table that is a table can be checked by
 * exhaustion — every variant of the input, and what it produces.
 *
 * That matters most for the variants that produce **nothing**. A protocol gains session
 * updates over time, and an update this adapter does not know about has to produce no
 * event rather than a wrong one: an unrecognised update that invented an activity would
 * put a card on the reader's screen that no tool call ever justified.
 *
 * ## The ignore list, and why each entry is ignored
 *
 * - **The user's own message** — the application already knows what the user typed.
 * - **The agent's thoughts** — the agent's private reasoning is not the reader's business.
 * - **Plan v2 and plan removal** — both are unstable protocol features this application
 *   does not advertise support for; the stable plan update carries everything shown.
 * - **Available commands, current mode, configuration options** — session settings. They
 *   belong to a settings screen, not to the story of what the agent just did.
 * - **Session information and token usage** — bookkeeping. Usage has its own screen.
 * - **Unknown updates** — forward compatibility. An update this SDK version does not
 *   know about is not yet something the reader should be shown.
 *
 * ## The status vocabulary
 *
 * The protocol has four tool-call statuses; the application has three. A call that is
 * pending and a call that is in progress are the same thing to a reader — *it is
 * happening* — and the difference between them is the agent's bookkeeping.
 */
object AcpEventAdapter {

    /**
     * The activity an update describes, or null when the update says nothing the reader
     * needs to see.
     */
    fun adapt(update: SessionUpdate): AgentEvent? = when (update) {
        is SessionUpdate.AgentMessageChunk -> update.content.textOrNull()?.let { AgentEvent.TextDelta(it) }

        is SessionUpdate.ToolCall -> AgentEvent.ToolCallUpdated(
            id = update.toolCallId.value,
            name = update.title,
            status = update.status.toStatus(),
            summary = summaryFor(update.title, update.rawInput),
        )

        is SessionUpdate.ToolCallUpdate -> AgentEvent.ToolCallUpdated(
            id = update.toolCallId.value,
            name = update.title.orEmpty(),
            status = update.status.toStatus(),
            summary = update.title?.let { summaryFor(it, update.rawInput) },
        )

        is SessionUpdate.PlanUpdate -> AgentEvent.PlanUpdated(
            steps = update.entries.map { entry ->
                AgentPlanStep(title = entry.content, status = entry.status.toStepStatus())
            },
        )

        // Everything else is either the user's own message, the agent's private
        // reasoning, a session setting, an unstable variant this application does not
        // advertise, or an update this SDK version does not know. None of them is
        // activity, and an unrecognised update must not invent one.
        is SessionUpdate.UserMessageChunk,
        is SessionUpdate.AgentThoughtChunk,
        is SessionUpdate.PlanUpdateV2,
        is SessionUpdate.PlanRemoved,
        is SessionUpdate.AvailableCommandsUpdate,
        is SessionUpdate.CurrentModeUpdate,
        is SessionUpdate.ConfigOptionUpdate,
        is SessionUpdate.SessionInfoUpdate,
        is SessionUpdate.UsageUpdate,
        is SessionUpdate.UnknownSessionUpdate,
        -> null
    }

    /**
     * How a turn that ended with [stopReason] ended.
     *
     * The distinction that carries weight: a turn that hit a limit **succeeded**. The
     * agent answered, and the answer was cut short — which is a caveat, not a failure.
     * Calling that a failure would tell the reader their agent broke when it did not.
     *
     * A refusal is a failure: the agent declined to answer, and there is no answer.
     */
    fun outcomeFor(stopReason: StopReason): AgentTurnOutcome = when (stopReason) {
        StopReason.END_TURN -> AgentTurnOutcome.Completed()

        StopReason.MAX_TOKENS -> AgentTurnOutcome.Completed(
            note = "The agent stopped because it reached its token limit.",
        )

        StopReason.MAX_TURN_REQUESTS -> AgentTurnOutcome.Completed(
            note = "The agent stopped because it reached its limit on turn requests.",
        )

        StopReason.REFUSAL -> AgentTurnOutcome.Failed("The agent refused to answer.")

        StopReason.CANCELLED -> AgentTurnOutcome.Cancelled
    }

    /**
     * The protocol's four statuses, collapsed into the application's three.
     *
     * A missing status is a running call: the protocol makes it optional, and the only
     * sensible reading of "no status yet" is "it has not finished".
     */
    private fun AcpToolCallStatus?.toStatus(): ToolCallStatus = when (this) {
        AcpToolCallStatus.PENDING, AcpToolCallStatus.IN_PROGRESS, null -> ToolCallStatus.RUNNING
        AcpToolCallStatus.COMPLETED -> ToolCallStatus.COMPLETED
        AcpToolCallStatus.FAILED -> ToolCallStatus.FAILED
    }

    private fun PlanEntryStatus.toStepStatus(): PlanStepStatus = when (this) {
        PlanEntryStatus.PENDING -> PlanStepStatus.PENDING
        PlanEntryStatus.IN_PROGRESS -> PlanStepStatus.IN_PROGRESS
        PlanEntryStatus.COMPLETED -> PlanStepStatus.COMPLETED
    }

    /**
     * The text of a content block, when it is text.
     *
     * An image or a resource in a message is not something to render as a delta of the
     * answer; the reader is here for the words.
     */
    private fun ContentBlock.textOrNull(): String? = (this as? ContentBlock.Text)?.text

    /**
     * The bounded summary of a tool call.
     *
     * The protocol's raw input is the tool's arguments, and the arguments are where the
     * user's content lives. The summary names the fields and quotes only the values that
     * are short enough to be labels — the same rule the built-in agent's calls follow,
     * applied by the same function, so a card cannot widen it.
     */
    private fun summaryFor(title: String, rawInput: kotlinx.serialization.json.JsonElement?): String =
        toolCallSummary(title, rawInput?.toString())
}
