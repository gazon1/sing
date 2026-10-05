---
title: "Detekt rules to make the slow path unreachable, not just documented"
date: 2026-10-05
tags: [quality-tools, detekt, performance, testing]
status: proposed
---

## Context

This session's three avoidable debugging detours shared a shape — a failure
mode that is invisible until something forces it into the open. Two are now
closed by code ([2026-10-05-blocking-resolved-per-list-not-per-task]) and two
remain **documented but enforceable**:

1. **Per-task blocking in a loop.** `TaskComputed.isBlocked(task, allTasks)`
   rebuilds its lookup table per call. Called in a `filter`/`map` over tasks it
   is quadratic — measured 8.4s at n=8000 versus 65ms for the batch form. The
   KDoc now states the cost, but nothing stops the next caller.

2. **Silently mis-seeded fakes.** A `FakeTaskRepository` row with the wrong
   `userId` is invisible to reads, and the failure surfaces on an unrelated
   assertion. Documented on `seed`, not enforced — because read-isolation tests
   seed another user's rows on purpose, and a fake cannot tell a mistake from
   the thing it exists to test.

3. **Polymorphic serialization in the wrong context.** Reifying to a concrete
   subtype of a sealed hierarchy writes JSON the base type cannot decode. One
   test now pins the round trip, but nothing prevents a new asymmetric call site.

## Idea

The repo already has a custom detekt module (`:detekt-rules`, 19 registered
providers) and a `PassThroughUseCase` rule precedent — a domain invariant
expressed as a lint failure rather than a review comment. That is the right
tool for all three.

## Decision

**Proposed, not implemented.** Recording the shape so the next person does not
have to rediscover that these three are one class of problem.

Candidate rules, in value order:

1. **`NoPerTaskBlockingInLoop`** — report `TaskComputed.isBlocked(...)` inside a
   `filter`/`map`/`forEach`/`count` lambda whose receiver is a task collection.
   Highest value: it converts the single worst measured defect in this session
   from "documented" to "impossible", and the fix is always `blockedIds`.
2. **`PolymorphicSealedSerializationContext`** — report `encodeToString(x)` where
   `x` is statically a subtype of a sealed class annotated `@Serializable`, when
   production reads that column through the base serializer. Narrower and more
   false-positive-prone; needs the sealed hierarchies enumerated to be useful.
3. **`FakeSeedUserMismatch`** — a fake *may not* enforce this (see
   `2026-10-05-fakes-hide-failure-modes-document-dont-enforce`), so the honest
   version is a **warning-severity** rule on `seed(...)` arguments whose userId
   differs from `TestUsers.DEFAULT`, naming the deliberate alternative. Worth
   weighing only if rule (1) proves the pattern.

## Rationale

The value of a lint rule over a comment is that it moves the cost from *discovery*
to *prevention*. All three of these cost real session time here, and each would
have been caught in seconds by a rule that exists.

Rule (3) is deliberately ranked last and scoped narrowly, because it needs to
know which serializer production uses for each persisted column — knowledge that
belongs in the rule's configuration, not in a general heuristic. A rule that
guesses wrong is worse than no rule, because it teaches agents to suppress it.

## Consequences

- Nothing changes in the tree until a rule is written. Both defects remain
  documented at their call sites, which is where a reader arrives anyway.
- Rule (1) is the one to write first. It is small, its fix is unambiguous, and
  the measured numbers to justify it are in the perf ADR.
- `:detekt-rules` is itself subject to detekt, so a new rule must be clean code
  — the module's own gate covers that.

## Links

- `2026-10-05-blocking-resolved-per-list-not-per-task` — the measurements
- `2026-10-05-fakes-hide-failure-modes-document-dont-enforce` — why rule 3 is not a throw
- `.agents/skills/singularity-todo-detekt-rules-authoring` — how to register one
- `detekt-rules/` — the module and the `PassThroughUseCase` precedent