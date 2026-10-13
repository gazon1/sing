---
name: speckit-engineering-model
description: 'Build model.md — states, events, transitions, side effects, cancellation, concurrency and recovery, shaped to map onto jqwik state-machine testing — or a justified Not applicable for work that has no state. Third step of Engineering SDD.'
disable-model-invocation: true
---

# Engineering SDD — Model

## User Input

```text
$ARGUMENTS
```

`$ARGUMENTS` carries `slug`, `depth` and `request`.

## Target

**Read first, every time:** `.specify/templates/engineering/conventions.md`.

- Read: `openspec/changes/<slug>/spec.md`, `investigation.md`, and the code they cite.
- Write: `openspec/changes/<slug>/model.md`

## Goal

Model the states and transitions the change will introduce or alter — **or** establish, with
reasons, that there is nothing to model.

### First: does this change have state?

Run the four tests:

1. Does anything hold a value that a later event reads? → state.
2. Can the same event arrive twice with a different outcome depending on what happened before?
   → state.
3. Can work be interrupted and resumed, or retried after failure? → state.
4. Can two callers interleave? → state.

If all four are no, write `## Not applicable` and stop. See below.

### The transition table

The table is the artifact. Its columns are fixed, and deliberately mirror jqwik's state-machine
model so that a later migration to property-based testing is mechanical:

| Column | Maps to |
|---|---|
| Source | the state before |
| Event / trigger | `Action` |
| Guard | `Action` precondition — constrains when the action is generated |
| Target | the state after |
| State mutation | `Transformer.mutate()` plus its postcondition |
| Invariants checked | `withInvariant(...)` — held after every step |
| Side effects | I/O the step causes |
| Failure behaviour | what happens when the effect fails |
| Cancellation behaviour | what happens if the coroutine is cancelled mid-step |
| Verification reference | the test id from `spec.md`'s `AC-NN` |

### Required content

```
# <title>

## Purpose and scope        — what is modelled and what is deliberately outside the boundary
## Not applicable           — ONLY when no model is needed (see below)
## Model boundary            — the component whose states these are
## States
## Events and triggers
## Transition table          — the table above
## Side effects
## Failure transitions
## Cancellation and shutdown
## Concurrency and serialization
## Recovery and retry
## Invariants                — INV-NN from spec.md, restated as state assertions
## Counterexamples           — sequences you believe break an invariant
## Mapping to implementation — which file and function realises each transition
## Verification coverage     — which AC-NN test covers which transition
```

Answer all of these, or state why they do not apply:

- Are there transitions the code can take that this table does not describe?
- What happens if the same event arrives twice?
- What happens if two callers arrive concurrently?
- What happens if the coroutine is cancelled between the mutation and the side effect?
- Which operations can be applied twice, and is that safe?
- Can an event be lost, or applied twice?
- Are there terminal states, and is there a path back?

### Counterexamples

List the sequences you believe break an invariant. The four that find real bugs most often in this
codebase:

- **Re-entry** — the same event twice; second time with a stale generation, as
  `CountdownStateMachine.start { predicate }` handles.
- **Parallel calls** — two callers entering the same non-reentrant path.
- **Cancellation mid-flight** — cancelled between a state mutation and its side effect. The
  mutation is not rolled back unless the design says so.
- **Restart** — the same operation replayed after a process restart, replaying work already applied.

State the *expected* behaviour for each, not just the risk. A counterexample with no expected
outcome is a worry, not a test case.

## When no model is needed

Write exactly this, and nothing else:

```markdown
## Not applicable

This change adds no state and does not alter the order in which events are handled.
<one or two sentences of concrete justification>

Behaviour equivalence is held by: `<existing TestClass>::<testFunction>`, which covers
<what>.
```

Two rules:

- Justify it **concretely**. "This is a simple change" is not a justification; "the edit is inside a
  pure mapping function that is called once per input and holds no reference to prior calls" is.
- An empty `model.md`, or a table invented to fill the template, is worse than a justified
  *Not applicable*. A fabricated machine sends the next agent looking for states that do not exist.

## Forbidden

- Inventing states not grounded in observed behaviour. If you read the code and did not find it,
  it is not there — unless you are specifying new behaviour, in which case say which `AC-NN`
  requires it.
- Presenting the model as verified. This is a proposal, checked by the `review-spec` gate.
- Dropping the cancellation column because "cancellation is handled elsewhere". It is not handled
  elsewhere in a way that makes this component immune.
- Editing `spec.md`, `design.md`, `verification.md` or any code.

## Done When

- `model.md` exists and starts with either `## States` or `## Not applicable`.
- Every transition row has all ten columns filled, or an explicit `n/a` with a reason.
- Every `INV-NN` from `spec.md` is restated and is checked by at least one transition.
- Every significant transition references an `AC-NN` test id.
- At least three counterexamples, unless the four state tests in the Goal all came back no.