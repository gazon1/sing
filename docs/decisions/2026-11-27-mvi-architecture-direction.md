---
title: "MVI architecture direction and migration policy"
date: 2026-11-27
status: accepted
tags: [mvi, architecture, framework]
deciders: Singularity Developer
supersedes: 2026-09-25-local-mvi-framework
epic: "#3225"
issue: "#3235"
---

# MVI architecture direction and migration policy

> Decision for epic #3225 / issue #3235: the long-term MVI direction, recorded as an ADR
> with evidence and trade-offs rather than as a preference.

## Context

Epic #3225 asked which outcome should hold for the MVI layer of the app: retain the
stabilized in-repo MVI, adopt a third-party framework gradually, or retain the current MVI
while borrowing selected ideas.

The repository already carried a deliberate lightweight MVI:
`2026-09-25-local-mvi-framework.md` introduced `MviViewModel<S, I, E>` — single state flow,
Channel-backed `EventBus`, typed `onIntent` — and later ADRs corrected parts of that proposal
(`2026-09-27-mvi-single-state-entry-and-vm-sweep.md`,
`2026-11-26-mvi-state-is-observed-read-only.md`,
`2026-11-26-the-draft-keeps-one-value-and-one-snapshot.md`).

The Reflex decision (`2026-11-18-reflex-adoption-strategy-mvi-adapter-evolution-migration.md`)
kept the MVI seam and evolved it gradually, without replacing the runtime.

The FlowMVI evaluation (`2026-11-27-flowmvi-compatibility-assessment.md`) followed by the
measured spike (`build/investigation/flowmvi-spike/FINDINGS.md`, closed by PR #3259) compared
FlowMVI 3.2.1 against the protected in-repo contracts and recommended **no adoption**.

## Idea

Three outcomes were on the table:

1. retain the stabilized in-repo MVI;
2. adopt FlowMVI gradually;
3. retain the current MVI while borrowing selected ideas.

## Decision

**Option 3: retain the in-repo MVI as the application architecture; borrow ideas only when
they solve a demonstrated local problem.**

What stays:

- `MviViewModel<S, I, E>` with the read-only observed state, the single state-update entry
  (`updateState` / `setState`), the typed `onIntent` dispatcher and the Channel-backed
  single-consumer `EventBus`;
- the injected `AutoCloseableCoroutineScope` teardown semantics;
- the `DraftMviViewModel` / `DraftState` invariants;
- the error path (`runCatchingCancellable` bounds at `Exception`; fatal `Error` escapes).

What changes: nothing in the runtime. The decision is a direction, not a migration, and the
migration boundaries below are deliberately not executed.

Adoption boundary:

- No production FlowMVI dependency, no parallel production implementation, no published reusable
  framework. The spike remains disposable evidence.
- No new MVI Gradle module as part of adopting a framework or building a parallel runtime; the
  existing in-repo MVI remains the architecture. The planned package extraction is a separate,
  independently gated initiative — epic #3268, stages #3269–#3271, starting with an in-repository
  KMP module and gated on independent consumer validation before any separate repository or
  publication — and is not preempted or contradicted by this decision.

## Rationale

The decision is evidence-based, not preference-based.

- The #3234 spike ran the six comparable Task List scenarios against both implementations and
  produced **identical observable behavior** — including one-shot events, which the error case
  asserts because state alone cannot tell a swallowed error from a reported one.
- Measured contract gaps favor the in-repo implementation: a fatal JVM `Error` leaves a FlowMVI
  pipeline stalled with nothing observable through any handler the app can install; the post-close
  drop counter the app contract requires has no FlowMVI equivalent; a long-lived repository
  subscription wired through `init` silently ate intents until `asyncInit` was paired correctly.
- The operational cost of adopting FlowMVI (Compose/lifecycle downgrade to defend against, a
  per-destination store wrapper for Nav3 ownership, lifecycle semantics that differ from the
  app's teardown contract) outweighs a behavior gain the comparison did not find.

## Consequences

- The in-repo MVI stays the application architecture; no framework expansion is added
  speculatively.
- Existing contracts are treated as public: read-only observation, single-consumer events,
  scope ownership, draft invariants, error policy — changes to any of them go through a focused
  ADR and test, not through a framework swap.
- The FlowMVI spike directory (`build/investigation/flowmvi-spike/`) stays disposable and is
  deleted when the issue closes.

## Migration policy

- Retain now; evolve by narrow, evidence-led changes over the existing seam (coordinators /
  slots / Reflex-style adapters already carry screen-specific coordination above the base class).
- No production migration is executing, no ViewModel rewrite, no new MVI module under this
  direction; the separately gated package extraction (epic #3268, stages #3269–#3271) is a
  distinct initiative.
- Any future move toward a different framework requires a new ADR superseding this one, with the
  measured gates (Compose 1.12+ baseline, observable fatal-error handling, documented action
  delivery semantics) re-run first.

## Revisit triggers

Reopen the direction only when evidence changes materially, for example: a maintained FlowMVI
stable release no longer downgrades the app's Compose/Lifecycle baseline and integrates safely;
fatal-error and action-delivery contracts meet the app's requirements; a representative
production feature shows maintenance/testability gains outweighing the wrapper and migration cost.

## Links

- Epic #3225; issues #3226–#3232, #3233, #3234, #3255–#3259; follow-ups #3265–#3267.
- Package extraction (separate initiative): epic #3268, stages #3269–#3271.
- ADRs: `2026-09-25-local-mvi-framework`, `2026-09-27-mvi-single-state-entry-and-vm-sweep`,
  `2026-11-18-reflex-adoption-strategy-mvi-adapter-evolution-migration`,
  `2026-11-26-mvi-state-is-observed-read-only`,
  `2026-11-27-flowmvi-compatibility-assessment`.
- `build/investigation/flowmvi-spike/FINDINGS.md` — measured #3234 findings and the no-go verdict.
- `docs/decisions/2026-11-26-the-draft-keeps-one-value-and-one-snapshot.md` — Draft invariants.
