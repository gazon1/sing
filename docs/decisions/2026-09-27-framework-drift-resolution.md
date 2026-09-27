---
title: "MVI framework drift — StateStrategy.Atomic deferred, NoCombineSideEffectRule written"
date: 2026-09-27
tags: [mvi, framework, detekt, tech-debt]
status: accepted
---

## Context

While building the `FeatureSlot` pattern, an audit of `docs/decisions/2026-09-25-local-mvi-framework.md`
against the actual code found the ADR described a framework that does not exist:

- `StateStrategy.Atomic` — the ADR documents a `ReentrantMutex` + `stateStrategy` constructor
  parameter on `MviViewModel`. Neither exists: `StateStrategy.kt` contains only `Direct`, and
  `MviViewModel` takes no `stateStrategy` argument.
- The ADR's `updateState<reified T : S>` overload does not match the real API either. The
  shipped class has a non-suspending `updateState(transform: (S) -> S)` plus a separate
  `updateStateAs<reified T>`. This is drift in the *update-state API shape*, not just a
  missing data object.

A second drift was found in the skill layer.
`singularity-todo-detekt-rules-authoring` publishes a table of "Existing Rules" that lists
`NoCombineSideEffectRule` (`no-combine-side-effect`). That rule **had existed once and been
deleted the previous day** by `2026-09-26-detekt-rules-activation-audit.md`, which removed it
as an orphan — no provider, no ServiceLoader entry, no `detekt.yml` block, no tests. The skill
was never updated. An agent following it would have concluded the codebase was still protected
and skipped writing the rule.

## Idea

1. Bring the code up to the ADR — add `StateStrategy.Atomic` and the `stateStrategy` plumbing.
2. Update the ADRs and skills to describe the framework that actually exists.
3. Do both, on the grounds that "the intent was documented, so honour it".

## Decision

**`StateStrategy.Atomic` is deferred, not implemented.** `StateStrategy.kt` keeps only
`Direct`; `MviViewModel` keeps its non-suspending `updateState` and `updateStateAs`. This
ADR is amended to describe the real API.

**`NoCombineSideEffectRule` is rewritten now.** The rule genuinely enforces a rule the project
already documents in `singularity-todo-testable-vm` ("side effects belong in collectors, not
combines") but which nothing mechanically checked. Its first run found a live production bug
on the same day it was restored — see the feature-slot ADR. The 2026-09-26 audit set the
condition for restoring it ("rewritten with tests and proper registration"); that condition is
met.

**The skill's rule table is treated as a claim to verify, not as fact.** Any rule named in a
skill must be confirmed to exist in `detekt-rules/` before work is scoped around it.

## Rationale

`Atomic` costs a `ReentrantMutex` on every state update — the ADR itself measures it at ~15x
slower than `Direct` — and no VM in the codebase has a demonstrated concurrent read-modify-write
race today. Adding an unused, opt-in locking path is speculative: it would be untested in
production paths, and the non-suspending `updateState` shape that *is* shipped is what every
existing VM and test is written against. Changing the update-state shape to match a stale ADR
would have churned 20+ VMs for no behavioural gain. The honest move is to make the document
match reality and revisit the decision when a VM actually needs it.

The detekt rule is the opposite case: the guardrail is documented in three places, a real bug
of exactly that shape was sitting in `TaskDetail.kt`, and nothing caught it. The cost of the
rule is one AST visitor plus its tests.

## Consequences

- Do not add `StateStrategy.Atomic` or a `stateStrategy` parameter until a VM demonstrates a
  concurrent read-modify-write race. When that VM appears, write a focused MR that adds the
  mutex path, migrates that VM, and measures.
- `MviViewModel.updateState(transform: (S) -> S)` is non-suspending and stays that way. Use
  `updateStateAs<T>` for the sealed-hierarchy smart cast.
- `NoCombineSideEffectRule` is active in `config/detekt/detekt.yml` and fails the build. A
  `combine` transform must be pure: no `.value =`, no `seed()`, no `Channel.send`, no
  `launchIn`. The check is AST-local, so a side effect hidden inside a callee is not reported.
- **Verify a rule exists before relying on it.** The skill table listed a rule that had never
  been written; treat every "existing rules" table as unverified until the file, the
  ServiceLoader entry, and the `detekt.yml` block are all present.
- When a new detekt rule is added, follow the three-step activation checklist: ServiceLoader
  entry, `detekt.yml` block, and a positive control run against real code. A rule missing any
  one of the three reports nothing and looks working.

## Links

- `2026-09-25-local-mvi-framework.md` — the ADR this one corrects
- `detekt-rules/src/main/kotlin/com/singularity/todo/detekt/NoCombineSideEffectRule.kt`
- `config/detekt/detekt.yml` — `no-combine-side-effect` block
- `2026-09-27-feature-slot-pattern.md`
