---
title: A test fake's default clock is the fixed one
date: 2026-10-05
status: accepted
tags: [clock, testing, testability, detekt, rules]
---

## Context

`2026-10-05-today-is-two-required-parameters.md` decided that neither parameter
of `todayAt(clock, zone)` may be defaulted, on the grounds that a default is a
way to forget what "now" means.

That decision was about production code, and it left `FakeRepositories.kt` in a
contradictory state. Twenty entity-timestamp reads went through
`Clock.System.now()`, hidden behind a `/test/fakes/` entry in
`NoDirectClockSystemRule.isAllowedPath`. The exemption was added for a reason
that turned out to be temporary — the fake reads the wall clock because it had no
clock — and it outlived the reason.

A test that was written during the suppression and then hardened pins the
behaviour it was written against:

```kotlin
fun `tag delete stamps the real clock, not epoch zero`() = runTest {
    // deletedAt > Instant.fromEpochMilliseconds(1_000)
}
```

So the defect was not only in the fakes. It was also written down, as a
requirement, in the fidelity test that exists to protect the fakes.

## Decision

**A fake takes a `Clock` and defaults it to `FakeClock()`. The rule's
`/test/fakes/` exemption is deleted rather than kept.**

The asymmetry with production is deliberate and is the whole point. In
production, a defaulted clock is a hazard: the code compiles, runs, and computes
a date that moves with the machine. In a fake, the same default points at
`1970-01-01T00:00:00Z` — the unsafe default is the one being removed, not the
one being relied on. A test that needs a specific instant names it; a test that
does not care gets a value that cannot drift.

The default is a default in the production sense, and that is acceptable here
for a reason that does not generalise: the alternative — 200-odd call sites each
passing `FakeClock()` — buys the appearance of explicitness at the cost of
repetition, and repetition is not what makes a clock read visible. Visibility
comes from the rule, which no longer skips this directory.

`NoDirectClockSystemRuleTest` now runs the rule over every file under
`/test/fakes/` and fails on any finding, so the door cannot reopen quietly. That
test runs the rule rather than grepping for the string `Clock.System`, because a
grep flags the file's own header comment the moment someone explains what they
replaced — and a guard that fails when you explain yourself gets deleted.

## Rationale

The allow-list entry and the reads were removed together because the entry was
only ever a placeholder for the reads. Leaving it behind would have been the
worst of the three outcomes: a rule that matches nothing there, indistinguishable
from a rule that is working, which is the exact shape this repository has spent
the season removing — from `@file:Suppress`, from baselines that never shrank,
from gates that print a violation and exit zero.

The fidelity test is rewritten rather than deleted. `deletedAt == stampedAt`
catches strictly more than `deletedAt > 1_000`: an unstamped entity is still
epoch zero, and an entity stamped from the wrong source is now a different value
rather than merely "large enough".

## Consequences

- `shared:jvmTest` stays at 1880 tests. The fidelity test was renamed and
  rewritten, not added, so the floor is unchanged — this is a change in what is
  asserted, not in how much is asserted.
- `FakeRepositoryFidelityTest` lost its `@file:Suppress("NoDirectClockSystem")`.
  That suppression was invisible to `check-suppression-intent.py`, which scans
  production source only; it is worth knowing that the gate does not cover
  `commonTest`.
- Six fakes gained a parameter. All of them default, so no call site changed.
- Any test that asserted "this timestamp is after the wall clock" would have
  failed loudly. One did, and it was the test above.

## Links

- `2026-10-05-today-is-two-required-parameters.md` — the production-side decision
- `2026-10-05-positive-control-registry-is-derived.md` — deriving a registry from
  its registration, the same move applied to this allow-list
- `shared/src/commonTest/kotlin/com/singularity/todo/test/fakes/FakeRepositoryFidelityTest.kt`
- `detekt-rules/src/test/kotlin/com/singularity/todo/detekt/NoDirectClockSystemRuleTest.kt`