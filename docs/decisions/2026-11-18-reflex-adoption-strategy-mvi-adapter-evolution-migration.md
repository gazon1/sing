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
`TaskDetailScreenContract` over the real `TaskDetailCoordinator`, with an allow-listed snapshot, an
action catalogue with JSON-Schema inputs, validate-before-dispatch, availability flags, and an
explicit accept-vs-completion dispatch semantic. The verdict was GO; the reusable-contract extraction
was authorized but bounded, pending this decision.

The repository already carries a deliberate lightweight MVI: `MviViewModel<S, I, E>` (single
StateFlow, EventBus, typed `onIntent`), coordinator-plus-slots for complex screens, and an agent
skill that routes migrations through it.

## Idea

Decide how an agent-facing screen/action contract maps onto the existing MVI, choosing between an
adapter over the current MVI, gradual Reflex-style evolution, and a broader migration.

## Decision

**Gradual evolution.** Keep the existing MVI seam; introduce the canonical agent-facing
screen/action contract on top of it; implement it first as per-screen adapters over the existing
`onIntent` path (Task Detail as the reference); do not replace `MviViewModel`, add a Gradle module,
or build a CLI in this issue.

### What the POC proved, and what it did not

**Proven** (Task Detail, JVM, `:shared:jvmTest`): allow-listed snapshots; explicit action catalogue;
JSON-Schema input validation; validate-before-dispatch; availability requirements; accept-vs-
completion dispatch semantics; real coordinator path.

**POC limitations** (task-specific, not yet universal contract requirements): undo tracking is
per-contract-instance; loading state was not verifiable end to end; only Task Detail was covered;
verification was JVM-only.

**Open for the canonical contract (#3040):** universal async-operation model, cancellation, navigation
and focus context, error representation, versioning, deterministic serialization.

### Comparison of the three options

| Criterion | Adapter over current MVI | Gradual evolution | Broader migration |
|---|---|---|---|
| POC evidence | proven for Task Detail | same contract, made reusable | not yet |
| State/action semantics ownership | per-screen adapters; semantics re-derived per screen | shared contract semantics; adapters per screen | uniform model, highest cost |
| Safe allowlisting / validation | proven; `additionalProperties: false` | preserved | preserved |
| Async completion / errors / undo / navigation | task-specific, proven in POC | carried once into contract | same at migration scale |
| Testability without Compose/emulator; Android + JVM Desktop | proven on JVM via existing harness | preserved | preserved, at migration cost |
| Cost / migration risk / maintenance | lowest; additive | low-med; extraction | high; big-bang risk |

The real architectural difference between the adapter and the evolution option is where the contract
semantics live: the adapter puts them in each screen's own adapter (Task Detail already did, 413 LOC),
so generalizing re-derives them per screen; the evolution puts the semantics in the architecture and
screens implement local adapters against them. The decision takes evolution so those semantics stop
being Task-Detail-shaped.

The broader migration is rejected for now: the local-MVI ADR records the lightweight framework as the
project's intentional architecture and its agent skill routes migrations through it, but neither
decides the future agent-facing boundary; retaining MVI now is supported by the research evidence, not
by historical approval.

- The contract rules proven by the POC are fixed and not negotiable: explicit action allow-list,
  `available` flag plus requirement, snapshot allow-list, exact-schema input check
  (`additionalProperties: false`), refusal messages naming supported actions and expected input,
  and the accept-vs-completion dispatch distinction.
- Module: no dedicated module now; the public contract's candidate home is a `core/llm`-style port
  next to `ToolCallSummary`, screen-specific adapters live in the feature and depend inward on the
  contract, and the contract does not depend on feature or Compose/Android types. Reconsider only on
  real duplication or an independent consumer that the current boundary blocks.
- CLI: first-class candidate for the agent-facing workflow, shaped after the canonical contract; a
  standalone executable is deferred until the contract exists (#3041). MCP domain tools remain a
  separate mechanism and are not duplicated as screen actions.

## Rationale

The adapter option was validated at low cost by the POC (413 LOC contract, real coordinator, full
shared lane green). The local-MVI ADR and its agent skill record the current architecture as
intentional and route migrations through it, but neither pre-approves a Reflex adapter; retaining MVI
now is supported by the research evidence, not by that history. No evidence yet shows adapter-level
duplication or inconsistent semantics, which is why the broader migration is not justified yet. Once
the semantics become cross-screen, gradual evolution is the honest shape: shared contract semantics
with per-screen adapters, no speculative framework.

## Consequences

- Migration proceeds by vertical slices with Task Detail as the reference; rollback means disabling
  the introspection adapter, not reverting the MVI.
- Production surface, MCP transport choice, and contract versioning remain open items owned by #3040
  and #3041.

## Links

- Epic #3038; decision: #3046; contract: #3040; driver/CLI: #3041; POC: #3035; PR: #3036; hardening:
  #3039.
- ADR `2026-11-18-headless-screen-introspection-poc`; ADR `2026-09-25-local-mvi-framework`.
