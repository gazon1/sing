# Contract: How a Tool Declares That It Writes

Every tool an agent can call must say, in the registry, how it writes. The point is that "all writes ask first" becomes an enforced fact rather than a belief.

## The field

Added to the tool registry entry beside the existing annotations:

```kotlin
enum class WriteBehavior {
    READ_ONLY,               // does not change state
    PROPOSAL_WRITE,          // changes user data through the proposal pipeline
    DIRECT_WRITE_EXCEPTION,  // writes directly — exceptionReason is required
}
```

```kotlin
data class ToolEntry(
    val name: String,
    val toolClass: KClass<out Tool<*, *>>,
    val domain: String,
    val annotations: ToolAnnotations = ToolAnnotations(),
    val writeBehavior: WriteBehavior,                              // no default
    val exceptionReason: String? = null,                           // required iff DIRECT_WRITE_EXCEPTION
)
```

**`writeBehavior` has no default value.** A tool added without classifying it does not compile. That is the whole mechanism: the failure mode is a red build, not a tool that quietly writes.

## Validation

1. Every entry declares exactly one behaviour.
2. `DIRECT_WRITE_EXCEPTION` requires a non-empty `exceptionReason`; the other two forbid one.
3. The registry and the tools actually exposed to agents agree in both directions. **This check already exists** — the catalog and the Koin list currently match at 43 classes with zero difference, and an existing test enforces it. Part A extends that test with the classification rather than building new infrastructure (D-15).
4. Because the exported tool-surface artifact is generated and gated, adding the field requires regenerating it in the same change; otherwise the gate reports a stale artifact instead of the real problem.

## The current classification

| Behaviour | Count | Tools | Why |
|---|---|---|---|
| `PROPOSAL_WRITE` | 13 | create/update/delete task, create/update/delete note, create/update/delete project, create/delete tag, task decomposition, task-proposal writing | These change what the user owns, so the user confirms them. |
| `DIRECT_WRITE_EXCEPTION` | 2 | write decision record · update decision record | Decision records write decision files under the project's decisions directory, not user data. |
| `READ_ONLY` | 28 | the remaining reads and analyses | Nothing changes. |

`create_tag` used to be the third exception — "a tag is not user content — a proposal for
one is noise". That was the stale half of a drift: the tool had been proposing for a
while, and the declaration had not caught up. A tag is user content after all, so it is
classified as a proposal-gated tool like every other change to what the user owns. See
`docs/decisions/2026-10-23-the-agent-surface-says-what-it-does.md`.

Both exception reasons are recorded in the registry and in the ADR, so the exception list is auditable without reading tool code (FR-050).

## The invariant this buys

> No write to the user's data happens outside the proposal pipeline, and every exception is named, justified, and counted by a test.

The tests are of two kinds:

- **Completeness** — every exposed tool is classified; every direct-write exception carries a reason. Breaks the build when violated.
- **Contract** — for a proposal-gated tool: calling it creates a proposal; confirming it writes; declining it leaves the data unchanged. The negative case is the one that matters, and it is the one a completeness test cannot express.

## Relationship to the three trust contours

This classification is about **our** writes, and only about our writes:

| Contour | Governs | Gate |
|---|---|---|
| External agent permission requests | the agent's own tools (bash, file writes) | the confirmation sheet in the app |
| Proposal pipeline | our writes to the user's data | the existing proposal confirmation |
| Direct-write exception | two named tools | the registry reason, verified by test |

An agent's permission request never gates our writes — those go to the agent over the tool server and come back as proposals. And our permission sheet never gates the agent's own tools. The two are separate mechanisms and must not be presented to the user as one.

## Rejected alternatives

| Alternative | Why not |
|---|---|
| Classify in the test rather than the registry | A test can check consistency, but a tool author has nothing to fill in; the registry is where the choice is made. |
| Default `writeBehavior` to `READ_ONLY` | Turns "forgot to classify" into "silently allowed to write". |
| Route the two exceptions through proposals too | A proposal for writing a decision record interrupts a flow that never touched user data; both would train the user to approve reflexively, which is worse than the exception. A tag used to be the third exception, on the grounds that "a proposal for one is noise the user learns to dismiss" — and that reasoning was wrong: a tag is user content, and the tool proposes like every other change to what the user owns. |
| Derive the behaviour from the existing annotations | The existing hints describe the MCP-visible shape; the question here is "what happens to the user's data", which is a different statement. |