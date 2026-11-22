---
title: "Cross-screen journeys drive the contract, and navigation is published rather than performed"
date: 2026-11-22
tags: [introspection, agent, testing, architecture]
status: accepted
---

## Context

Issue #3043 is the rollout-evidence step of the headless introspection epic (#3038). The
contract (#3040), its Task Detail adapter and the local host (#3041/#3042) are delivered and the
Task Detail POC is closed (#3039). What is missing is the evidence that the contract is useful
beyond one screen: journeys that cross real screen boundaries, and a decision about where further
rollout is worth its cost.

Two facts shaped this change.

**The contract had no honest way to express a transition.** Its `ScreenState` carried a status, a
subject and a focus. A screen that decided "the user asked to open this task" had nowhere to say
so: the destination lived in a one-shot UI event (`AgendaUiEvent.NavigateToTask`,
`TaskDetailUiEvent.NavigateBack`) that only the screen's own event handler reads. A driver could
only get past that point by pushing routes itself, which the issue forbids — the driver must not
mutate navigation state behind the application's back.

**The second screen is structurally different from the first.** Task Detail is a coordinator whose
subject is one task. The agenda is a plain `MviViewModel` over a list: the subject is many rows,
the focus is a selection, and the transition is an event rather than a state value. A contract
that only works for the first shape is not a contract, it is one screen's adapter with a shared
vocabulary.

## Idea

Three representative journeys over the real ViewModels of three different screens — the agenda
task list, the task editor and the Task Detail coordinator — plus one small addition to the
contract that lets a screen publish where it wants to go.

## Decision

1. **`ScreenState` gains a `navigation` field**, published in the snapshot as
   `{"screen": "<id>", "params": {...}}` and null when the screen has asked for nothing. The
   destination is the one the screen itself decided on — the same value its click handler hands
   the application's navigator — so a driver opens the next screen *from the screen's own answer*
   instead of constructing routes. The contract version moves to 2: this changes what a snapshot
   may contain.
2. **The authority stays with the screen.** An adapter maps the screen's own one-shot event into
   `navigation` (the app's event handler, in miniature); the driver only reads it. Nothing in the
   contract dispatches navigation, and a screen that cannot navigate never publishes the field.
3. **A second adapter covers the task list** (`agenda.tasks`, `AgendaScreenIntrospection`), with
   the screen's project as a parameter rather than a second screen id: the Inbox tab and a
   project's task list are one screen with different parameters.
4. **Journeys assert on snapshots and repository effects only.** Three cross-screen journeys run
   in the existing JVM lane on the existing fakes and virtual time: list → detail → change → list
   re-render; project list → project-scoped task → complete; list completion plus a task created
   through the real editor. Each action mapping additionally has negative coverage — unpublished
   action, missing field, wrong type, extra property, and a task id the list does not show are all
   refused before dispatch, and the repository is asserted untouched.
5. **The list adapter is not registered with the inspector host in this issue.** It is reached by
   the journeys, which is what #3043 asks for. Host exposure is a separate question (a session
   over an `AgendaViewModel` resolved from Koin, with its own completion observation) and is not
   smuggled in here; `scripts/find-unwired-surfaces-baseline.txt` records that with this ADR as
   its backlog reference.

## Rationale

Publishing the destination is the smallest change that satisfies "navigation transitions must be
observable in the contract" without giving the driver a second way to move the application. The
alternative — a callback the driver invokes to push a route — would put navigation state under the
driver's control and make the application's own navigator a bystander.

Leaving the list adapter out of the host registry is the lazy-but-complete choice: the deliverable
of #3043 is evidence, and the evidence is the journeys. Wiring a host session for the second screen
is #3041-shaped work with its own completion semantics, and shipping it here would double the diff
for no additional evidence about the contract.

Coverage limits are stated where the tests are: these journeys say nothing about whether a Compose
control is wired to a published action, whether every visible value is in a snapshot, or whether
anything renders. Those belong to #3049 and the existing Maestro/Compose lanes.

## Consequences

- A new adapter is a thin projection plus an intent mapping; the validation, availability and
  dispatch semantics still come from `ScreenIntrospection`. The second adapter needed no change to
  those rules, which is the evidence the rollout was asked for.
- Snapshot consumers see one new optional key (`navigation`). The Task Detail adapter never sets
  it, so its snapshots and its completion post-conditions are unchanged.
- The negative control was run: with `dispatch` reduced to a no-op, exactly the three dispatch
  journeys fail and the three contract/refusal journeys still pass — the tests measure the
  application's behavior, not their own setup.
- If a later issue exposes the list through the host, the baseline row goes and the ADR is
  superseded in the usual way.

## Links

- Epic #3038; this issue: #3043; contract: #3040; host: #3041/#3042; UI wiring: #3049.
- ADR `2026-11-18-the-reusable-screen-introspection-contract` (the contract this extends);
  ADR `2026-11-18-reflex-adoption-strategy-mvi-adapter-evolution-migration` (gradual evolution).
- Code: `shared/src/commonMain/kotlin/com/singularity/todo/core/llm/ScreenIntrospection.kt`;
  `.../feature/agenda/presentation/introspection/AgendaScreenIntrospection.kt`;
  `shared/src/commonTest/kotlin/com/singularity/todo/feature/journey/`.
