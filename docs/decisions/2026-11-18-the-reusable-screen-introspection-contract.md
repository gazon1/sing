---
title: "The reusable screen-introspection contract"
date: 2026-11-18
tags: [introspection, agent, architecture]
status: accepted
---

## Context

Issue #3040 defines the canonical, reusable agent-facing screen/action contract, sequenced
after the Task Detail POC (#3035, PR #3036, hardened by #3039) and after ADR
`2026-11-18-reflex-adoption-strategy-mvi-adapter-evolution-migration` (#3046), which chose
**gradual evolution**: shared contract semantics with per-screen adapters, no new module, no CLI,
no MVI replacement, Task Detail as the reference adapter.

The POC proved the contract *shape*; #3040 is where that shape becomes a reusable contract.

## Idea

Introduce the reusable contract as the `ScreenIntrospection` family in `core/llm` — the candidate
home named by #3046, next to `ToolCallSummary` — together with the feature-agnostic `ScreenState`
and `ScreenAction` types and the `SCREEN_INTROSPECTION_CONTRACT_VERSION` constant. Task Detail
becomes the first per-screen adapter, `TaskDetailScreenIntrospection`, in
`feature/tasks/presentation/introspection`, which depends inward on the contract and binds the
actions, the input schemas, the availability rules and the state mapping to the real coordinator
through the existing `onIntent` path. No production surface, module or dependency is added beyond
the contract itself; the dispatcher already refuses input that does not fit the published schema, so
the mapping into an intent now runs only after validation succeeds.

## Decision

1. The contract owns the semantics every adapter shares: stable screen identity, allow-listed
   snapshot, action catalogue with JSON-Schema inputs, validate-before-dispatch, availability
   requirements, and the accept-vs-completion dispatch distinction.
2. The rules the POC proved are fixed and not negotiable: explicit action allow-list, `available`
   flag plus requirement, snapshot allow-list, exact-schema input check (`additionalProperties:
   false`), refusal messages naming supported actions and expected input.
3. The contract does not depend on feature or Compose/Android types; the screen-specific half
   (actions, schemas, availability, state mapping, intent mapping) lives in the feature adapter.
4. The contract is versioned (`SCREEN_INTROSPECTION_CONTRACT_VERSION = 1`) and its capability
   surface is discovered through the action catalogue published by `describe`.

## Rationale

The adapter-over-MVI option and the evolution option converged on the same semantics; only their
ownership differed. #3046 chose evolution because the semantics were already single-sourced in the
POC adapter and re-deriving them per screen would copy-paste the same refusal messages, the same
schema check and the same dispatch distinction into every future adapter.

## Consequences

- Task Detail moved from the POC adapter in `commonTest` to a production adapter implementing the
  reusable contract; the contract and the adapter keep the POC's behaviour (13 introspection tests,
  migrated verbatim in meaning, all green on `:shared:jvmTest`).
- The minimum stable abstraction is deliberately small: a screen id, a state, an action, and the
  dispatch pipeline over them. Adapters add their own action catalogues and state mappings.
- Deterministic serialization and safe-field allowlisting are part of the contract's tests.
- Compatibility: version 1 publishes the meanings above; the mechanism for moving beyond it is the
  versioning constant, and the open items for #3040 (universal async model, error channel, navigation
  context, focus semantics) are tracked against it rather than worked around here.

## What is deliberately not generalized

The contract intentionally does not yet own:

- the error channel — operation failures still ride the screen's error event, and the
  screen-specific way of reporting them stays in the adapter;
- the async-operation model, cancellation and navigation context;
- the shape of a screen's subject beyond the allow-listed snapshot a screen chooses to publish.

## Links

- ADR `2026-11-18-headless-screen-introspection-poc`; ADR
  `2026-11-18-reflex-adoption-strategy-mvi-adapter-evolution-migration`; decision #3046; epic #3038.
- Issue: https://github.com/gazon1/sing/issues/3040
- Code: `shared/src/commonMain/kotlin/com/singularity/todo/core/llm/ScreenIntrospection.kt`;
  `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/introspection/TaskDetailScreenIntrospection.kt`
