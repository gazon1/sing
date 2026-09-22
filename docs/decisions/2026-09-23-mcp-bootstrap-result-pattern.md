---
title: "ProfileBootstrapper returns an immutable result carrier — eliminates MCP race"
date: 2026-09-23
tags: [mcp, profile, concurrency, bootstrap]
status: accepted
---

## Context

The MCP server (`Main.kt`) calls `bootstrapper.run(...)` and then, when activating "AI Agent", performs a **separate** `repository.observeAll().first()` call to look up the agent's `ProfileId`:

```kotlin
// Main.kt (before)
bootstrapper.run(seedExtras, activateName)
val agentId = repository.observeAll().first()  // ← separate call after bootstrap
    .first { it.name == "AI Agent" }.id.value
```

Between `bootstrapper.run()` returning and this `.first()` call, a concurrent coroutine could theoretically observe an inconsistent state. While in practice the Flow is conflated, this is a **potential race** that violates the principle of atomic bootstrap + activate.

## Idea

Return an immutable `ProfileBootstrapResult` from `bootstrapper.run()` that contains:
- `created: Set<String>` — names of profiles created during this bootstrap.
- `activated: ProfileId?` — the id of the activated profile, if any.

The MCP server reads `result.activated` directly, eliminating the separate `.first()` call entirely.

## Decision

Replace `ProfileBootstrapper.run()`'s `Unit` return with `ProfileBootstrapResult`:

```kotlin
data class ProfileBootstrapResult(
    val created: Set<String>,
    val activated: ProfileId?,
)

suspend fun run(seedExtras, activateName): ProfileBootstrapResult {
    val alreadyExisted = repository.observeAll().first().associateBy { it.name }
    repository.ensureDefaults(extraProfiles = seedTuples)
    val profiles = repository.observeAll().first().associateBy { it.name }
    val activated = if (activateName != null) {
        profiles[activateName]?.also { repository.switchTo(it.id) }
    } else null
    return ProfileBootstrapResult(
        created = profiles.keys - alreadyExisted.keys,
        activated = activated?.id,
    )
}
```

`Main.kt` now reads `result.activated` directly:

```kotlin
val result = bootstrapper.run(seedExtras, activateName)
if (result.activated != null) {
    val agentId = result.activated.value
    // retro-migrate using agentId…
}
```

## Rationale

- **Atomicity**: seed + lookup + activate all happen inside `run()`, and the activated id is captured in the return value. No separate Flow subscription needed.
- **No race**: the MCP server acts on `result.activated` immediately, before any other coroutine can observe the profile state.
- **Observability**: `created` tells the caller which profiles were newly seeded, useful for logging and testing.
- **The Flow calls inside `run()` are necessary**: we must call `observeAll().first()` twice (before and after `ensureDefaults`) to compute the `created` set. This is inherent to the idempotent seed logic.

## Consequences

- `ProfileBootstrapper.run()` now returns a value; all 3 call sites (`Main.kt`, any Android/Desktop bootstrappers) must handle the result or ignore it.
- The `created` field is not currently consumed by any caller — it is there for future observability / logging use cases.
