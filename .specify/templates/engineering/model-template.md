# Model — <title>

> Written by `speckit-engineering-model`. The transition table's columns deliberately mirror jqwik's
> state-machine model (`Action`, precondition, `Transformer.mutate`, `withInvariant`), so that
> migrating to property-based testing later is mechanical rather than a rewrite.
>
> **This step always produces a file.** Either a model, or a justified *Not applicable*. An empty
> document, or a table invented to fill the template, is a worse outcome than an honest omission.

## Purpose and scope

<What is modelled, and where the model boundary is drawn — which component's states these are.>

## Not applicable

_<**Delete everything else in this template** and keep only this section, if the four state tests
all came back "no". Keep it with:_

### Why no model is needed

This change <adds no state / does not alter the order in which events are handled>. Concretely:
<the specific reason — which values, if any, persist across calls, and why no event can arrive
twice with a different outcome>.

### How equivalence is held

`<ExistingTestClass>::<testFunction>` covers <what>.

_Justify concretely. "This is a simple change" is not a justification; "the edit is inside a pure
mapping function called once per input, holding no reference to prior calls" is._

## Model boundary

_<The component whose states these are. What is explicitly outside it?>

## States

| State | Meaning | Reachable from |
|---|---|---|
| `<Idle>` | <what this state means> | <initial / after X> |

## Events and triggers

| Event | Raised by |
|---|---|
| `<start()>` | <the caller> |

## Transition table

| Source | Event / trigger | Guard | Target | State mutation | Invariants checked | Side effects | Failure behaviour | Cancellation behaviour | Verification reference |
|---|---|---|---|---|---|---|---|---|---|
| `<Idle>` | `start()` | `windowMs > 0` | `<Ticking>` | sets generation, starts job | INV-01, INV-02 | none | n/a | job cancelled, generation left advanced | AC-01 — `FooTest::bar` |
| `<Ticking>` | `tick()` | predicate still true | `<Ticking>` | decrements remaining | INV-02 | emits progress | n/a | job cancelled | AC-02 — `FooTest::baz` |
| `<Ticking>` | `tick()` | predicate false | `<Idle>` | clears slot | INV-01 | none | n/a | as above | AC-03 — `FooTest::qux` |
| `<Ticking>` | `cancel()` | none | `<Idle>` | cancels job | INV-01 | none | n/a | idempotent | AC-04 — `FooTest::quux` |

_Column meanings:_

- **Guard** — the condition under which the transition is permitted. `none` if unconditional.
- **Invariants checked** — the `INV-NN` from `spec.md` that must hold after this transition.
- **Cancellation behaviour** — what happens if the coroutine is cancelled mid-step. "n/a" only for
  steps with no suspension point.

## Side effects

_<I/O caused by transitions — network, database, notifications, file writes. Not part of the state.>

## Failure transitions

_<What happens when a side effect fails. Does the state roll back, stay mutated, or enter an error
state? Is the failure retryable?>

## Cancellation and shutdown

_<Cancellation mid-flight between a state mutation and its side effect. Does the mutation roll
back? Who owns the scope? What is left behind?_

## Concurrency and serialization

_<What can run in parallel with what. What is serialized, and by what. What is the re-entrancy
rule? Are there read-modify-write windows?>

## Recovery and retry

_<What happens on retry. Is the operation idempotent? What prevents a replayed operation from
applying twice? What are the terminal states, and is there a path back from each?>

## Invariants

| Id | Restated as a state assertion | Checked by |
|---|---|---|
| INV-01 | <assertion over states> | <transition rows / test ids> |

## Counterexamples

### C-1 — <name>
- **Sequence:** <the events, in order>
- **Expected behavior:** <what should happen>
- **Why this is a risk:** <what could actually go wrong>

_The four that find real bugs most often here:_

- **Re-entry** — the same event twice; the second time with a stale generation.
- **Parallel calls** — two callers entering the same non-reentrant path.
- **Cancellation mid-flight** — cancelled between a mutation and its side effect; the mutation is
  not rolled back unless the design says so.
- **Restart** — an operation replayed after restart, re-applying work already done.

_A counterexample with no expected behavior is a worry, not a test case._

## Mapping to implementation

| Transition | Implemented at |
|---|---|
| `Idle --start()--> Ticking` | `path/to/File.kt:120` |

_If the code does not implement a transition yet, say "not yet implemented" — do not cite a line
that does not exist._

## Verification coverage

| Transition | Scenario | Acceptance |
|---|---|---|
| `Idle --start()--> Ticking` | V-01 | AC-01 |

_Any transition with no scenario is an untested risk, and `converge` will report it._