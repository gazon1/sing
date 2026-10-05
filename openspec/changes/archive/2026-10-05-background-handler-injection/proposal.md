# background-handler-injection

Issues: #125, #126, #127, #128, #129, #140 · Follows: `apptracer-integration`, `failure-visibility`
Spec delta: `crash-reporting` (REQ-5, REQ-6)

## What

Retire the process-wide `BackgroundFailureHandler` by requiring every background scope to be
injected, and harden the three things that made the global survivable rather than correct.

The handler exists because `createBackgroundScope()` is a top-level `expect fun` with no Koin
handle that runs *inside* graph construction, so it cannot take a dependency. The shipped
solution was a swappable target installed at process start. That works, and the ADR says
plainly that it is interim. This change is the other end of that sentence.

## Why

Three things are currently true that should not be:

1. **A global is load-bearing.** `NoStaticProfileAwareCurrentUserRule` exists to ban exactly this
   shape; the handler is the one admitted exception, and its KDoc argues the case honestly. An
   argument in a KDoc is not a guarantee that the next change respects it.
2. **Its test safety depends on `forkEvery = 1`.** The build file states that setting as an
   incidental way to isolate Koin globals, not as a precondition for a global-mutable-state
   test. Raise it for build speed and the isolation is gone, silently.
3. **The guard that protects the shape is a regex.** `CrashReportingWiringTest` decides whether a
   ViewModel can fail by matching source text. This repository has ADR
   `2026-10-05-no-direct-dispatchers-rule-was-a-no-op` for a gate that could not fail at all, and
   `2026-10-05-positive-tests-for-every-detekt-rule` for the habit that followed. A text match
   has both failure modes: a helper called from a ViewModel is not in that ViewModel's file, and
   a composable that catches nothing but delegates to something that does is invisible.

The migration is the work; the three items above are what would otherwise make it unsafe to
start.

## Ordering

Do the hardening before the migration, not after. A refactor that removes a global is exactly the
change under which a test-only dependency on a Gradle flag stops mattering — and also exactly the
change during which a missed site becomes invisible.

## What does not change

The behaviour being guaranteed. A failed background launch is still reported, under the same
`background.coroutine_failed` key, and the process still survives it. Only the mechanism for
deciding *which* handler a scope carries moves, and it moves from a process-wide default to the
owner's choice.
