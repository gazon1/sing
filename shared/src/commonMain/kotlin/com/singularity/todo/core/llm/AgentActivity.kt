package com.singularity.todo.core.llm

/**
 * What an agent has done so far, as the reader sees it.
 *
 * The folded form of a turn's [AgentEvent]s. It is a value, not a live thing: folding the
 * same events always produces the same state, which is what makes the reader's view
 * predictable and what makes the rule testable without a screen.
 */
data class AgentActivityState(
    /**
     * The tool calls, one entry per call.
     *
     * A call that is still running and the same call finished are one entry, not two.
     */
    val calls: List<AgentToolCall> = emptyList(),
    /** The agent's plan, as a snapshot. */
    val plan: List<AgentPlanStep> = emptyList(),
) {

    /**
     * Folds one event into the activity.
     *
     * ## Why this is a function and not a block of state updates
     *
     * Three surfaces will show an agent's activity: the built-in chat today, the external
     * agent's screen tomorrow, and whatever comes after. If each of them grew its own copy
     * of "what does this event do to what is on screen", the rules would drift — and the
     * drift would show up as a card that duplicates itself on one screen and not another.
     *
     * So the rules live here, once:
     *
     * - a text delta changes nothing, because the text belongs to the message, not to the
     *   activity;
     * - a tool call updates the card of that call, or adds one if it is new;
     * - a plan update **replaces** the plan, because it is a snapshot of the plan as it is now
     *   and not a patch to a plan the reader may no longer be looking at;
     * - the end of the turn changes nothing.
     */
    operator fun plus(event: AgentEvent): AgentActivityState = when (event) {
        is AgentEvent.TextDelta -> this

        is AgentEvent.TurnCompleted -> this

        is AgentEvent.ToolCallUpdated -> {
            val call = AgentToolCall(
                id = event.id,
                name = event.name,
                status = event.status,
                summary = event.summary,
            )
            val existing = calls.indexOfFirst { it.id == event.id }
            val updated = if (existing >= 0) {
                calls.toMutableList().also { it[existing] = call }
            } else {
                calls + call
            }
            copy(calls = updated)
        }

        is AgentEvent.PlanUpdated -> copy(plan = event.steps)
    }
}
