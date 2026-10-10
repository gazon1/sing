---
status: open
status-was: proposed
title: "Retire NoDirectClockSystemRule"
---

# Context

`NoDirectClockSystemRule` bans direct `Clock.System` references in production code,
enforcing injection of `kotlin.time.Clock` instead. It was introduced to catch
implicit time dependencies that cannot be mocked in tests.

When the rule was written (2026-09), the only observed violations were
`Clock.System.now()` calls in production. The rule was not extended to
`System.currentTimeMillis()` — a separate, parallel clock usage — because doing so
would require baselining 14 additional findings.

The rule therefore covers only half of direct clock access in the codebase.

# Decision

Retire `NoDirectClockSystemRule`.

# Rationale

A rule that reads as enforcing a policy while missing half the violations is
worse than no rule. It gives a false sense of coverage and a de-facto exemption
to every `System.currentTimeMillis()` call in the tree — including in production
code paths — with no declarative record of that exemption.

The exemptions accumulated to 35 `@file:Suppress("NoDirectClockSystem")` annotations
across commonMain, jvmTest, and commonTest. At that scale, the suppression is
invisible to anyone reading a diff: it does not appear in the PR that introduced
the call, only in the PR that added the suppression — if anyone remembered to
add one.

The honest alternatives were:
1. **Extend the rule** to `System.currentTimeMillis()`. This requires baseline entries
   for all 14 additional findings. Baselining is the correct action for genuine
   pre-existing violations, but the baseline itself would be invisible in a PR,
   making it impossible to review whether the exemptions are appropriate.
2. **Retire the rule**. Keep the `Clock` interface and injection pattern as
   documentation and convention. Enforce it in code review.

Option 2 is chosen.

# Consequences

- `config/detekt/detekt.yml`: remove `no-direct-clock-system` block.
- `config/detekt/baseline-shared.xml`: remove `NoDirectClockSystem` baseline entry.
- 35 `@file:Suppress("NoDirectClockSystem")` annotations removed from source files.
- `detekt-rules/src/main/kotlin/com/singularity/todo/detekt/NoDirectClockSystemRule.kt`
  and its test file remain in the tree but are no longer wired into `detekt.yml`.
- A future lint rule that covers both `Clock.System` **and** `System.currentTimeMillis()`
  may be added if a machine-verifiable form of the convention is needed.

# Links

- Issue #160
