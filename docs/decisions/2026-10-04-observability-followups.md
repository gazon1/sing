---
title: "Observability follow-ups: what the AppTracer work left open"
date: 2026-10-04
status: accepted
tags: [observability, testing, tooling, analytics]
---

## Context

The AppTracer integration (`2026-10-04-apptracer-integration`) was implemented with a
deliberately narrow surface: the central error path, the background failure sites, and four
co-class defects found in the same subsystems. It deliberately did not audit the rest of the
codebase. This record lists what that left open, separates the two defects that were worth
fixing immediately from the structural limits that are not, and names the traps a future agent
is most likely to fall into while working in this area.

Everything here was observed while building the integration, not inferred.

## Fixed here

### 1. The daily analytics throttle never persisted

`logEventOncePerDay` read the last-logged day, logged the event, then called `updateData` with
a block that built a mutated map of the preferences, cast it back, wrote the key into that map
— and then **returned the original `prefs` unchanged**. Nothing was ever written. The
function's own comments conceded it ("we can't mutate in place, so we record the intent and
rely on callers to persist"); no caller persisted anything. The cast to `Map` was also always
null, so the mutated map was never even built.

The net effect: the throttle was a no-op, and the function logged on **every** call. Its name
and its KDoc both promised deduplication.

It has zero production call sites and the bound implementation is `NoopAnalytics`, so nothing
in the shipped product was affected today. That is exactly why it is a trap: it is correct-looking,
documented, and unit-testable, and the first person to wire a real analytics SDK would inherit
an event-spam bug that only appears once the SDK starts sending.

Fixed by doing the read-compare-write inside a single `updateData` transform, which DataStore
serialises against other writers. The trade is explicit in the KDoc: `updateData` may retry and
its block must be pure, so a duplicate log is possible in principle — deliberately chosen over
the alternative of losing events, because analytics is best-effort. The function now returns
whether it logged, so a caller can assert on it.

**Lesson worth keeping:** the previous signature returned `Unit`, which made the bug
*untestable from the outside* — "did it log?" was unobservable. Returning the decision turns
the next change to this function into something a test can pin.

### 2. The test-tag coverage gate could not see an aliased `@Tag`

`TestTagCoverageTest` matches `@Tag(` or `@org.junit.jupiter.api.Tag(`. A file that imports
the annotation under an alias and writes `@JUnitTag("slow")` was therefore reported as
untagged.

**It was a false positive, and the first response to it was wrong.** `@JUnitTag("slow")` *is*
`@Tag("slow")` at runtime, so the class was correctly tagged and correctly included by
`-Ptest.tags=fast,slow` — nothing was being silently skipped. The obvious "fix" is to change
the file to plain `@Tag`, and that is actively harmful: `SavedAgendaSelectorConfiguratorFlowTest`
also imports the **domain** entity `com.singularity.todo.feature.tags.Tag`, so the alias is a
name-collision workaround, and removing it creates a genuine import ambiguity.

Fixed on the correct side — the gate now reads the file's own imports and treats any local
name bound to `org.junit.jupiter.api.Tag` as the annotation, so the check is right regardless
of how the file spells it. The upstream file is untouched.

**Lesson worth keeping:** when a check fails, establish whether the check is wrong before
changing the thing it checked. A gate that reports a false positive pushes work onto the wrong
file, and "fixing" it there can break a real constraint the alias existed to satisfy. This is
the same failure shape as `2026-10-04-measurement-integrity`: a boolean where there was a
magnitude.

## Recorded, not fixed

### 3. `AppError` codes are all generic — 0 of 31 construction sites pass one

Every subtype defaults to a stable code (`error.not_found`, `error.network`, …), so grouping
works and is stable, but every `NotFound` in the app lands in one group regardless of domain.
Trivial for six sites, coarse for thirty-one.

Making them domain-specific (`task.not_found`, `project.not_found`, `tag.not_found`) is
per-site domain judgement, not a mechanical change, and it needs someone who can tell a
deliberate grouping from an accidental one. Recorded as a requirement in
`openspec/changes/apptracer-integration/specs/crash-reporting/spec.md` (REQ-2 covers the
grouping contract; the code vocabulary is deliberately unspecified there).

### 4. ~139 silent `runCatching` sites in `commonMain`

Data-layer failures still return `Result.failure` with a raw `SQLiteException` or
`IllegalArgumentException` and reach no reporter. They are not crashes and they are not
swallowed — they surface as UI messages — so nothing is broken. They are simply invisible to
whoever maintains this.

Instrumenting them is a per-site review of *which* failures are worth reporting and under
which key, and doing it blind would produce a flood of low-value groups that makes the
dashboard harder to read, not easier. Left for that review.

### 5. The unwired-surface audit does not cover top-level functions

`find-unwired-surfaces.py` checks classes and Composable `fun`s whose names end in a UI
suffix. It never looks at an ordinary top-level function, so `core.log.debugInfo` had zero
call sites for its entire life and the audit could not have said so — it was not a naming
mismatch, that whole category is out of scope.

Broadening it is not obviously right: top-level pure functions are called from everywhere and
the signal-to-noise would be poor. The sharper fix is a check for the specific pattern that
matters here — a top-level function documented as "call this at startup" that nothing calls.
Recorded rather than built, because a noisy gate is worse than a missing one.

### 6. `flushLogs()` is a module-level mutable global, and that is deliberate

`LogBootstrap.kt` holds the active `FileLogWriter` in a private top-level `var` so
`flushLogs()` can drain it from the uncaught-exception handler.

This sits uncomfortably next to `NoStaticProfileAwareCurrentUserRule`, which exists to ban
exactly this shape. The distinction, on the record so the next reviewer does not have to
re-derive it: this is not a service locator. It is bootstrap state — written once by
`initLogging`, read by one function, and never consulted for a dependency. The reporter
deliberately has no such global, because a reporter *is* a dependency and must be injectable.

Cost accepted: a test that calls `initLogging` twice silently overwrites the handle. No test
does, and the write-once-at-bootstrap contract is stated in the KDoc.

### 7. The crash-path flush can block the dying main thread

`flushLogs()` → `beginShutdown()` uses `runBlocking` with a 2-second timeout. On a crash the
handler runs on whichever thread threw — frequently the main thread — so the worst case is a
2-second stall while the process is already dying.

The queue is drained by a background worker, so the join normally completes immediately and
the timeout is a backstop for a stalled disk. Left as is: a 2-second delay on a process that
is about to exit anyway is cheaper than the alternative of losing the last few log lines, which
are the ones that explain the crash.

## Consequences

- Two real defects fixed, each with tests that fail against the old code.
- One of them existed only as a landmine; the other was a wrong check.
- Four structural limits are now written down, so the next agent reads them here instead of
  re-deriving them from a confusing failure — which is what happened with the tag gate.
- No change to production behaviour of the app, the reporter, or the analytics port beyond the
  throttle actually throttling.

## Links

- `core/analytics/Analytics.kt` — the fixed throttle
- `core/analytics/LogEventOncePerDayTest.kt` — its tests
- `arch/TestTagCoverageTest.kt` — the alias-aware tag scan
- `2026-10-04-apptracer-integration.md` — the integration this follows
- `2026-10-04-measurement-integrity.md` — a boolean where there was a magnitude
- `openspec/changes/apptracer-integration/specs/crash-reporting/spec.md` — REQ-2, REQ-6
