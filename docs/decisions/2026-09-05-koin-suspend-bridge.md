---
title: "Bridge suspend code into Koin factories via koinBridge { ... }"
date: 2026-09-05
tags: [koin, di, coroutines]
---

## Context

Koin's factory DSL is synchronous. Several bindings needed suspend code:
- `createKoogPromptExecutor(secureStorage, settings)` reads Flow-backed settings.
- `AiApiKeyMigration.run(dataStore, secureStorage)` runs on first DataStore access.

We initially used `runBlocking { ... }` directly in three places (two in `AiToolsModule` actuals, one in `PlatformModule.android.kt`).

## Idea

- Keep raw `runBlocking` and grep for it during code review.
- Wrap `runBlocking` in a named helper to make the bridge greppable and replaceable.

## Decision

Introduce `koinBridge { ... }` in `core/di/KoinBridge.kt`:

```kotlin
internal inline fun <T> koinBridge(crossinline block: suspend () -> T): T =
    runBlocking { block() }
```

Replace every `runBlocking { ... }` inside `module { ... }` blocks with `koinBridge { ... }`.

## Rationale

`git grep runBlocking` should now return only `KoinBridge.kt`. This makes the bridge a single concept:

- Greppable.
- Documented at the helper.
- Trivially replaceable when Koin coroutine-aware factories land (one file changes).

The helper is `internal inline` — zero overhead, zero allocations, same `runBlocking` semantics.

## Consequences

- Use `koinBridge { ... }` in any Koin factory that calls a `suspend` function.
- Don't use raw `runBlocking { ... }` inside `module { ... }` blocks.
- Don't use `GlobalScope.launch { ... }` inside factories — non-deterministic.
- When the script's grep is broken (a stray `runBlocking` appears), fix it immediately; the helper exists specifically so this is detectable.
- `koinBridge` is for one-shot startup reads. Not for hot-path code, not for long-running operations.

## Links

- `shared/src/commonMain/.../core/di/KoinBridge.kt`
- `shared/src/jvmMain/.../core/di/AiToolsModule.jvm.kt`
- `shared/src/androidMain/.../core/di/AiToolsModule.android.kt`
- `shared/src/androidMain/.../core/di/PlatformModule.android.kt`
- Commit `cf07eaa`