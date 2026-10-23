package com.singularity.todo.core.llm

/**
 * What an agent did, as it did it.
 *
 * ## Why the answer is not just text
 *
 * A turn used to produce text and nothing else, and that was a deliberate position: tool
 * calls and tool outcomes were the conversation's business, not the reader's. The person
 * asked a question and wanted the answer.
 *
 * That position holds while the chat answers questions. It stops holding the moment the
 * question is about the user's own data, because then three questions have no answer on the
 * screen: *what did you just do to my tasks?*, *that tool you called — did it work?*, and
 * *you have been busy for two minutes, are you stuck?* A failed tool was invisible, and a
 * cancelled turn left the spinner running.
 *
 * So a turn produces activity, and the text is one kind of it.
 *
 * ## Why the vocabulary is this small
 *
 * Four kinds. Text as it arrives, a tool call and its outcome, the plan, and the end of the
 * turn. Small enough to enumerate, which is what makes the mapping from a protocol's many
 * session updates testable by exhaustion — including the ones deliberately dropped.
 *
 * A general activity platform, an event bus and a hierarchy of adapters were all considered
 * and all rejected: each adds a lifecycle to own and a leak to debug, in exchange for
 * flexibility nothing here uses.
 *
 * ## What this vocabulary deliberately does not carry
 *
 * **The raw payload of a tool call.** Not "not by default" — not at all. A payload is where
 * the user's content lives, and an event that carries one puts that content into the state
 * of every screen that renders it, and tempts it into the log. A tool call carries a
 * [ToolCallUpdated.summary] instead: identifiers, titles and field names, bounded in
 * length, built by the adapter that knows the tool.
 *
 * **Transport failure.** A failure is not an event, it is the absence of the completion
 * event, and it becomes a terminal outcome on the turn. Dressing a failure up as a
 * successful completion is the one thing this vocabulary must not permit: the reader would
 * be told the agent finished when it did not.
 *
 * **The agent's private reasoning.** Not asked for, and not part of the four kinds.
 *
 * ## Where the vocabulary lives
 *
 * In `core/llm`, beside the port it replaces, because `core/ai` already depends on
 * `core/llm` in one direction only. Declaring it in the feature package and using it from
 * the port would invert that.
 */
sealed interface AgentEvent {

    /**
     * A piece of the answer, as it arrives.
     *
     * An **addition**, never the whole message: the deltas of one turn concatenate to the
     * answer. Overwriting with a delta loses the text that came before it, which is the
     * bug this wording exists to prevent.
     */
    data class TextDelta(val text: String) : AgentEvent

    /**
     * A tool call, and where it got to.
     *
     * [id] identifies the call and is stable across updates of the same call, so the card
     * for a call is updated in place rather than replaced. [name] is the tool's own name —
     * the one the agent asked for.
     *
     * [summary] is a short, sanitized, bounded description of what the call touched. It is
     * the only detail about the call that travels with the event; the raw payload does not.
     */
    data class ToolCallUpdated(
        val id: String,
        val name: String,
        val status: ToolCallStatus,
        val summary: String? = null,
    ) : AgentEvent

    /**
     * The agent's plan, as it currently stands.
     *
     * A **snapshot**, not a delta: each update replaces the plan wholesale, because a
     * reader looking at the screen wants the plan as it is now, not a patch to apply to a
     * plan they may no longer be looking at.
     */
    data class PlanUpdated(val steps: List<AgentPlanStep>) : AgentEvent

    /**
     * The turn finished, successfully.
     *
     * Emitted once per turn, and only on success. A turn that ends any other way ends
     * without this event, and that absence is what the terminal outcome is derived from.
     */
    data object TurnCompleted : AgentEvent
}

/**
 * Where a tool call got to.
 *
 * Three values, because the reader has one question: is it still going, did it work, did it
 * break. The granularity of the source is not the reader's business.
 */
enum class ToolCallStatus {
    RUNNING,
    COMPLETED,
    FAILED,
}

/**
 * One step of an agent's plan.
 *
 * [title] is the step as the agent wrote it. [status] is where the step got to.
 */
data class AgentPlanStep(val title: String, val status: PlanStepStatus)

/** Where a step of an agent's plan got to. */
enum class PlanStepStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
}
