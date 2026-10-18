---
id: 2026-10-18-greeting-init-no-runblocking
title: Compute greeting in init without runBlocking
description: How AgendaViewModel initializes the time-of-day greeting without using runBlocking or LaunchedEffect in tests
status: accepted
deciders:
  - agent
date: 2026-10-18
---

## Context

`AgendaViewModel.computeGreeting()` is a suspend function that reads two flow values
(`greetingSettings.morningEndHour.first()` and `greetingSettings.afternoonEndHour.first()`)
and the current clock hour to produce a string like "Good morning".

The greeting was originally computed lazily on first `todayFlow` emission inside
a `scope.launch { ... .collect { if (greeting == null) greeting = computeGreeting() } }`
block. A `LaunchedEffect` in `AgendaRouteMapping.kt` was supposed to trigger the
first emission.

In JVM unit tests (without a Compose runtime) `collectAsStateWithLifecycle()` /
`LaunchedEffect` does not fire, so the `collect` never executes and `greeting` stays null.

A naive fix would use `runBlocking` in `init`, but `NoRunBlockingRule` forbids it.

## Decision

1. Keep `computeGreeting()` as a private suspend function.
2. In `init`, immediately `scope.launch` a coroutine that calls `computeGreeting()`
   and stores the result in a private field, which the `collect` block references.
3. The `collect` block — which already runs on `scope` — uses `greeting` directly
   (already captured via closure) and no longer needs its own null-check.
4. The `LaunchedEffect` in `AgendaRouteMapping.kt` is unchanged; it only triggers
   a re-composition, not the greeting computation itself.

The launched coroutine starts before the `collect` block's first emission in
all non-test environments. In tests, `runCurrent()` must be called enough times
to allow the init-launched coroutine to complete before the assertion.

## Consequences

- `computeGreeting()` still runs asynchronously — no `runBlocking` in init.
- Tests must call `runCurrent()` at least 4 times (once for the init scope.launch
  to start, once for each `first()` suspension in `computeGreeting()`, and once
  for the result to be visible in state) before asserting.
- This pattern (init-launched async init) is already used for other lazy fields
  in the codebase (e.g. `pendingDelete` countdown).

## Alternatives considered

- **runBlocking in init**: forbidden by `NoRunBlockingRule`.
- **Eager `first()` in init via GlobalScope**: too broad; `reportingScope` is the
  intended scope.
- **Move greeting into `AgendaRouteMapping.kt`**: would duplicate the `first()` calls
  in the Composable, making settings reads untestable in VM tests.
