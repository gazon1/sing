---
title: "Fakes hide failure modes; name them in KDoc instead of enforcing them"
date: 2026-10-05
tags: [testing, fakes, testability]
status: accepted
---

## Context

Three separate debugging detours in one implementation session came from the
same shape, and this repo has already named the shape: **a measurement that
cannot be distinguished from its own failure**.

1. **Seeding a task with the wrong `userId`.** `FakeTaskRepository` scopes every
   read to the current user. A task seeded with any other id is *invisible* —
   `get` returns null, `observeByFilter` returns empty — and the test fails on an
   assertion about behaviour. The failure names the assertion, never the seed.
   Cost: reading the fake's internals to discover a rule that is not written
   anywhere.

2. **The wrong serialization context.** `RecurrenceSpec` is polymorphic, so
   `encodeToString(spec)` on a concrete value writes JSON that
   `decodeFromString<RecurrenceSpec>` **cannot read** — no discriminator. A probe
   test that reified to the concrete type produced a blob that failed to decode
   against production's own `RecurrenceSpec.serializer()`. Both halves are
   correct; only one pair is round-trippable.

3. **A predicate that is right but slow.** See
   `2026-10-05-blocking-resolved-per-list-not-per-task` — `isBlocked` returned the
   correct answer on every call, so nothing reported a problem until a measured
   1.1s stall at n=4000.

## Idea

For (1), the tempting fix is to make the fake throw on a mismatched `userId`.
That would break every read-isolation test, which seeds another user's rows *on
purpose* — the fake cannot distinguish a mistake from the thing it exists to
test. Enforcement would delete a legitimate use to catch a common one.

For (2) and (3) the fixes are real code changes, recorded in their own ADRs.

## Decision

**Documentation over enforcement for the seeding trap.** `FakeTaskRepository.seed`
now carries a KDoc naming the invisible-data failure, the default
([TestUsers.DEFAULT]), and the deliberate alternative
(`explicitCurrentUser = …`). No behavioural change.

The general rule this follows: **a fake must not reject input it cannot
distinguish from legitimate input.** It should make the rule discoverable at the
call site, and the test suite should carry examples.

## Rationale

A throw in `seed()` is a false positive on every isolation test — five in this
repo alone — to catch a mistake that is, in the author's judgement, worth
documenting rather than banning. That trade would make the fake *less* usable to
buy a guard rail that a KDoc already provides at the exact moment someone
reaches for `seed`.

The deeper point is about what a fake is for. A fake's job is to be a
production-shaped substitute. It already reproduces production's user scoping —
that fidelity is exactly why the trap exists, and exactly what makes isolation
tests possible. Removing the fidelity to remove the trap would be removing the
value.

For the other two, the lesson generalises: **the dangerous fakes are the ones
that fail quietly**. A wrong discriminator produces a decode error far from its
cause; a slow predicate produces correct results slowly. Both are invisible until
something forces them into the open — a probe test, or a measurement. Where a
probe is what found it, the probe is what should exist as a permanent test.

## Consequences

- `FakeTaskRepository.seed` KDoc records the trap. A `seedFor(user, …)` helper
  remains a reasonable addition, but was not added — there are no current callers
  that would use it, and YAGNI applies to test helpers too.
- The `RecurrenceSpec` round-trip test added in this session is the permanent
  version of probe (2): it asserts a rule written by one serializer decodes
  through another.
- The blocking cost is recorded on `isBlocked` itself, so the next caller meets it
  before running it rather than after.
- **Nothing prevents a future `isBlocked`-in-a-loop.** A detekt rule would, and is
  still unwritten — the same gap is named in the perf ADR.

## Links

- `test/fakes/FakeRepositories.kt` — the `seed` KDoc
- `test/fakes/TestUsers.kt` — `DEFAULT`
- `commonTest/.../CompleteRecurringTaskUseCaseTest.kt` — the round-trip test
- `2026-10-05-blocking-resolved-per-list-not-per-task` — case (3)
- `2026-10-05-recurrence-end-date-in-spec-blob` — where the polymorphism lives