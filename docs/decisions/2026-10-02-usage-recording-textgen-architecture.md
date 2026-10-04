---
date: 2026-10-02
title: UsageRecordingTextGen — Ownership and DI Shape
status: accepted
status-was: proposed  # non-vocabulary value, normalized 2026-10-05
deciders: Singularity Developer
---

# UsageRecordingTextGen — Ownership and DI Shape

## Context

`RoomUsageRecorder.record()` in `core/observability` has no production call site.
`UsageRecordingTextGen` was designed as a decorator on `TextGenPort` to record every
AI call without coupling `core.observability` to `feature.ai`.

The decorator requires access to the information that `TextGenPort` abstracts over:
model used, token counts, duration, and cost. The current `TextGenPort` interface
exposes only `generate(prompt): Result<String>` — it has no hooks for instrumentation.

To record usage, the decorator needs one of:
- Access to the underlying Koog `PromptExecutor` to intercept raw request/response metadata
- A richer `TextGenPort` interface with instrumentation callbacks
- A separate `AiUsagePort` that the `TextGenPort` implementation calls back into

All three paths require a non-trivial architectural decision before any implementation.

## Decision

**Do not implement `UsageRecordingTextGen` as a `core.observability` component.**

`core.observability` may not depend on `feature.ai` — that would be a reversed
dependency (core cannot import feature). The decorator cannot live in `core` and
receive a `feature.ai` port as a constructor parameter, because that would make
`core` depend on `feature`.

**Instead: implement `UsageRecordingTextGen` as a component of `feature.ai`.**

The decorator lives in `feature/ai/chat/` alongside `ChatViewModel` and receives
`RoomUsageRecorder` as a constructor parameter (this direction is allowed: feature
may depend on core). The Koin binding in `feature/ai/AiDiModule` wires the decorator
between the raw `KoogPromptExecutorPort` and the `TextGenPort` alias.

```kotlin
// feature/ai/AiDiModule.kt
single<TextGenPort> { UsageRecordingTextGen(get(), get<RoomUsageRecorder>()) }
```

The `RoomUsageRecorder` already exists in `core.observability` and is already bound
as a singleton — no new DI contract is needed in `core`.

### Why not a richer `TextGenPort` interface?

Adding instrumentation callbacks to `TextGenPort` would require changing the interface
in `feature.ai` and updating all actual implementations (Koog, Fake). That is
acceptable as a follow-up, but it is a separate concern from the recording decorator.
The decorator can work with the current interface if it wraps the `KoogPromptExecutorPort`
(which exposes the raw executor) rather than the `TextGenPort` abstraction.

### Why not `AiUsagePort`?

An `AiUsagePort` in `core.observability` that `TextGenPort` calls back into would
create an architectural coupling: every `TextGenPort` implementation would need to
know about `AiUsagePort`. This couples `feature.ai` to `core.observability` in the
opposite direction (feature must call core's port), which is allowed but creates
more boilerplate than the decorator approach.

## Consequences

- `UsageRecordingTextGen` is implemented in `feature/ai/chat/`, not `core/observability`.
- `RoomUsageRecorder` becomes a dependency of `feature.ai` (allowed: feature → core).
- The `TextGenPort` alias in `AiDiModule` is re-bound to the decorator.
- No `AiUsagePort` is introduced.
- If `TextGenPort` later gains instrumentation hooks, `UsageRecordingTextGen` can be
  refactored to use them instead of reaching into `KoogPromptExecutorPort`.

## Alternatives Considered

1. **Decorator in `core.observability` receiving `TextGenPort`** — blocked by
   reversed dependency: `core` cannot import `feature`.
2. **`AiUsagePort` called by every `TextGenPort` implementation** — acceptable but
   more boilerplate; deferred to a future ADR if the decorator approach proves
   insufficient.
3. **Richer `TextGenPort` with instrumentation callbacks** — separate concern; can
   be done as a follow-up without changing the decorator decision.
