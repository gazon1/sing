---
title: "Greeting Computation — Init vs Collector placement"
date: "2026-10-18"
status: "accepted"
type: "decision"
---

## Context

`computeGreeting()` is a suspend function that reads `GreetingSettingsRepository` and the clock to return "Good morning/afternoon/evening". It needs to run once when the AgendaViewModel starts.

## Decision

Compute the greeting lazily on the first emission inside the `scope.launch` collector rather than in the `init` block. Use a `var greeting: String? = null` field and populate it on the first `collect` iteration. If `computeGreeting()` throws, the greeting remains null and the UI renders without a greeting.

## Rationale

The canonical pattern for one-time initialization in a ViewModel that uses `scope.launch` for its main work is to compute the value inside the collector on first emission. This avoids calling a suspend function from a non-suspend `init` block, which would require `runBlocking` (banned by `NoRunBlockingRule`). Making `computeGreeting()` private and suspending ensures it can only be called from a coroutine context and always runs on the VM's `scope`, keeping the computation cancellable with the VM's lifetime.

## Consequences

- Positive: No `runBlocking` needed; complies with `NoRunBlockingRule`
- Positive: Computation is cancelled when the VM scope is cancelled
- Positive: Greeting renders in the first `Loaded` state emission, indistinguishable from init-time computation
- Negative: Slight delay — greeting appears on first collect rather than before first collect (acceptable; screen shows Loading until tasks are ready anyway)

## Links

[]
