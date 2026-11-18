---
title: "Reflex adoption strategy: MVI adapter, evolution, or migration"
date: 2026-11-18
status: accepted
tags: [introspection, agent, architecture]
---

## Context

Epic #3038 adopts a Reflex-style headless introspection surface across Singularity Todo and
sequences the work: close and assess the Task Detail POC (#3035, PR #3036, hardening #3039), then
decide the architecture (#3046), then define the canonical contract (#3040), then build the
inspector/driver/CLI (#3041).

The Task Detail POC proved the contract shape end to end without touching production code:
`TaskDetailScreenContract` over the real `TaskDetailCoordinator`, with an allow-listed snapshot,
an action catalogue with JSON-Schema inputs, validate-before-dispatch, availability flags, and an
explicit accept-vs-completion dispatch semantic. The verdict was GO; the reusable-contract
extraction was authorized but bounded, pending this decision.

The repository already carries a deliberate lightweight MVI: `MviViewModel<S, I, E>` (single
StateFlow, EventBus, typed `onIntent`), coordinator-plus-slots for complex screens, and an agent
skill that records the local MVI contract as intentional rather than a candidate for replacement.

## Idea

Decide how an agent-facing screen/action contract maps onto the existing MVI, choosing between an
adapter over the current MVI, gradual Reflex-style evolution, and a broader migration.

## Decision

**Gradual evolution.** Keep the existing MVI seam; introduce the canonical agent-facing
screen/action contract on top of it; implement it first as per-screen adapters over the existing
`onIntent` path (Task Detail as the reference); do not replace `MviViewModel`, add a Gradle module,
or build a CLI in this issue.

The three options against the epic's decision criteria:

| Criterion | Adapter over current MVI | Gradual evolution | Broader migration |
|---|---|---|---|
| Task Detail POC evidence | proven (413 LOC contract, real coordinator, shared lane green) | same contract, made reusable | not yet |
| Agent ergonomics, state/action semantics | per-screen adapters; semantics per screen | shared contract semantics; adapters per screen | uniform model, highest cost |
| Safe allowlisting / validate-before-dispatch | proven; `additionalProperties: false` | preserved | preserved |
| Async completion / errors / cancellation / navigation / undo | task-specific, proven in POC | carried once into the contract | same at migration scale |
| Testability without Compose/emulator; Android + JVM Desktop | proven on JVM via existing harness | preserved | preserved, at migration cost |
| Implementation cost / migration risk / maintenance | lowest; additive | low-med; extraction step | high; big-bang risk |

The broader migration is rejected for now: the local-MVI ADR and its agent skill pre-approve
adapter-over-MVI rather than MVI replacement, and the evidence does not yet show adapter-level
duplication or inconsistent semantics.

- The contract rules proven by the POC are fixed and not negotiable: explicit action allow-list,
  `available` flag plus requirement, snapshot allow-list, exact-schema input check
  (`additionalProperties: false`), refusal messages naming supported actions and expected input,
  and the accept-vs-completion dispatch distinction.
- Module: no dedicated module now; if the contract graduates to production, its home is a
  `core/llm`-style port inside an existing module, with feature/core inward dependency direction.
- CLI: first-class candidate, shaped after the canonical contract; standalone executable deferred
  until the contract exists (#3041).

## Rationale

The adapter option was validated at low cost by the POC (413 LOC contract, real coordinator, full
shared lane green). The local-MVI ADR and its agent skill pre-approve adapter-over-MVI rather than
MVI replacement, so a broader migration is not justified by current evidence. A pure adapter is kept
small only while the contract is Task-Detail-specific; once the semantics become cross-screen
(allow-list, schema validation, availability), gradual evolution is the honest shape: shared contract
semantics with per-screen adapters, no speculative framework.

## Consequences

- Migration proceeds by vertical slices with Task Detail as the reference; rollback boundary is the
  existing MVI path; no big-bang rewrite.
- Production surface, MCP transport choice, and contract versioning remain open items owned by
  #3040 and #3041.

## Links

- Epic #3038; decision: #3046; contract: #3040; driver/CLI: #3041; POC: #3035; PR: #3036; hardening:
  #3039.
- ADR `2026-11-18-headless-screen-introspection-poc`; ADR `2026-09-25-local-mvi-framework`.
