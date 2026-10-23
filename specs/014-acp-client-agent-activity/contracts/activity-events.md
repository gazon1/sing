# Contract: The Activity Event Vocabulary

The single language every agent surface speaks. The built-in agent emits it; the external-agent adapter translates the protocol into it; every ViewModel consumes it.

Declared in `commonMain` under `core/llm` (D-16) because `core/ai` already depends on `core/llm` in one direction only — putting the vocabulary in `feature/ai` and using it from the port would invert that.

## Shape

```kotlin
sealed interface AgentEvent {
    data class TextDelta(val text: String) : AgentEvent
    data class ToolCallUpdated(
        val id: String,
        val name: String,
        val status: ToolCallStatus,
        val summary: String? = null,
    ) : AgentEvent
    data class PlanUpdated(val steps: List<AgentPlanStep>) : AgentEvent
    data object TurnCompleted : AgentEvent
}

enum class ToolCallStatus { RUNNING, COMPLETED, FAILED }
data class AgentPlanStep(val title: String, val status: PlanStepStatus)
enum class PlanStepStatus { PENDING, IN_PROGRESS, COMPLETED }
```

## Guarantees

| Guarantee | Requirement | How it is proved |
|---|---|---|
| Exactly four kinds | FR-017 | The sealed hierarchy is the enumeration; the adapter's mapping table is tested exhaustively. |
| Terminal outcome is not an event | FR-023 | `TurnCompleted` means *success*. Failure is turn state, produced in the collector's `finally` when no completion event arrived (D-05). |
| Exactly one terminal outcome | FR-022 | A single guarded transition from unset to set; a second attempt is ignored. |
| No raw payloads | FR-019, FR-020 | `summary` is the only tool detail on the event, and it is sanitized and bounded by the adapter. |
| Order is deterministic | spec §6.5 | The flow preserves emission order; the adapter is a pure per-update mapping. |

## Port change

```kotlin
fun streamChat(message: String): Flow<AgentEvent>   // was Flow<String>
suspend fun generate(...): Result<String>           // unchanged
```

`generate()` stays text-shaped for callers that only need an answer (FR-027). Three implementations change; there is one call site.

## Producers

### Built-in agent

`ToolCallLoop` currently returns `Flow<String>` and its KDoc states that tool calls are the conversation's business, not the reader's. That principle is reversed, and the KDoc is rewritten to say why (FR-025):

- model text → `TextDelta`
- a tool the model asked for → `ToolCallUpdated(RUNNING)` before execution
- its result → `ToolCallUpdated(COMPLETED)`, or `(FAILED)` with a summary on failure
- the model's own plan, where it has one → `PlanUpdated`
- normal end of the loop → `TurnCompleted`

A failed tool is an event, not an exception: the loop already converts a tool failure into a result the model can read, and that same outcome now also becomes a visible `FAILED` status.

### External agent

One adapter maps protocol session updates into the vocabulary (D-07, D-08):

| Protocol update | Event |
|---|---|
| agent message chunk | `TextDelta` |
| tool call | `ToolCallUpdated`, status collapsed |
| tool call update | `ToolCallUpdated` for the same id |
| plan update | `PlanUpdated` |
| everything else | ignored, by decision |

## Consumer

A ViewModel holds the turn state and renders one card per tool call id plus one plan card. The presentation is shared, so the built-in chat and the external-agent screen render identically — which is the point: a reader who has learned one screen has learned both.

## Rejected alternatives

| Alternative | Why not |
|---|---|
| An event bus with subscribers | Nothing else needs the events. A bus would add a lifecycle to own and a leak to debug. |
| Raw tool payloads on the event | Leaks user content into UI state and tempts it into the log. |
| A "tool failed" event alongside `TurnCompleted` | Mixes activity with transport outcome; the review round 2 rejected exactly this. |
| Four terminal outcomes (add "truncated") | A truncation note on a successful turn says everything a fourth outcome would, without splitting the state machine. |